// Cross-representation conversion between `plist.xml@1` and
// `plist.binary@1`.
//
// Data authority:
//   - RFC 0013 §7 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md):
//     conversion is a first-class transform, not an internal detail; exact
//     when every native fact is expressible and atomic otherwise; each
//     conversion emits report events identifying the representation change
//     and the per-value provenance mapping; binary-only facts (UID values,
//     Float32 width facts, unpaired-surrogate strings, fractional-second
//     dates, shared object identity) fail conversion to XML atomically; the
//     round-trip contract is native-model equality across a chain of
//     conversions with every representation change reported.
//   - conformance/vectors/plist-v1.json (plist.conversion.*) pins the
//     outcomes; https://github.com/consema/consema-rs/blob/main/consema-plist/src/document.rs is the
//     byte-arbitration authority (convert_to document.rs,
//     convert_xml_to_binary document.rs, convert_binary_to_xml
//     document.rs, analyze document.rs, the XML serializer
//     document.rs, the failure codes document.rs).
//
// Kotlin-idiomatic design: the conversion is a Document extension returning
// the immutable ConvertedDocument; the target XML serializer emits the root
// value at depth 0 (the conversion render pinned by the vectors), distinct
// from the materializer's depth-1 root.

package consema.plist

import consema.document.FormationStatus
import consema.document.NodeRef
import consema.document.NodeRole
import kotlin.math.absoluteValue

/** One conversion report event kind (document.rs). */
enum class ConversionEventKind {
    /** The target representation differs from the source. */
    RepresentationChange,

    /** One source native value mapped to its target ordinal. */
    ValueMapped,
}

/** One ordered conversion report event. */
data class ConversionEvent(
    /** Stable event kind. */
    val kind: ConversionEventKind,
    /** Source native value ordinal (ValueMapped). */
    val sourceOrdinal: Int = -1,
    /** Target post-order rank or object index (ValueMapped). */
    val targetOrdinal: Int = -1,
)

/** Complete ordered conversion report (document.rs). */
class ConversionReport private constructor(private val events: List<ConversionEvent>) {
    companion object {
        internal fun new(events: List<ConversionEvent>): ConversionReport = ConversionReport(events)
    }

    /** Whether the representation changed. */
    fun representationChanged(): Boolean =
        events.any { it.kind == ConversionEventKind.RepresentationChange }

    /** Ordered events. */
    fun events(): List<ConversionEvent> = events

    override fun equals(other: Any?): Boolean =
        other is ConversionReport && events == other.events

    override fun hashCode(): Int = events.hashCode()
}

/** One successful cross-representation conversion (document.rs). */
data class ConvertedDocument(
    /** The new immutable target snapshot. */
    val document: Document,
    /** The conversion report with the representation change and value
     * mapping events. */
    val report: ConversionReport,
)

/** The typed conversion failure carrying the frozen `plist.conversion.*@1`
 * code (document.rs). */
class ConversionFailureException(
    /** The frozen conversion code. */
    val code: String,
    message: String = code,
    /** Stable limit name (RESOURCE_LIMIT). */
    val name: String = "",
    /** Stable arguments of the failure diagnostic. */
    val arguments: Map<String, String> = emptyMap(),
) : Exception(message)

/**
 * Converts this complete document to the other representation (RFC 0013 §7;
 * document.rs). The conversion is exact when every native fact is
 * expressible in the target representation and fails atomically otherwise;
 * each conversion emits report events identifying the representation change
 * and the per-value provenance mapping.
 */
fun Document.convertTo(
    target: PlistProfile,
    limits: PlistParseLimits = PlistParseLimits.default,
): ConvertedDocument {
    val sourceRepresentation = profile
    if (sourceRepresentation == target) {
        throw ConversionFailureException(PlistCodes.CONVERSION_SAME_REPRESENTATION)
    }
    if (formationStatus != FormationStatus.Complete || nativeRoot == null) {
        throw ConversionFailureException(
            PlistCodes.CONVERSION_FORMATION,
            arguments = mapOf("status" to "recovered"),
        )
    }
    return when (target) {
        PlistProfile.BinaryV1 -> convertXmlToBinary(limits)
        PlistProfile.XmlV1 -> convertBinaryToXml(limits)
    }
}

