// Scalar and structural edit transactions for TOML 1.0.
//
// Data authority:
//   - RFC 0001 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0001-toml-1.0-profile.md): scalar
//     edits accept only TomlItem scalar targets; the four representation
//     policies; transactions validate all targets, conflicts, and candidate
//     representations before one atomic replace-and-reparse.
//   - RFC 0004 §12-§16 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md): native ownership of the five
//     structural operations, the transaction conflict algebra, the dry-run
//     EditPlan, the untouched-byte proof, and SourcePatch derivation.
//   - https://github.com/consema/consema-rs/blob/main/consema-toml/src/operation_registry.rs pins the seven
//     frozen operation IDs and their target roles.
//   - https://github.com/consema/consema-rs/blob/main/consema-toml/src/edit.rs (RepresentationPolicy,
//     ScalarReplacement, EditOperation, transaction and builder,
//     EditCommit/EditFailure, commit, dry_run, preparation,
//     validate_dependencies, limits, metadata and summaries, the frozen
//     diagnostic_code mapping, PreparedEdit, validate_exact_scalar,
//     semantic_literal, canonical_literal, find_item_by_span).
//   - conformance/vectors/toml-v1.json toml.edit.* (lines 71-82) and
//     operations-v1.json operations.v1.toml-* (lines 173-223) pin the
//     exact replacement bytes and failure codes.
//   - consema-go/go/toml/edit.go is a cross-reference only.
//
// Kotlin-idiomatic design: failures are typed exceptions carrying the
// frozen core.edit.*@1 code and the stable kind name (the vector
// `"failure": "UnsupportedSemanticValue"` fact); ChangeSet is not shipped
// in the Kotlin TOML family (recorded gap, six-repo audit G090), so
// the commit exposes the derived node mappings and fallback diagnostics as
// family records.

package consema.toml

import consema.core.Kind
import consema.core.PortableValue
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvOffsetDateTime
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanSourceId
import consema.document.FormatOperationId
import consema.document.MaterializationLimits
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ParseLimits
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.Span
import consema.document.UntouchedByteProof
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.Severity

/** Explicit semantic scalar representation policy (edit.rs). */
enum class RepresentationPolicy {
    /** Caller must use an exact literal operation instead. */
    ExactLiteral,

    /** New public value must retain the target native scalar category. */
    PreserveCompatible,

    /** Use the frozen deterministic TOML 1.0 scalar representation. */
    CanonicalForProfile,

    /** Preserve the category when compatible, otherwise report canonical
     * fallback. */
    PreserveElseCanonical,
    ;

    /** The stable summary spelling (toml_policy_name, edit.rs). */
    internal fun summaryName(): String =
        when (this) {
            ExactLiteral -> "exact-literal"
            PreserveCompatible -> "preserve-compatible"
            CanonicalForProfile -> "canonical-for-profile"
            PreserveElseCanonical -> "preserve-else-canonical"
        }
}

/** One scalar operation bound to a transaction base snapshot (edit.rs). */
sealed class ScalarReplacement {
    /** Replace by public semantic value under an explicit policy. */
    data class Semantic(
        /** Exact TOML item target. */
        override val target: NodeRef,
        /** New complete core scalar. */
        val value: PortableValue,
        /** Representation contract. */
        val policy: RepresentationPolicy,
    ) : ScalarReplacement()

    /** Replace by exact candidate literal bytes after full profile
     * validation. */
    data class Literal(
        /** Exact TOML item target. */
        override val target: NodeRef,
        /** Exact candidate scalar bytes. */
        val literal: ByteArray,
    ) : ScalarReplacement()

    /** The exact item target of either replacement form. */
    internal abstract val target: NodeRef
}

/** One typed TOML edit operation bound to an immutable base snapshot
 * (edit.rs). */
sealed class EditOperation {
    /** Existing scalar semantic or literal replacement. */
    data class ReplaceScalar(val replacement: ScalarReplacement) : EditOperation()

    /** Inserts one direct entry into a root, standard, or inline table. */
    data class InsertEntry(
        /** Exact table item target. */
        val table: NodeRef,
        /** Decoded direct key segment. */
        val key: String,
        /** Complete inserted value. */
        val value: PortableValue,
        /** Explicit association placement. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Removes one exact direct entry identity. */
    data class RemoveEntry(
        /** Exact `TomlEntry` target. */
        val target: NodeRef,
    ) : EditOperation()

    /** Replaces only one exact entry's direct key segment. */
    data class RenameEntry(
        /** Exact `TomlEntry` target. */
        val target: NodeRef,
        /** New decoded direct key segment. */
        val key: String,
    ) : EditOperation()

    /** Inserts one complete element into a TOML array value. */
    data class InsertArrayElement(
        /** Exact Array item target. */
        val array: NodeRef,
        /** Complete inserted value. */
        val value: PortableValue,
        /** Explicit association placement. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Removes one exact TOML array element identity. */
    data class RemoveArrayElement(
        /** Exact `TomlArrayElement` target. */
        val target: NodeRef,
    ) : EditOperation()
}

/** Immutable transaction; every operation resolves against one base
 * snapshot (edit.rs). */
class EditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: consema.document.SnapshotIdentity,
    private val operations: List<EditOperation>,
) {
    /** Ordered declared operations. */
    fun operations(): List<EditOperation> = operations
}

/** Builder that is not a committed edit (edit.rs). */
class EditTransactionBuilder internal constructor(
    private val base: consema.document.SnapshotIdentity,
    private val operations: MutableList<EditOperation> = ArrayList(),
) {
    companion object {
        /** Binds a new transaction to one immutable base document
         * (edit.rs). */
        fun new(document: TomlDocument): EditTransactionBuilder =
            EditTransactionBuilder(document.snapshotIdentity)
    }

    /** Adds a semantic scalar replacement (edit.rs). */
    fun semanticScalar(
        target: NodeRef,
        value: PortableValue,
        policy: RepresentationPolicy,
    ): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceScalar(ScalarReplacement.Semantic(target, value, policy)))
        return this
    }

    /** Adds an exact TOML scalar literal replacement (edit.rs). */
    fun literalScalar(target: NodeRef, literal: ByteArray): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceScalar(ScalarReplacement.Literal(target, literal)))
        return this
    }

    /** Adds one direct TOML table entry insertion (edit.rs). */
    fun insertEntry(
        table: NodeRef,
        key: String,
        value: PortableValue,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertEntry(table, key, value, placement))
        return this
    }

    /** Adds one exact TOML table entry removal (edit.rs). */
    fun removeEntry(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveEntry(target))
        return this
    }

    /** Adds one exact TOML direct key rename (edit.rs). */
    fun renameEntry(target: NodeRef, key: String): EditTransactionBuilder {
        operations.add(EditOperation.RenameEntry(target, key))
        return this
    }

    /** Adds one TOML array element insertion (edit.rs). */
    fun insertArrayElement(
        array: NodeRef,
        value: PortableValue,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertArrayElement(array, value, placement))
        return this
    }

    /** Adds one exact TOML array element removal (edit.rs). */
    fun removeArrayElement(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveArrayElement(target))
        return this
    }

    /** Completes the immutable request; target validation occurs atomically
     * at commit (edit.rs). */
    fun build(): EditTransaction = EditTransaction(base, operations.toList())
}

