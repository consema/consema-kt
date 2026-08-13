// The PGCE/1 canonical byte codec.
//
// Byte layout authority: consema-rs/consema-graph/src/pgce.rs (the frozen Rust
// reference codec), pinned byte-for-byte by
// conformance/vectors/portable-graph-v1.json:
//   - stream magic is the ASCII octets "PGCE" (pgce.rs:12);
//   - version is minimal unsigned LEB128 1 (pgce.rs:14);
//   - node records are 0x20 Scalar, 0x40 Sequence, 0x41 Mapping
//     (pgce.rs:16-18);
//   - all unsigned lengths/counts/IDs are minimal unsigned LEB128
//     (pgce.rs:398-419).
//
// Golden vectors transcribed into tests: portable-graph-v1.json
// pgce.empty-vector "50474345010000", pgce.scalar-vector
// "504743450101010020157461673a79616d6c2e6f72672c323030323a7374720178".
//
// The encoder applies the canonical numbering of RFC 0006 §4 first, so
// isomorphic graphs have byte-identical PGCE. The decoder is strict: it
// rejects every non-canonical form listed in RFC 0006 §5, including any
// stream whose re-encoding differs from the input (defense-in-depth).
// Failures are [PgceException] with the frozen core.pgce.*@1 codes.

package consema.graph

/** The PGCE/1 stream magic (ASCII "PGCE", consema-rs/consema-graph/src/pgce.rs:12). */
internal val PGCE_MAGIC = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte())

/** The frozen PGCE/1 version (consema-rs/consema-graph/src/pgce.rs:14). */
internal const val PGCE_VERSION: ULong = 1uL

// Node record octets (consema-rs/consema-graph/src/pgce.rs:16-18).
private const val NODE_SCALAR: Byte = 0x20
private const val NODE_SEQUENCE: Byte = 0x40
private const val NODE_MAPPING: Byte = 0x41

/**
 * The bounded PGCE/1 encode/decode resource limits (RFC 0006 §6; the Rust
 * PgceLimits, consema-rs/consema-graph/src/pgce.rs:21-54). The zero value
 * rejects every stream; use [PgceLimits.default].
 */
data class PgceLimits(
    /** Maximum complete PGCE stream bytes. */
    val maxStreamBytes: Int,
    /** Maximum ordered roots. */
    val maxRoots: Int,
    /** Maximum graph nodes. */
    val maxNodes: Int,
    /** Maximum sequence-item plus mapping key/value edges. */
    val maxEdges: Int,
    /** Maximum items or associations in one container. */
    val maxContainerEntries: Int,
    /** Maximum UTF-8 bytes in one tag identifier. */
    val maxTagBytes: Int,
    /** Maximum UTF-8 bytes in one scalar's canonical content. */
    val maxScalarBytes: Int,
    /** Maximum canonical first-visit traversal depth. */
    val maxTraversalDepth: Int,
) {
    companion object {
        /** The frozen defaults (64 MiB stream, 1,000,000 roots, 1,000,000
         * nodes, 2,000,000 edges, 1,000,000 container entries, 1 MiB tag,
         * 64 MiB scalar, depth 256; consema-rs/consema-graph/src/pgce.rs:
         * 41-54). */
        val default = PgceLimits(
            maxStreamBytes = 64 shl 20,
            maxRoots = 1_000_000,
            maxNodes = 1_000_000,
            maxEdges = 2_000_000,
            maxContainerEntries = 1_000_000,
            maxTagBytes = 1 shl 20,
            maxScalarBytes = 64 shl 20,
            maxTraversalDepth = 256,
        )
    }

    /** The construction-limits subset of the codec limits (the Rust
     * PgceLimits::graph_limits, consema-rs/consema-graph/src/pgce.rs:56-68). */
    fun graphLimits(): GraphLimits = GraphLimits(
        maxRoots = maxRoots,
        maxNodes = maxNodes,
        maxEdges = maxEdges,
        maxContainerEntries = maxContainerEntries,
        maxTagBytes = maxTagBytes,
        maxScalarBytes = maxScalarBytes,
        maxTraversalDepth = maxTraversalDepth,
    )
}

/** Encodes one graph as a complete canonical PGCE/1 stream with the default
 * bounded policy (RFC 0016 §4.2). The bytes are byte-identical to the Rust
 * codec's output. */