/** Converts one `plist.xml@1` document to `plist.binary@1` (document.rs
 *). */
private fun Document.convertXmlToBinary(limits: PlistParseLimits): ConvertedDocument {
    val valueIndices = valueOnlyIndices()
    val nodeCount = valueIndices.size
    if (nodeCount > limits.maxConversionNodes) {
        throw ConversionFailureException(
            PlistCodes.CONVERSION_RESOURCE_LIMIT,
            name = "conversion-nodes",
        )
    }
    // Count key objects: one per dictionary entry.
    var keyCount = 0
    for (index in valueIndices) {
        val native = (entities[index] as Entity.Value).entity.native
        if (native is NativeValue.Dict) {
            keyCount += native.entries.size
        }
    }
    val targetObjectCount = nodeCount + keyCount
    if (targetObjectCount > limits.maxObjectCount) {
        throw ConversionFailureException(
            PlistCodes.CONVERSION_RESOURCE_LIMIT,
            name = "object-count",
        )
    }
    val eventCount = 1 + nodeCount
    if (eventCount > limits.maxReportEvents) {
        throw ConversionFailureException(
            PlistCodes.CONVERSION_RESOURCE_LIMIT,
            name = "report-events",
        )
    }
    val bytes = conversionBinaryBytes()
    val formed = try {
        parseBinaryEntry(bytes, PlistEncodingSelection.ProfileDefault, limits)
    } catch (e: PlistFormationException) {
        throw ConversionFailureException(PlistCodes.CONVERSION_REPARSE)
    }
    if (formed.formationStatus != FormationStatus.Complete || !conversionClosureEqual(formed)) {
        throw ConversionFailureException(PlistCodes.CONVERSION_REPARSE)
    }
    // Mapping: node i lands after the key objects of every earlier
    // dictionary and its own (document.rs).
    val events = ArrayList<ConversionEvent>(eventCount)
    events.add(ConversionEvent(ConversionEventKind.RepresentationChange))
    var keysBefore = 0
    for ((position, index) in valueIndices.withIndex()) {
        val native = (entities[index] as Entity.Value).entity.native
        val dictKeys = (native as? NativeValue.Dict)?.entries?.size ?: 0
        events.add(
            ConversionEvent(
                ConversionEventKind.ValueMapped,
                sourceOrdinal = position,
                targetOrdinal = position + keysBefore + dictKeys,
            ),
        )
        keysBefore += dictKeys
    }
    return ConvertedDocument(formed, ConversionReport.new(events))
}

/** Serializes the native model as a binary object table: every dictionary's
 * key objects immediately precede their dictionary, and every node is
 * written fresh (document.rs; no scalar deduplication in the
 * conversion path). Node `i` lands after the key objects of every earlier
 * dictionary and its own (document.rs). */
