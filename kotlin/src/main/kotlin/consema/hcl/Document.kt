// The immutable HCL document and its native handles: the schema-free body
// tree of RFC 0014 §6 bound to one snapshot, plus the formation facade.
//
// Data authority:
//   - RFC 0014 §3-§6 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md:94-446):
//     Complete/Recovered/FatalFormationFailure; recovery retains every
//     independently proven construct; the native semantic model
//     (HclDocument/HclBody/HclAttribute/HclBlock/HclBlockLabel/
//     HclExpression/HclTemplatePart/HclNumber/HclErrorRegion) preserves
//     source order and per-occurrence identity; an expression is a
//     first-class native role with its exact span-derived text always
//     available.
//   - RFC 0014 §2 (:56-93): the UTF-8-only source contract; a BOM is
//     Recovered with `hcl.parse.byte-order-mark@1`, invalid UTF-8 is fatal
//     with `hcl.parse.invalid-utf8@1`, a lone CR is Recovered with
//     `hcl.parse.lone-cr@1`.
//   - RFC 0014 §5 (:359-393): `hcl.tfvars@1` is `hcl.native@1` under one
//     structural restriction — a block anywhere at the top level makes
//     formation Recovered with one `hcl.tfvars.block-not-allowed@1`
//     diagnostic per top-level block occurrence; the rejected block remains
//     a native item of the Recovered document.
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/document.rs:50-217 (Document and its
//     accessors), https://github.com/consema/consema-rs/blob/main/consema-hcl/src/native.rs:37-325 (HclDocument,
//     HclBody, HclBodyItem, HclAttribute, HclBlock, HclBlockLabel,
//     HclErrorRegion), https://github.com/consema/consema-rs/blob/main/consema-hcl/src/lib.rs:275-311 (the formation
//     entry), and the frozen NodeRole spellings (document/Location.kt:
//     202-231: HclDocument/HclBody/HclAttribute/HclBlock/HclBlockLabel/
//     HclExpression/HclTemplatePart/HclErrorRegion/HclSyntaxPiece).
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the native tree is built
// into a flat immutable entity arena during formation; every handle is an
// immutable (document, rank) pair resolving through the snapshot-bound
// NodeRef, exactly like the TOML/JSON family handles. The public surface
// pins the read accessors; query, projection, materialization, and edit
// consume the module-internal entity access.

package consema.hcl

import consema.document.DocumentAuthority
import consema.document.FormatFamilyId
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ProfileId
import consema.document.SnapshotIdentity
import consema.document.SourceSnapshot
import consema.document.Span
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** One recovered HCL error region with its stable diagnostic code (RFC 0014
 * §3, §7.2; native.rs:302-325). */
data class HclErrorRegion(
    /** Exact recovered region span. */
    val span: Span,
    /** Stable `hcl.parse.*@1` diagnostic code of the region. */
    val code: String,
)

/** One block label with its quote/naked fact (RFC 0014 §4.2, §6;
 * native.rs:259-291). */
data class HclBlockLabel(
    /** Label text; for a quoted label this is the content without the quote
     * delimiters (escapes are decoded by the parser). */
    val text: String,
    /** Exact span, including the quote delimiters when quoted. */
    val span: Span,
    /** Whether the label is a quoted literal string; false for a naked
     * identifier. */
    val quoted: Boolean,
)

/** One block occurrence: type, ordered labels, and nested body (RFC 0014
 * §4.2, §6; native.rs:203-251). A one-line block is the same native shape
 * with at most one attribute and no nested blocks. */
data class HclBlock(
    /** Block type identifier. */
    val blockType: String,
    /** Ordered labels; each carries its quote/naked fact. */
    val labels: List<HclBlockLabel>,
    /** Nested body (empty for a one-line block or a block with no items). */
    val body: HclBody,
    /** Exact span of the whole block, from the type identifier through the
     * closing brace. */
    val span: Span,
)

/** One attribute occurrence: name, equals sign, and expression (RFC 0014
 * §4.2, §6; native.rs:145-193). */
data class HclAttribute(
    /** Attribute name; keyword spellings such as `true` are valid names. */
    val name: String,
    /** Exact span of the name identifier. */
    val nameSpan: Span,
    /** Exact span of the `=` equals sign. */
    val equalsSpan: Span,
    /** Value expression, unevaluated (RFC 0014 §1). */
    val expression: HclExpression,
) {
    /** The attribute's full source range: the union of the name, equals,
     * and expression spans. */
    val span: Span
        get() = consema.document.Span(
            nameSpan.snapshot,
            nameSpan.startByte,
            expression.span.endByte,
        )
}

/** One body item: an attribute or a block occurrence (RFC 0014 §4.2, §6;
 * native.rs:111-136). Identity is per-occurrence; nothing is merged. */
sealed class HclBodyItem {
    /** An attribute occurrence. */
    data class Attribute(val attribute: HclAttribute) : HclBodyItem()

    /** A block occurrence, including one-line blocks. */
    data class Block(val block: HclBlock) : HclBodyItem()
}

/** Ordered body item container (RFC 0014 §6; native.rs:72-103). The root
 * body of a document and every nested block body share this container. */
data class HclBody(val items: List<HclBodyItem>) {
    /** Number of body items. */
    val size: Int get() = items.size

    /** Whether the body has no items. */
    val isEmpty: Boolean get() = items.isEmpty()
}

/** One internal arena entity; every native fact has exactly one entity with
 * a stable rank, in deterministic pre-order. */
internal sealed class HclEntity {
    /** One body; [items] are the entity ranks of its attribute/block
     * items. */
    data class Body(val body: HclBody, val items: List<Int>) : HclEntity()

    /** One attribute occurrence; [expression] is the expression entity
     * rank. */
    data class Attribute(val attribute: HclAttribute, val expression: Int) : HclEntity()

    /** One block occurrence; [labels] are label entity ranks and [body] the
     * nested body entity rank. */
    data class Block(val block: HclBlock, val labels: List<Int>, val body: Int) : HclEntity()

    /** One block label occurrence. */
    data class BlockLabel(val label: HclBlockLabel) : HclEntity()

    /** One expression AST node; [children] are the ordered child expression
     * entity ranks (RFC 0014 §6). */
    data class Expression(val expression: HclExpression, val children: List<Int>) : HclEntity()

    /** One recovered error region in source order. */
    data class ErrorRegion(val region: HclErrorRegion) : HclEntity()
}

/**
 * Opaque immutable HCL document snapshot (RFC 0014 §3; document.rs:50-217).
 * Both profiles share the one syntax system and the one native model; the
 * profile gates Complete formation and the operation surface.
 */
class HclDocument internal constructor(
    internal val authority: DocumentAuthority,
    internal val source: SourceSnapshot,
    internal val profile: HclProfile,
    private val structuralIndex: LosslessStructuralIndex,
    private val syntaxKinds: List<HclSyntaxKind>,
    internal val formationStatus: FormationStatus,
    internal val diagnosticsList: List<HclDiagnostic>,
    internal val entities: List<HclEntity>,
    internal val rootBodyIndex: Int,
    internal val parseLimits: HclParseLimits,
) {
    /** Snapshot identity to which every NodeRef and Span belongs. */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable UTF-8 source. */
    fun source(): SourceSnapshot = source

    /** Default rendering is byte-for-byte identical to the source (RFC 0014
     * §2: unmodified rendering returns the exact original bytes). */
    fun render(): ByteArray = source.bytes()

    /** HCL format family contract (RFC 0014 §1; document.rs:164-166). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("hcl", 1)

    /** Exact language profile (document.rs:158-160). */
    fun profileId(): ProfileId = profile.id()

    /** Complete or explicitly recovered formation state (RFC 0014 §3). */
    fun formationStatus(): FormationStatus = formationStatus

    /** Deterministically ordered formation diagnostics; the tfvars gate
     * diagnostics are merged with the parser's own (document.rs:142-147). */
    fun diagnostics(): List<HclDiagnostic> = diagnosticsList

    /** Exhaustive ordered lossless piece coverage of the raw bytes; always
     * present under both profiles (RFC 0014 §7.2). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Ordered syntax kinds, parallel to the lossless structural pieces
     * (RFC 0014 §7.2). */
    fun losslessSyntaxKinds(): List<HclSyntaxKind> = syntaxKinds

    /** Recovered error regions in source order (RFC 0014 §3, §7.2). The
     * tfvars gate never contributes an error region: a rejected top-level
     * block is a proven construct, not a recovered region. */
    fun errorRegions(): List<HclErrorRegion> = entities.mapNotNull { entity ->
        (entity as? HclEntity.ErrorRegion)?.region
    }

    /** Resource contract used to form this snapshot and any edit successor. */
    fun parseLimits(): HclParseLimits = parseLimits

    /** Root body handle; an empty body is a valid body (RFC 0014 §3, §6). */
    fun rootBody(): HclBodyHandle = HclBodyHandle(this, rootBodyIndex)

    /** Resolves a snapshot-bound HCL body handle. Throws
     * [HclAccessException] for a foreign snapshot, a non-body role, or an
     * unknown index. */
    fun body(node: NodeRef): HclBodyHandle {
        val index = validateRef(node, NodeRole.HclBody)
        return HclBodyHandle(this, index)
    }

    /** Resolves a snapshot-bound HCL attribute handle. */
    fun attribute(node: NodeRef): HclAttributeHandle {
        val index = validateRef(node, NodeRole.HclAttribute)
        return HclAttributeHandle(this, index)
    }

    /** Resolves a snapshot-bound HCL block handle. */
    fun block(node: NodeRef): HclBlockHandle {
        val index = validateRef(node, NodeRole.HclBlock)
        return HclBlockHandle(this, index)
    }

    /** Resolves a snapshot-bound HCL block label handle. */
    fun blockLabel(node: NodeRef): HclBlockLabelHandle {
        val index = validateRef(node, NodeRole.HclBlockLabel)
        return HclBlockLabelHandle(this, index)
    }

    /** Resolves a snapshot-bound HCL expression handle. */
    fun expression(node: NodeRef): HclExpressionHandle {
        val index = validateRef(node, NodeRole.HclExpression)
        return HclExpressionHandle(this, index)
    }

    internal fun entity(index: Int): HclEntity = entities[index]

    internal fun nodeRef(index: Int, role: NodeRole): NodeRef =
        authority.nodeRef(index.toLong(), role)

    internal fun validateRef(node: NodeRef, role: NodeRole): Int {
        if (node.snapshot != authority.identity) {
            throw HclAccessException(HclAccessErrorKind.WrongSnapshot)
        }
        if (node.role != role) {
            throw HclAccessException(HclAccessErrorKind.WrongRole)
        }
        val index = node.index
        if (index < 0 || index >= entities.size.toLong()) {
            throw HclAccessException(HclAccessErrorKind.UnknownNode)
        }
        return index.toInt()
    }
}

/** Borrowed native HCL body bound to one document snapshot. */
class HclBodyHandle internal constructor(
    internal val document: HclDocument,
    internal val index: Int,
) {
    private fun entity(): HclEntity.Body =
        document.entity(index) as? HclEntity.Body
            ?: error("typed HCL body handle")

    /** Exact body identity. */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.HclBody)

    /** Ordered body items as attribute/block handles, interleaved in source
     * order. */
    fun items(): List<HclBodyItemHandle> = entity().items.map { itemIndex ->
        when (val item = document.entity(itemIndex)) {
            is HclEntity.Attribute -> HclBodyItemHandle.Attribute(HclAttributeHandle(document, itemIndex))
            is HclEntity.Block -> HclBodyItemHandle.Block(HclBlockHandle(document, itemIndex))
            else -> error("body items are attributes or blocks")
        }
    }

    /** Ordered attribute items in source order. */
    fun attributes(): List<HclAttributeHandle> = entity().items.mapNotNull { itemIndex ->
        (document.entity(itemIndex) as? HclEntity.Attribute)?.let { HclAttributeHandle(document, itemIndex) }
    }

    /** Ordered block items in source order. */
    fun blocks(): List<HclBlockHandle> = entity().items.mapNotNull { itemIndex ->
        (document.entity(itemIndex) as? HclEntity.Block)?.let { HclBlockHandle(document, itemIndex) }
    }

    /** Number of body items. */
    fun size(): Int = entity().items.size
}