fun encodePgce(g: Graph): ByteArray = encodePgceBounded(g, PgceLimits.default)

/**
 * Encodes one complete canonical PGCE/1 stream after exact size measurement
 * (the Rust encode_pgce_bounded, consema-rs/consema-graph/src/pgce.rs:
 * 224-275). It never truncates: exceeding any limit throws a resource-limit
 * exception with no partial output (RFC 0006 §6).
 */
fun encodePgceBounded(g: Graph, limits: PgceLimits): ByteArray {
    validateGraphLimits(g, limits)
    val layout = g.layout()
    val size = measure(g, layout, limits)
    if (size > limits.maxStreamBytes) {
        throw pgceResourceLimit("stream-bytes")
    }
    val out = ArrayList<Byte>(size)
    for (octet in PGCE_MAGIC) out.add(octet)
    appendVarint(out, PGCE_VERSION)
    appendVarint(out, g.roots.size.toULong())
    appendVarint(out, g.nodes.size.toULong())
    for (root in g.roots) {
        appendVarint(out, layout.canonicalIds[root.index].toULong())
    }
    for (index in layout.order) {
        val node = g.nodes[index]
        when (node.kind) {
            NodeKind.Scalar -> {
                out.add(NODE_SCALAR)
                appendBlob(out, node.tag.toByteArray(Charsets.UTF_8))
                appendBlob(out, node.scalar.toByteArray(Charsets.UTF_8))
            }
            NodeKind.Sequence -> {
                out.add(NODE_SEQUENCE)
                appendBlob(out, node.tag.toByteArray(Charsets.UTF_8))
                appendVarint(out, node.items.size.toULong())
                for (item in node.items) {
                    appendVarint(out, layout.canonicalIds[item.index].toULong())
                }
            }
            NodeKind.Mapping -> {
                out.add(NODE_MAPPING)
                appendBlob(out, node.tag.toByteArray(Charsets.UTF_8))
                appendVarint(out, node.entries.size.toULong())
                for (entry in node.entries) {
                    appendVarint(out, layout.canonicalIds[entry.key.index].toULong())
                    appendVarint(out, layout.canonicalIds[entry.value.index].toULong())
                }
            }
        }
    }
    return out.toByteArray()
}

/** Checks the whole-graph limits and the traversal depth before any encoding
 * work (the Rust validate_graph_limits, consema-rs/consema-graph/src/pgce.rs:
 * 277-284). */
private fun validateGraphLimits(g: Graph, limits: PgceLimits) {
    checkEncodeLimit("graph-roots", g.roots.size, limits.maxRoots)
    checkEncodeLimit("graph-nodes", g.nodes.size, limits.maxNodes)
    checkEncodeLimit("graph-edges", g.edges, limits.maxEdges)
    // A completed graph only reaches the resource-limit path here.
    try {
        canonicalOrder(g.nodes, g.roots, limits.maxTraversalDepth)
    } catch (e: GraphException) {
        if (e.kind == GraphErrorKind.RESOURCE_LIMIT) {
            throw pgceResourceLimit(e.field)
        }
        throw PgceException(PgceErrorKind.INVALID_GRAPH, "graph: PGCE/1 invalid graph: ${e.message}", cause = e)
    }
}

/** Computes the exact encoded size of one graph under canonical numbering,
 * enforcing the per-node limits (the Rust measure,
 * consema-rs/consema-graph/src/pgce.rs:286-339). */
private fun measure(g: Graph, layout: Layout, limits: PgceLimits): Int {
    var size = PGCE_MAGIC.size
    size += varintSize(PGCE_VERSION)
    size += varintSize(g.roots.size.toULong())
    size += varintSize(g.nodes.size.toULong())
    for (root in g.roots) {
        size += varintSize(layout.canonicalIds[root.index].toULong())
    }
    for (index in layout.order) {
        val node = g.nodes[index]
        val tagBytes = node.tag.toByteArray(Charsets.UTF_8).size
        checkEncodeLimit("tag-bytes", tagBytes, limits.maxTagBytes)
        size += 1
        size += blobSize(tagBytes)
        when (node.kind) {
            NodeKind.Scalar -> {
                val scalarBytes = node.scalar.toByteArray(Charsets.UTF_8).size
                checkEncodeLimit("scalar-bytes", scalarBytes, limits.maxScalarBytes)
                size += blobSize(scalarBytes)
            }
            NodeKind.Sequence -> {
                checkEncodeLimit("container-entries", node.items.size, limits.maxContainerEntries)
                size += varintSize(node.items.size.toULong())
                for (item in node.items) {
                    size += varintSize(layout.canonicalIds[item.index].toULong())
                }
            }
            NodeKind.Mapping -> {
                checkEncodeLimit("container-entries", node.entries.size, limits.maxContainerEntries)
                size += varintSize(node.entries.size.toULong())
                for (entry in node.entries) {
                    size += varintSize(layout.canonicalIds[entry.key.index].toULong())
                    size += varintSize(layout.canonicalIds[entry.value.index].toULong())
                }
            }
        }
    }
    return size
}