/** The closed edit failure vocabulary (edit.rs). The kind names
 * are the language-neutral comparison facts (the vector
 * `"failure": "UnsupportedSemanticValue"` spelling); [code] is the frozen
 * registered code (the StableFailure diagnostic_code mapping,
 * edit.rs). */
sealed class EditFailureKind {
    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : EditFailureKind()

    /** Target is not a TOML scalar item. */
    data object WrongRole : EditFailureKind()

    /** Public value cannot be represented as a TOML 1.0 scalar without
     * semantic loss. */
    data class UnsupportedSemanticValue(val kind: Kind) : EditFailureKind()

    /** Candidate bytes are not exactly one complete TOML 1.0 scalar
     * literal. */
    data object InvalidLiteral : EditFailureKind()

    /** `PreserveCompatible` could not retain the scalar category. */
    data object RepresentationIncompatible : EditFailureKind()

    /** `ExactLiteral` was requested without literal bytes. */
    data object ExactLiteralRequiresLiteralOperation : EditFailureKind()

    /** Two source edits overlap or target the same scalar. */
    data object ConflictingEdits : EditFailureKind()

    /** More than one operation names the same exact destructive target. */
    data object DuplicateTarget : EditFailureKind()

    /** Prepared source ownership intervals overlap or reuse one insertion
     * point. */
    data object OverlappingOwnership : EditFailureKind()

    /** One transaction edits an association and one of its owned
     * descendants. */
    data object AncestorDescendantConflict : EditFailureKind()

    /** An insertion anchor is removed by the same transaction. */
    data object PlacementAnchorRemoved : EditFailureKind()

    /** A target or placement anchor is not present in its declared
     * container. */
    data object TargetNotFound : EditFailureKind()

    /** The requested key already exists in the target table. */
    data object DuplicateKey : EditFailureKind()

    /** The requested structural operation is outside the frozen TOML v1
     * edit surface. */
    data object UnsupportedOperation : EditFailureKind()

    /** A structural value cannot be represented by TOML 1.0. */
    data class UnrepresentableValue(val kind: Kind) : EditFailureKind()

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(val limitName: String) : EditFailureKind()

    /** Replacement document could not be formed under the original
     * limits. */
    data object NewDocumentFormationFailed : EditFailureKind()
    ;

    /** The frozen registered code (edit.rs). */
    val code: String
        get() = when (this) {
            WrongSnapshot -> "core.edit.wrong-snapshot@1"
            WrongRole -> "core.edit.wrong-role@1"
            is UnsupportedSemanticValue, is UnrepresentableValue ->
                "core.edit.unsupported-value@1"
            InvalidLiteral -> "core.edit.invalid-literal@1"
            RepresentationIncompatible -> "core.edit.representation-incompatible@1"
            ExactLiteralRequiresLiteralOperation ->
                "core.edit.exact-literal-requires-literal@1"
            ConflictingEdits, DuplicateTarget, OverlappingOwnership,
            AncestorDescendantConflict, PlacementAnchorRemoved ->
                "core.edit.conflicting-edits@1"
            TargetNotFound -> "core.edit.target-not-found@1"
            DuplicateKey -> "core.edit.duplicate-key@1"
            UnsupportedOperation -> "core.edit.operation-unsupported@1"
            is ResourceLimit -> "core.edit.resource-limit@1"
            NewDocumentFormationFailed -> "core.edit.formation-failed@1"
        }

    /** The stable kind name (the Rust enum variant name). */
    val name: String
        get() = this::class.simpleName ?: "EditFailure"
}

/** The typed edit failure; [kind] is the stable language-neutral fact and
 * [code] the frozen registered code. */
class TomlEditException(val kind: EditFailureKind) :
    Exception("toml edit: ${kind.name} (${kind.code})") {
    /** The frozen registered code of the failure. */
    val code: String
        get() = kind.code
}

/** Atomic edit success (edit.rs). ChangeSet is not shipped in the
 * Kotlin TOML family (recorded gap, six-repo audit G090); the commit
 * exposes the equivalent facts ([diagnostics], [nodeMappings]) as family
 * records. */
class EditCommit(
    /** New immutable document. */
    val document: TomlDocument,
    /** Portable exact raw-byte application fact. */
    val sourcePatch: SourcePatch,
    /** Verifiable evidence for every byte outside the replacement set. */
    val untouchedProof: UntouchedByteProof,
    /** Ordered fallback diagnostics (the ChangeSet diagnostics of the Rust
     * commit; RFC 0001 §6). */
    val diagnostics: List<TomlDiagnostic>,
    /** Old-to-new node mapping facts (the ChangeSet node mappings of the
     * Rust commit). */
    val nodeMappings: List<TomlNodeMapping>,
)

/** One old-to-new node mapping fact (the Rust NodeMapping of the
 * not-shipped-in-Kotlin ChangeSet, recorded gap, six-repo audit G090;
 * edit.rs). */
data class TomlNodeMapping(
    /** Old structural identity. */
    val old: NodeRef,
    /** New structural identity, or null when deleted/unmapped. */
    val new: NodeRef?,
    /** Mapping status. */
    val status: TomlNodeMappingStatus,
    /** Stable reason for non-replaced mappings. */
    val reason: String?,
)

/** Node mapping status (the Rust NodeMappingStatus). */
enum class TomlNodeMappingStatus {
    /** The item was replaced at the same span. */
    Replaced,

    /** The item was deleted. */
    Deleted,

    /** The item was not mapped. */
    Unmapped,
}

/**
 * Atomically commits scalar and structural operations. A failure never
 * changes this snapshot (edit.rs; RFC 0004 §13).
 */