/** One body item handle: an attribute or block occurrence. */
sealed class HclBodyItemHandle {
    /** An attribute occurrence. */
    data class Attribute(val handle: HclAttributeHandle) : HclBodyItemHandle()

    /** A block occurrence. */
    data class Block(val handle: HclBlockHandle) : HclBodyItemHandle()
}

/** Borrowed native HCL attribute bound to one document snapshot. */
class HclAttributeHandle internal constructor(
    internal val document: HclDocument,
    internal val index: Int,
) {
    private fun entity(): HclEntity.Attribute =
        document.entity(index) as? HclEntity.Attribute
            ?: error("typed HCL attribute handle")

    /** Exact attribute identity. */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.HclAttribute)

    /** The attribute's full source span (name through expression). */
    val span: Span
        get() = entity().attribute.span

    /** Attribute name; keyword spellings such as `true` are valid names. */
    fun name(): String = entity().attribute.name

    /** Exact span of the name identifier. */
    fun nameSpan(): Span = entity().attribute.nameSpan

    /** Exact span of the `=` equals sign. */
    fun equalsSpan(): Span = entity().attribute.equalsSpan

    /** Value expression, unevaluated (RFC 0014 §1). */
    fun expression(): HclExpressionHandle = HclExpressionHandle(document, entity().expression)
}

