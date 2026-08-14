// Canonical `hcl.canonical-document@1` materialization (RFC 0014 §9).
//
// Data authority:
//   - RFC 0014 §9 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md:574-628):
//     materialization consumes a validated `hcl.body@1` record (or, under
//     the tfvars profile, an attribute-only `hcl.body@1`) and creates a new
//     Document; it is not a formatter for an existing source. The canonical
//     style emits UTF-8 without BOM, LF line endings, two-space indentation
//     per body nesting level, `name = value` attributes, block headers as
//     `type "label" {`, and a trailing newline after the final item;
//     strings re-quote with minimal deterministic escapes; numbers emit
//     their canonical decimal spelling; booleans and null emit `true`,
//     `false`, and `null`; tuples and objects emit with comma separators,
//     deterministic one-item-per-line layout, and `=` keys; `hcl.expression@1`
//     values emit their canonical text and must reparse to the same
//     structural fingerprint; labels are always quoted with double quotes.
//     The record accepts both the raw typed member (the form projection
//     publishes) and the equivalent value record with a string `kind`
//     member (the form pinned by the conformance vectors). The tfvars
//     profile accepts only attribute-only records; a record containing a
//     block fails `hcl.materialization.unrepresentable@1`. Every style
//     validates the complete input, encodes, reparses the exact generated
//     bytes, and compares the reparsed native model to the promised input
//     semantics; failure returns no target Document, partial bytes, or
//     partial provenance.
//   - RFC 0004 §3, §7-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-structural-edit-v1.md:56-92, 171-218): the common
//     MaterializationRequest fields and the completion algebra.
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/materialization.rs pins the record validation
//     (materialization.rs:232-264), the request validation
//     (materialization.rs:267-285), the canonical layout, and the closure
//     verification; the failure mapping is materialization.rs:124-133
//     (Unrepresentable -> hcl.materialization.unrepresentable@1,
//     ResourceLimit -> hcl.materialization.resource-limit@1,
//     InvalidRequest/Unsupported*/FormationFailed -> core.materialization.*@1).
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: the completion algebra is a sealed class; the
// family failure record carries the frozen `hcl.materialization.*@1` codes
// (RFC 0014 §11: the `hcl.*` codes never enter the `consema-protocol` core
// registry), and the shared request/limits/provenance shapes come from
// consema.document.

package consema.hcl

import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.CompleteMaterialization
import consema.document.MaterializationFidelity
import consema.document.MaterializationInputLocation
import consema.document.MaterializationLimits
import consema.document.MaterializationProvenanceEntry
import consema.document.MaterializationProvenanceMap
import consema.document.MaterializationRelation
import consema.document.MaterializationReport
import consema.document.MaterializationRequest
import consema.document.MaterializedOrigin
import consema.document.NewlinePolicy
import consema.document.NodeRole
import consema.document.SourceEncoding
import java.math.BigInteger

/** The closed family failure category of one materialization (RFC 0014
 * §9; materialization.rs:124-133). */
sealed class HclMaterializationFailure {
    /** A record fact cannot be expressed under the promised profile: the
     * tfvars block restriction, invalid attribute names and block types,
     * duplicate attributes in one body, non-finite reals, or unexpressible
     * record shapes. */
    data class Unrepresentable(val reason: String) : HclMaterializationFailure()

    /** A configured limit was reached; no partial output exists. */
    data class ResourceLimit(val name: String) : HclMaterializationFailure()

    /** Request fields contradict the target contract. */
    data object InvalidRequest : HclMaterializationFailure()

    /** The target profile is unavailable. */
    data object UnsupportedProfile : HclMaterializationFailure()

    /** The style is unavailable for the target profile. */
    data object UnsupportedStyle : HclMaterializationFailure()

    /** The encoding is unavailable for the style. */
    data object UnsupportedEncoding : HclMaterializationFailure()

    /** The newline policy is unavailable for the style. */
    data object UnsupportedNewline : HclMaterializationFailure()

    /** The generated bytes did not form a Complete document under the
     * promised profile, or the reparsed model disagrees with the promised
     * input semantics. */
    data object FormationFailed : HclMaterializationFailure()

    /** The frozen registered code (materialization.rs:124-133). */
    val code: String
        get() = when (this) {
            is Unrepresentable -> HCL_MATERIALIZATION_UNREPRESENTABLE
            is ResourceLimit -> HCL_MATERIALIZATION_RESOURCE_LIMIT
            InvalidRequest -> "core.materialization.invalid-request@1"
            UnsupportedProfile -> "core.materialization.unsupported-profile@1"
            UnsupportedStyle -> "core.materialization.unsupported-style@1"
            UnsupportedEncoding -> "core.materialization.unsupported-encoding@1"
            UnsupportedNewline -> "core.materialization.unsupported-newline@1"
            FormationFailed -> "core.materialization.formation-failed@1"
        }
}