private fun Document.conversionBinaryBytes(): ByteArray {
    val valueIndices = valueOnlyIndices()
    val ordinalOf = HashMap<Int, Int>()
    for ((ordinal, index) in valueIndices.withIndex()) {
        ordinalOf[index] = ordinal
    }
    // target_index[i] = i + keys of earlier dicts + the node's own keys.
    val targetIndex = IntArray(valueIndices.size)
    var keysBefore = 0
    for ((ordinal, index) in valueIndices.withIndex()) {
        val native = (entities[index] as Entity.Value).entity.native
        val dictKeys = (native as? NativeValue.Dict)?.entries?.size ?: 0
        targetIndex[ordinal] = ordinal + keysBefore + dictKeys
        keysBefore += dictKeys
    }
    val refSize = refSizeForConversion(valueIndices.size + keysBefore)
    val out = ArrayList<Byte>(1024)
    "bplist00".forEach { out.add(it.code.toByte()) }
    val offsets = ArrayList<Int>()
    for ((ordinal, index) in valueIndices.withIndex()) {
        val native = (entities[index] as Entity.Value).entity.native
            ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
        if (native is NativeValue.Dict) {
            for (entryIndex in native.entries) {
                val entry = dictEntryEntity(entryIndex)
                val keyNative = (valueEntityOf(entry.keyIndex).native as? NativeValue.StringV)
                    ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
                offsets.add(out.size)
                writeConversionString(out, keyNative.string)
            }
        }
        offsets.add(out.size)
        writeConversionValue(out, native, ordinal, targetIndex, ordinalOf, refSize)
    }
    val offsetTableOffset = out.size
    val offsetIntSize = refSizeForConversion(offsetTableOffset)
    for (offset in offsets) {
        writeConversionBe(out, offset.toLong(), offsetIntSize)
    }
    repeat(5) { out.add(0) }
    out.add(0)
    out.add(offsetIntSize.toByte())
    out.add(refSize.toByte())
    writeConversionBe(out, offsets.size.toLong(), 8)
    // topObject is the root's target ordinal (document.rs).
    val rootOrdinal = ordinalOf[rootIndex] ?: 0
    writeConversionBe(out, targetIndex[rootOrdinal].toLong(), 8)
    writeConversionBe(out, offsetTableOffset.toLong(), 8)
    return out.toByteArray()
}

/** One ref width for the conversion writer. */
private fun refSizeForConversion(maxIndex: Int): Int {
    var size = 1
    var capacity = 256L
    while (maxIndex >= capacity && size < 8) {
        size += 1
        capacity = if (capacity > Long.MAX_VALUE / 256) Long.MAX_VALUE else capacity * 256
    }
    return size
}

private fun writeConversionBe(out: ArrayList<Byte>, value: Long, width: Int) {
    for (shift in (0 until width).reversed()) {
        out.add(((value ushr (8 * shift)) and 0xFF).toByte())
    }
}

private fun writeConversionString(out: ArrayList<Byte>, string: PlistString) {
    val units = string.codeUnits()
    if (units.all { it < 0x80 }) {
        writeConversionSized(out, 0x50, units.size)
        for (unit in units) {
            out.add(unit.toByte())
        }
    } else {
        writeConversionSized(out, 0x60, units.size)
        for (unit in units) {
            out.add((unit ushr 8).toByte())
            out.add(unit.toByte())
        }
    }
}

private fun writeConversionSized(out: ArrayList<Byte>, marker: Int, count: Int) {
    if (count < 0x0F) {
        out.add((marker or count).toByte())
        return
    }
    out.add((marker or 0x0F).toByte())
    val width = if (count <= 0xFF) 1 else if (count <= 0xFFFF) 2 else 4
    out.add((0x10 or java.lang.Integer.numberOfTrailingZeros(width)).toByte())
    writeConversionBe(out, count.toLong(), width)
}

