// Formation of `plist.binary@1` documents: the `bplist00` object-table
// representation with offset-table and trailer facts, byte-exact spans, and
// Complete/Recovered outcomes.
//
// Data authority:
//   - RFC 0013 §2.2, §3, §5 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:
//     78-89, 90-124, 276-460): the 42-byte minimum, the header, the admitted
//     marker table, the integer width rules, extended sizes, real/date/
//     string/data/UID payloads, array/dictionary references, the offset
//     table and trailer layout, and the mandatory integrity checks of
//     §5.11 (no false Complete: every check runs before any object is
//     decoded, and every offset/ref/size arithmetic is checked before
//     allocation).
//   - RFC 0013 §5.12 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-...md:451-460): non-minimal widths,
//     extended-size spellings, and duplicated scalars are legal input
//     facts, preserved and normalized only by canonical materialization.
//   - conformance/vectors/plist-v1.json (plist.binary-formation.*) pins the
//     recover/complete outcomes and the diagnostic codes case by case.
//   - consema-rs/consema-plist/src/parser_binary.rs is the byte-arbitration
//     authority (trailer checks parser_binary.rs:776-917, offset table
//     parser_binary.rs:919-972, object scan parser_binary.rs:976-1252,
//     extended sizes parser_binary.rs:1254-1324, dict keys parser_binary.rs:
//     1326-1354, region assembly parser_binary.rs:703-730); consema-go/go/plist is a
//     cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the parser is a single
// deterministic forward pass over raw bytes with explicit checked Long
// arithmetic; the proven prefix is index-based (objects 0..cut in table
// order); native values are built after the prefix is proven, so no
// unproven fact can enter the native graph (hard gate 4).

package consema.plist

import consema.document.BinaryRegion
import consema.document.BinaryStructuralIndex
import consema.document.DocumentAuthority
import consema.document.FormationStatus
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourceSnapshot
import consema.document.Span
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** Exact `bplist00` header bytes (RFC 0013 §5.1; parser_binary.rs:42-43). */
private val HEADER = byteArrayOf('b'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
    'i'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), '0'.code.toByte(), '0'.code.toByte())

/** Minimum admissible source length: 8-byte header, at least one 1-byte
 * object, at least one 1-byte offset entry, and the 32-byte trailer
 * (RFC 0013 §2.2; parser_binary.rs:44-47). */
private const val MIN_SOURCE_BYTES = 42

/** Trailer byte length (RFC 0013 §5.10; parser_binary.rs:48-49). */
private const val TRAILER_BYTES = 32

/** Largest legal integer/offset/ref payload width in bytes (RFC 0013 §5.11;
 * parser_binary.rs:50-51). */
private const val MAX_FIELD_WIDTH = 8

/** Binary profile formation entry (lib.rs:241-260). */
internal fun parseBinaryEntry(
    bytes: ByteArray,
    selection: PlistEncodingSelection,
    limits: PlistParseLimits,
): Document {
    when (selection) {
        PlistEncodingSelection.ProfileDefault -> {}
        is PlistEncodingSelection.Explicit ->
            if (selection.encoding !== SourceEncoding.Binary) {
                throw PlistFormationException(
                    PlistCodes.BINARY_ENCODING,
                    "plist binary parse: incompatible encoding selection",
                )
            }
    }
    if (bytes.size > limits.common.maxSourceBytes) {
        throw commonLimit("source-bytes", bytes.size, limits.common.maxSourceBytes)
    }
    val source = try {
        SourceSnapshot.fromBinary(
            bytes,
            SourceLimits(
                maxRawBytes = limits.common.maxSourceBytes,
                maxDecodedUtf8Bytes = 0,
                maxDecodedScalars = 0,
            ),
        )
    } catch (e: consema.document.SourceException) {
        throw wrapBinarySourceError(e)
    }
    val authority = DocumentAuthority.fresh()
    return BinaryParser(source, authority, limits).parse()
}

private fun wrapBinarySourceError(error: consema.document.SourceException): PlistFormationException =
    when (error.kind) {
        consema.document.SourceErrorKind.RESOURCE_LIMIT ->
            PlistFormationException(
                "core.source.resource-limit@1",
                "plist binary parse: source construction limit",
                name = error.name ?: "",
                observed = error.observed,
                limit = error.limit,
                cause = error,
            )

        else -> PlistFormationException(
            "core.source.invalid-sequence@1",
            "plist binary parse: source construction failure",
            cause = error,
        )
    }

/** One object's structural shape: kind, marker, extent, and references
 * (parser_binary.rs:407-418). */
private class ObjectShape(
    val kind: ShapeKind,
    val marker: Int,
    val offset: Int,
    val extent: Int,
    val count: Int,
    val keyCount: Int,
    val payloadStart: Int,
    val refs: List<RefTarget>,
)

/** One decoded object-table reference with its exact byte span
 * (parser_binary.rs:400-405). */
private class RefTarget(val target: Int, val span: Span)