/** The typed family materialization failure. */
class HclMaterializationException(val failure: HclMaterializationFailure) :
    Exception("hcl materialization: ${failure.code}") {
    /** The frozen registered code of the failure. */
    val code: String
        get() = failure.code
}

/** The closed materialization completion algebra (RFC 0004 §7): exactly one
 * of Complete or Failed; failed attempts contain no Document and no partial
 * output bytes. */
sealed class HclMaterializationResult {
    /** Complete success with every required artifact. */
    data class Complete(
        val materialization: CompleteMaterialization<HclDocument>,
    ) : HclMaterializationResult()

    /** Failed attempt without a Document or partial output bytes. */
    data class Failed(
        val failure: HclMaterializationFailure,
        /** Stable input paths analyzed before failure. */
        val analyzedInputPaths: List<ValuePath>,
    ) : HclMaterializationResult()
}

/**
 * Materializes one validated `hcl.body@1` record into a new canonical HCL
 * document (RFC 0014 §9; materialization.rs:213-226). A failure contains
 * no Document, no partial bytes, and no provenance that can be mistaken for
 * a result (RFC 0004 §3).
 */
fun materialize(
    value: PortableValue,
    request: MaterializationRequest,
): HclMaterializationResult {
    val analyzed = ArrayList<ValuePath>()
    return try {
        HclMaterializationResult.Complete(materializeComplete(value, request, analyzed))
    } catch (e: HclMaterializationException) {
        HclMaterializationResult.Failed(e.failure, analyzed)
    }
}

private fun materializeComplete(
    value: PortableValue,
    request: MaterializationRequest,
    analyzed: ArrayList<ValuePath>,
): CompleteMaterialization<HclDocument> {
    val target = validateRequest(request)
    val limits = request.limits
    val record = Record.validate(value, request, analyzed)
    val writer = CanonicalWriter(limits, analyzed)
    writeBody(writer, record.body, 0)
    val bytes = writer.finish()
    val profile = if (target == Target.Tfvars) HclProfile.TFVARS_V1 else HclProfile.NATIVE_V1
    val document = try {
        parse(bytes, profile, parseLimits(limits))
    } catch (e: HclFormationException) {
        throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
    }
    if (document.formationStatus() != consema.document.FormationStatus.Complete) {
        throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
    }
    verifyClosure(record, document, limits)
    val provenance = buildProvenance(document, limits)
    return CompleteMaterialization(
        document = document,
        fidelity = MaterializationFidelity.Exact,
        report = MaterializationReport.new(emptyList(), limits),
        provenance = provenance,
    )
}

/** The canonical materialization style (RFC 0014 §9). */
private enum class Target {
    /** `hcl.native@1`: any body is representable. */
    Native,

    /** `hcl.tfvars@1`: attribute-only records only (RFC 0014 §5, §9). */
    Tfvars,
}

/** Validates the request against the frozen style contract (RFC 0014 §9;
 * materialization.rs:267-285). */
private fun validateRequest(request: MaterializationRequest): Target {
    val profile = request.targetProfile
    val style = request.style
    val target = when {
        profile.id == "hcl.native" && profile.version == 1 &&
            style.id == HclStyle.CANONICAL_DOCUMENT && style.version == 1 -> Target.Native

        profile.id == "hcl.tfvars" && profile.version == 1 &&
            style.id == HclStyle.CANONICAL_DOCUMENT && style.version == 1 -> Target.Tfvars

        (profile.id != "hcl.native" && profile.id != "hcl.tfvars") || profile.version != 1 ->
            throw HclMaterializationException(HclMaterializationFailure.UnsupportedProfile)

        else -> throw HclMaterializationException(HclMaterializationFailure.UnsupportedStyle)
    }
    if (request.encoding !== SourceEncoding.Utf8) {
        throw HclMaterializationException(HclMaterializationFailure.UnsupportedEncoding)
    }
    if (request.newline != NewlinePolicy.Lf) {
        throw HclMaterializationException(HclMaterializationFailure.UnsupportedNewline)
    }
    return target
}

/** Parse limits for the closure reparse, derived from the request so a
 * bounded input cannot fail its own closure (materialization.rs:295-328). */