fun TomlDocument.commit(transaction: EditTransaction): EditCommit {
    if (transaction.baseSnapshot != snapshotIdentity) {
        throw TomlEditException(EditFailureKind.WrongSnapshot)
    }
    validateDependencies(transaction)
    val fallbackDiagnostics = ArrayList<TomlDiagnostic>()
    val prepared = ArrayList<PreparedEdit>()
    for (operation in transaction.operations()) {
        prepared.addAll(prepareOperation(operation, fallbackDiagnostics))
    }

    prepared.sortWith(compareBy({ it.oldSpan.startByte }, { it.oldSpan.endByte }))
    for (index in 1 until prepared.size) {
        val first = prepared[index - 1]
        val second = prepared[index]
        if (!first.oldSpan.isEmpty && !second.oldSpan.isEmpty &&
            (first.oldSpan.endByte > second.oldSpan.startByte || first.oldSpan == second.oldSpan)
        ) {
            throw TomlEditException(EditFailureKind.AncestorDescendantConflict)
        }
        if (first.oldSpan == second.oldSpan ||
            (first.oldSpan.isEmpty && second.oldSpan.isEmpty &&
                first.oldSpan.startByte == second.oldSpan.startByte)
        ) {
            throw TomlEditException(EditFailureKind.OverlappingOwnership)
        }
    }

    var targetLen = source.len
    for (edit in prepared) {
        val delta = edit.replacement.size - edit.oldSpan.len
        targetLen = try {
            Math.addExact(targetLen, delta)
        } catch (e: ArithmeticException) {
            throw TomlEditException(EditFailureKind.ResourceLimit("target-bytes"))
        }
    }
    if (targetLen > parseLimits.maxSourceBytes) {
        throw TomlEditException(EditFailureKind.ResourceLimit("target-bytes"))
    }
    val rendered = java.io.ByteArrayOutputStream(targetLen)
    var cursor = 0
    for (edit in prepared) {
        rendered.write(source.rawBytes(), cursor, edit.oldSpan.startByte - cursor)
        rendered.write(edit.replacement)
        cursor = edit.oldSpan.endByte
    }
    rendered.write(source.rawBytes(), cursor, source.len - cursor)
    val newDocument = try {
        parse(rendered.toByteArray(), profile, parseLimits)
    } catch (e: TomlFormationException) {
        throw TomlEditException(EditFailureKind.NewDocumentFormationFailed)
    }

    var delta = 0
    val replacements = ArrayList<SourceReplacement>(prepared.size)
    val nodeMappings = ArrayList<TomlNodeMapping>()
    val mappedOld = HashSet<NodeRef>()
    for (edit in prepared) {
        val newStart = edit.oldSpan.startByte + delta
        val newEnd = newStart + edit.replacement.size
        val replacement = SourceReplacement.new(
            edit.oldSpan.startByte,
            edit.oldSpan.endByte,
            source.rawBytes().copyOfRange(edit.oldSpan.startByte, edit.oldSpan.endByte),
            edit.replacement,
        )
        replacements.add(replacement)
        if (edit.mapping != null) {
            val (old, plan) = edit.mapping
            if (mappedOld.add(old)) {
                val (new, status, reason) = when (plan) {
                    MappingPlan.ReplacedLiteral -> {
                        val found = findItemBySpan(newDocument, newStart, newEnd)
                        Triple(
                            found?.let { newDocument.nodeRef(it, NodeRole.TomlItem) },
                            TomlNodeMappingStatus.Replaced,
                            if (found == null) "reparsed-item-not-uniquely-located" else null,
                        )
                    }
                    MappingPlan.Deleted -> Triple(null, TomlNodeMappingStatus.Deleted, null)
                    is MappingPlan.Unmapped -> {
                        Triple(null, TomlNodeMappingStatus.Unmapped, plan.reason)
                    }
                }
                nodeMappings.add(TomlNodeMapping(old, new, status, reason))
            }
        }
        delta += edit.replacement.size - edit.oldSpan.len
    }

    val patchLimits = sourcePatchLimits(parseLimits, prepared.size)
    val sourcePatch = try {
        SourcePatch.create(
            source,
            replacements,
            operationMetadata(transaction),
            patchLimits,
        )
    } catch (e: consema.document.SourcePatchException) {
        throw TomlEditException(EditFailureKind.NewDocumentFormationFailed)
    }
    val untouchedProof = try {
        UntouchedByteProof.create(source, newDocument.source(), sourcePatch.replacements())
    } catch (e: consema.document.UntouchedByteProofException) {
        throw TomlEditException(EditFailureKind.NewDocumentFormationFailed)
    }
    return EditCommit(newDocument, sourcePatch, untouchedProof, fallbackDiagnostics, nodeMappings)
}

/**
 * Fully validates and plans an edit without returning a new Document
 * (edit.rs; RFC 0004 §14). Dry-run and commit produce the same
 * replacement set and target digest.
 */
fun TomlDocument.dryRun(
    transaction: EditTransaction,
    sourceId: EditPlanSourceId,
): EditPlan {
    val commit = commit(transaction)
    val registry = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)
    val report = commit.diagnostics.map { it.toProtocolDiagnostic(sourceId.asStr(), registry) }
    return try {
        EditPlan.new(
            sourceId,
            profile(),
            operationSummaries(transaction),
            commit.sourcePatch,
            report,
        )
    } catch (e: consema.document.EditPlanException) {
        // edit.rs maps the plan-closure failure to the frozen
        // formation code.
        throw TomlEditException(EditFailureKind.NewDocumentFormationFailed)
    }
}

/** One prepared raw-byte edit (edit.rs). */
private class PreparedEdit(
    val oldSpan: Span,
    val replacement: ByteArray,
    val mapping: Pair<NodeRef, MappingPlan>?,
)

/** The node-mapping plan of one prepared edit (edit.rs). */
private sealed class MappingPlan {
    data object ReplacedLiteral : MappingPlan()
    data object Deleted : MappingPlan()
    data class Unmapped(val reason: String) : MappingPlan()
}

private fun TomlDocument.prepareOperation(
    operation: EditOperation,
    diagnostics: MutableList<TomlDiagnostic>,
): List<PreparedEdit> = when (operation) {
    is EditOperation.ReplaceScalar ->
        listOf(prepareScalar(operation.replacement, diagnostics))
    is EditOperation.InsertEntry ->
        prepareInsertEntry(operation.table, operation.key, operation.value, operation.placement)
    is EditOperation.RemoveEntry -> prepareRemoveEntry(operation.target)
    is EditOperation.RenameEntry -> listOf(prepareRenameEntry(operation.target, operation.key))
    is EditOperation.InsertArrayElement ->
        prepareInsertArrayElement(operation.array, operation.value, operation.placement)
    is EditOperation.RemoveArrayElement -> prepareRemoveArrayElement(operation.target)
}