/** Object shape kinds (parser_binary.rs:379-398). */
private enum class ShapeKind {
    False,
    True,
    Integer,
    Real,
    Date,
    Data,
    AsciiString,
    Utf16String,
    Uid,
    Array,
    Dict,
    ;

    fun isString(): Boolean = this == AsciiString || this == Utf16String
}

/** Raw trailer field values (RFC 0013 §5.10; parser_binary.rs:420-478). */
private class RawTrailer(
    val unused: BooleanArray,
    val sortVersion: Int,
    val offsetIntSize: Int,
    val objectRefSize: Int,
    val numObjects: Long,
    val topObject: Long,
    val offsetTableOffset: Long,
) {
    companion object {
        fun read(bytes: ByteArray): RawTrailer {
            val start = bytes.size - TRAILER_BYTES
            return RawTrailer(
                unused = BooleanArray(5) { bytes[start + it].toInt() == 0 },
                sortVersion = bytes[start + 5].toInt() and 0xFF,
                offsetIntSize = bytes[start + 6].toInt() and 0xFF,
                objectRefSize = bytes[start + 7].toInt() and 0xFF,
                numObjects = readBeU64Top(bytes, start + 8),
                topObject = readBeU64Top(bytes, start + 16),
                offsetTableOffset = readBeU64Top(bytes, start + 24),
            )
        }
    }
}

/** Big-endian unsigned read of `width` bytes (1, 2, 4, or 8); top-level so
 * every parser component can resolve it. */
internal fun readBeU64Top(bytes: ByteArray, start: Int, width: Int = 8): Long {
    var value = 0L
    for (shift in 0 until width) {
        value = (value shl 8) or (bytes[start + shift].toLong() and 0xFF)
    }
    return value
}