private fun parseLimits(limits: MaterializationLimits): HclParseLimits =
    HclParseLimits(
        common = consema.document.ParseLimits(
            maxSourceBytes = limits.maxOutputBytes,
            maxNestingDepth = limits.maxDepth + 2,
            maxTokenCount = limits.maxOutputBytes,
            maxNodeCount = limits.maxOutputBytes,
            maxDiagnostics = limits.maxReportEntries,
        ),
        maxDecodedUtf8Bytes = limits.maxOutputBytes * 3,
        maxDecodedScalars = limits.maxOutputBytes,
        maxBodyDepth = limits.maxDepth + 2,
        maxExpressionDepth = limits.maxDepth + 2,
        maxTemplateDepth = limits.maxDepth + 2,
        maxAttributeCount = limits.maxInputNodes,
        maxBlockCount = limits.maxInputNodes,
        maxLabelCount = limits.maxInputNodes,
        maxBodyItemCount = limits.maxInputNodes * 2,
        maxIdentifierLen = limits.maxOutputBytes,
        maxStringLen = limits.maxOutputBytes,
        maxNumberDigits = limits.maxOutputBytes,
        maxTemplateLen = limits.maxOutputBytes,
        maxTemplateInterpolations = limits.maxInputNodes,
        maxHeredocLines = limits.maxOutputBytes,
        maxHeredocBytes = limits.maxOutputBytes,
        maxTupleElements = limits.maxInputNodes,
        maxObjectEntries = limits.maxInputNodes,
        maxForExtent = limits.maxInputNodes,
        maxRecoveryRegions = limits.maxReportEntries,
        maxErrorRegions = limits.maxReportEntries,
        maxSyntaxPieces = limits.maxOutputBytes,
        maxReportEvents = limits.maxReportEntries,
    )

// ---------------------------------------------------------------------------
// Record validation
// ---------------------------------------------------------------------------

/** One validated body. */
private class Body(val items: List<BodyItem>)

/** One validated body item. */
private sealed class BodyItem {
    abstract val path: ValuePath

    /** One attribute occurrence. */
    data class Attribute(
        override val path: ValuePath,
        val name: String,
        val value: ValueNode,
    ) : BodyItem()

    /** One block occurrence. */
    data class Block(
        override val path: ValuePath,
        val blockType: String,
        val labels: List<String>,
        val body: Body,
    ) : BodyItem()
}

/** One validated value node (either spelling). */
private sealed class ValueNode {
    abstract val path: ValuePath

    data class String(override val path: ValuePath, val text: kotlin.String) : ValueNode()
    data class Integer(override val path: ValuePath, val value: BigInteger) : ValueNode()
    data class Real(override val path: ValuePath, val value: PvDecimal) : ValueNode()
    data class Boolean(override val path: ValuePath, val value: kotlin.Boolean) : ValueNode()
    data class Null(override val path: ValuePath) : ValueNode()
    data class Tuple(override val path: ValuePath, val elements: List<ValueNode>) : ValueNode()
    data class Object(
        override val path: ValuePath,
        val entries: List<Pair<kotlin.String, ValueNode>>,
    ) : ValueNode()

    data class Expression(
        override val path: ValuePath,
        val kind: kotlin.String,
        val text: kotlin.String,
        val fingerprint: ULong?,
    ) : ValueNode()
}

/** One validated record; the validation follows materialization.rs:232-264
 * and the RFC 0014 §9 record contract. */