private fun Document.writeConversionValue(
    out: ArrayList<Byte>,
    native: NativeValue,
    sourceOrdinal: Int,
    targetIndex: IntArray,
    ordinalOf: HashMap<Int, Int>,
    refSize: Int,
) {
    when (native) {
        is NativeValue.BooleanV -> out.add(if (native.value) 0x09 else 0x08)
        is NativeValue.Integer -> {
            val width = if (native.value >= 0) {
                when {
                    native.value <= 0xFF -> 1
                    native.value <= 0xFFFF -> 2
                    native.value <= 0xFFFF_FFFFL -> 4
                    else -> 8
                }
            } else {
                8
            }
            out.add((0x10 or java.lang.Integer.numberOfTrailingZeros(width)).toByte())
            writeConversionBe(out, native.value.toULong().toLong(), width)
        }
        is NativeValue.Real -> when (native.real.width) {
            RealWidth.Float64 -> {
                out.add(0x23)
                writeConversionBe(out, native.real.bits, 8)
            }
            RealWidth.Float32 -> {
                out.add(0x22)
                writeConversionBe(out, native.real.bits, 4)
            }
        }
        is NativeValue.Date -> {
            out.add(0x33)
            writeConversionBe(out, native.seconds.toRawBits(), 8)
        }
        is NativeValue.Data -> {
            writeConversionSized(out, 0x40, native.data.len)
            for (byte in native.data.bytes()) {
                out.add(byte)
            }
        }
        is NativeValue.StringV -> writeConversionString(out, native.string)
        is NativeValue.Uid -> {
            val value = native.uid.toLong()
            val width = when {
                value <= 0xFF -> 1
                value <= 0xFFFF -> 2
                value <= 0xFF_FFFF -> 3
                else -> 4
            }
            out.add((0x80 or (width - 1)).toByte())
            writeConversionBe(out, value, width)
        }
        is NativeValue.Array -> {
            writeConversionSized(out, 0xA0, native.elements.size)
            for (elementIndex in native.elements) {
                val valueIndex = arrayElementEntity(elementIndex).valueIndex
                val ordinal = ordinalOf[valueIndex]
                    ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
                writeConversionBe(out, targetIndex[ordinal].toLong(), refSize)
            }
        }
        is NativeValue.Dict -> {
            writeConversionSized(out, 0xD0, native.entries.size)
            // The dict's own key objects immediately precede it
            // (document.rs).
            val keyStart = targetIndex[sourceOrdinal] - native.entries.size
            for (position in 0 until native.entries.size) {
                writeConversionBe(out, (keyStart + position).toLong(), refSize)
            }
            for (entryIndex in native.entries) {
                val entry = dictEntryEntity(entryIndex)
                val ordinal = ordinalOf[entry.valueIndex]
                    ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
                writeConversionBe(out, targetIndex[ordinal].toLong(), refSize)
            }
        }
    }
}

/** Converts one `plist.binary@1` document to `plist.xml@1` (document.rs
 *). The reachable value graph is validated for XML expressibility
 * first; any binary-only fact fails the whole conversion atomically. */
private fun Document.convertBinaryToXml(limits: PlistParseLimits): ConvertedDocument {
    val valueIndices = valueOnlyIndices()
    if (valueIndices.size > limits.maxConversionNodes) {
        throw ConversionFailureException(
            PlistCodes.CONVERSION_RESOURCE_LIMIT,
            name = "conversion-nodes",
        )
    }
    // Reachable-graph analysis with expressibility validation
    // (document.rs).
    val graph = analyzeXmlExpressibility(valueIndices, limits)
    val eventCount = 1 + graph.reachable.size
    if (eventCount > limits.maxReportEvents) {
        throw ConversionFailureException(
            PlistCodes.CONVERSION_RESOURCE_LIMIT,
            name = "report-events",
        )
    }
    val bytes = conversionXmlBytes(graph)
    val formed = try {
        parseXml(bytes, PlistEncodingSelection.ProfileDefault, limits)
    } catch (e: PlistFormationException) {
        throw ConversionFailureException(PlistCodes.CONVERSION_REPARSE)
    }
    if (formed.formationStatus != FormationStatus.Complete || !conversionClosureEqual(formed)) {
        throw ConversionFailureException(PlistCodes.CONVERSION_REPARSE)
    }
    val events = ArrayList<ConversionEvent>(eventCount)
    events.add(ConversionEvent(ConversionEventKind.RepresentationChange))
    for (index in graph.reachable) {
        events.add(
            ConversionEvent(
                ConversionEventKind.ValueMapped,
                sourceOrdinal = index,
                targetOrdinal = graph.ranks[index],
            ),
        )
    }
    return ConvertedDocument(formed, ConversionReport.new(events))
}