/** Returns the encoded size of one length-prefixed byte string (the Rust
 * blob_size, consema-rs/consema-graph/src/pgce.rs:348-350). */
private fun blobSize(length: Int): Int = varintSize(length.toULong()) + length

/** Reports [PgceErrorKind.RESOURCE_LIMIT] when [observed] exceeds [limit]
 * (the Rust check_encode_limit, consema-rs/consema-graph/src/pgce.rs:360-374). */
private fun checkEncodeLimit(name: String, observed: Int, limit: Int) {
    if (observed > limit) {
        throw pgceResourceLimit(name)
    }
}

/** Writes a length-prefixed byte string (the Rust write_blob,
 * consema-rs/consema-graph/src/pgce.rs:392-396). */
private fun appendBlob(out: MutableList<Byte>, bytes: ByteArray) {
    appendVarint(out, bytes.size.toULong())
    for (octet in bytes) out.add(octet)
}

/** Writes the minimal unsigned LEB128 encoding of [value] (the Rust
 * write_varint, consema-rs/consema-graph/src/pgce.rs:398-410). */
private fun appendVarint(out: MutableList<Byte>, value: ULong) {
    var remaining = value
    while (true) {
        var octet = (remaining and 0x7fuL).toByte()
        remaining = remaining shr 7
        if (remaining != 0uL) {
            octet = (octet.toInt() or 0x80).toByte()
        }
        out.add(octet)
        if (remaining == 0uL) {
            return
        }
    }
}

/** Returns the encoded length of [value] as a minimal unsigned LEB128 (the
 * Rust const varint_size, consema-rs/consema-graph/src/pgce.rs:412-419). */
private fun varintSize(value: ULong): Int {
    var size = 1
    var remaining = value
    while (remaining >= 0x80uL) {
        remaining = remaining shr 7
        size++
    }
    return size
}

/**
 * Strictly decodes one canonical PGCE/1 stream (RFC 0016 §4.2). The decoder
 * rejects every non-canonical form of RFC 0006 §5: wrong magic or version,
 * non-minimal or overflowing or truncated varints, unknown node records,
 * trailing bytes, invalid UTF-8, empty or invalid tags, counts or blobs
 * outside limits, out-of-range references, node records not ordered by
 * canonical first discovery, unreachable nodes, and any stream whose
 * re-encoding differs from the input. No failure returns a partial graph
 * (RFC 0006 §6).
 */
fun decodePgce(stream: ByteArray, limits: PgceLimits): Graph {
    if (stream.size > limits.maxStreamBytes) {
        throw pgceResourceLimit("stream-bytes")
    }
    val d = Decoder(stream, limits)
    val magic = d.take(PGCE_MAGIC.size)
    if (!magic.contentEquals(PGCE_MAGIC)) {
        throw PgceException(PgceErrorKind.INVALID_MAGIC, "graph: PGCE/1 stream magic did not match \"PGCE\"")
    }
    val version = d.varint()
    if (version != PGCE_VERSION) {
        throw PgceException(
            PgceErrorKind.UNSUPPORTED_VERSION,
            "graph: PGCE/1 unsupported version $version (want 1)",
            value = version,
        )
    }
    val rootCount = d.count("graph-roots", limits.maxRoots)
    val nodeCount = d.count("graph-nodes", limits.maxNodes)

    val builder = try {
        decodeNodes(d, nodeCount, rootCount, limits)
    } catch (e: GraphException) {
        throw mapBuildToDecode(e)
    }
    if (d.offset != stream.size) {
        throw PgceException(PgceErrorKind.TRAILING_BYTES, "graph: PGCE/1 trailing bytes after the one complete graph")
    }
    val graph = try {
        builder.build()
    } catch (e: GraphException) {
        throw mapBuildToDecode(e)
    }
    val layout = graph.layout()
    for (i in layout.order.indices) {
        if (layout.order[i] != i) {
            throw PgceException(
                PgceErrorKind.NON_CANONICAL_NODE_ORDER,
                "graph: PGCE/1 node records are not ordered by canonical first discovery",
            )
        }
    }
    val encoded = try {
        encodePgceBounded(graph, limits)
    } catch (e: PgceException) {
        throw mapEncodeToDecode(e)
    }
    if (!encoded.contentEquals(stream)) {
        throw PgceException(
            PgceErrorKind.NON_CANONICAL_ENCODING,
            "graph: PGCE/1 re-encoding produced different bytes",
        )
    }
    return graph
}

