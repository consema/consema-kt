// Strict PortableGraph equality and deterministic hashing.
//
// Data authority: RFC 0006 §4 (canonical numbering, strict isomorphism
// equality), pinned by conformance/vectors/portable-graph-v1.json
// (graph.isomorphic-builder-numbering: different builder numbering is equal;
// graph.sharing-is-not-duplication: sharing is not duplication). The hashing
// contract (FNV-1a over the canonical PGCE/1 bytes) follows the cross-
// language rule that equal graphs hash equal and that the bytes of equal
// graphs are identical (RFC 0006 §4; consema-go/go/graph/equal.go as cross-reference).

package consema.graph

/**
 * The canonical numbering of one graph (RFC 0006 §4): [order] lists
 * original node indices in deterministic depth-first pre-order, and
 * [canonicalIds] maps each original index to its canonical ID.
 */
internal class Layout(val order: IntArray, val canonicalIds: IntArray)

/** Computes the canonical numbering of a completed graph. Completed graphs
 * always traverse cleanly ([Builder.build] validated reachability and
 * depth), so a failure here is an internal invariant violation. */
internal fun Graph.layout(): Layout {
    val (order, canonicalIds) = try {
        canonicalOrder(nodes, roots, -1)
    } catch (e: GraphException) {
        throw IllegalStateException("graph: completed graph traversal failed", e)
    }
    return Layout(order, canonicalIds)
}

/**
 * Reports strict PortableGraph equality (RFC 0006 §4): two graphs are equal
 * when there is a root-preserving ordered graph isomorphism preserving root
 * order, node kind, exact resolved tag, exact canonical scalar content,
 * sequence edge order, mapping association order (including duplicates),
 * key/value edge roles, and shared-reference and cycle topology. Builder
 * numbering is not semantic: graphs built with different local IDs compare
 * equal when their canonical numbering and content match (RFC 0006 §4).
 *
 * [equal] is total: it never throws, it never compares object identities,
 * and it never recurses through edges, so shared and cyclic graphs are safe
 * (RFC 0006 §4: "Consema computes this without recursive expansion").
 * [equal](null, null) is true; [equal](null, x) is false for any non-null
 * x.
 */
fun equal(a: Graph?, b: Graph?): Boolean {
    if (a == null || b == null) {
        return a === b
    }
    if (a.roots.size != b.roots.size || a.nodes.size != b.nodes.size || a.edges != b.edges) {
        return false
    }
    val left = a.layout()
    val right = b.layout()
    for (i in a.roots.indices) {
        if (left.canonicalIds[a.roots[i].index] != right.canonicalIds[b.roots[i].index]) {
            return false
        }
    }
    for (i in left.order.indices) {
        if (!canonicalNodeEqual(a.nodes[left.order[i]], left.canonicalIds,
                b.nodes[right.order[i]], right.canonicalIds)
        ) {
            return false
        }
    }
    return true
}

/** Compares two nodes under their canonical ID mappings (the Rust
 * canonical_node_eq, https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs:634-661). */
private fun canonicalNodeEqual(
    left: NodeData,
    leftIds: IntArray,
    right: NodeData,
    rightIds: IntArray,
): Boolean {
    if (left.kind != right.kind || left.tag != right.tag) {
        return false
    }
    return when (left.kind) {
        NodeKind.Scalar -> left.scalar == right.scalar
        NodeKind.Sequence -> {
            if (left.items.size != right.items.size) {
                false
            } else {
                var same = true
                for (i in left.items.indices) {
                    if (leftIds[left.items[i].index] != rightIds[right.items[i].index]) {
                        same = false
                        break
                    }
                }
                same
            }
        }
        NodeKind.Mapping -> {
            if (left.entries.size != right.entries.size) {
                false
            } else {
                var same = true
                for (i in left.entries.indices) {
                    if (leftIds[left.entries[i].key.index] != rightIds[right.entries[i].key.index] ||
                        leftIds[left.entries[i].value.index] != rightIds[right.entries[i].value.index]
                    ) {
                        same = false
                        break
                    }
                }
                same
            }
        }
    }
}

// FNV-1a 64-bit parameters (the standard FNV-1a constants).
private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
private const val FNV_PRIME: ULong = 0x100000001b3uL

/**
 * Returns a deterministic 64-bit hash consistent with [equal] (RFC 0006
 * §4): equal graphs always hash equal. It is defined as FNV-1a over the
 * canonical PGCE/1 encoding of the graph, so [equal](a, b) holds exactly
 * when the encoded bytes of a and b are identical; the hash is therefore
 * identity-order-sensitive and cycle-safe. [hash](null) is 0.
 */
fun hash(g: Graph?): ULong {
    val bytes = try {
        encodePgce(g ?: return 0uL)
    } catch (e: PgceException) {
        return 0uL
    }
    var h = FNV_OFFSET_BASIS
    for (octet in bytes) {
        h = h xor (octet.toULong() and 0xffuL)
        h = h * FNV_PRIME
    }
    return h
}