private fun TomlDocument.prepareScalar(
    operation: ScalarReplacement,
    diagnostics: MutableList<TomlDiagnostic>,
): PreparedEdit {
    val index = resolveTarget(operation.target, NodeRole.TomlItem)
    val oldKind = itemEntity(index).kind.publicKind()
    if (!isScalarKind(oldKind)) {
        throw TomlEditException(EditFailureKind.WrongRole)
    }
    val replacement = when (operation) {
        is ScalarReplacement.Literal -> {
            validateExactScalar(operation.literal)
            // Defensive copy: the transaction owns its literal bytes
            // (edit.rs `literal.to_vec()`).
            operation.literal.copyOf()
        }
        is ScalarReplacement.Semantic -> semanticLiteral(
            operation.value,
            oldKind,
            operation.policy,
            entity(index).span,
            diagnostics,
        )
    }
    return PreparedEdit(
        entity(index).span,
        replacement,
        operation.target to MappingPlan.ReplacedLiteral,
    )
}

private fun TomlDocument.prepareInsertEntry(
    table: NodeRef,
    key: String,
    value: PortableValue,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val tableIndex = resolveTarget(table, NodeRole.TomlItem)
    val item = itemEntity(tableIndex).kind
    val entries: List<Int> = when (item) {
        is InternalItemKind.Table -> item.entries
        is InternalItemKind.InlineTable -> item.entries
        else -> throw TomlEditException(EditFailureKind.WrongRole)
    }
    val kind = item.publicKind()
    if (kind != TomlItemKind.RootTable && kind != TomlItemKind.StandardTable &&
        kind != TomlItemKind.InlineTable
    ) {
        throw TomlEditException(EditFailureKind.UnsupportedOperation)
    }
    if (entries.any { entryName(it) == key }) {
        throw TomlEditException(EditFailureKind.DuplicateKey)
    }
    val fragmentBuilder = java.io.ByteArrayOutputStream()
    fragmentBuilder.write(canonicalString(key).toByteArray(Charsets.UTF_8))
    fragmentBuilder.write(" = ".toByteArray(Charsets.UTF_8))
    fragmentBuilder.write(fragment(value))
    val fragment = fragmentBuilder.toByteArray()
    if (fragment.size > parseLimits.maxSourceBytes) {
        // append_fragment bound (edit.rs).
        throw TomlEditException(EditFailureKind.ResourceLimit("insert-fragment"))
    }
    return if (kind == TomlItemKind.InlineTable) {
        listOf(
            prepareDelimitedInsertion(
                table,
                entity(tableIndex).span,
                entries,
                DelimitedSyntax(NodeRole.TomlEntry, TomlSyntaxKind.LeftBrace, TomlSyntaxKind.RightBrace),
                placement,
                fragment,
            ),
        )
    } else {
        listOf(prepareTableLineInsertion(table, tableIndex, entries, placement, fragment))
    }
}

private fun TomlDocument.prepareInsertArrayElement(
    array: NodeRef,
    value: PortableValue,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val index = resolveTarget(array, NodeRole.TomlItem)
    val item = itemEntity(index).kind as? InternalItemKind.Array
        ?: throw TomlEditException(EditFailureKind.WrongRole)
    return listOf(
        prepareDelimitedInsertion(
            array,
            entity(index).span,
            item.elements,
            DelimitedSyntax(NodeRole.TomlArrayElement, TomlSyntaxKind.LeftBracket, TomlSyntaxKind.RightBracket),
            placement,
            fragment(value),
        ),
    )
}

/** The delimiter syntax of one delimited container (edit.rs). */
private class DelimitedSyntax(
    val anchorRole: NodeRole,
    val open: TomlSyntaxKind,
    val close: TomlSyntaxKind,
)

/** Prepares an insertion inside a delimited container (array or inline
 * table): a comma-owned zero-width replacement (edit.rs). */
private fun TomlDocument.prepareDelimitedInsertion(
    container: NodeRef,
    containerSpan: Span,
    associations: List<Int>,
    syntax: DelimitedSyntax,
    placement: AssociationPlacement,
    fragment: ByteArray,
): PreparedEdit {
    val position: Int
    var prefixComma = false
    var suffixComma = false
    if (associations.isEmpty()) {
        when (placement) {
            AssociationPlacement.Start -> {
                position = delimiter(syntax.open, containerSpan, last = false).endByte
            }
            AssociationPlacement.End -> {
                position = delimiter(syntax.close, containerSpan, last = true).startByte
            }
            is AssociationPlacement.Before, is AssociationPlacement.After ->
                throw TomlEditException(EditFailureKind.TargetNotFound)
        }
    } else {
        when (placement) {
            AssociationPlacement.Start -> {
                position = entity(associations[0]).span.startByte
                suffixComma = true
            }
            AssociationPlacement.End -> {
                position = entity(associations[associations.size - 1]).span.endByte
                prefixComma = true
            }
            is AssociationPlacement.Before -> {
                val anchor = resolveAnchor(placement.anchor, syntax.anchorRole, associations)
                position = entity(anchor).span.startByte
                suffixComma = true
            }
            is AssociationPlacement.After -> {
                val anchor = resolveAnchor(placement.anchor, syntax.anchorRole, associations)
                position = entity(anchor).span.endByte
                prefixComma = true
            }
        }
    }
    val replacement = java.io.ByteArrayOutputStream()
    if (prefixComma) {
        replacement.write(','.code)
    }
    replacement.write(fragment)
    if (suffixComma) {
        replacement.write(','.code)
    }
    return PreparedEdit(
        authority.span(position, position),
        replacement.toByteArray(),
        container to MappingPlan.Unmapped("container-reparsed-after-structural-insertion"),
    )
}

/** Prepares a line-owned insertion into a root or standard table
 * (edit.rs). */
private fun TomlDocument.prepareTableLineInsertion(
    table: NodeRef,
    tableIndex: Int,
    entries: List<Int>,
    placement: AssociationPlacement,
    fragment: ByteArray,
): PreparedEdit {
    val kind = itemEntity(tableIndex).kind.publicKind()
    val position = when (placement) {
        AssociationPlacement.Start -> {
            if (kind == TomlItemKind.RootTable) {
                0
            } else {
                firstLineAfterHeader(entity(tableIndex).span)
            }
        }
        AssociationPlacement.End -> tableEndInsertion(entries, tableIndex)
        is AssociationPlacement.Before -> {
            val anchor = resolveAnchor(placement.anchor, NodeRole.TomlEntry, entries)
            lineStart(entity(anchor).span.startByte)
        }
        is AssociationPlacement.After -> {
            val anchor = resolveAnchor(placement.anchor, NodeRole.TomlEntry, entries)
            if (isTableKind(entryItemKind(anchor))) {
                throw TomlEditException(EditFailureKind.UnsupportedOperation)
            }
            lineAfter(entity(anchor).span.endByte)
        }
    }
    return PreparedEdit(
        authority.span(position, position),
        lineFragment(position, fragment),
        table to MappingPlan.Unmapped("table-reparsed-after-entry-insertion"),
    )
}