/** Decodes the root references and all node records into a builder (the
 * body of the Rust decode_pgce, consema-rs/consema-graph/src/pgce.rs:437-505).
 * Throws [GraphException] for construction failures; the caller maps them
 * with [mapBuildToDecode]. */
private fun decodeNodes(
    d: Decoder,
    nodeCount: Int,
    rootCount: Int,
    limits: PgceLimits,
): Builder {
    val builder = Builder.withLimits(limits.graphLimits())
    val ids = ArrayList<NodeId>(nodeCount)
    repeat(nodeCount) {
        ids.add(builder.reserveNode())
    }

    val roots = ArrayList<NodeId>(rootCount)
    repeat(rootCount) {
        roots.add(ids[d.reference(nodeCount)])
    }
    for (root in roots) {
        builder.pushRoot(root)
    }

    for (i in 0 until nodeCount) {
        val kindOctet = d.byte()
        val tag = d.string("tag-bytes", limits.maxTagBytes)
        when (kindOctet) {
            NODE_SCALAR -> {
                val content = d.string("scalar-bytes", limits.maxScalarBytes)
                builder.defineScalar(ids[i], tag, content)
            }
            NODE_SEQUENCE -> {
                val count = d.count("container-entries", limits.maxContainerEntries)
                d.addEdges(count)
                val items = ArrayList<NodeId>(count)
                repeat(count) {
                    items.add(ids[d.reference(nodeCount)])
                }
                builder.defineSequence(ids[i], tag, items)
            }
            NODE_MAPPING -> {
                val count = d.count("container-entries", limits.maxContainerEntries)
                // A mapping association contributes a key and a value edge;
                // count is bounded by the container limit, so this product
                // cannot overflow.
                d.addEdges(count * 2)
                val entries = ArrayList<MappingEntry>(count)
                repeat(count) {
                    val keyIndex = d.reference(nodeCount)
                    val valueIndex = d.reference(nodeCount)
                    entries.add(MappingEntry(ids[keyIndex], ids[valueIndex]))
                }
                builder.defineMapping(ids[i], tag, entries)
            }
            else -> throw PgceException(
                PgceErrorKind.UNKNOWN_NODE_KIND,
                "graph: PGCE/1 unknown node record octet 0x${kindOctet.toInt().and(0xff).toString(16)}",
                value = (kindOctet.toInt() and 0xff).toULong(),
            )
        }
    }
    return builder
}

/** The strict streaming PGCE/1 decoder (the Rust Decoder,
 * consema-rs/consema-graph/src/pgce.rs:509-595). */
internal class Decoder(private val bytes: ByteArray, private val limits: PgceLimits) {
    var offset = 0
        private set
    private var edges = 0

    /** Consumes one octet. */
    fun byte(): Byte {
        if (offset >= bytes.size) {
            throw PgceException(PgceErrorKind.UNEXPECTED_END, "graph: PGCE/1 input ended inside a required field")
        }
        return bytes[offset++]
    }

    /** Consumes [count] octets. */
    fun take(count: Int): ByteArray {
        if (count < 0 || offset + count > bytes.size) {
            throw PgceException(PgceErrorKind.UNEXPECTED_END, "graph: PGCE/1 input ended inside a required field")
        }
        val value = bytes.copyOfRange(offset, offset + count)
        offset += count
        return value
    }