/** Borrowed native HCL block bound to one document snapshot. */
class HclBlockHandle internal constructor(
    internal val document: HclDocument,
    internal val index: Int,
) {
    private fun entity(): HclEntity.Block =
        document.entity(index) as? HclEntity.Block
            ?: error("typed HCL block handle")

    /** Exact block identity. */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.HclBlock)

    /** Exact span of the whole block, from the type identifier through the
     * closing brace. */
    val span: Span
        get() = entity().block.span

    /** Block type identifier. */
    fun blockType(): String = entity().block.blockType

    /** Ordered labels; each carries its quote/naked fact. */
    fun labels(): List<HclBlockLabelHandle> =
        entity().labels.map { HclBlockLabelHandle(document, it) }

    /** Nested body (empty for a one-line block or a block with no items). */
    fun body(): HclBodyHandle = HclBodyHandle(document, entity().body)
}

/** Borrowed native HCL block label bound to one document snapshot. */
class HclBlockLabelHandle internal constructor(
    internal val document: HclDocument,
    internal val index: Int,
) {
    private fun entity(): HclEntity.BlockLabel =
        document.entity(index) as? HclEntity.BlockLabel
            ?: error("typed HCL block label handle")

    /** Exact label identity. */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.HclBlockLabel)

    /** Exact span, including the quote delimiters when quoted. */
    val span: Span
        get() = entity().label.span

    /** Label text; for a quoted label this is the content without the quote
     * delimiters. */
    fun text(): String = entity().label.text

    /** Whether the label is a quoted literal string. */
    fun quoted(): Boolean = entity().label.quoted
}