private fun TomlDocument.prepareRemoveEntry(target: NodeRef): List<PreparedEdit> {
    val index = resolveTarget(target, NodeRole.TomlEntry)
    if (isTableKind(entryItemKind(index))) {
        throw TomlEditException(EditFailureKind.UnsupportedOperation)
    }
    val parent = parentTable(index)
        ?: throw TomlEditException(EditFailureKind.TargetNotFound)
    val (container, entries, ordinal) = parent
    return when (itemEntity(container).kind.publicKind()) {
        TomlItemKind.InlineTable -> prepareDelimitedRemoval(
            target,
            index,
            entries,
            ordinal,
            entity(container).span.endByte,
        )
        TomlItemKind.RootTable, TomlItemKind.StandardTable -> listOf(
            PreparedEdit(
                entity(index).span,
                ByteArray(0),
                target to MappingPlan.Deleted,
            ),
        )
        else -> throw TomlEditException(EditFailureKind.UnsupportedOperation)
    }
}

private fun TomlDocument.prepareRemoveArrayElement(target: NodeRef): List<PreparedEdit> {
    val index = resolveTarget(target, NodeRole.TomlArrayElement)
    val parent = parentArray(index)
        ?: throw TomlEditException(EditFailureKind.TargetNotFound)
    val (container, elements, ordinal) = parent
    return prepareDelimitedRemoval(
        target,
        index,
        elements,
        ordinal,
        entity(container).span.endByte,
    )
}

/** Prepares a removal inside a delimited container, owning the necessary
 * comma (edit.rs). */
private fun TomlDocument.prepareDelimitedRemoval(
    target: NodeRef,
    index: Int,
    associations: List<Int>,
    ordinal: Int,
    containerEnd: Int,
): List<PreparedEdit> {
    val targetSpan = entity(index).span
    val edits = ArrayList<PreparedEdit>(2)
    val comma = removalComma(associations, ordinal, containerEnd)
    if (comma != null && (comma.endByte == targetSpan.startByte || comma.startByte == targetSpan.endByte)) {
        edits.add(
            PreparedEdit(
                authority.span(
                    minOf(comma.startByte, targetSpan.startByte),
                    maxOf(comma.endByte, targetSpan.endByte),
                ),
                ByteArray(0),
                target to MappingPlan.Deleted,
            ),
        )
        return edits
    }
    edits.add(
        PreparedEdit(
            targetSpan,
            ByteArray(0),
            target to MappingPlan.Deleted,
        ),
    )
    if (comma != null) {
        edits.add(
            PreparedEdit(
                comma,
                ByteArray(0),
                null,
            ),
        )
    }
    return edits
}

private fun TomlDocument.prepareRenameEntry(target: NodeRef, key: String): PreparedEdit {
    val index = resolveTarget(target, NodeRole.TomlEntry)
    if (isTableKind(entryItemKind(index))) {
        throw TomlEditException(EditFailureKind.UnsupportedOperation)
    }
    val parent = parentTable(index)
        ?: throw TomlEditException(EditFailureKind.TargetNotFound)
    val (container, entries, _) = parent
    if (itemEntity(container).kind.publicKind() != TomlItemKind.RootTable &&
        itemEntity(container).kind.publicKind() != TomlItemKind.StandardTable &&
        itemEntity(container).kind.publicKind() != TomlItemKind.InlineTable
    ) {
        throw TomlEditException(EditFailureKind.UnsupportedOperation)
    }
    if (entries.any { candidate -> candidate != index && entryName(candidate) == key }) {
        throw TomlEditException(EditFailureKind.DuplicateKey)
    }
    val entry = entity(index).kind as? EntityKind.Entry
        ?: throw TomlEditException(EditFailureKind.WrongRole)
    return PreparedEdit(
        entity(entry.entry.key).span,
        canonicalString(key).toByteArray(Charsets.UTF_8),
        target to MappingPlan.Unmapped("entry-reparsed-after-key-rename"),
    )
}

private fun TomlDocument.resolveTarget(target: NodeRef, role: NodeRole): Int {
    if (target.snapshot != snapshotIdentity) {
        throw TomlEditException(EditFailureKind.WrongSnapshot)
    }
    if (target.role != role) {
        throw TomlEditException(EditFailureKind.WrongRole)
    }
    return try {
        validateRef(target, role)
    } catch (e: TomlAccessException) {
        when (e.kind) {
            TomlAccessError.WRONG_SNAPSHOT -> throw TomlEditException(EditFailureKind.WrongSnapshot)
            TomlAccessError.WRONG_ROLE -> throw TomlEditException(EditFailureKind.WrongRole)
            TomlAccessError.UNKNOWN_NODE -> throw TomlEditException(EditFailureKind.TargetNotFound)
        }
    }
}

private fun TomlDocument.resolveAnchor(
    anchor: NodeRef,
    role: NodeRole,
    associations: List<Int>,
): Int {
    val index = resolveTarget(anchor, role)
    if (index !in associations) {
        throw TomlEditException(EditFailureKind.TargetNotFound)
    }
    return index
}

/** The canonical materialization fragment of one complete value
 * (edit.rs). */