private class RecordValidator(
    private val target: Target,
    private val limits: MaterializationLimits,
    private val analyzed: ArrayList<ValuePath>,
) {
    private var nodes = 0

    private fun step(path: ValuePath) {
        nodes += 1
        analyzed.add(path)
        if (nodes > limits.maxInputNodes) {
            throw HclMaterializationException(HclMaterializationFailure.ResourceLimit("input-nodes"))
        }
    }

    fun validateBody(value: PortableValue, path: ValuePath): Body {
        step(path)
        val objectValue = value as? PvObject
            ?: throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("body-record"),
            )
        val entries = objectValue.entries()
        // A body record declares `items`; only the top-level record also
        // declares the `record` discriminator (materialization.rs:384-414
        // versus 525-542; the nested block bodies in the vectors carry no
        // discriminator). The top-level identity is checked in
        // Record.validate before this entry.
        val itemsValue = when (entries.size) {
            1 -> if (entries[0].key == "items") {
                entries[0].value
            } else {
                throw HclMaterializationException(
                    HclMaterializationFailure.Unrepresentable("body-record"),
                )
            }
            2 -> if (entries[0].key == "record" && entries[1].key == "items") {
                entries[1].value
            } else {
                throw HclMaterializationException(
                    HclMaterializationFailure.Unrepresentable("body-record"),
                )
            }
            else -> throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("body-record"),
            )
        }
        if (itemsValue !is PvArray) {
            throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("body-record"),
            )
        }
        val items = ArrayList<BodyItem>()
        val names = HashSet<String>()
        for ((index, itemValue) in itemsValue.items().withIndex()) {
            val itemPath = path.child(ValuePathSegment.ObjectValue("items"))
                .child(ValuePathSegment.SequenceElement(index.toLong()))
            step(itemPath)
            val item = validateItem(itemValue, itemPath, names)
            items.add(item)
        }
        return Body(items)
    }

    private fun validateItem(
        value: PortableValue,
        path: ValuePath,
        names: HashSet<String>,
    ): BodyItem {
        val objectValue = value as? PvObject
            ?: throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("item"),
            )
        val entries = objectValue.entries()
        if (entries.isEmpty()) {
            throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("item"),
            )
        }
        val kind = entries[0].value as? PvString
            ?: throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("item"),
            )
        if (entries[0].key != "kind") {
            throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("item"),
            )
        }
        return when (kind.value) {
            "attribute" -> {
                val fields = exactFields(objectValue, listOf("kind", "name", "value"), "attribute")
                val name = (fields[1] as? PvString)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("attribute-name"),
                    )
                if (!validIdentifier(name)) {
                    throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("attribute-name"),
                    )
                }
                if (!names.add(name)) {
                    throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("duplicate-attribute"),
                    )
                }
                val valuePath = path.child(ValuePathSegment.ObjectValue("value"))
                BodyItem.Attribute(
                    path = path,
                    name = name,
                    value = validateValue(fields[2], valuePath),
                )
            }
            "block" -> {
                if (target == Target.Tfvars) {
                    // The tfvars profile accepts only attribute-only records
                    // (RFC 0014 §5, §9).
                    throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("block-in-tfvars"),
                    )
                }
                val fields = exactFields(objectValue, listOf("kind", "type", "labels", "body"), "block")
                val blockType = (fields[1] as? PvString)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("block-type"),
                    )
                if (!validIdentifier(blockType)) {
                    throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("block-type"),
                    )
                }
                val labelsValue = fields[2] as? PvArray
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("block-labels"),
                    )
                val labels = labelsValue.items().map { label ->
                    (label as? PvString)?.value
                        ?: throw HclMaterializationException(
                            HclMaterializationFailure.Unrepresentable("block-labels"),
                        )
                }
                val bodyPath = path.child(ValuePathSegment.ObjectValue("body"))
                BodyItem.Block(
                    path = path,
                    blockType = blockType,
                    labels = labels,
                    body = validateBody(fields[3], bodyPath),
                )
            }
            else -> throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("item"),
            )
        }
    }

    private fun validateValue(value: PortableValue, path: ValuePath): ValueNode {
        step(path)
        if (value is PvObject && value.entries().firstOrNull()?.key == "kind") {
            return validateRecordValue(value, path)
        }
        return validateRawValue(value, path)
    }

    /** The value-record spelling with a string `kind` member (the form
     * pinned by the conformance vectors, RFC 0014 §9). */
    private fun validateRecordValue(value: PvObject, path: ValuePath): ValueNode {
        val entries = value.entries()
        val kind = (entries[0].value as? PvString)?.value
            ?: throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("value"),
            )
        return when (kind) {
            "string" -> {
                val fields = exactFields(value, listOf("kind", "text"), "string")
                val text = (fields[1] as? PvString)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("string"),
                    )
                ValueNode.String(path, text)
            }
            "integer" -> {
                val fields = exactFields(value, listOf("kind", "value"), "integer")
                val integer = (fields[1] as? PvInteger)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("integer"),
                    )
                ValueNode.Integer(path, integer)
            }
            "real" -> {
                val fields = exactFields(value, listOf("kind", "value"), "real")
                val real = fields[1] as? PvDecimal
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("real"),
                    )
                ValueNode.Real(path, real)
            }
            "boolean" -> {
                val fields = exactFields(value, listOf("kind", "value"), "boolean")
                val boolean = (fields[1] as? PvBoolean)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("boolean"),
                    )
                ValueNode.Boolean(path, boolean)
            }
            "null" -> {
                exactFields(value, listOf("kind"), "null")
                ValueNode.Null(path)
            }
            "tuple" -> {
                val fields = exactFields(value, listOf("kind", "elements"), "tuple")
                val elementsValue = fields[1] as? PvArray
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("tuple"),
                    )
                val elements = elementsValue.items().mapIndexed { index, element ->
                    validateValue(
                        element,
                        path.child(ValuePathSegment.ObjectValue("elements"))
                            .child(ValuePathSegment.SequenceElement(index.toLong())),
                    )
                }
                ValueNode.Tuple(path, elements)
            }
            "object" -> {
                val fields = exactFields(value, listOf("kind", "entries"), "object")
                // The value-record spelling declares `entries` as the
                // ordered sequence of [key, value] pairs (materialization.rs:
                // 822-858); the projection's raw typed member spelling uses
                // an ordered EntryMapping (materialization.rs:972-987).
                val pairs = when (val entriesValue = fields[1]) {
                    is PvEntryMapping -> entriesValue.entries().map { entry ->
                        Pair((entry.key as? PvString)?.value, entry.value)
                    }
                    is PvArray -> entriesValue.items().map { pair ->
                        val pairItems = (pair as? PvArray)?.items()
                            ?: throw HclMaterializationException(
                                HclMaterializationFailure.Unrepresentable("object"),
                            )
                        if (pairItems.size != 2) {
                            throw HclMaterializationException(
                                HclMaterializationFailure.Unrepresentable("object"),
                            )
                        }
                        Pair((pairItems[0] as? PvString)?.value, pairItems[1])
                    }
                    else -> throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("object"),
                    )
                }
                val entries = pairs.mapIndexed { index, (key, entryValue) ->
                    val keyText = key
                        ?: throw HclMaterializationException(
                            HclMaterializationFailure.Unrepresentable("object-key"),
                        )
                    keyText to validateValue(
                        entryValue,
                        path.child(ValuePathSegment.ObjectValue("entries"))
                            .child(ValuePathSegment.SequenceElement(index.toLong())),
                    )
                }
                ValueNode.Object(path, entries)
            }
            "expression" -> {
                val fields = exactFields(value, listOf("kind", "expression"), "expression")
                val expressionValue = fields[1] as? PvObject
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("expression"),
                    )
                // The `hcl.expression@1` record is `{ record, kind, text }`
                // with an optional `fingerprint` member (RFC 0014 §8.2).
                val expressionEntries = expressionValue.entries()
                val expressionFields = if (expressionEntries.size == 3) {
                    listOfNotNull(
                        expressionEntries.getOrNull(0)?.takeIf { it.key == "record" }?.value,
                        expressionEntries.getOrNull(1)?.takeIf { it.key == "kind" }?.value,
                        expressionEntries.getOrNull(2)?.takeIf { it.key == "text" }?.value,
                    )
                } else if (expressionEntries.size == 4) {
                    listOfNotNull(
                        expressionEntries.getOrNull(0)?.takeIf { it.key == "record" }?.value,
                        expressionEntries.getOrNull(1)?.takeIf { it.key == "kind" }?.value,
                        expressionEntries.getOrNull(2)?.takeIf { it.key == "text" }?.value,
                        expressionEntries.getOrNull(3)?.takeIf { it.key == "fingerprint" }?.value,
                    )
                } else {
                    emptyList()
                }
                if (expressionFields.size != expressionEntries.size) {
                    throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("expression"),
                    )
                }
                val record = (expressionFields[0] as? PvString)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("expression"),
                    )
                if (record != HCL_EXPRESSION_RECORD) {
                    throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("expression"),
                    )
                }
                val kind = (expressionFields[1] as? PvString)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("expression"),
                    )
                val text = (expressionFields[2] as? PvString)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("expression"),
                    )
                val fingerprint = expressionFields.getOrNull(3)?.let {
                    (it as? PvString)?.value?.toULongOrNull(16)
                }
                ValueNode.Expression(path, kind, text, fingerprint)
            }
            else -> throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable("value"),
            )
        }
    }

    /** The raw typed member spelling the projection publishes (RFC 0014
     * §9). */
    private fun validateRawValue(value: PortableValue, path: ValuePath): ValueNode = when (value) {
        is PvString -> ValueNode.String(path, value.value)
        is PvInteger -> ValueNode.Integer(path, value.value)
        is PvDecimal -> ValueNode.Real(path, value)
        is PvBoolean -> ValueNode.Boolean(path, value.value)
        is PvNull -> ValueNode.Null(path)
        is PvArray -> {
            val elements = value.items().mapIndexed { index, element ->
                validateValue(
                    element,
                    path.child(ValuePathSegment.SequenceElement(index.toLong())),
                )
            }
            ValueNode.Tuple(path, elements)
        }
        is PvEntryMapping -> {
            val entries = value.entries().mapIndexed { index, entry ->
                val key = (entry.key as? PvString)?.value
                    ?: throw HclMaterializationException(
                        HclMaterializationFailure.Unrepresentable("object-key"),
                    )
                key to validateValue(
                    entry.value,
                    path.child(ValuePathSegment.SequenceElement(index.toLong())),
                )
            }
            ValueNode.Object(path, entries)
        }
        else -> throw HclMaterializationException(
            HclMaterializationFailure.Unrepresentable("value"),
        )
    }
}