/** Borrowed native HCL expression AST node bound to one document snapshot
 * (RFC 0014 §6). */
class HclExpressionHandle internal constructor(
    internal val document: HclDocument,
    internal val index: Int,
) {
    private fun entity(): HclEntity.Expression =
        document.entity(index) as? HclEntity.Expression
            ?: error("typed HCL expression handle")

    /** Exact expression identity. */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.HclExpression)

    /** Exact source span. */
    val span: Span
        get() = entity().expression.span

    /** The closed unevaluated expression kind (RFC 0014 §6). */
    fun kind(): HclExpressionKind = entity().expression.kind

    /** Closed payload-free kind spelling (RFC 0014 §7.1
     * `hcl.expression-kind-is@1`). */
    fun kindName(): String = entity().expression.kind.kindName.spelling

    /** Exact source text derived from the immutable source span. */
    fun text(): String =
        document.source.decodedText()
            ?.substring(span.startByte, span.endByte)
            ?: error("HCL sources are always decoded")

    /** Ordered child expression nodes (RFC 0014 §6). */
    fun children(): List<HclExpressionHandle> =
        entity().children.map { HclExpressionHandle(document, it) }

    /** Whether the expression is literal-complete (RFC 0014 §8.1). */
    fun isLiteral(): Boolean = isLiteralComplete(entity().expression)

    /** The immutable AST node value (module-internal access shared by
     * query, projection, materialization, and edit). */
    internal fun expressionValue(): HclExpression = entity().expression
}