/** The self-contained binary parser. */
internal class BinaryParser(
    private val source: SourceSnapshot,
    private val authority: DocumentAuthority,
    private val limits: PlistParseLimits,
) {
    private val bytes: ByteArray = source.rawBytes()
    private val diagnostics = ArrayList<PlistDiagnostic>()
    private var recovered = false
    private var occurrence = 0L
    private var uidCount = 0
    private var extendedIntegers = 0
    private var facts = 0

    fun parse(): Document {
        val len = bytes.size
        if (len < MIN_SOURCE_BYTES) {
            throw PlistFormationException(
                PlistCodes.BINARY_MINIMUM_SIZE,
                "plist binary parse: source below the 42-byte minimum",
            )
        }
        val trailerStart = len - TRAILER_BYTES

        // Header (RFC 0013 §5.1): any other version string is Recovered and
        // formation continues so the remaining constructs are still judged.
        val headerOk = bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)
        if (!headerOk) {
            recover(PlistCodes.BINARY_HEADER, DiagnosticCategory.Syntax,
                arguments = mapOf("expected" to "bplist00"))
        }

        // Trailer facts are bytes of the source and are always recorded.
        val raw = RawTrailer.read(bytes)
        recordFact()
        val trailerFacts = BinaryTrailerFacts(
            sortVersion = raw.sortVersion,
            offsetIntSize = raw.offsetIntSize,
            objectRefSize = raw.objectRefSize,
            numObjects = raw.numObjects,
            topObject = raw.topObject,
            offsetTableOffset = raw.offsetTableOffset,
            span = span(trailerStart, len),
        )

        // Mandatory integrity checks run before any object is decoded
        // (RFC 0013 §5.11).
        val trailerOk = validateTrailer(raw)
        if (!trailerOk) {
            // The object table cannot be located; the middle bytes are one
            // error region and no native document exists.
            val regions = listOf(
                region(0, if (headerOk) "header" else "error-region", 0, 8),
                region(1, "error-region", 8, trailerStart),
                region(2, "error-region", trailerStart, len),
            )
            return finish(
                nativeRoot = null,
                rootIndex = -1,
                facts = BinaryFacts(emptyList(), emptyList(), emptyList(), trailerFacts),
                regions = regions,
                hasEntities = false,
            )
        }

        val offsetTableOffset = toUsize(raw.offsetTableOffset)
        val numObjects = toUsize(raw.numObjects)
        val offsetIntSize = raw.offsetIntSize
        val objectRefSize = raw.objectRefSize
        val tableBytes = checkedMul(numObjects.toLong(), offsetIntSize.toLong())
        if (tableBytes > limits.maxOffsetTableBytes.toLong()) {
            throw plistLimit("offset-table-bytes", tableBytes.toInt(), limits.maxOffsetTableBytes)
        }

        val (offsetFacts, objectOffsets, entryCut) = readOffsetTable(
            offsetTableOffset, numObjects, offsetIntSize,
        )
        val (shapes, shapeCut) = scanObjects(
            objectOffsets, entryCut, offsetTableOffset, objectRefSize, numObjects,
        )
        val cut = verifyDictKeys(shapes, shapeCut)

        // Native document eligibility: the top object and every reference of
        // a proven object must stay inside the proven prefix.
        val topObject = toUsize(raw.topObject)
        var nativeUnproven = false
        if (topObject >= cut) {
            recover(PlistCodes.BINARY_UNPROVEN_TOP_OBJECT, DiagnosticCategory.Syntax,
                arguments = mapOf("top-object" to topObject.toString()))
            nativeUnproven = true
        }
        for (owner in 0 until cut) {
            val shape = shapes[owner]
            for (reference in shape.refs) {
                if (reference.target >= cut) {
                    recover(PlistCodes.BINARY_UNPROVEN_REFERENCE, DiagnosticCategory.Syntax,
                        arguments = mapOf(
                            "owner" to owner.toString(),
                            "target" to reference.target.toString(),
                        ))
                    nativeUnproven = true
                }
            }
        }

        // Native values in object-table order so value entity indices equal
        // object indices (shared identity preserved, RFC 0013 §6). Value
        // entities occupy entity indices 0..cut-1; dict-entry and
        // array-element entities follow at cut.., and container natives hold
        // those entity indices.
        var nativeRoot: NativeValue? = null
        var rootIndex = -1
        val entities = ArrayList<Entity>()
        if (!nativeUnproven && cut > 0) {
            // Cycle and container-depth validation from the top object
            // (RFC 0013 §5.11; parser_binary.rs:657-670).
            val open = HashSet<Int>()
            val cycle = !visit(shapes, topObject, 0, open)
            if (cycle) {
                recover(PlistCodes.BINARY_CYCLE, DiagnosticCategory.Syntax)
                nativeUnproven = true
            } else {
                // Global element/entry entity bases per object: the entry
                // and element entities are appended after the value entities
                // in object order, so an object's association entities start
                // at cut + (entries + elements of all earlier objects).
                val elementBase = IntArray(cut)
                val entryBase = IntArray(cut)
                var associationCount = 0
                for (index in 0 until cut) {
                    elementBase[index] = associationCount
                    entryBase[index] = associationCount
                    when (shapes[index].kind) {
                        ShapeKind.Array -> associationCount += shapes[index].refs.size
                        ShapeKind.Dict -> associationCount += shapes[index].keyCount
                        else -> {}
                    }
                }
                val values = buildValues(shapes, cut, elementBase, entryBase)
                for (index in 0 until cut) {
                    entities.add(
                        Entity.Value(
                            ValueEntity(
                                span(shapes[index].offset, shapes[index].offset + shapes[index].extent),
                                values[index],
                            ),
                        ),
                    )
                }
                for (index in 0 until cut) {
                    val shape = shapes[index]
                    when (shape.kind) {
                        ShapeKind.Dict -> {
                            for (position in 0 until shape.keyCount) {
                                val keyRef = shape.refs[position]
                                val valueRef = shape.refs[shape.keyCount + position]
                                entities.add(
                                    Entity.DictEntry(
                                        DictEntryEntity(
                                            authority.span(keyRef.span.startByte, valueRef.span.endByte),
                                            keyRef.target,
                                            valueRef.target,
                                            position,
                                        ),
                                    ),
                                )
                            }
                        }
                        ShapeKind.Array -> {
                            for ((position, element) in shape.refs.withIndex()) {
                                entities.add(
                                    Entity.ArrayElement(
                                        ArrayElementEntity(element.span, element.target, position),
                                    ),
                                )
                            }
                        }
                        else -> {}
                    }
                }
                nativeRoot = values[topObject]
                rootIndex = topObject
            }
        }

        // Facts of the proven prefix (RFC 0013 §8.3).
        val objects = ArrayList<BinaryObjectFact>(cut)
        for (index in 0 until cut) {
            recordFact()
            val shape = shapes[index]
            objects.add(
                BinaryObjectFact(
                    index,
                    shape.offset,
                    shape.marker,
                    span(shape.offset, checkedAdd(shape.offset.toLong(), shape.extent.toLong()).toInt()),
                ),
            )
        }
        val refs = ArrayList<BinaryObjectRefFact>()
        for (owner in 0 until cut) {
            val shape = shapes[owner]
            for ((position, reference) in shape.refs.withIndex()) {
                recordFact()
                refs.add(BinaryObjectRefFact(owner, position, reference.target, reference.span))
            }
        }
        val binaryFacts = BinaryFacts(objects, offsetFacts, refs, trailerFacts)

        // Exhaustive region coverage (parser_binary.rs:703-730).
        val regions = ArrayList<BinaryRegion>()
        regions.add(region(0, if (headerOk) "header" else "error-region", 0, 8))
        if (cut > 0) {
            val lastShape = shapes[cut - 1]
            val lastEnd = checkedAdd(lastShape.offset.toLong(), lastShape.extent.toLong()).toInt()
            regions.add(region(1, "object-table", 8, lastEnd))
            if (cut < numObjects) {
                if (lastEnd < offsetTableOffset) {
                    regions.add(region(2, "error-region", lastEnd, offsetTableOffset))
                }
            } else if (lastEnd < offsetTableOffset) {
                regions.add(region(2, "padding", lastEnd, offsetTableOffset))
            }
        } else if (8 < offsetTableOffset) {
            regions.add(region(1, "error-region", 8, offsetTableOffset))
        }
        regions.add(region(
            regions.size,
            "offset-table",
            offsetTableOffset,
            checkedAdd(offsetTableOffset.toLong(), tableBytes).toInt(),
        ))
        regions.add(region(regions.size, "trailer", trailerStart, len))

        return finish(
            nativeRoot = nativeRoot,
            rootIndex = rootIndex,
            facts = binaryFacts,
            regions = regions,
            hasEntities = true,
            entities = entities,
        )
    }

    private fun finish(
        nativeRoot: NativeValue?,
        rootIndex: Int,
        facts: BinaryFacts,
        regions: List<BinaryRegion>,
        hasEntities: Boolean,
        entities: List<Entity> = emptyList(),
    ): Document {
        val errorRegions = regions.count { it.kind == "error-region" }
        if (errorRegions > limits.maxRecoveryRegions) {
            throw plistLimit("recovery-regions", errorRegions, limits.maxRecoveryRegions)
        }
        val structuralIndex = try {
            BinaryStructuralIndex.new(authority.identity, source.len, regions)
        } catch (e: consema.document.LocationException) {
            for (region in regions) {
                System.err.println(
                    "REGION [${region.span.startByte},${region.span.endByte}) ${region.kind}",
                )
            }
            throw PlistFormationException(
                PlistCodes.BINARY_COVERAGE,
                "plist binary parse: structural coverage failure",
            )
        }
        val status = if (recovered) FormationStatus.Recovered else FormationStatus.Complete
        return Document(
            authority = authority,
            source = source,
            profile = PlistProfile.BinaryV1,
            losslessIndex = null,
            binaryIndex = structuralIndex,
            formationStatus = status,
            diagnosticsList = diagnostics,
            entities = if (hasEntities) entities else emptyList(),
            rootIndex = rootIndex,
            nativeRoot = nativeRoot,
            syntaxKinds = null,
            binaryFacts = facts,
            parseLimits = limits,
        )
    }

    /** Validates the mandatory trailer checks (RFC 0013 §5.11; parser_binary
     * .rs:776-917). */
    private fun validateTrailer(raw: RawTrailer): Boolean {
        var ok = true
        val len = bytes.size
        val start = len - TRAILER_BYTES

        if (raw.unused.any { !it }) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf("check" to "unused-bytes"))
            ok = false
        }
        if (raw.sortVersion != 0 && raw.sortVersion != 1) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "check" to "sort-version",
                    "sort-version" to "0x%02x".format(raw.sortVersion),
                ))
            ok = false
        }
        if (raw.offsetIntSize !in 1..MAX_FIELD_WIDTH) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "check" to "offset-int-size",
                    "offset-int-size" to raw.offsetIntSize.toString(),
                ))
            ok = false
        } else if (raw.offsetIntSize > limits.maxOffsetIntSize) {
            throw plistLimit("offset-int-size", raw.offsetIntSize, limits.maxOffsetIntSize)
        }
        if (raw.objectRefSize !in 1..MAX_FIELD_WIDTH) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "check" to "object-ref-size",
                    "object-ref-size" to raw.objectRefSize.toString(),
                ))
            ok = false
        } else if (raw.objectRefSize > limits.maxObjectRefSize) {
            throw plistLimit("object-ref-size", raw.objectRefSize, limits.maxObjectRefSize)
        }
        if (raw.numObjects == 0L) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf("check" to "num-objects"))
            ok = false
        } else if (raw.numObjects > limits.maxObjectCount.toLong()) {
            throw plistLimit("object-count", raw.numObjects.toInt(), limits.maxObjectCount)
        }
        if (raw.topObject >= raw.numObjects) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "check" to "top-object",
                    "top-object" to raw.topObject.toString(),
                ))
            ok = false
        }
        val maxTableOffset = (len - TRAILER_BYTES).toLong()
        if (raw.offsetTableOffset !in 9 until maxTableOffset) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "check" to "offset-table-offset",
                    "offset-table-offset" to raw.offsetTableOffset.toString(),
                ))
            ok = false
        }
        if (raw.offsetIntSize in 1..MAX_FIELD_WIDTH && raw.offsetIntSize < MAX_FIELD_WIDTH) {
            val capacity = 1L shl (8 * raw.offsetIntSize)
            if (capacity <= raw.offsetTableOffset) {
                recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                    arguments = mapOf("check" to "offset-int-size-sufficiency"))
                ok = false
            }
        }
        if (raw.objectRefSize in 1..MAX_FIELD_WIDTH && raw.objectRefSize < MAX_FIELD_WIDTH) {
            val capacity = 1L shl (8 * raw.objectRefSize)
            if (capacity <= raw.numObjects) {
                recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                    arguments = mapOf("check" to "object-ref-size-sufficiency"))
                ok = false
            }
        }
        val expected = raw.offsetTableOffset +
            checkedMul(raw.numObjects, raw.offsetIntSize.toLong()) +
            TRAILER_BYTES.toLong()
        if (expected != len.toLong()) {
            recover(PlistCodes.BINARY_TRAILER, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "check" to "total-length",
                    "expected" to expected.toString(),
                    "observed" to len.toString(),
                ))
            ok = false
        }
        return ok
    }

    /** Reads and validates the offset table in entry order (RFC 0013 §5.10,
     * §5.11; parser_binary.rs:919-972). */
    private fun readOffsetTable(
        offsetTableOffset: Int,
        numObjects: Int,
        offsetIntSize: Int,
    ): Triple<List<BinaryOffsetFact>, List<Int>, Int> {
        val facts = ArrayList<BinaryOffsetFact>()
        val offsets = ArrayList<Int>()
        var cut = numObjects
        for (index in 0 until numObjects) {
            val start = checkedAdd(offsetTableOffset.toLong(), checkedMul(index.toLong(), offsetIntSize.toLong())).toInt()
            val end = checkedAdd(start.toLong(), offsetIntSize.toLong()).toInt()
            if (end > bytes.size) {
                recover(PlistCodes.BINARY_OFFSET_TABLE, DiagnosticCategory.Syntax,
                    arguments = mapOf("index" to index.toString(), "end" to end.toString()))
                cut = index
                break
            }
            val value = readBeU64Top(bytes, start, offsetIntSize)
            val valueUsize = toUsize(value)
            if (valueUsize < 8 || valueUsize >= offsetTableOffset) {
                recover(PlistCodes.BINARY_OFFSET_TABLE, DiagnosticCategory.Syntax,
                    arguments = mapOf(
                        "index" to index.toString(),
                        "value" to "0x%x".format(value),
                    ))
                cut = index
                break
            }
            recordFact()
            facts.add(BinaryOffsetFact(index, valueUsize, span(start, end)))
            offsets.add(valueUsize)
        }
        return Triple(facts, offsets, cut)
    }

    /** Scans objects in index order and returns the proven shapes plus the
     * prefix cut (RFC 0013 §5.2-§5.9; parser_binary.rs:974-1000). */
    private fun scanObjects(
        objectOffsets: List<Int>,
        initialCut: Int,
        offsetTableOffset: Int,
        objectRefSize: Int,
        numObjects: Int,
    ): Pair<List<ObjectShape>, Int> {
        val shapes = ArrayList<ObjectShape>()
        var cut = initialCut
        for (index in 0 until initialCut) {
            val shape = scanObject(
                index, objectOffsets[index], offsetTableOffset, objectRefSize, numObjects,
            ) ?: run {
                cut = index
                break
            }
            shapes.add(shape)
        }
        return shapes to cut
    }

    /** Decodes one object's marker, size, extent, and references; null is a
     * fault that cuts the proven prefix at [index] (parser_binary.rs:1002-
     * 1252). */
    private fun scanObject(
        index: Int,
        offset: Int,
        tableEnd: Int,
        objectRefSize: Int,
        numObjects: Int,
    ): ObjectShape? {
        if (offset >= bytes.size) {
            recover(PlistCodes.BINARY_OFFSET_TABLE, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "index" to index.toString(),
                    "value" to "0x%x".format(offset),
                ))
            return null
        }
        val marker = bytes[offset].toInt() and 0xFF
        val markerSpan = span(offset, offset + 1)
        val (kind, count, extBytes) = when (marker) {
            0x08 -> Triple(ShapeKind.False, 0, 0)
            0x09 -> Triple(ShapeKind.True, 0, 0)
            in 0x10..0x13 -> Triple(ShapeKind.Integer, 1 shl (marker and 0x0F), 0)
            0x22 -> Triple(ShapeKind.Real, 4, 0)
            0x23 -> Triple(ShapeKind.Real, 8, 0)
            0x33 -> Triple(ShapeKind.Date, 8, 0)
            in 0x40..0x4F -> {
                val sized = sizedCount(marker, offset, index) ?: return null
                if (sized.first > limits.maxDataBytes) {
                    throw plistLimit("data-bytes", sized.first, limits.maxDataBytes)
                }
                Triple(ShapeKind.Data, sized.first, sized.second)
            }
            in 0x50..0x5F -> {
                val sized = sizedCount(marker, offset, index) ?: return null
                if (sized.first > limits.maxStringCodeUnits) {
                    throw plistLimit("string-code-units", sized.first, limits.maxStringCodeUnits)
                }
                Triple(ShapeKind.AsciiString, sized.first, sized.second)
            }
            in 0x60..0x6F -> {
                val sized = sizedCount(marker, offset, index) ?: return null
                if (sized.first > limits.maxStringCodeUnits) {
                    throw plistLimit("string-code-units", sized.first, limits.maxStringCodeUnits)
                }
                Triple(ShapeKind.Utf16String, sized.first, sized.second)
            }
            in 0x80..0x8F -> Triple(ShapeKind.Uid, (marker and 0x0F) + 1, 0)
            in 0xA0..0xAF -> {
                val sized = sizedCount(marker, offset, index) ?: return null
                if (sized.first > limits.maxArrayElements) {
                    throw plistLimit("array-elements", sized.first, limits.maxArrayElements)
                }
                Triple(ShapeKind.Array, sized.first, sized.second)
            }
            in 0xD0..0xDF -> {
                val sized = sizedCount(marker, offset, index) ?: return null
                if (sized.first > limits.maxDictEntries) {
                    throw plistLimit("dict-entries", sized.first, limits.maxDictEntries)
                }
                Triple(ShapeKind.Dict, sized.first, sized.second)
            }
            else -> {
                recover(PlistCodes.BINARY_MARKER, DiagnosticCategory.Syntax,
                    arguments = mapOf(
                        "marker" to "0x%02x".format(marker),
                        "object" to index.toString(),
                    ))
                return null
            }
        }
        val payloadStart = checkedAdd(checkedAdd(offset.toLong(), 1L), extBytes.toLong()).toInt()
        val payloadLen = when (kind) {
            ShapeKind.Uid, ShapeKind.Data, ShapeKind.AsciiString, ShapeKind.False, ShapeKind.True ->
                count.toLong()
            ShapeKind.Integer, ShapeKind.Real -> (1 shl (marker and 0x0F)).toLong()
            ShapeKind.Date -> 8L
            ShapeKind.Utf16String -> checkedMul(count.toLong(), 2L)
            ShapeKind.Array -> checkedMul(count.toLong(), objectRefSize.toLong())
            ShapeKind.Dict -> checkedMul(checkedMul(count.toLong(), 2L), objectRefSize.toLong())
        }
        val extent = checkedAdd(checkedAdd(1L, extBytes.toLong()), payloadLen)
        val end = checkedAdd(offset.toLong(), extent)
        if (end > tableEnd.toLong()) {
            recover(PlistCodes.BINARY_EXTENT, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "object" to index.toString(),
                    "end" to end.toString(),
                    "table-end" to tableEnd.toString(),
                ))
            return null
        }

        // Value-validity checks that cut the prefix here (RFC 0013 §5.5-5.8).
        when (kind) {
            ShapeKind.AsciiString -> {
                for (at in payloadStart until end.toInt()) {
                    if ((bytes[at].toInt() and 0xFF) >= 0x80) {
                        recover(PlistCodes.BINARY_STRING, DiagnosticCategory.Syntax,
                            arguments = mapOf(
                                "byte" to "0x%02x".format(bytes[at].toInt() and 0xFF),
                                "object" to index.toString(),
                            ))
                        return null
                    }
                }
            }
            ShapeKind.Date -> {
                val seconds = java.lang.Double.longBitsToDouble(readBeU64Top(bytes, payloadStart, 8))
                if (!seconds.isFinite()) {
                    recover(PlistCodes.BINARY_DATE, DiagnosticCategory.Syntax,
                        arguments = mapOf("object" to index.toString()))
                    return null
                }
            }
            ShapeKind.Uid -> {
                val value = readBeU64Top(bytes, payloadStart, count)
                if (value > 0xFFFF_FFFFL) {
                    recover(PlistCodes.BINARY_UID, DiagnosticCategory.Syntax,
                        arguments = mapOf(
                            "value" to "0x%x".format(value),
                            "object" to index.toString(),
                        ))
                    return null
                }
                uidCount = checkedAdd(uidCount.toLong(), 1L).toInt()
                if (uidCount > limits.maxUidCount) {
                    throw plistLimit("uid-count", uidCount, limits.maxUidCount)
                }
            }
            else -> {}
        }

        // Container references (RFC 0013 §5.9).
        val refs = ArrayList<RefTarget>()
        if (kind == ShapeKind.Array || kind == ShapeKind.Dict) {
            val total = if (kind == ShapeKind.Dict) {
                checkedMul(count.toLong(), 2L)
            } else {
                count.toLong()
            }
            for (position in 0 until total.toInt()) {
                val refStart = checkedAdd(payloadStart.toLong(), checkedMul(position.toLong(), objectRefSize.toLong())).toInt()
                val refEnd = checkedAdd(refStart.toLong(), objectRefSize.toLong()).toInt()
                val refSpan = span(refStart, refEnd)
                val target = toUsize(readBeU64Top(bytes, refStart, objectRefSize))
                if (target >= numObjects) {
                    recover(PlistCodes.BINARY_REFERENCE, DiagnosticCategory.Syntax,
                        arguments = mapOf(
                            "owner" to index.toString(),
                            "target" to target.toString(),
                        ))
                    return null
                }
                refs.add(RefTarget(target, refSpan))
            }
            facts = checkedAdd(facts.toLong(), total).toInt()
            if (facts > limits.maxBinaryFacts) {
                throw plistLimit("binary-facts", facts, limits.maxBinaryFacts)
            }
        }
        return ObjectShape(
            kind = kind,
            marker = marker,
            offset = offset,
            extent = extent.toInt(),
            count = count,
            keyCount = if (kind == ShapeKind.Dict) count else 0,
            payloadStart = payloadStart,
            refs = refs,
        )
    }

    /** Reads a sized construct's count, honoring the extended-size integer
     * rule (RFC 0013 §5.4; parser_binary.rs:1254-1267). */
    private fun sizedCount(marker: Int, objectOffset: Int, index: Int): Pair<Int, Int>? {
        val nibble = marker and 0x0F
        if (nibble != 0x0F) {
            return nibble to 0
        }
        return readCount(objectOffset, index)
    }

    /** Reads one extended-size integer and enforces its limits (RFC 0013
     * §5.4, §12; parser_binary.rs:1269-1324). */
    private fun readCount(objectOffset: Int, index: Int): Pair<Int, Int>? {
        if (objectOffset + 1 >= bytes.size) {
            recover(PlistCodes.BINARY_OFFSET_TABLE, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "index" to index.toString(),
                    "value" to "0x%x".format(objectOffset),
                ))
            return null
        }
        val marker = bytes[objectOffset + 1].toInt() and 0xFF
        if (marker !in 0x10..0x13) {
            recover(PlistCodes.BINARY_EXTENDED_SIZE, DiagnosticCategory.Syntax,
                arguments = mapOf(
                    "marker" to "0x%02x".format(marker),
                    "object" to index.toString(),
                ))
            return null
        }
        val width = 1 shl (marker and 0x0F)
        val value = readBeU64Top(bytes, objectOffset + 2, width)
        if (value > limits.maxExtendedSizeValue.toLong()) {
            throw plistLimit("extended-size-value", value.toInt(), limits.maxExtendedSizeValue)
        }
        extendedIntegers = checkedAdd(extendedIntegers.toLong(), 1L).toInt()
        if (extendedIntegers > limits.maxExtendedSizeIntegers) {
            throw plistLimit("extended-size-integers", extendedIntegers, limits.maxExtendedSizeIntegers)
        }
        return toUsize(value) to checkedAdd(1L, width.toLong()).toInt()
    }

    /** Verifies that every dictionary key target is a string object (RFC
     * 0013 §5.9; parser_binary.rs:1326-1354). */
    private fun verifyDictKeys(shapes: List<ObjectShape>, initialCut: Int): Int {
        var cut = initialCut
        for (index in 0 until cut) {
            val shape = shapes[index]
            if (shape.kind != ShapeKind.Dict) {
                continue
            }
            for (position in 0 until shape.keyCount) {
                val keyRef = shape.refs[position]
                if (keyRef.target >= cut) {
                    // The target is unproven; the unproven-reference rule
                    // decides native-document eligibility.
                    continue
                }
                if (!shapes[keyRef.target].kind.isString()) {
                    recover(PlistCodes.BINARY_NON_STRING_KEY, DiagnosticCategory.Syntax,
                        arguments = mapOf(
                            "key-object" to keyRef.target.toString(),
                            "object" to index.toString(),
                        ))
                    return index
                }
            }
        }
        return cut
    }

    /** Builds native values in object-table order (parser_binary.rs:1356-
     * 1449). Container natives hold element/entry ENTITY indices: value
     * entities occupy 0..cut-1 and the element/entry entities follow at
     * `cut + elementBase[i] + position`. */
    private fun buildValues(
        shapes: List<ObjectShape>,
        cut: Int,
        elementBase: IntArray,
        entryBase: IntArray,
    ): List<NativeValue> {
        val values = ArrayList<NativeValue>(cut)
        for ((index, shape) in shapes.take(cut).withIndex()) {
            val value = when (shape.kind) {
                ShapeKind.False -> NativeValue.BooleanV(false)
                ShapeKind.True -> NativeValue.BooleanV(true)
                ShapeKind.Integer -> {
                    val width = 1 shl (shape.marker and 0x0F)
                    NativeValue.Integer(readInteger(shape.payloadStart, width))
                }
                ShapeKind.Real -> {
                    val width = if (shape.marker == 0x22) 4 else 8
                    NativeValue.Real(readReal(shape.payloadStart, width))
                }
                ShapeKind.Date -> {
                    val seconds = java.lang.Double.longBitsToDouble(readBeU64Top(bytes, shape.payloadStart, 8))
                    NativeValue.Date(seconds)
                }
                ShapeKind.Data -> NativeValue.Data(
                    PlistData.fromBytes(bytes.copyOfRange(shape.payloadStart, shape.payloadStart + shape.count)),
                )
                ShapeKind.AsciiString -> NativeValue.StringV(
                    PlistString.fromCodeUnits(
                        IntArray(shape.count) { bytes[shape.payloadStart + it].toInt() and 0xFF },
                    ),
                )
                ShapeKind.Utf16String -> {
                    val units = IntArray(shape.count)
                    for (position in 0 until shape.count) {
                        units[position] = ((bytes[shape.payloadStart + position * 2].toInt() and 0xFF) shl 8) or
                            (bytes[shape.payloadStart + position * 2 + 1].toInt() and 0xFF)
                    }
                    NativeValue.StringV(PlistString.fromCodeUnits(units))
                }
                ShapeKind.Uid -> NativeValue.Uid(
                    PlistUid(readBeU64Top(bytes, shape.payloadStart, shape.count).toInt()),
                )
                ShapeKind.Array -> NativeValue.Array(
                    shape.refs.mapIndexed { position, _ -> cut + elementBase[index] + position },
                )
                ShapeKind.Dict -> NativeValue.Dict(emptyList())
            }
            values.add(value)
        }
        // Dictionary entries need the entry entity indices, which are only
        // known after every value exists (the entry entities follow the
        // value entities at cut..).
        for ((index, shape) in shapes.take(cut).withIndex()) {
            if (shape.kind == ShapeKind.Dict) {
                val entries = ArrayList<Int>(shape.keyCount)
                for (position in 0 until shape.keyCount) {
                    entries.add(cut + entryBase[index] + position)
                }
                values[index] = NativeValue.Dict(entries)
            }
        }
        return values
    }

    /** Recursive cycle and depth validation from one object (RFC 0013
     * §5.11); returns false when a cycle revisits an open object. */
    private fun visit(shapes: List<ObjectShape>, index: Int, depth: Int, open: HashSet<Int>): Boolean {
        if (!open.add(index)) {
            return false
        }
        if (depth > limits.maxContainerDepth) {
            throw plistLimit("container-depth", depth, limits.maxContainerDepth)
        }
        val shape = shapes[index]
        for (reference in shape.refs) {
            if (!visit(shapes, reference.target, depth + 1, open)) {
                return false
            }
        }
        open.remove(index)
        return true
    }

    /** Reads one signed integer payload of the given width (RFC 0013 §5.3):
     * 1/2/4-byte widths are unsigned, 8-byte is signed two's complement. */
    private fun readInteger(payloadStart: Int, width: Int): Long {
        val raw = readBeU64Top(bytes, payloadStart, width)
        return when (width) {
            8 -> raw.toLong()
            else -> raw
        }
    }

    /** Reads one real payload: 4-byte single or 8-byte double with the exact
     * bits (RFC 0013 §5.5). */
    private fun readReal(payloadStart: Int, width: Int): PlistReal =
        if (width == 4) {
            PlistReal.single(readBeU32(bytes, payloadStart))
        } else {
            PlistReal.double(readBeU64Top(bytes, payloadStart, 8))
        }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun span(start: Int, end: Int): Span = authority.span(start, end)

    private fun region(ordinal: Int, kind: String, start: Int, end: Int): BinaryRegion {
        val nodeRef = NodeRef(authority.identity, ordinal.toLong(), NodeRole.BinaryRegion)
        return BinaryRegion(nodeRef, span(start, end), kind)
    }

    private fun recordFact() {
        facts = checkedAdd(facts.toLong(), 1L).toInt()
        if (facts > limits.maxBinaryFacts) {
            throw plistLimit("binary-facts", facts, limits.maxBinaryFacts)
        }
    }

    private fun recover(code: String, category: DiagnosticCategory, arguments: Map<String, String> = emptyMap()) {
        recovered = true
        diagnostics.add(
            PlistDiagnostic(
                code = code,
                category = category,
                severity = Severity.Error,
                startByte = null,
                endByte = null,
                arguments = arguments,
                notes = emptyList(),
                occurrence = occurrence++,
            ),
        )
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        val sum = left + right
        if (sum > Int.MAX_VALUE.toLong() || sum < 0) {
            throw PlistFormationException(
                PlistCodes.BINARY_OVERFLOW,
                "plist binary parse: checked arithmetic overflow",
            )
        }
        return sum
    }

    private fun checkedMul(left: Long, right: Long): Long {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw PlistFormationException(
                PlistCodes.BINARY_OVERFLOW,
                "plist binary parse: checked multiplication overflow",
            )
        }
        val product = left * right
        if (product > Int.MAX_VALUE.toLong() || product < 0) {
            throw PlistFormationException(
                PlistCodes.BINARY_OVERFLOW,
                "plist binary parse: checked multiplication overflow",
            )
        }
        return product
    }

    private fun toUsize(value: Long): Int {
        if (value > Int.MAX_VALUE.toLong()) {
            throw PlistFormationException(
                PlistCodes.BINARY_OVERFLOW,
                "plist binary parse: size exceeds the host range",
            )
        }
        return value.toInt()
    }
}

/** Big-endian unsigned 32-bit read. */
private fun readBeU32(bytes: ByteArray, start: Int): Int =
    ((bytes[start].toInt() and 0xFF) shl 24) or
        ((bytes[start + 1].toInt() and 0xFF) shl 16) or
        ((bytes[start + 2].toInt() and 0xFF) shl 8) or
        (bytes[start + 3].toInt() and 0xFF)