/** The validated `hcl.body@1` record (RFC 0014 §9). */
private class Record private constructor(val body: Body) {
    companion object {
        /** The frozen record validation entry (materialization.rs:232-
         * 264). */
        fun validate(
            value: PortableValue,
            request: MaterializationRequest,
            analyzed: ArrayList<ValuePath>,
        ): Record {
            val target = validateRequest(request)
            // The top-level record carries the `record` discriminator
            // (materialization.rs:384-402); the vector pins the stable
            // failure name "invalid-record" for a wrong record identity
            // (hcl-v1.json hcl.materialization.unrepresentable sample 2).
            val objectValue = value as? PvObject
                ?: throw HclMaterializationException(
                    HclMaterializationFailure.Unrepresentable("body-record"),
                )
            val entries = objectValue.entries()
            if (entries.size != 2 || entries[0].key != "record" || entries[1].key != "items") {
                throw HclMaterializationException(
                    HclMaterializationFailure.Unrepresentable("body-record"),
                )
            }
            val record = entries[0].value as? PvString
                ?: throw HclMaterializationException(
                    HclMaterializationFailure.Unrepresentable("body-record"),
                )
            if (record.value != HCL_BODY_RECORD) {
                throw HclMaterializationException(
                    HclMaterializationFailure.Unrepresentable("invalid-record"),
                )
            }
            val validator = RecordValidator(target, request.limits, analyzed)
            return Record(validator.validateBody(value, ValuePath.root()))
        }
    }
}