/** The reachable graph with post-order ranks and the XML expressibility
 * verdict (document.rs). */
private class ReachableGraph(
    val reachable: List<Int>,
    val ranks: IntArray,
    val children: Map<Int, List<Int>>,
    val nodeRefs: Map<Int, NodeRef>,
)

private fun Document.analyzeXmlExpressibility(
    valueIndices: List<Int>,
    limits: PlistParseLimits,
): ReachableGraph {
    // Incoming reference counts over the whole arena (shared identity).
    // Container natives hold association ENTITY indices; the value targets
    // resolve through the entry/element entities.
    val incoming = HashMap<Int, Int>()
    for (index in valueIndices) {
        val native = (entities[index] as Entity.Value).entity.native ?: continue
        when (native) {
            is NativeValue.Array -> for (element in native.elements) {
                val valueIndex = arrayElementEntity(element).valueIndex
                incoming[valueIndex] = (incoming[valueIndex] ?: 0) + 1
            }
            is NativeValue.Dict -> for (entryIndex in native.entries) {
                val entry = dictEntryEntity(entryIndex)
                incoming[entry.keyIndex] = (incoming[entry.keyIndex] ?: 0) + 1
                incoming[entry.valueIndex] = (incoming[entry.valueIndex] ?: 0) + 1
            }
            else -> {}
        }
    }
    // Expressibility and reachability walk from the root.
    val reachable = ArrayList<Int>()
    val visited = HashSet<Int>()
    fun walk(index: Int) {
        if (!visited.add(index)) {
            return
        }
        reachable.add(index)
        val native = (entities[index] as Entity.Value).entity.native ?: return
        when (native) {
            is NativeValue.Uid ->
                throw ConversionFailureException(PlistCodes.CONVERSION_INEXPRESSIBLE,
                    arguments = mapOf("fact" to "uid"))
            is NativeValue.Real ->
                if (native.real.width == RealWidth.Float32) {
                    throw ConversionFailureException(PlistCodes.CONVERSION_INEXPRESSIBLE,
                        arguments = mapOf("fact" to "float32"))
                }
            is NativeValue.StringV ->
                if (native.string.status == PlistStringStatus.UnpairedSurrogate ||
                    !isXmlConvertibleText(native.string)
                ) {
                    throw ConversionFailureException(PlistCodes.CONVERSION_INEXPRESSIBLE,
                        arguments = mapOf("fact" to "string"))
                }
            is NativeValue.Date ->
                if (native.seconds % 1.0 != 0.0 || native.seconds.toRawBits() ==
                    java.lang.Double.doubleToRawLongBits(-0.0)
                ) {
                    throw ConversionFailureException(PlistCodes.CONVERSION_INEXPRESSIBLE,
                        arguments = mapOf("fact" to "date"))
                }
            else -> {}
        }
        if ((incoming[index] ?: 0) > 1 && index != rootIndex) {
            throw ConversionFailureException(PlistCodes.CONVERSION_INEXPRESSIBLE,
                arguments = mapOf("fact" to "shared-identity"))
        }
        when (native) {
            is NativeValue.Array -> for (element in native.elements) {
                walk(arrayElementEntity(element).valueIndex)
            }
            is NativeValue.Dict -> for (entryIndex in native.entries) {
                val entry = dictEntryEntity(entryIndex)
                walk(entry.keyIndex)
                walk(entry.valueIndex)
            }
            else -> {}
        }
    }
    walk(rootIndex)
    // Post-order ranks: the value-only close order of the emitted XML.
    val ranks = IntArray(valueIndices.size) { -1 }
    val children = HashMap<Int, List<Int>>()
    val nodeRefs = HashMap<Int, NodeRef>()
    var rank = 0
    fun postOrder(index: Int) {
        if (ranks[index] >= 0) {
            return
        }
        val native = (entities[index] as Entity.Value).entity.native ?: return
        val kids = ArrayList<Int>()
        when (native) {
            is NativeValue.Array -> for (element in native.elements) {
                val valueIndex = arrayElementEntity(element).valueIndex
                kids.add(valueIndex)
                postOrder(valueIndex)
            }
            is NativeValue.Dict -> for (entryIndex in native.entries) {
                val entry = dictEntryEntity(entryIndex)
                kids.add(entry.valueIndex)
                postOrder(entry.valueIndex)
            }
            else -> {}
        }
        children[index] = kids
        ranks[index] = rank
        rank += 1
        nodeRefs[index] = nodeRef(index.toLong(), NodeRole.PlistValue)
    }
    postOrder(rootIndex)
    return ReachableGraph(reachable, ranks, children, nodeRefs)
}