/**
 * Forms one HCL document from raw bytes under one exact profile (RFC 0014
 * §1, §3, §5; lib.rs:275-311). The profile is selected by the caller before
 * formation; neither the `.tf` nor the `.tfvars` extension selects a
 * profile, representation, or encoding. The frozen formation order is:
 * max_source_bytes, UTF-8 validation, the native grammar with recovery, the
 * configured limits, then the tfvars top-level restriction. A fatal failure
 * throws [HclFormationException]; no partial Document exists.
 */
fun parse(
    source: ByteArray,
    profile: HclProfile,
    limits: HclParseLimits = HclParseLimits.default,
): HclDocument {
    if (source.size > limits.common.maxSourceBytes) {
        // The common source bound uses the frozen core resource-limit code
        // (RFC 0016 §5.1; consema-document lib.rs:771-791).
        throw HclFormationException(
            listOf(
                HclDiagnostic(
                    code = CORE_PARSE_RESOURCE_LIMIT,
                    category = DiagnosticCategory.Resource,
                    severity = Severity.Error,
                    startByte = null,
                    endByte = null,
                    arguments = mapOf(
                        "limit" to limits.common.maxSourceBytes.toString(),
                        "name" to "source_bytes",
                        "observed" to source.size.toString(),
                    ),
                    notes = emptyList(),
                    occurrence = 0,
                ),
            ),
        )
    }
    val snapshot = try {
        SourceSnapshot.fromUtf8(source)
    } catch (e: consema.document.SourceException) {
        // fromUtf8 maps every invalid sequence to INVALID_UTF8 carrying the
        // valid prefix; invalid UTF-8 makes formation FatalFormationFailure
        // with hcl.parse.invalid-utf8@1 (RFC 0014 §2-§3).
        throw HclFormationException(listOf(hclInvalidUtf8Diagnostic(e.validUpTo ?: 0)))
    }
    val text = snapshot.decodedText() ?: error("HCL parser constructs a UTF-8 source")
    val scalars = text.codePointCount(0, text.length)
    if (scalars > limits.maxDecodedScalars) {
        throw HclFormationException(
            listOf(hclLimitDiagnostic("hcl.limit.offset-overflow@1", "decoded-scalars", scalars, limits.maxDecodedScalars)),
        )
    }
    if (text.toByteArray(Charsets.UTF_8).size > limits.maxDecodedUtf8Bytes) {
        throw HclFormationException(
            listOf(hclLimitDiagnostic("hcl.limit.offset-overflow@1", "decoded-utf8-bytes", text.length, limits.maxDecodedUtf8Bytes)),
        )
    }
    val authority = DocumentAuthority.fresh()
    val formed = parseHcl(source, snapshot, authority, limits)

    // The tfvars gate (RFC 0014 §5; document.rs:87-116).
    var status = formed.status
    val diagnostics = ArrayList(formed.diagnostics)
    if (profile == HclProfile.TFVARS_V1) {
        for (item in formed.document.items) {
            if (item is HclBodyItem.Block) {
                status = FormationStatus.Recovered
                diagnostics.add(
                    HclDiagnostic(
                        code = HCL_TFVARS_BLOCK_NOT_ALLOWED,
                        category = DiagnosticCategory.Syntax,
                        severity = Severity.Error,
                        startByte = item.block.span.startByte,
                        endByte = item.block.span.endByte,
                        arguments = emptyMap(),
                        notes = emptyList(),
                        occurrence = 0,
                    ),
                )
            }
        }
    }
    diagnostics.sortWith(
        compareBy({ it.startByte ?: -1 }, { it.endByte ?: -1 }, { it.code }),
    )

    val builder = EntityBuilder(authority, formed)
    val rootIndex = builder.build(formed.document)
    return HclDocument(
        authority = authority,
        source = snapshot,
        profile = profile,
        structuralIndex = LosslessStructuralIndex.new(authority.identity, source.size, formed.pieces),
        syntaxKinds = formed.kinds ?: emptyList(),
        formationStatus = status,
        diagnosticsList = diagnostics,
        entities = builder.entities,
        rootBodyIndex = rootIndex,
        parseLimits = limits,
    )
}