/** One `hcl.body@1` record object with exactly the named fields in order. */
private fun exactFields(value: PvObject, names: List<String>, context: String): List<PortableValue> {
    val entries = value.entries()
    if (entries.size != names.size) {
        throw HclMaterializationException(
            HclMaterializationFailure.Unrepresentable(context),
        )
    }
    return entries.mapIndexed { index, entry ->
        if (entry.key != names[index]) {
            throw HclMaterializationException(
                HclMaterializationFailure.Unrepresentable(context),
            )
        }
        entry.value
    }
}

/** Whether one name is a valid HCL identifier (RFC 0014 §4.1:
 * `Identifier = ID_Start (ID_Continue | "-")*`; keyword spellings are
 * valid names). */
internal fun validIdentifier(name: String): Boolean {
    if (name.isEmpty()) {
        return false
    }
    val first = name.codePointAt(0)
    if (first == '_'.code || !Character.isUnicodeIdentifierStart(first)) {
        return false
    }
    var index = Character.charCount(first)
    while (index < name.length) {
        val scalar = name.codePointAt(index)
        if (!Character.isUnicodeIdentifierPart(scalar) && scalar != '-'.code) {
            return false
        }
        index += Character.charCount(scalar)
    }
    return true
}

// ---------------------------------------------------------------------------
// Canonical writer (RFC 0014 §9)
// ---------------------------------------------------------------------------

/** The canonical `hcl.canonical-document@1` writer (RFC 0014 §9;
 * materialization.rs:236-264). */