private fun isXmlConvertibleText(string: PlistString): Boolean {
    val units = string.codeUnits()
    var index = 0
    while (index < units.size) {
        val unit = units[index]
        val scalar = if (unit in 0xD800..0xDBFF) {
            if (index + 1 < units.size && units[index + 1] in 0xDC00..0xDFFF) {
                index += 2
                0x1_0000 + ((unit - 0xD800) shl 10) + (units[index - 1] - 0xDC00)
            } else {
                return false
            }
        } else if (unit in 0xDC00..0xDFFF) {
            return false
        } else {
            index += 1
            unit
        }
        if (scalar != 0x9 && scalar != 0xA && scalar != 0xD &&
            !(scalar in 0x20..0xD7FF) && !(scalar in 0xE000..0xFFFD) &&
            !(scalar in 0x1_0000..0x10_FFFF)
        ) {
            return false
        }
    }
    return true
}

/** One serializer frame: an open container with the next child cursor, or a
 * close-tag pass. */
private class XmlFrame(val node: Int, val depth: Int, val childCursor: Int)

/** Serializes the reachable native graph as XML with the root value at
 * depth 0 (document.rs; the conversion render pinned by the
 * vectors). */
private fun Document.conversionXmlBytes(graph: ReachableGraph): ByteArray {
    val out = StringBuilder()
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    out.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
    out.append("<plist version=\"1.0\">\n")
    val rootNative = (entities[rootIndex] as Entity.Value).entity.native
        ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
    if (rootNative is NativeValue.Dict || rootNative is NativeValue.Array) {
        val stack = ArrayDeque<XmlFrame>()
        stack.add(XmlFrame(rootIndex, 0, 0))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val native = (entities[frame.node] as Entity.Value).entity.native
                ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
            val kids = graph.children[frame.node] ?: emptyList()
            if (frame.childCursor == 0) {
                writeConversionIndent(out, frame.depth)
                when (native) {
                    is NativeValue.Dict -> {
                        if (kids.isEmpty()) {
                            out.append("<dict></dict>\n")
                            continue
                        }
                        out.append("<dict>\n")
                    }
                    is NativeValue.Array -> {
                        if (kids.isEmpty()) {
                            out.append("<array></array>\n")
                            continue
                        }
                        out.append("<array>\n")
                    }
                    else -> throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
                }
            }
            if (frame.childCursor < kids.size) {
                stack.add(XmlFrame(frame.node, frame.depth, frame.childCursor + 1))
                val child = kids[frame.childCursor]
                if (native is NativeValue.Dict) {
                    val entry = dictEntryEntity(native.entries[frame.childCursor])
                    val key = (entities[entry.keyIndex] as Entity.Value).entity.native
                        as? NativeValue.StringV
                        ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
                    writeConversionIndent(out, frame.depth + 1)
                    out.append("<key>")
                    writeConversionEscape(out, key.string.toUnicode() ?: "")
                    out.append("</key>\n")
                }
                val childNative = (entities[child] as Entity.Value).entity.native
                if (childNative is NativeValue.Dict || childNative is NativeValue.Array) {
                    stack.add(XmlFrame(child, frame.depth + 1, 0))
                } else {
                    writeConversionScalar(out, child, frame.depth + 1)
                }
            } else {
                writeConversionIndent(out, frame.depth)
                when (native) {
                    is NativeValue.Dict -> out.append("</dict>\n")
                    is NativeValue.Array -> out.append("</array>\n")
                    else -> throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
                }
            }
        }
    } else {
        writeConversionScalar(out, rootIndex, 0)
    }
    out.append("</plist>\n")
    return out.toString().toByteArray(Charsets.UTF_8)
}