/** Builds the flat immutable entity arena from the parsed body tree; ranks
 * are assigned in deterministic pre-order so every native fact has a
 * snapshot-bound identity (the Kotlin analogue of the Rust arena). */
internal class EntityBuilder(
    private val authority: DocumentAuthority,
    private val formed: HclFormed,
) {
    internal val entities = ArrayList<HclEntity>()

    /** Builds the arena; returns the root body entity rank. */
    fun build(body: HclBody): Int {
        val expressionRanks = HashMap<HclExpression, Int>()
        // Expressions first: the root body's items reference them by rank.
        collectExpressions(body, expressionRanks)
        val errorRanks = HashMap<HclErrorRegion, Int>()
        for (region in formed.errorRegions.sortedBy { it.span.startByte }) {
            errorRanks[region] = entities.size
            entities.add(HclEntity.ErrorRegion(region))
        }
        return buildBody(body, expressionRanks, errorRanks)
    }

    private fun collectExpressions(body: HclBody, ranks: HashMap<HclExpression, Int>) {
        for (item in body.items) {
            when (item) {
                is HclBodyItem.Attribute -> collectExpression(item.attribute.expression, ranks)
                is HclBodyItem.Block -> {
                    collectExpressions(item.block.body, ranks)
                }
            }
        }
    }

    private fun collectExpression(expression: HclExpression, ranks: HashMap<HclExpression, Int>): Int {
        // Children are collected first (post-order), then the node entity is
        // appended, so the recorded rank is exactly the entity position.
        val childRanks = expression.kind.children.map { child ->
            collectExpression(child, ranks)
        }
        val rank = entities.size
        entities.add(HclEntity.Expression(expression, childRanks))
        ranks[expression] = rank
        return rank
    }

    private fun buildBody(
        body: HclBody,
        expressionRanks: HashMap<HclExpression, Int>,
        errorRanks: HashMap<HclErrorRegion, Int>,
    ): Int {
        val itemRanks = ArrayList<Int>()
        for (item in body.items) {
            when (item) {
                is HclBodyItem.Attribute -> {
                    val expression = expressionRanks[item.attribute.expression]
                        ?: error("expression arena covers every attribute expression")
                    itemRanks.add(entities.size)
                    entities.add(HclEntity.Attribute(item.attribute, expression))
                }
                is HclBodyItem.Block -> {
                    val labelRanks = item.block.labels.map { label ->
                        entities.size.also { entities.add(HclEntity.BlockLabel(label)) }
                    }
                    val nestedBody = buildBody(item.block.body, expressionRanks, errorRanks)
                    itemRanks.add(entities.size)
                    entities.add(HclEntity.Block(item.block, labelRanks, nestedBody))
                }
            }
        }
        val bodyRank = entities.size
        entities.add(HclEntity.Body(body, itemRanks))
        return bodyRank
    }
}