private fun TomlDocument.fragment(value: PortableValue): ByteArray {
    val limits = MaterializationLimits(
        maxInputNodes = parseLimits.maxNodeCount,
        maxOutputBytes = parseLimits.maxSourceBytes,
        maxDepth = parseLimits.maxNestingDepth,
        maxReportEntries = parseLimits.maxDiagnostics,
        maxProvenanceEntries = (parseLimits.maxNodeCount.toLong() * 4)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
    return try {
        canonicalFragment(value, limits)
    } catch (e: consema.document.MaterializationException) {
        when (e.kind) {
            consema.document.MaterializationFailureKind.UNREPRESENTABLE ->
                throw TomlEditException(
                    EditFailureKind.UnrepresentableValue(e.valueKind ?: value.kind),
                )
            consema.document.MaterializationFailureKind.RESOURCE_LIMIT ->
                throw TomlEditException(EditFailureKind.ResourceLimit(e.name))
            else -> throw TomlEditException(EditFailureKind.NewDocumentFormationFailed)
        }
    }
}

private fun TomlDocument.parentTable(entry: Int): Triple<Int, List<Int>, Int>? {
    for ((index, entity) in entities.withIndex()) {
        val kind = entity.kind
        if (kind is EntityKind.Item) {
            val entries = when (val item = kind.item.kind) {
                is InternalItemKind.Table -> item.entries
                is InternalItemKind.InlineTable -> item.entries
                else -> null
            }
            if (entries != null) {
                val ordinal = entries.indexOf(entry)
                if (ordinal >= 0) {
                    return Triple(index, entries, ordinal)
                }
            }
        }
    }
    return null
}

private fun TomlDocument.parentArray(element: Int): Triple<Int, List<Int>, Int>? {
    for ((index, entity) in entities.withIndex()) {
        val kind = entity.kind
        if (kind is EntityKind.Item) {
            val elements = (kind.item.kind as? InternalItemKind.Array)?.elements
            if (elements != null) {
                val ordinal = elements.indexOf(element)
                if (ordinal >= 0) {
                    return Triple(index, elements, ordinal)
                }
            }
        }
    }
    return null
}

private fun TomlDocument.entryName(entry: Int): String {
    val entryEntity = (entities[entry].kind as? EntityKind.Entry)?.entry
        ?: error("typed TOML entry")
    val key = (entities[entryEntity.key].kind as? EntityKind.Key)?.key
        ?: error("typed TOML key")
    return key.name
}

private fun TomlDocument.entryItemKind(entry: Int): TomlItemKind {
    val entryEntity = (entities[entry].kind as? EntityKind.Entry)?.entry
        ?: error("typed TOML entry")
    return itemEntity(entryEntity.item).kind.publicKind()
}

private fun TomlDocument.tableEndInsertion(entries: List<Int>, tableIndex: Int): Int {
    val tableEntry = entries.firstOrNull { isTableKind(entryItemKind(it)) }
    if (tableEntry != null) {
        return lineStart(entity(tableEntry).span.startByte)
    }
    val last = entries.lastOrNull()
    if (last != null) {
        return lineAfter(entity(last).span.endByte)
    }
    if (itemEntity(tableIndex).kind.publicKind() == TomlItemKind.StandardTable) {
        return firstLineAfterHeader(entity(tableIndex).span)
    }
    return entity(tableIndex).span.endByte
}

private fun TomlDocument.firstLineAfterHeader(tableSpan: Span): Int =
    lineAfter(tableSpan.startByte)

private fun TomlDocument.lineStart(position: Int): Int {
    for (index in position - 1 downTo 0) {
        if (source.rawBytes()[index] == '\n'.code.toByte()) {
            return index + 1
        }
    }
    return 0
}

private fun TomlDocument.lineAfter(position: Int): Int {
    for (index in position until source.len) {
        if (source.rawBytes()[index] == '\n'.code.toByte()) {
            return index + 1
        }
    }
    return source.len
}

/** Builds the line-owned insertion fragment with the document's newline
 * spelling (edit.rs). */
private fun TomlDocument.lineFragment(position: Int, fragment: ByteArray): ByteArray {
    val newline = newlineBytes()
    val needsPrefix = position > 0 && source.rawBytes()[position - 1] != '\n'.code.toByte()
    val needsSuffix = position < source.len
    val output = java.io.ByteArrayOutputStream(
        fragment.size + newline.size * (if (needsPrefix) 1 else 0) + newline.size * (if (needsSuffix) 1 else 0),
    )
    if (needsPrefix) {
        output.write(newline)
    }
    output.write(fragment)
    if (needsSuffix) {
        output.write(newline)
    }
    return output.toByteArray()
}

/** The document's newline spelling: the first Newline piece, or LF when
 * the document has none (edit.rs). */
private fun TomlDocument.newlineBytes(): ByteArray {
    for ((index, kind) in syntaxKinds.withIndex()) {
        if (kind == TomlSyntaxKind.Newline) {
            val piece = structuralIndex.pieces()[index]
            return source.rawBytes().copyOfRange(piece.span.startByte, piece.span.endByte)
        }
    }
    return byteArrayOf('\n'.code.toByte())
}

/** Finds the comma owned by one removal, or null (edit.rs). */
private fun TomlDocument.removalComma(
    associations: List<Int>,
    ordinal: Int,
    containerEnd: Int,
): Span? {
    val current = entity(associations[ordinal]).span
    val followingEnd = if (ordinal + 1 < associations.size) {
        entity(associations[ordinal + 1]).span.startByte
    } else {
        containerEnd
    }
    val comma = syntaxBetween(TomlSyntaxKind.Comma, current.endByte, followingEnd, last = false)
    if (comma != null) {
        return comma
    }
    if (ordinal == 0) {
        return null
    }
    val previous = entity(associations[ordinal - 1]).span
    return syntaxBetween(TomlSyntaxKind.Comma, previous.endByte, current.startByte, last = true)
        ?: throw TomlEditException(EditFailureKind.TargetNotFound)
}

private fun TomlDocument.delimiter(
    kind: TomlSyntaxKind,
    container: Span,
    last: Boolean,
): Span = syntaxBetween(kind, container.startByte, container.endByte, last)
    ?: throw TomlEditException(EditFailureKind.TargetNotFound)

private fun TomlDocument.syntaxBetween(
    kind: TomlSyntaxKind,
    start: Int,
    end: Int,
    last: Boolean,
): Span? {
    val matches = structuralIndex.pieces().asSequence()
        .zip(syntaxKinds.asSequence())
        .filter { (piece, candidate) ->
            candidate == kind && piece.span.startByte >= start && piece.span.endByte <= end
        }
        .map { (piece, _) -> piece.span }
    return if (last) {
        matches.lastOrNull()
    } else {
        matches.firstOrNull()
    }
}

/** Validates cross-operation dependencies before any preparation
 * (edit.rs). */
private fun validateDependencies(transaction: EditTransaction) {
    val destructive = HashSet<NodeRef>()
    val removed = HashSet<NodeRef>()
    val anchors = ArrayList<NodeRef>()
    for (operation in transaction.operations()) {
        val target = when (operation) {
            is EditOperation.ReplaceScalar -> operation.replacement.target
            is EditOperation.RemoveEntry -> operation.target
            is EditOperation.RenameEntry -> operation.target
            is EditOperation.RemoveArrayElement -> operation.target
            is EditOperation.InsertEntry, is EditOperation.InsertArrayElement -> null
        }
        if (target != null && !destructive.add(target)) {
            throw TomlEditException(EditFailureKind.DuplicateTarget)
        }
        when (operation) {
            is EditOperation.RemoveEntry -> removed.add(operation.target)
            is EditOperation.RemoveArrayElement -> removed.add(operation.target)
            is EditOperation.InsertEntry -> {
                val placement = operation.placement
                if (placement is AssociationPlacement.Before) {
                    anchors.add(placement.anchor)
                }
                if (placement is AssociationPlacement.After) {
                    anchors.add(placement.anchor)
                }
            }
            is EditOperation.InsertArrayElement -> {
                val placement = operation.placement
                if (placement is AssociationPlacement.Before) {
                    anchors.add(placement.anchor)
                }
                if (placement is AssociationPlacement.After) {
                    anchors.add(placement.anchor)
                }
            }
            is EditOperation.ReplaceScalar, is EditOperation.RenameEntry -> {}
        }
    }
    if (anchors.any { it in removed }) {
        throw TomlEditException(EditFailureKind.PlacementAnchorRemoved)
    }
}

/** The derived SourcePatch limits (edit.rs). */
private fun sourcePatchLimits(parseLimits: ParseLimits, operationCount: Int): SourcePatchLimits =
    SourcePatchLimits(
        source = SourceLimits(
            maxRawBytes = parseLimits.maxSourceBytes,
            maxDecodedUtf8Bytes = parseLimits.maxSourceBytes,
            maxDecodedScalars = parseLimits.maxSourceBytes,
        ),
        maxReplacements = operationCount,
        maxPatchBytes = parseLimits.maxSourceBytes.coerceAtMost(Int.MAX_VALUE / 2) * 2,
    )

/** The ordered `operation.{index}` metadata of the derived SourcePatch
 * (edit.rs). */
private fun operationMetadata(transaction: EditTransaction): Map<String, String> =
    transaction.operations().mapIndexed { index, operation ->
        "operation.$index" to operationId(operation)
    }.toMap()

private fun operationId(operation: EditOperation): String = when (operation) {
    is EditOperation.ReplaceScalar -> when (operation.replacement) {
        is ScalarReplacement.Semantic -> "toml.edit.replace-scalar-semantic@1"
        is ScalarReplacement.Literal -> "toml.edit.replace-scalar-literal@1"
    }
    is EditOperation.InsertEntry -> "toml.edit.insert-entry@1"
    is EditOperation.RemoveEntry -> "toml.edit.remove-entry@1"
    is EditOperation.RenameEntry -> "toml.edit.rename-entry@1"
    is EditOperation.InsertArrayElement -> "toml.edit.insert-array-element@1"
    is EditOperation.RemoveArrayElement -> "toml.edit.remove-array-element@1"
}

/** The safe content-free operation summaries of the EditPlan
 * (edit.rs). */
private fun operationSummaries(transaction: EditTransaction): List<EditOperationSummary> =
    transaction.operations().map { operation ->
        val (id, targetRole, arguments) = when (operation) {
            is EditOperation.ReplaceScalar -> when (val replacement = operation.replacement) {
                is ScalarReplacement.Semantic -> Triple(
                    "toml.edit.replace-scalar-semantic",
                    "toml.scalar-item@1",
                    mapOf(
                        "representation_policy" to replacement.policy.summaryName(),
                        "value_kind" to valueKindName(replacement.value.kind),
                    ),
                )
                is ScalarReplacement.Literal -> Triple(
                    "toml.edit.replace-scalar-literal",
                    "toml.scalar-item@1",
                    mapOf("literal_bytes" to replacement.literal.size.toString()),
                )
            }
            is EditOperation.InsertEntry -> Triple(
                "toml.edit.insert-entry",
                "toml.table-item@1",
                mapOf(
                    "key_bytes" to operation.key.length.toString(),
                    "placement" to placementName(operation.placement),
                    "value_kind" to valueKindName(operation.value.kind),
                ),
            )
            is EditOperation.RemoveEntry -> Triple(
                "toml.edit.remove-entry",
                "toml.entry@1",
                emptyMap(),
            )
            is EditOperation.RenameEntry -> Triple(
                "toml.edit.rename-entry",
                "toml.entry@1",
                mapOf("key_bytes" to operation.key.length.toString()),
            )
            is EditOperation.InsertArrayElement -> Triple(
                "toml.edit.insert-array-element",
                "toml.array-item@1",
                mapOf(
                    "placement" to placementName(operation.placement),
                    "value_kind" to valueKindName(operation.value.kind),
                ),
            )
            is EditOperation.RemoveArrayElement -> Triple(
                "toml.edit.remove-array-element",
                "toml.array-element@1",
                emptyMap(),
            )
        }
        EditOperationSummary.new(
            FormatOperationId(id, 1),
            arguments + ("target_role" to targetRole),
        )
    }

private fun placementName(placement: AssociationPlacement): String = when (placement) {
    AssociationPlacement.Start -> "start"
    AssociationPlacement.End -> "end"
    is AssociationPlacement.Before -> "before"
    is AssociationPlacement.After -> "after"
}

/** The stable value-kind summary spelling (edit.rs). */
internal fun valueKindName(kind: Kind): String = when (kind) {
    Kind.Null -> "null"
    Kind.Boolean -> "boolean"
    Kind.Integer -> "integer"
    Kind.Decimal -> "decimal"
    Kind.BinaryFloat32 -> "binary-float32"
    Kind.BinaryFloat64 -> "binary-float64"
    Kind.String -> "string"
    Kind.Bytes -> "bytes"
    Kind.Date -> "date"
    Kind.Time -> "time"
    Kind.LocalDateTime -> "local-date-time"
    Kind.OffsetDateTime -> "offset-date-time"
    Kind.Sequence -> "sequence"
    Kind.Object -> "object"
    Kind.EntryMapping -> "entry-mapping"
}

internal fun isScalarKind(kind: TomlItemKind): Boolean = when (kind) {
    TomlItemKind.String, TomlItemKind.Integer, TomlItemKind.Float, TomlItemKind.Boolean,
    TomlItemKind.OffsetDateTime, TomlItemKind.LocalDateTime, TomlItemKind.LocalDate,
    TomlItemKind.LocalTime -> true
    else -> false
}

internal fun isTableKind(kind: TomlItemKind): Boolean = when (kind) {
    TomlItemKind.RootTable, TomlItemKind.StandardTable, TomlItemKind.ImplicitTable,
    TomlItemKind.DottedTable, TomlItemKind.ArrayOfTables -> true
    else -> false
}

/**
 * Validates that the candidate bytes are exactly one complete TOML 1.0
 * scalar literal (edit.rs): the parse of `_ = <literal>` must
 * yield exactly one entry whose value span is exactly the literal range
 * and whose category is scalar.
 */
internal fun validateExactScalar(literal: ByteArray): TomlItemKind {
    val literalText = try {
        String(literal, Charsets.UTF_8)
    } catch (e: Exception) {
        throw TomlEditException(EditFailureKind.InvalidLiteral)
    }
    val prefix = "_ = "
    val source = (prefix + literalText).toByteArray(Charsets.UTF_8)
    val tree = try {
        parseTree(source)
    } catch (e: ParseError) {
        throw TomlEditException(EditFailureKind.InvalidLiteral)
    }
    if (tree.items.size != 1) {
        throw TomlEditException(EditFailureKind.InvalidLiteral)
    }
    val item = tree.items[0]
    if (item.kind != PItemKind.KEYVAL) {
        throw TomlEditException(EditFailureKind.InvalidLiteral)
    }
    val value = item.value!!
    val expectedStart = prefix.toByteArray(Charsets.UTF_8).size
    if (value.span.start != expectedStart || value.span.end != expectedStart + literal.size) {
        throw TomlEditException(EditFailureKind.InvalidLiteral)
    }
    return when (value.kind) {
        PValueKind.STRING -> TomlItemKind.String
        PValueKind.INTEGER -> TomlItemKind.Integer
        PValueKind.FLOAT -> TomlItemKind.Float
        PValueKind.BOOLEAN -> TomlItemKind.Boolean
        PValueKind.DATETIME -> {
            val dt = value.dateTime
            when {
                dt.date != null && dt.time != null && dt.offset != null -> TomlItemKind.OffsetDateTime
                dt.date != null && dt.time != null -> TomlItemKind.LocalDateTime
                dt.date != null -> TomlItemKind.LocalDate
                dt.time != null -> TomlItemKind.LocalTime
                else -> throw TomlEditException(EditFailureKind.InvalidLiteral)
            }
        }
        PValueKind.ARRAY, PValueKind.INLINE_TABLE ->
            throw TomlEditException(EditFailureKind.InvalidLiteral)
    }
}

/** Computes the semantic replacement literal under an explicit policy
 * (edit.rs). */
private fun semanticLiteral(
    value: PortableValue,
    oldKind: TomlItemKind,
    policy: RepresentationPolicy,
    targetSpan: Span,
    diagnostics: MutableList<TomlDiagnostic>,
): ByteArray {
    if (policy == RepresentationPolicy.ExactLiteral) {
        throw TomlEditException(EditFailureKind.ExactLiteralRequiresLiteralOperation)
    }
    val newKind = portableTomlKind(value)
        ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
    val compatible = oldKind == newKind
    when {
        policy == RepresentationPolicy.PreserveCompatible && !compatible ->
            throw TomlEditException(EditFailureKind.RepresentationIncompatible)
        policy == RepresentationPolicy.PreserveElseCanonical && !compatible -> {
            diagnostics.add(
                TomlDiagnostic(
                    code = TOML_EDIT_REPRESENTATION_FALLBACK,
                    category = DiagnosticCategory.Edit,
                    severity = Severity.Warning,
                    startByte = targetSpan.startByte,
                    endByte = targetSpan.endByte,
                    arguments = mapOf(
                        "old_kind" to oldKind.name,
                        "new_kind" to newKind.name,
                    ),
                    notes = emptyList(),
                    occurrence = diagnostics.size.toLong(),
                ),
            )
        }
    }
    val literal = canonicalLiteral(value)
    val validatedKind = validateExactScalar(literal.toByteArray(Charsets.UTF_8))
    if (validatedKind != newKind) {
        throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
    }
    return literal.toByteArray(Charsets.UTF_8)
}

/** The TOML scalar category of one PortableValue (edit.rs). */
internal fun portableTomlKind(value: PortableValue): TomlItemKind? = when (value.kind) {
    Kind.String -> TomlItemKind.String
    Kind.Integer -> TomlItemKind.Integer
    Kind.BinaryFloat64 -> TomlItemKind.Float
    Kind.Boolean -> TomlItemKind.Boolean
    Kind.Date -> TomlItemKind.LocalDate
    Kind.Time -> TomlItemKind.LocalTime
    Kind.LocalDateTime -> TomlItemKind.LocalDateTime
    Kind.OffsetDateTime -> TomlItemKind.OffsetDateTime
    else -> null
}

/** The canonical scalar literal of one PortableValue (edit.rs). */
internal fun canonicalLiteral(value: PortableValue): String = when (value) {
    is PvString -> canonicalString(value.value)
    is PvInteger -> {
        if (value.value.abs().bitLength() > 63) {
            throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
        }
        value.value.toString()
    }
    is PvBinaryFloat64 -> canonicalFloatText(value.bits)
        ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
    is PvBoolean -> value.value.toString()
    is consema.core.PvDate -> {
        val year = try {
            value.year.intValueExact()
        } catch (e: ArithmeticException) {
            -1
        }
        canonicalDateText(year, value.month, value.day)
            ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
    }
    is consema.core.PvTime -> {
        val nanoseconds = exactNanoseconds(value.fractionalSecond)
            ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
        canonicalTimeText(value.hour, value.minute, value.second, nanoseconds)
    }
    is consema.core.PvLocalDateTime -> {
        val date = value.date
        val time = value.time
        val year = try {
            date.year.intValueExact()
        } catch (e: ArithmeticException) {
            -1
        }
        val nanoseconds = exactNanoseconds(time.fractionalSecond)
            ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
        canonicalLocalDateTimeText(
            year, date.month, date.day,
            time.hour, time.minute, time.second, nanoseconds,
        ) ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
    }
    is PvOffsetDateTime -> {
        val date = value.local.date
        val time = value.local.time
        val year = try {
            date.year.intValueExact()
        } catch (e: ArithmeticException) {
            -1
        }
        val nanoseconds = exactNanoseconds(time.fractionalSecond)
            ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
        canonicalOffsetDateTimeText(
            year, date.month, date.day,
            time.hour, time.minute, time.second, nanoseconds,
            value.offsetSeconds,
        ) ?: throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
    }
    else -> throw TomlEditException(EditFailureKind.UnsupportedSemanticValue(value.kind))
}

/** Finds the unique item whose span is exactly [start, end) in the
 * reparsed document (edit.rs). */
private fun findItemBySpan(document: TomlDocument, start: Int, end: Int): Int? {
    val matches = document.entities.withIndex()
        .filter { (_, entity) ->
            entity.kind is EntityKind.Item &&
                entity.span.startByte == start && entity.span.endByte == end
        }
        .map { (index, _) -> index }
    if (matches.size == 1) {
        return matches[0]
    }
    return null
}