private fun writeConversionIndent(out: StringBuilder, depth: Int) {
    repeat(depth) { out.append("    ") }
}

private fun writeConversionEscape(out: StringBuilder, text: String) {
    for (character in text) {
        when (character) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '\r' -> out.append("&#13;")
            else -> out.append(character)
        }
    }
}

private fun Document.writeConversionScalar(out: StringBuilder, index: Int, depth: Int) {
    val native = (entities[index] as Entity.Value).entity.native
        ?: throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
    writeConversionIndent(out, depth)
    when (native) {
        is NativeValue.StringV -> {
            out.append("<string>")
            writeConversionEscape(out, native.string.toUnicode()
                ?: throw ConversionFailureException(PlistCodes.CONVERSION_INEXPRESSIBLE,
                    arguments = mapOf("fact" to "string")))
            out.append("</string>\n")
        }
        is NativeValue.Integer -> {
            out.append("<integer>")
            out.append(native.value.toString())
            out.append("</integer>\n")
        }
        is NativeValue.Real -> {
            out.append("<real>")
            out.append(renderConversionReal(native.real))
            out.append("</real>\n")
        }
        is NativeValue.BooleanV -> out.append(if (native.value) "<true/>\n" else "<false/>\n")
        is NativeValue.Date -> {
            val fields = wholeSecondDateConversion(native.seconds)
                ?: throw ConversionFailureException(PlistCodes.CONVERSION_INEXPRESSIBLE,
                    arguments = mapOf("fact" to "date"))
            out.append("<date>")
            out.append(
                String.format(
                    java.util.Locale.ROOT,
                    "%s%04d-%02d-%02dT%02d:%02d:%02dZ",
                    if (fields.year < 0) "-" else "",
                    fields.year.absoluteValue, fields.month, fields.day,
                    fields.hour, fields.minute, fields.second,
                ),
            )
            out.append("</date>\n")
        }
        is NativeValue.Data -> {
            out.append("<data>")
            out.append(encodeConversionBase64(native.data.bytes(), depth))
            out.append("</data>\n")
        }
        else -> throw ConversionFailureException(PlistCodes.CONVERSION_INTERNAL)
    }
}

private fun renderConversionReal(real: PlistReal): String {
    val value = real.asDouble()
    return when {
        value.isNaN() -> "nan"
        value.isInfinite() -> if (negativeSign(value)) "-inf" else "inf"
        else -> value.toString()
    }
}

private fun wholeSecondDateConversion(seconds: Double): DateFieldsConversion? {
    if (seconds % 1.0 != 0.0) {
        return null
    }
    val unix = seconds + 978307200.0
    if (kotlin.math.abs(unix) >= 9_007_199_254_740_992.0) {
        return null
    }
    val unixInt = unix.toLong()
    val days = Math.floorDiv(unixInt, 86_400L)
    val secondsOfDay = Math.floorMod(unixInt, 86_400L)
    val z = days + 719_468
    val era = if (z >= 0) z else z - 146_096
    val eraNormalized = era / 146_097
    val dayOfEra = z - eraNormalized * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    var year = yearOfEra + eraNormalized * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
    val month = monthPrime + if (monthPrime < 10) 3 else -9
    year += if (month <= 2) 1 else 0
    if (year.absoluteValue > 0xFFFF_FFFFL) {
        return null
    }
    return DateFieldsConversion(
        year, month, day,
        secondsOfDay / 3_600, (secondsOfDay % 3_600) / 60, secondsOfDay % 60,
    )
}