    /** Reads one unsigned varint, rejecting non-minimal encodings and
     * 64-bit overflow (the Rust Decoder::varint, consema-rs/consema-graph/
     * src/pgce.rs:539-557). */
    fun varint(): ULong {
        val start = offset
        var value = 0uL
        var shift = 0
        while (shift <= 63) {
            val octet = byte()
            val payload = (octet.toInt() and 0x7f).toULong()
            if (shift == 63 && payload > 1uL) {
                throw PgceException(PgceErrorKind.VARINT_OVERFLOW, "graph: PGCE/1 varint or host-size conversion overflowed")
            }
            value = value or (payload shl shift)
            if (octet.toInt() and 0x80 == 0) {
                if (offset - start != varintSize(value)) {
                    throw PgceException(
                        PgceErrorKind.NON_MINIMAL_VARINT,
                        "graph: PGCE/1 non-canonical (non-minimal) unsigned varint",
                    )
                }
                return value
            }
            shift += 7
        }
        throw PgceException(PgceErrorKind.VARINT_OVERFLOW, "graph: PGCE/1 varint or host-size conversion overflowed")
    }

    /** Reads one varint count, converts it to the host Int, and enforces
     * the named limit (the Rust Decoder::count, pgce.rs:559-564). */
    fun count(name: String, limit: Int): Int {
        val value = varint()
        if (value > Int.MAX_VALUE.toULong()) {
            throw PgceException(PgceErrorKind.VARINT_OVERFLOW, "graph: PGCE/1 varint or host-size conversion overflowed")
        }
        val count = value.toInt()
        if (count > limit) {
            throw pgceResourceLimit(name)
        }
        return count
    }

    /** Reads one node reference and rejects out-of-range IDs (the Rust
     * Decoder::reference, pgce.rs:566-574). */
    fun reference(nodeCount: Int): Int {
        val value = varint()
        if (value > Int.MAX_VALUE.toULong()) {
            throw PgceException(
                PgceErrorKind.REFERENCE_OUT_OF_RANGE,
                "graph: PGCE/1 node reference $value is outside node_count",
                value = value,
            )
        }
        val index = value.toInt()
        if (index >= nodeCount) {
            throw PgceException(
                PgceErrorKind.REFERENCE_OUT_OF_RANGE,
                "graph: PGCE/1 node reference $value is outside node_count",
                value = value,
            )
        }
        return index
    }

    /** Reads one length-delimited string (the Rust Decoder::string,
     * pgce.rs:576-586). UTF-8 validity is enforced here so that malformed
     * bytes fail with INVALID_UTF8 instead of being silently replaced. */
    fun string(name: String, limit: Int): String {
        val length = count(name, limit)
        val value = take(length)
        if (!consema.core.isValidUtf8(value)) {
            throw PgceException(PgceErrorKind.INVALID_UTF8, "graph: PGCE/1 string bytes are not valid UTF-8")
        }
        return String(value, Charsets.UTF_8)
    }

    /** Accumulates decoded edges under the graph-edges limit (the Rust
     * Decoder::add_edges, pgce.rs:588-594). */
    fun addEdges(count: Int) {
        edges += count
        if (edges > limits.maxEdges) {
            throw pgceResourceLimit("graph-edges")
        }
    }
}

/** Maps graph construction failures onto strict decode failures (the Rust
 * map_build_to_decode, consema-rs/consema-graph/src/pgce.rs:613-627): resource
 * limits pass through, invalid tags surface directly, invalid UTF-8 maps to
 * the wire failure, and every other construction failure wraps as
 * INVALID_GRAPH. */
internal fun mapBuildToDecode(e: GraphException): PgceException = when (e.kind) {
    GraphErrorKind.RESOURCE_LIMIT -> pgceResourceLimit(e.field)
    GraphErrorKind.INVALID_TAG ->
        PgceException(PgceErrorKind.INVALID_TAG, "graph: PGCE/1 tag is empty or contains ASCII control or whitespace")
    else -> PgceException(PgceErrorKind.INVALID_GRAPH, "graph: PGCE/1 invalid graph: ${e.message}", cause = e)
}

/** Maps re-encoding failures onto strict decode failures (the Rust
 * map_encode_to_decode, consema-rs/consema-graph/src/pgce.rs:629-642):
 * resource limits pass through; any other encode failure (unreachable here)
 * reports as a varint overflow. */
private fun mapEncodeToDecode(e: PgceException): PgceException =
    if (e.kind == PgceErrorKind.RESOURCE_LIMIT) {
        e
    } else {
        PgceException(PgceErrorKind.VARINT_OVERFLOW, "graph: PGCE/1 varint or host-size conversion overflowed")
    }