private class CanonicalWriter(
    private val limits: MaterializationLimits,
    private val analyzed: ArrayList<ValuePath>,
) {
    private val out = StringBuilder()
    private var nodes = 0

    internal fun step(path: ValuePath) {
        nodes += 1
        analyzed.add(path)
        if (nodes > limits.maxInputNodes) {
            throw HclMaterializationException(HclMaterializationFailure.ResourceLimit("input-nodes"))
        }
        if (out.length > limits.maxOutputBytes) {
            throw HclMaterializationException(HclMaterializationFailure.ResourceLimit("output-bytes"))
        }
    }

    fun finish(): ByteArray {
        if (out.length > limits.maxOutputBytes) {
            throw HclMaterializationException(HclMaterializationFailure.ResourceLimit("output-bytes"))
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun line(depth: Int, text: kotlin.String) {
        out.append("  ".repeat(depth))
        out.append(text)
        out.append('\n')
    }
}

/** The two-space-per-depth indentation string of one nesting level (RFC
 * 0014 §9). */
private fun indentString(depth: Int): String = "  ".repeat(depth)

private fun writeBody(writer: CanonicalWriter, body: Body, depth: Int) {
    for (item in body.items) {
        when (item) {
            is BodyItem.Attribute -> {
                val value = StringBuilder()
                writeValue(writer, item.value, depth, value)
                writer.line(depth, "${item.name} = $value")
            }
            is BodyItem.Block -> {
                val header = StringBuilder(item.blockType)
                for (label in item.labels) {
                    header.append(" \"")
                    header.append(escapeString(label))
                    header.append('"')
                }
                header.append(" {")
                writer.line(depth, header.toString())
                writeBody(writer, item.body, depth + 1)
                writer.line(depth, "}")
            }
        }
    }
}

/** Writes one value node into the given buffer; a non-empty tuple/object
 * uses the deterministic one-item-per-line layout at the chosen
 * indentation (RFC 0014 §9). */
private fun writeValue(writer: CanonicalWriter, node: ValueNode, depth: Int, out: StringBuilder) {
    writer.step(node.path)
    when (node) {
        is ValueNode.String -> {
            out.append('"')
            out.append(escapeString(node.text))
            out.append('"')
        }
        is ValueNode.Integer -> out.append(node.value.toString())
        is ValueNode.Real -> {
            val canonical = canonicalDecimalString(node.value.coefficient, node.value.exponent.toInt())
            out.append(canonical)
        }
        is ValueNode.Boolean -> out.append(if (node.value) "true" else "false")
        is ValueNode.Null -> out.append("null")
        is ValueNode.Tuple -> {
            if (node.elements.isEmpty()) {
                out.append("[]")
                return
            }
            // One item per line with comma separators, closing on a line of
            // its own (RFC 0014 §9; the vector render of hcl-v1.json
            // hcl.materialization.canonical-document has no trailing comma).
            out.append("[\n")
            for ((index, element) in node.elements.withIndex()) {
                out.append(indentString(depth + 1))
                val elementText = StringBuilder()
                writeValue(writer, element, depth + 1, elementText)
                out.append(elementText)
                if (index < node.elements.size - 1) {
                    out.append(',')
                }
                out.append('\n')
            }
            out.append(indentString(depth))
            out.append(']')
        }
        is ValueNode.Object -> {
            if (node.entries.isEmpty()) {
                out.append("{}")
                return
            }
            out.append("{\n")
            for ((key, value) in node.entries) {
                out.append(indentString(depth + 1))
                val keyText = if (key != "for" && validIdentifier(key)) key else "\"${escapeString(key)}\""
                val valueText = StringBuilder()
                writeValue(writer, value, depth + 1, valueText)
                out.append("$keyText = $valueText\n")
            }
            out.append(indentString(depth))
            out.append('}')
        }
        is ValueNode.Expression -> {
            // `hcl.expression@1` values emit their canonical text verbatim
            // (RFC 0014 §9).
            out.append(node.text)
        }
    }
}

/** The minimal deterministic string escaping of RFC 0014 §9: `\n`, `\r`,
 * `\t`, `\"`, `\\`, `\uNNNN` for control characters, and `$${`/`%%{` so
 * the reparse keeps `${`/`%{` as literal text. */
internal fun escapeString(text: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        when {
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '$' && index + 1 < text.length && text[index + 1] == '{' -> {
                out.append("\$\${")
                index += 1
            }
            ch == '%' && index + 1 < text.length && text[index + 1] == '{' -> {
                out.append("%%{")
                index += 1
            }
            ch.code in 0x00..0x1f || ch.code == 0x7f -> out.append("\\u%04x".format(ch.code))
            else -> out.append(ch)
        }
        index += 1
    }
    return out.toString()
}

// ---------------------------------------------------------------------------
// Closure verification and provenance (RFC 0014 §9)
// ---------------------------------------------------------------------------

/** Walks the reparsed native model in lockstep with the record: numbers by
 * canonical-decimal value equality, strings and object keys by exact
 * decoded text, constructors element-wise, `hcl.expression@1` values by
 * structural equality plus fingerprint equality (RFC 0014 §6, §9). */
private fun verifyClosure(
    record: Record,
    document: HclDocument,
    limits: MaterializationLimits,
) {
    val root = document.rootBody()
    verifyBodyClosure(record.body, root, limits)
}

private fun verifyBodyClosure(body: Body, handle: HclBodyHandle, limits: MaterializationLimits) {
    val items = handle.items()
    if (items.size != body.items.size) {
        throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
    }
    for ((recordItem, nativeItem) in body.items.zip(items)) {
        when (recordItem) {
            is BodyItem.Attribute -> {
                val attribute = (nativeItem as? HclBodyItemHandle.Attribute)?.handle
                    ?: throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
                if (attribute.name() != recordItem.name) {
                    throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
                }
                verifyValueClosure(recordItem.value, attribute.expression(), limits)
            }
            is BodyItem.Block -> {
                val block = (nativeItem as? HclBodyItemHandle.Block)?.handle
                    ?: throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
                if (block.blockType() != recordItem.blockType) {
                    throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
                }
                val labels = block.labels().map { it.text() }
                if (labels != recordItem.labels) {
                    throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
                }
                verifyBodyClosure(recordItem.body, block.body(), limits)
            }
        }
    }
}

private fun verifyValueClosure(
    node: ValueNode,
    expression: HclExpressionHandle,
    limits: MaterializationLimits,
) {
    when (node) {
        is ValueNode.String -> {
            val literal = literalValue(expression.expressionValue())
            if (literal !is HclLiteralValue.String || literal.text != node.text) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
        }
        is ValueNode.Integer -> {
            val literal = literalValue(expression.expressionValue())
            if (literal !is HclLiteralValue.Integer || BigInteger(literal.text) != node.value) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
        }
        is ValueNode.Real -> {
            val literal = literalValue(expression.expressionValue())
            if (literal !is HclLiteralValue.Decimal) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
            val canonical = canonicalDecimalString(node.value.coefficient, node.value.exponent.toInt())
            if (literal.text != canonical) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
        }
        is ValueNode.Boolean -> {
            val literal = literalValue(expression.expressionValue())
            if (literal !is HclLiteralValue.Boolean || literal.value != node.value) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
        }
        is ValueNode.Null -> {
            val literal = literalValue(expression.expressionValue())
            if (literal !is HclLiteralValue.Null) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
        }
        is ValueNode.Tuple -> {
            val literal = literalValue(expression.expressionValue())
            if (literal !is HclLiteralValue.Tuple || literal.elements.size != node.elements.size) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
            val children = expression.children()
            for ((recordElement, child) in node.elements.zip(children)) {
                verifyValueClosure(recordElement, child, limits)
            }
        }
        is ValueNode.Object -> {
            val literal = literalValue(expression.expressionValue())
            if (literal !is HclLiteralValue.Object || literal.entries.size != node.entries.size) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
            val children = expression.children()
            for ((recordEntry, child) in node.entries.zip(children)) {
                verifyValueClosure(recordEntry.second, child, limits)
            }
        }
        is ValueNode.Expression -> {
            // The promised expression must reparse to the same structural
            // fingerprint (RFC 0014 §9).
            val reparsed = parseExpressionText(node.text, limits)
            if (reparsed == null || reparsed.kind.kindFamily != node.kind) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
            if (node.fingerprint != null && structuralFingerprint(reparsed) != node.fingerprint) {
                throw HclMaterializationException(HclMaterializationFailure.FormationFailed)
            }
        }
    }
}

/** Parses one standalone expression text (the `expr = <text>` sentinel
 * form of materialization.rs:190-192); null when the text is not exactly
 * one expression. */
private fun parseExpressionText(text: String, limits: MaterializationLimits): HclExpression? {
    val wrapped = "expr = $text\n"
    val document = try {
        parse(
            wrapped.toByteArray(Charsets.UTF_8),
            HclProfile.NATIVE_V1,
            parseLimits(limits),
        )
    } catch (e: HclFormationException) {
        return null
    }
    if (document.formationStatus() != consema.document.FormationStatus.Complete) {
        return null
    }
    val body = document.rootBody()
    if (body.size() != 1) {
        return null
    }
    val item = body.items().firstOrNull() as? HclBodyItemHandle.Attribute ?: return null
    return item.handle.expression().expressionValue()
}

/** One provenance entry per input item, attribute value, block label, and
 * the root record, mapped to exact output origins (RFC 0014 §9;
 * materialization.rs:139-148). Relations are Direct. */
private fun buildProvenance(
    document: HclDocument,
    limits: MaterializationLimits,
): MaterializationProvenanceMap {
    val entries = ArrayList<MaterializationProvenanceEntry>()
    val root = document.rootBody()
    entries.add(
        MaterializationProvenanceEntry(
            input = MaterializationInputLocation.Value(ValuePath.root()),
            outputs = listOf(
                MaterializedOrigin(
                    snapshot = document.snapshotIdentity,
                    node = document.nodeRef(document.rootBodyIndex, NodeRole.HclBody),
                    span = document.authority.span(0, document.source().len),
                    relation = MaterializationRelation.Direct,
                ),
            ),
        ),
    )
    collectProvenance(root, entries, document)
    return MaterializationProvenanceMap.new(entries, document.snapshotIdentity, limits)
}

private fun collectProvenance(
    body: HclBodyHandle,
    entries: ArrayList<MaterializationProvenanceEntry>,
    document: HclDocument,
) {
    for (item in body.items()) {
        when (item) {
            is HclBodyItemHandle.Attribute -> {
                val attribute = item.handle
                entries.add(
                    MaterializationProvenanceEntry(
                        input = MaterializationInputLocation.Value(ValuePath.root()),
                        outputs = listOf(
                            MaterializedOrigin(
                                snapshot = document.snapshotIdentity,
                                node = attribute.nodeRef,
                                span = attribute.span,
                                relation = MaterializationRelation.Direct,
                            ),
                        ),
                    ),
                )
                entries.add(
                    MaterializationProvenanceEntry(
                        input = MaterializationInputLocation.Value(ValuePath.root()),
                        outputs = listOf(
                            MaterializedOrigin(
                                snapshot = document.snapshotIdentity,
                                node = attribute.expression().nodeRef,
                                span = attribute.expression().span,
                                relation = MaterializationRelation.Direct,
                            ),
                        ),
                    ),
                )
            }
            is HclBodyItemHandle.Block -> {
                val block = item.handle
                entries.add(
                    MaterializationProvenanceEntry(
                        input = MaterializationInputLocation.Value(ValuePath.root()),
                        outputs = listOf(
                            MaterializedOrigin(
                                snapshot = document.snapshotIdentity,
                                node = block.nodeRef,
                                span = block.span,
                                relation = MaterializationRelation.Direct,
                            ),
                        ),
                    ),
                )
                for (label in block.labels()) {
                    entries.add(
                        MaterializationProvenanceEntry(
                            input = MaterializationInputLocation.Value(ValuePath.root()),
                            outputs = listOf(
                                MaterializedOrigin(
                                    snapshot = document.snapshotIdentity,
                                    node = label.nodeRef,
                                    span = label.span,
                                    relation = MaterializationRelation.Direct,
                                ),
                            ),
                        ),
                    )
                }
                collectProvenance(block.body(), entries, document)
            }
        }
    }
}