private data class DateFieldsConversion(val year: Long, val month: Long, val day: Long, val hour: Long, val minute: Long, val second: Long)

/** Base64 wrapping at `76 - 8 * depth` per line (RFC 0013 §4.8). */
private fun encodeConversionBase64(bytes: ByteArray, depth: Int): String {
    val budget = (76 - 8 * depth).coerceAtLeast(1)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = StringBuilder()
    var line = 0
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        val second = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
        val third = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
        val chunkLen = minOf(3, bytes.size - index)
        if (line + 4 > budget && line > 0) {
            out.append('\n')
            writeConversionIndent(out, depth)
            line = 0
        }
        out.append(alphabet[first ushr 2])
        out.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
        out.append(if (chunkLen > 1) alphabet[((second and 0x0F) shl 2) or (third ushr 6)] else '=')
        out.append(if (chunkLen > 2) alphabet[third and 0x3F] else '=')
        line += 4
        index += 3
    }
    return out.toString()
}

/** Value-only entity indices in entity order (XML: close order; binary:
 * object-table order). */
private fun Document.valueOnlyIndices(): List<Int> =
    entities.indices.filter {
        (entities[it] as? Entity.Value)?.entity?.isKey == false
    }

private fun Document.valueEntityOf(index: Int): ValueEntity = (entities[index] as Entity.Value).entity

/** Structural equality of the reparsed target and this source native model
 * (RFC 0013 §7 round-trip contract). */
private fun Document.conversionClosureEqual(target: Document): Boolean {
    val sourceRoot = valueEntityOf(rootIndex).native ?: return false
    return closureNodeEqual(sourceRoot, target, target.rootIndex)
}

private fun Document.closureNodeEqual(source: NativeValue, target: Document, targetIndex: Int): Boolean {
    val targetNative = target.valueEntity(targetIndex).native ?: return false
    return when (source) {
        is NativeValue.Dict -> {
            val dict = targetNative as? NativeValue.Dict ?: return false
            if (dict.entries.size != source.entries.size) return false
            for ((position, entryIndex) in source.entries.withIndex()) {
                val sourceEntry = dictEntryEntity(entryIndex)
                val targetEntry = target.dictEntryEntity(dict.entries[position])
                val sourceKey = valueEntityOf(sourceEntry.keyIndex).native as? NativeValue.StringV
                    ?: return false
                val targetKey = target.valueEntity(targetEntry.keyIndex).native
                    as? NativeValue.StringV ?: return false
                if (sourceKey.string != targetKey.string) return false
                if (!closureNodeEqual(
                        valueEntityOf(sourceEntry.valueIndex).native ?: return false,
                        target,
                        targetEntry.valueIndex,
                    )
                ) {
                    return false
                }
            }
            true
        }
        is NativeValue.Array -> {
            val array = targetNative as? NativeValue.Array ?: return false
            if (array.elements.size != source.elements.size) return false
            for ((position, elementIndex) in source.elements.withIndex()) {
                val sourceValue = valueEntityOf(
                    arrayElementEntity(elementIndex).valueIndex,
                ).native ?: return false
                val targetElement = target.arrayElementEntity(array.elements[position])
                if (!closureNodeEqual(sourceValue, target, targetElement.valueIndex)) {
                    return false
                }
            }
            true
        }
        is NativeValue.StringV -> targetNative == source
        is NativeValue.Integer -> targetNative == source
        is NativeValue.Real -> targetNative == source
        is NativeValue.BooleanV -> targetNative == source
        is NativeValue.Date -> targetNative == source
        is NativeValue.Data -> targetNative == source
        is NativeValue.Uid -> targetNative == source
    }
}

/** The sign bit of one double (true for -0.0, -inf, and negatives). */
private fun negativeSign(value: Double): Boolean =
    java.lang.Double.doubleToRawLongBits(value) < 0
