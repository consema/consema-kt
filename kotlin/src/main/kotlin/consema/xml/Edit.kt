// Snapshot-bound XML structural edit (RFC 0012 §11; RFC 0004 §13-16).
//
// Data authority:
//   - RFC 0012 §11 (https://github.com/consema/consema/blob/main/docs/rfcs/0012-xml-1.0-safe-profile-v1.md):
//     V1 publishes eight versioned operations; each operation targets one
//     exact NodeRef; placement uses one exact parent and an optional
//     sibling/attribute anchor; duplicate expanded attributes, invalid
//     namespace bindings, unbound prefixes, reserved-prefix misuse,
//     ancestor/self placement, stale snapshots, overlapping replacements,
//     and operations that would break mixed-content or document-root
//     invariants fail before commit; semantic replacement accepts text or
//     validated QName facts, never raw untrusted markup; new literal content
//     is XML-escaped under the existing encoding; commit preserves every
//     byte outside operation-owned spans, reparses the target, verifies
//     promised XML/namespace semantics, produces a complete ChangeSet,
//     derives an UntouchedByteProof, and emits a replayable SourcePatch;
//     dry-run and commit have identical replacement sets and target digest.
//     XML ReplaceText excludes CDATA: the target role is RoleXmlText only.
//   - https://github.com/consema/consema-rs/blob/main/consema-xml/src/edit.rs is the byte-arbitration authority:
//     NameFacts (edit.rs), placements (edit.rs), the operation
//     enum (edit.rs), transaction/builder (edit.rs), the
//     commit algebra and EditFailure codes (edit.rs), dependency
//     checks (edit.rs), encoding helpers (edit.rs), the
//     per-operation span planning (edit.rs), the extent helpers
//     (edit.rs), patch limits and operation metadata
//     (edit.rs), operation summaries (edit.rs).
//   - The Kotlin document package owns SourcePatch (kotlin/src/main/kotlin/consema/document/Patch.kt),
//     UntouchedByteProof (kotlin/src/main/kotlin/consema/document/UntouchedProof.kt), and EditPlan
//     (kotlin/src/main/kotlin/consema/document/EditPlan.kt). ChangeSet is not shipped in the Kotlin XML
//     family (recorded gap, six-repo audit G090); the commit carries the
//     ordered edit diagnostics instead (the json family precedent,
//     kotlin/src/main/kotlin/consema/json/Edit.kt).
//   - conformance/vectors/xml-1-0-safe-v1.json cases xml.edit.* pin the
//     render outcomes; the conformance runner resolves name/ordinal
//     selectors to NodeRefs (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/xml_v1.rs).
//
// Kotlin-idiomatic design: operations are immutable data classes; failures
// are a sealed hierarchy carrying the language-neutral name and the frozen
// core.edit.* registered code (RFC 0004 §17, https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-structural-edit-v1.md);
// commit is atomic — on failure the base document remains unchanged.

package consema.xml

import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanException
import consema.document.EditPlanSourceId
import consema.document.FormatOperationId
import consema.document.FormationStatus
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SnapshotIdentity
import consema.document.SourceEncoding
import consema.document.SourcePatch
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.Span
import consema.document.UntouchedByteProof
import consema.protocol.Diagnostic
import java.nio.charset.StandardCharsets

/**
 * A validated element or attribute name for structural operations
 * (edit.rs). The prefix must already be bound to `namespace` in the
 * target's in-scope scope; the edit never guesses or fabricates namespace
 * declarations.
 */
data class NameFacts(
    /** Prefix spelling; null is an unprefixed name. */
    val prefix: String?,
    /** Local name. */
    val local: String,
    /** Namespace URI the prefix must resolve to; null forbids a prefix. */
    val namespace: String?,
) {
    /** The lexical spelling `prefix:local` or `local` (edit.rs). */
    fun spelling(): String =
        if (prefix == null) local else "$prefix:$local"
}

/** Attribute insertion placement inside one start tag (edit.rs). */
sealed class AttributePlacement {
    /** Insert immediately before one anchor attribute. */
    data class Before(val anchor: NodeRef) : AttributePlacement()

    /** Insert immediately after one anchor attribute. */
    data class After(val anchor: NodeRef) : AttributePlacement()

    /** Append before the closing `>` or `/>`. */
    data object End : AttributePlacement()
}

/** Content insertion placement inside one element (edit.rs). */
sealed class ContentPlacement {
    /** Insert immediately before one anchor content item. */
    data class Before(val anchor: NodeRef) : ContentPlacement()

    /** Insert immediately after one anchor content item. */
    data class After(val anchor: NodeRef) : ContentPlacement()

    /** Append before the end tag (or after the empty-element tag). */
    data object End : ContentPlacement()
}

/** One snapshot-bound XML structural operation (edit.rs). */
sealed class EditOperation {
    /** Replaces one text occurrence with new escaped literal content
     * (RoleXmlText only; RFC 0012 §11 excludes CDATA). */
    data class ReplaceText(
        /** Text occurrence. */
        val target: NodeRef,
        /** New literal character data. */
        val text: String,
    ) : EditOperation()

    /** Inserts one attribute association into an element start tag. */
    data class InsertAttribute(
        /** Owning element. */
        val target: NodeRef,
        /** Validated name facts. */
        val name: NameFacts,
        /** Semantic attribute value. */
        val value: String,
        /** Explicit placement. */
        val placement: AttributePlacement,
    ) : EditOperation()

    /** Removes one attribute association including its leading whitespace. */
    data class RemoveAttribute(
        /** Attribute association. */
        val target: NodeRef,
    ) : EditOperation()

    /** Renames one attribute name, preserving its value. */
    data class RenameAttribute(
        /** Attribute association. */
        val target: NodeRef,
        /** New validated name facts. */
        val name: NameFacts,
    ) : EditOperation()

    /** Replaces one attribute value with new escaped content. */
    data class SetAttributeValue(
        /** Attribute association. */
        val target: NodeRef,
        /** New semantic value. */
        val value: String,
    ) : EditOperation()

    /** Inserts one element into a parent's mixed content. */
    data class InsertElement(
        /** Owning element. */
        val target: NodeRef,
        /** Validated element name facts. */
        val name: NameFacts,
        /** Optional literal text content; null writes an empty element. */
        val content: String?,
        /** Explicit placement. */
        val placement: ContentPlacement,
    ) : EditOperation()

    /** Removes one element subtree including its leading whitespace. */
    data class RemoveElement(
        /** Element occurrence. */
        val target: NodeRef,
    ) : EditOperation()

    /** Renames one element in both its start and end tags. */
    data class RenameElement(
        /** Element occurrence. */
        val target: NodeRef,
        /** New validated name facts. */
        val name: NameFacts,
    ) : EditOperation()

    /** The frozen operation ID including the version suffix (edit.rs). */
    internal fun operationId(): String =
        when (this) {
            is ReplaceText -> "xml.edit.replace-text@1"
            is InsertAttribute -> "xml.edit.insert-attribute@1"
            is RemoveAttribute -> "xml.edit.remove-attribute@1"
            is RenameAttribute -> "xml.edit.rename-attribute@1"
            is SetAttributeValue -> "xml.edit.set-attribute-value@1"
            is InsertElement -> "xml.edit.insert-element@1"
            is RemoveElement -> "xml.edit.remove-element@1"
            is RenameElement -> "xml.edit.rename-element@1"
        }

    /** The unversioned operation ID (edit.rs). */
    internal fun operationIdUnversioned(): String =
        when (this) {
            is ReplaceText -> "xml.edit.replace-text"
            is InsertAttribute -> "xml.edit.insert-attribute"
            is RemoveAttribute -> "xml.edit.remove-attribute"
            is RenameAttribute -> "xml.edit.rename-attribute"
            is SetAttributeValue -> "xml.edit.set-attribute-value"
            is InsertElement -> "xml.edit.insert-element"
            is RemoveElement -> "xml.edit.remove-element"
            is RenameElement -> "xml.edit.rename-element"
        }
}

/** Immutable snapshot-bound transaction (edit.rs). */
class EditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: SnapshotIdentity,
    /** Ordered declared operations. */
    val operations: List<EditOperation>,
)

/** Builder that is not a committed edit (edit.rs). */
class EditTransactionBuilder internal constructor(private val base: SnapshotIdentity) {
    private val operations = ArrayList<EditOperation>()

    companion object {
        /** Binds a new transaction to one immutable base document
         * (edit.rs). */
        fun new(document: Document): EditTransactionBuilder =
            EditTransactionBuilder(document.snapshotIdentity)
    }

    /** Replaces one text occurrence with new literal content
     * (edit.rs). */
    fun replaceText(target: NodeRef, text: String): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceText(target, text))
        return this
    }

    /** Inserts one attribute with explicit placement (edit.rs). */
    fun insertAttribute(
        target: NodeRef,
        name: NameFacts,
        value: String,
        placement: AttributePlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertAttribute(target, name, value, placement))
        return this
    }

    /** Removes one attribute association (edit.rs). */
    fun removeAttribute(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveAttribute(target))
        return this
    }

    /** Renames one attribute (edit.rs). */
    fun renameAttribute(target: NodeRef, name: NameFacts): EditTransactionBuilder {
        operations.add(EditOperation.RenameAttribute(target, name))
        return this
    }

    /** Replaces one attribute value (edit.rs). */
    fun setAttributeValue(target: NodeRef, value: String): EditTransactionBuilder {
        operations.add(EditOperation.SetAttributeValue(target, value))
        return this
    }

    /** Inserts one element into a parent's mixed content (edit.rs). */
    fun insertElement(
        target: NodeRef,
        name: NameFacts,
        content: String?,
        placement: ContentPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertElement(target, name, content, placement))
        return this
    }

    /** Removes one element subtree (edit.rs). */
    fun removeElement(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveElement(target))
        return this
    }

    /** Renames one element (edit.rs). */
    fun renameElement(target: NodeRef, name: NameFacts): EditTransactionBuilder {
        operations.add(EditOperation.RenameElement(target, name))
        return this
    }

    /** Completes the immutable request; target validation happens atomically
     * at commit (edit.rs). */
    fun build(): EditTransaction = EditTransaction(base, operations.toList())
}

/** Atomic edit success (edit.rs). ChangeSet is not shipped in the
 * Kotlin XML family (recorded gap, six-repo audit G090); the commit
 * carries the ordered edit diagnostics instead (the json family precedent,
 * kotlin/src/main/kotlin/consema/json/Edit.kt). */
class EditCommit(
    /** New immutable document. */
    val document: Document,
    /** Portable exact raw-byte application fact. */
    val sourcePatch: SourcePatch,
    /** Verifiable evidence for every byte outside the replacement set. */
    val untouchedProof: UntouchedByteProof,
    /** Ordered edit diagnostics (fallback events). */
    val diagnostics: List<Diagnostic>,
)

/** Stable edit validation or commit failure (edit.rs). The [name]
 * is the exact vector spelling; [code] is the frozen registered code
 * (edit.rs; RFC 0004 §17). */
sealed class EditFailure(val name: String) {
    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : EditFailure("WrongSnapshot")

    /** Target role is not the operation's expected XML role. */
    data object WrongRole : EditFailure("WrongRole")

    /** The target or anchor NodeRef does not exist in this snapshot. */
    data object TargetNotFound : EditFailure("TargetNotFound")

    /** The base document is not `Complete`, so no target can be edited. */
    data object IncompleteTarget : EditFailure("IncompleteTarget")

    /** Name facts violate XML QName grammar. */
    data object InvalidQName : EditFailure("InvalidQName")

    /** A prefixed name has no in-scope binding to its promised namespace. */
    data class UnboundPrefix(val prefix: String) : EditFailure("UnboundPrefix")

    /** A reserved prefix or namespace was used as an ordinary name. */
    data class ReservedPrefix(val prefix: String) : EditFailure("ReservedPrefix")

    /** The renamed or inserted attribute duplicates an expanded name. */
    data object DuplicateExpandedAttribute : EditFailure("DuplicateExpandedAttribute")

    /** The document element cannot be removed. */
    data object CannotRemoveRoot : EditFailure("CannotRemoveRoot")

    /** An insertion targets the element itself or one of its descendants. */
    data object AncestorPlacement : EditFailure("AncestorPlacement")

    /** Two operations target the same exact occurrence. */
    data object ConflictingEdits : EditFailure("ConflictingEdits")

    /** Two operations own the same exact source interval. */
    data object OverlappingOwnership : EditFailure("OverlappingOwnership")

    /** One operation edits an element and another edits an owned
     * descendant. */
    data object AncestorDescendantConflict : EditFailure("AncestorDescendantConflict")

    /** An insertion anchor is modified by another operation in the
     * transaction. */
    data object PlacementAnchorModified : EditFailure("PlacementAnchorModified")

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(val limitName: String) : EditFailure("ResourceLimit")

    /** Replacement document could not be formed under the original limits. */
    data object NewDocumentFormationFailed : EditFailure("NewDocumentFormationFailed")

    /** The frozen registered code (edit.rs). */
    fun code(): String =
        when (this) {
            WrongSnapshot -> "core.edit.wrong-snapshot@1"
            WrongRole -> "core.edit.wrong-role@1"
            TargetNotFound -> "core.edit.target-not-found@1"
            IncompleteTarget -> "core.edit.incomplete-target@1"
            InvalidQName -> "core.edit.invalid-qname@1"
            is UnboundPrefix -> "core.edit.unbound-prefix@1"
            is ReservedPrefix -> "core.edit.reserved-prefix@1"
            DuplicateExpandedAttribute -> "core.edit.duplicate-expanded-attribute@1"
            CannotRemoveRoot -> "core.edit.cannot-remove-root@1"
            AncestorPlacement -> "core.edit.ancestor-placement@1"
            ConflictingEdits,
            OverlappingOwnership,
            AncestorDescendantConflict,
            PlacementAnchorModified,
            -> "core.edit.conflicting-edits@1"

            is ResourceLimit -> "core.edit.resource-limit@1"
            NewDocumentFormationFailed -> "core.edit.formation-failed@1"
        }
}

/** The typed edit failure thrown by [Document.commit] and
 * [Document.dryRun]. */
class EditFailureException(val failure: EditFailure) :
    Exception("edit: ${failure.name}")

/** One prepared raw-byte edit owned by the transaction (edit.rs). */
private data class PreparedEdit(
    val oldSpan: Span,
    val replacement: ByteArray,
    val mapping: Pair<NodeRef, MappingPlan>?,
)

/** One node-mapping plan (edit.rs). */
private enum class MappingPlan {
    Replaced,
    Deleted,
}

/** One source ownership interval helper (the json family precedent,
 * kotlin/src/main/kotlin/consema/json/Edit.kt). */
private data class SourceEdit(
    val oldSpan: Span,
    val newSpan: Span,
    val replacement: ByteArray,
)

/**
 * Atomically commits structural operations. On failure the document remains
 * unchanged (edit.rs).
 */
fun Document.commit(transaction: EditTransaction): EditCommit {
    if (transaction.baseSnapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (formationStatus != FormationStatus.Complete) {
        throw EditFailureException(EditFailure.IncompleteTarget)
    }
    validateDependencies(transaction)
    val prepared = ArrayList<PreparedEdit>()
    for (operation in transaction.operations) {
        prepared.addAll(prepareOperation(operation))
    }
    prepared.sortWith(compareBy<PreparedEdit> { it.oldSpan.startByte }.thenBy { it.oldSpan.endByte })
    for (index in 1 until prepared.size) {
        val left = prepared[index - 1]
        val right = prepared[index]
        if (left.oldSpan == right.oldSpan ||
            (left.oldSpan.isEmpty && right.oldSpan.isEmpty &&
                left.oldSpan.startByte == right.oldSpan.startByte)
        ) {
            throw EditFailureException(EditFailure.OverlappingOwnership)
        }
        if (!left.oldSpan.isEmpty && !right.oldSpan.isEmpty &&
            left.oldSpan.endByte > right.oldSpan.startByte
        ) {
            throw EditFailureException(EditFailure.OverlappingOwnership)
        }
    }
    var targetLen = source.len
    for (edit in prepared) {
        targetLen = targetLen - edit.oldSpan.len + edit.replacement.size
        if (targetLen < 0) {
            throw EditFailureException(EditFailure.ResourceLimit("target-bytes"))
        }
        if (targetLen > parseLimits.common.maxSourceBytes) {
            throw EditFailureException(EditFailure.ResourceLimit("target-bytes"))
        }
    }
    val rendered = ByteArray(targetLen)
    var cursor = 0
    var out = 0
    for (edit in prepared) {
        val keep = edit.oldSpan.startByte - cursor
        System.arraycopy(source.rawBytes(), cursor, rendered, out, keep)
        out += keep
        System.arraycopy(edit.replacement, 0, rendered, out, edit.replacement.size)
        out += edit.replacement.size
        cursor = edit.oldSpan.endByte
    }
    System.arraycopy(source.rawBytes(), cursor, rendered, out, source.len - cursor)
    val newDocument = try {
        parse(rendered, profile, XmlEncodingSelection.ProfileDefault, parseLimits)
    } catch (e: XmlFormationException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    if (newDocument.formationStatus != FormationStatus.Complete) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    var delta = 0
    val sourceEdits = ArrayList<SourceEdit>()
    for (edit in prepared) {
        val newStart = edit.oldSpan.startByte + delta
        val newEnd = newStart + edit.replacement.size
        val newSpan = newDocument.authority.span(newStart, newEnd)
        sourceEdits.add(SourceEdit(edit.oldSpan, newSpan, edit.replacement))
        delta = delta + (edit.replacement.size - edit.oldSpan.len)
    }
    val replacements = sourceEdits.map { edit ->
        SourceReplacement.new(
            edit.oldSpan.startByte,
            edit.oldSpan.endByte,
            source.rawBytes().copyOfRange(edit.oldSpan.startByte, edit.oldSpan.endByte),
            edit.replacement,
        )
    }
    val sourcePatch = try {
        SourcePatch.create(
            source,
            replacements,
            operationMetadata(transaction),
            sourcePatchLimits(parseLimits, replacements.size),
        )
    } catch (e: consema.document.SourcePatchException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val untouchedProof = try {
        UntouchedByteProof.create(source, newDocument.source(), replacements)
    } catch (e: consema.document.UntouchedByteProofException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    return EditCommit(newDocument, sourcePatch, untouchedProof, emptyList())
}

/**
 * Fully validates and plans an edit without returning a new Document
 * (edit.rs; RFC 0004 §14). Dry-run and commit produce the same
 * replacement set and target digest (RFC 0004 §20).
 */
fun Document.dryRun(
    transaction: EditTransaction,
    sourceId: EditPlanSourceId,
): EditPlan {
    val commit = commit(transaction)
    return try {
        EditPlan.new(
            sourceId,
            profileId(),
            operationSummaries(transaction),
            commit.sourcePatch,
            commit.diagnostics,
        )
    } catch (e: EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
}

/** Cross-operation dependency checks before any span is computed
 * (edit.rs). */
private fun validateDependencies(transaction: EditTransaction) {
    val targets = HashSet<NodeRef>()
    for (operation in transaction.operations) {
        val (target, anchor) = when (operation) {
            is EditOperation.ReplaceText -> operation.target to null
            is EditOperation.RemoveAttribute -> operation.target to null
            is EditOperation.SetAttributeValue -> operation.target to null
            is EditOperation.RemoveElement -> operation.target to null
            is EditOperation.RenameAttribute -> operation.target to null
            is EditOperation.RenameElement -> operation.target to null
            is EditOperation.InsertAttribute ->
                operation.target to when (val placement = operation.placement) {
                    is AttributePlacement.Before -> placement.anchor
                    is AttributePlacement.After -> placement.anchor
                    AttributePlacement.End -> null
                }

            is EditOperation.InsertElement ->
                operation.target to when (val placement = operation.placement) {
                    is ContentPlacement.Before -> placement.anchor
                    is ContentPlacement.After -> placement.anchor
                    ContentPlacement.End -> null
                }
        }
        if (!targets.add(target)) {
            throw EditFailureException(EditFailure.ConflictingEdits)
        }
        if (anchor != null && targets.contains(anchor)) {
            throw EditFailureException(EditFailure.PlacementAnchorModified)
        }
    }
}

/** Raw bytes per decoded character under the source encoding (edit.rs). */
private fun charWidth(encoding: SourceEncoding): Int =
    when (encoding) {
        SourceEncoding.Utf16Le, SourceEncoding.Utf16Be -> 2
        else -> 1
    }

/** Whether the element tag ending at `spanEnd` is written with a `/>`
 * close, probed in raw bytes (edit.rs). */
private fun emptyElementTagClose(source: ByteArray, spanEnd: Int, encoding: SourceEncoding): Boolean {
    val offset = spanEnd - 2 * charWidth(encoding)
    if (offset < 0) {
        return false
    }
    val slash = if (encoding === SourceEncoding.Utf16Be) offset + 1 else offset
    return source.getOrNull(slash) == '/'.code.toByte()
}

/** Appends literal text to a replacement buffer under the source encoding
 * (edit.rs). Every replacement byte is written in the encoding the
 * source stream uses, so spliced edits never misalign a UTF-16 stream. */
private fun pushEncodedText(out: java.io.ByteArrayOutputStream, text: String, encoding: SourceEncoding) {
    when (encoding) {
        SourceEncoding.Utf16Le, SourceEncoding.Utf16Be -> {
            val littleEndian = encoding === SourceEncoding.Utf16Le
            for (unit in text) {
                val high = (unit.code ushr 8) and 0xff
                val low = unit.code and 0xff
                out.write(if (littleEndian) byteArrayOf(low.toByte(), high.toByte()) else byteArrayOf(high.toByte(), low.toByte()))
            }
        }
        else -> out.write(text.toByteArray(StandardCharsets.UTF_8))
    }
}

/** Encodes one name spelling under the source encoding (edit.rs). */
private fun spellingBytes(name: NameFacts, encoding: SourceEncoding): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    name.prefix?.let { pushEncodedText(out, it, encoding); pushEncodedText(out, ":", encoding) }
    pushEncodedText(out, name.local, encoding)
    return out.toByteArray()
}

/** Encodes one source QName spelling under the source encoding
 * (edit.rs). */
private fun qnameSpellingBytes(qname: QNameFacts, encoding: SourceEncoding): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    qname.prefix?.let { pushEncodedText(out, it, encoding); pushEncodedText(out, ":", encoding) }
    pushEncodedText(out, qname.local, encoding)
    return out.toByteArray()
}

/** Escapes literal character data for text content under the source
 * encoding (edit.rs). */
private fun escapeText(text: String, encoding: SourceEncoding): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (c in text) {
        when (c) {
            '&' -> pushEncodedText(out, "&amp;", encoding)
            '<' -> pushEncodedText(out, "&lt;", encoding)
            else -> pushEncodedText(out, c.toString(), encoding)
        }
    }
    return out.toByteArray()
}

/** One inserted attribute replacement: `name="value"` with the requested
 * surrounding space (the Before/After/End spellings of edit.rs). */
private fun replacementFor(
    name: NameFacts,
    value: String,
    encoding: SourceEncoding,
    prefixSpace: Boolean,
    suffixSpace: Boolean,
): ByteArray {
    val bytes = java.io.ByteArrayOutputStream()
    if (prefixSpace) {
        pushEncodedText(bytes, " ", encoding)
    }
    bytes.write(spellingBytes(name, encoding))
    pushEncodedText(bytes, "=\"", encoding)
    bytes.write(escapeAttribute(value, encoding))
    pushEncodedText(bytes, "\"", encoding)
    if (suffixSpace) {
        pushEncodedText(bytes, " ", encoding)
    }
    return bytes.toByteArray()
}

/** Escapes literal text for double-quoted attribute values under the source
 * encoding (edit.rs). */
private fun escapeAttribute(text: String, encoding: SourceEncoding): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (c in text) {
        when (c) {
            '&' -> pushEncodedText(out, "&amp;", encoding)
            '<' -> pushEncodedText(out, "&lt;", encoding)
            '"' -> pushEncodedText(out, "&quot;", encoding)
            else -> pushEncodedText(out, c.toString(), encoding)
        }
    }
    return out.toByteArray()
}

// ---------------------------------------------------------------------------
// Operation preparation
// ---------------------------------------------------------------------------

private fun Document.prepareOperation(operation: EditOperation): List<PreparedEdit> =
    when (operation) {
        is EditOperation.ReplaceText -> prepareReplaceText(operation.target, operation.text)
        is EditOperation.InsertAttribute ->
            prepareInsertAttribute(operation.target, operation.name, operation.value, operation.placement)

        is EditOperation.RemoveAttribute -> prepareRemoveAttribute(operation.target)
        is EditOperation.RenameAttribute ->
            prepareRenameAttribute(operation.target, operation.name)

        is EditOperation.SetAttributeValue ->
            prepareSetAttributeValue(operation.target, operation.value)

        is EditOperation.InsertElement ->
            prepareInsertElement(operation.target, operation.name, operation.content, operation.placement)

        is EditOperation.RemoveElement -> prepareRemoveElement(operation.target)
        is EditOperation.RenameElement ->
            prepareRenameElement(operation.target, operation.name)
    }

private fun Document.prepareReplaceText(target: NodeRef, text: String): List<PreparedEdit> {
    val textData = textFor(target)
    val encoding = source.encodingFacts.selected
    return listOf(
        PreparedEdit(
            oldSpan = textData.span,
            replacement = escapeText(text, encoding),
            mapping = target to MappingPlan.Replaced,
        ),
    )
}

private fun Document.prepareInsertAttribute(
    target: NodeRef,
    name: NameFacts,
    value: String,
    placement: AttributePlacement,
): List<PreparedEdit> {
    val element = elementFor(target)
    validateNameFacts(name, element, attribute = true)
    rejectDuplicateAttribute(element, name)
    val encoding = source.encodingFacts.selected
    val (insertAt, replacement) = when (placement) {
        is AttributePlacement.Before -> {
            val anchorData = attributeFor(placement.anchor)
            anchorData.span.startByte to replacementFor(name, value, encoding, prefixSpace = false, suffixSpace = true)
        }
        is AttributePlacement.After -> {
            val anchorData = attributeFor(placement.anchor)
            anchorData.span.endByte to replacementFor(name, value, encoding, prefixSpace = true, suffixSpace = false)
        }
        AttributePlacement.End -> {
            val emptyElement = emptyElementTagClose(source.rawBytes(), element.span.endByte, encoding)
            val width = charWidth(encoding)
            val insertAt = element.span.endByte - (if (emptyElement) 2 * width else width)
            insertAt to replacementFor(name, value, encoding, prefixSpace = true, suffixSpace = false)
        }
    }
    val span = authority.span(insertAt, insertAt)
    return listOf(PreparedEdit(span, replacement, null))
}

private fun Document.prepareRemoveAttribute(target: NodeRef): List<PreparedEdit> {
    val attribute = attributeFor(target)
    val start = leadingWhitespaceStart(source.rawBytes(), attribute.span.startByte)
    val span = authority.span(start, attribute.span.endByte)
    return listOf(PreparedEdit(span, ByteArray(0), target to MappingPlan.Deleted))
}

private fun Document.prepareRenameAttribute(target: NodeRef, name: NameFacts): List<PreparedEdit> {
    val attribute = attributeFor(target)
    val element = elements().firstOrNull { (_, data) ->
        data.attributes.any { it.ordinal == attribute.ordinal }
    }?.second ?: throw EditFailureException(EditFailure.TargetNotFound)
    validateNameFacts(name, element, attribute = true)
    val remaining = element.attributes.filter { it.ordinal != attribute.ordinal }
    val newExpanded = expandedNameForFacts(name, element)
    if (newExpanded != null) {
        if (remaining.any { it.expanded != null && it.expanded == newExpanded }) {
            throw EditFailureException(EditFailure.DuplicateExpandedAttribute)
        }
    }
    val encoding = source.encodingFacts.selected
    return listOf(
        PreparedEdit(
            oldSpan = attribute.qname.span,
            replacement = spellingBytes(name, encoding),
            mapping = target to MappingPlan.Replaced,
        ),
    )
}

private fun Document.prepareSetAttributeValue(target: NodeRef, value: String): List<PreparedEdit> {
    val attribute = attributeFor(target)
    val encoding = source.encodingFacts.selected
    return listOf(
        PreparedEdit(
            oldSpan = attribute.valueSpan,
            replacement = escapeAttribute(value, encoding),
            mapping = target to MappingPlan.Replaced,
        ),
    )
}

private fun Document.prepareInsertElement(
    target: NodeRef,
    name: NameFacts,
    content: String?,
    placement: ContentPlacement,
): List<PreparedEdit> {
    val element = elementFor(target)
    validateNameFacts(name, element, attribute = false)
    val encoding = source.encodingFacts.selected
    val spelling = spellingBytes(name, encoding)
    val markup = java.io.ByteArrayOutputStream()
    pushEncodedText(markup, "<", encoding)
    markup.write(spelling)
    if (content != null) {
        pushEncodedText(markup, ">", encoding)
        markup.write(escapeText(content, encoding))
        pushEncodedText(markup, "</", encoding)
        markup.write(spelling)
        pushEncodedText(markup, ">", encoding)
    } else {
        pushEncodedText(markup, "/>", encoding)
    }
    val markupBytes = markup.toByteArray()
    val (start, end, replacement) = when (placement) {
        is ContentPlacement.Before -> {
            val (role, span) = contentSpanFor(placement.anchor)
            if (!element.children.any { child ->
                    nodes[child].span == span && nodeRoleOf(child) == role
                }
            ) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            Triple(span.startByte, span.startByte, markupBytes)
        }
        is ContentPlacement.After -> {
            val (role, span) = contentSpanFor(placement.anchor)
            if (!element.children.any { child ->
                    nodes[child].span == span && nodeRoleOf(child) == role
                }
            ) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            Triple(span.endByte, span.endByte, markupBytes)
        }
        ContentPlacement.End -> {
            val lastChild = element.children.lastOrNull()
            if (lastChild != null) {
                val at = contentExtentEnd(lastChild)
                Triple(at, at, markupBytes)
            } else {
                val end = element.span.endByte
                if (emptyElementTagClose(source.rawBytes(), end, encoding)) {
                    // `<root/>`: the element's own span ends after the `/>`,
                    // so a zero-width insertion there would create a second
                    // root. Replace the `/>` close with `>` plus the new
                    // element plus a fresh `</parent-name>` close.
                    val wrapped = java.io.ByteArrayOutputStream()
                    pushEncodedText(wrapped, ">", encoding)
                    wrapped.write(markupBytes)
                    pushEncodedText(wrapped, "</", encoding)
                    wrapped.write(qnameSpellingBytes(element.qname, encoding))
                    pushEncodedText(wrapped, ">", encoding)
                    Triple(end - 2 * charWidth(encoding), end, wrapped.toByteArray())
                } else {
                    // `<root></root>`: insert directly before the explicit
                    // end tag.
                    Triple(end, end, markupBytes)
                }
            }
        }
    }
    val span = authority.span(start, end)
    return listOf(PreparedEdit(span, replacement, null))
}

private fun Document.prepareRemoveElement(target: NodeRef): List<PreparedEdit> {
    val element = elementFor(target)
    if (root()?.index == element.index) {
        throw EditFailureException(EditFailure.CannotRemoveRoot)
    }
    val start = leadingWhitespaceStart(source.rawBytes(), element.span.startByte)
    // The element's span covers only its start tag; the removal must
    // consume the whole subtree including the closing `</name>`.
    val end = contentExtentEnd(element.index)
    val span = authority.span(start, end)
    return listOf(PreparedEdit(span, ByteArray(0), target to MappingPlan.Deleted))
}

private fun Document.prepareRenameElement(target: NodeRef, name: NameFacts): List<PreparedEdit> {
    val element = elementFor(target)
    validateNameFacts(name, element, attribute = false)
    val encoding = source.encodingFacts.selected
    val spelling = spellingBytes(name, encoding)
    val edits = ArrayList<PreparedEdit>()
    edits.add(
        PreparedEdit(
            oldSpan = element.qname.span,
            replacement = spelling.copyOf(),
            mapping = target to MappingPlan.Replaced,
        ),
    )
    // The end-tag name span: `</name>` after the last child, or directly
    // after the start tag for an element without content.
    val emptyElement = emptyElementTagClose(source.rawBytes(), element.span.endByte, encoding)
    if (!emptyElement) {
        val lastChildEnd = element.children.lastOrNull()
            ?.let { contentExtentEnd(it) }
            ?: element.span.endByte
        val width = charWidth(encoding)
        val nameStart = lastChildEnd + 2 * width
        val endName = authority.span(nameStart, nameStart + element.qname.span.len)
        edits.add(PreparedEdit(endName, spelling, null))
    }
    return edits
}

/** Resolves one element occurrence by arena index (edit.rs). */
private fun Document.elementFor(target: NodeRef): XmlElementData {
    if (target.snapshot != snapshotIdentity || target.role != NodeRole.XmlElement) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    val index = target.index
    if (index < 0 || index >= nodes.size.toLong()) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }
    val content = nodes[index.toInt()]
    if (content !is XmlContent.Element) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    return content.data
}

/** Resolves one attribute association by ordinal (edit.rs). */
private fun Document.attributeFor(target: NodeRef): XmlAttributeData {
    if (target.snapshot != snapshotIdentity || target.role != NodeRole.XmlAttribute) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    return attributes().firstOrNull { it.ordinal == target.index }
        ?: throw EditFailureException(EditFailure.TargetNotFound)
}

/** Resolves one text occurrence by ordinal (edit.rs). */
private fun Document.textFor(target: NodeRef): XmlTextData {
    if (target.snapshot != snapshotIdentity || target.role != NodeRole.XmlText) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    return texts().firstOrNull { it.ordinal == target.index }
        ?: throw EditFailureException(EditFailure.TargetNotFound)
}

/** The exact end of one content item's full extent: for an element child
 * this is its closing end tag, not its start-tag end (edit.rs). */
private fun Document.contentExtentEnd(index: Int): Int {
    val content = nodes[index]
    if (content !is XmlContent.Element) {
        return content.span.endByte
    }
    val data = content.data
    val encoding = source.encodingFacts.selected
    val width = charWidth(encoding)
    val lastChild = data.children.lastOrNull()
    if (lastChild == null) {
        // The element's own span covers only the start tag. An empty-element
        // tag already ends at `/>`; an explicit `</name>` pair continues
        // past the start tag.
        if (emptyElementTagClose(source.rawBytes(), data.span.endByte, encoding)) {
            return data.span.endByte
        }
        return (data.span.endByte + 2 * width)
            .let { it + data.qname.span.len }
            .let { it + width }
    }
    return (contentExtentEnd(lastChild) + 2 * width)
        .let { it + data.qname.span.len }
        .let { it + width }
}

/** Resolves one content item span by role (edit.rs). */
private fun Document.contentSpanFor(target: NodeRef): Pair<NodeRole, Span> {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    return when (target.role) {
        NodeRole.XmlElement -> {
            val data = elementFor(target)
            NodeRole.XmlElement to data.span
        }
        NodeRole.XmlText -> {
            val data = textFor(target)
            NodeRole.XmlText to data.span
        }
        NodeRole.XmlCdata -> {
            val data = cdatas().firstOrNull { it.ordinal == target.index }
                ?: throw EditFailureException(EditFailure.TargetNotFound)
            NodeRole.XmlCdata to data.span
        }
        NodeRole.XmlComment -> {
            val data = comments().firstOrNull { it.ordinal == target.index }
                ?: throw EditFailureException(EditFailure.TargetNotFound)
            NodeRole.XmlComment to data.span
        }
        NodeRole.XmlProcessingInstruction -> {
            val data = pis().firstOrNull { it.ordinal == target.index }
                ?: throw EditFailureException(EditFailure.TargetNotFound)
            NodeRole.XmlProcessingInstruction to data.span
        }
        else -> throw EditFailureException(EditFailure.WrongRole)
    }
}

/** Validates name facts against one element's in-scope scope
 * (edit.rs). */
private fun validateNameFacts(
    name: NameFacts,
    element: XmlElementData,
    attribute: Boolean,
) {
    if (name.local.isEmpty() ||
        name.local.contains(':') ||
        name.local[0].isDigit() ||
        name.local[0] == '-'
    ) {
        throw EditFailureException(EditFailure.InvalidQName)
    }
    when {
        name.prefix == null && name.namespace != null -> {
            if (attribute) {
                // An unprefixed attribute never carries a namespace.
                throw EditFailureException(EditFailure.UnboundPrefix(""))
            }
            // An unprefixed element name resolves through the default
            // namespace; it must equal the promised URI.
            val default = element.scope.bindings()
                .asReversed()
                .firstOrNull { it.prefix == null }
                ?.uri
            if (default != name.namespace) {
                throw EditFailureException(EditFailure.UnboundPrefix(""))
            }
        }
        name.prefix != null && name.namespace == null ->
            throw EditFailureException(EditFailure.UnboundPrefix(name.prefix))

        name.prefix == null && name.namespace == null -> {}

        name.prefix != null && name.namespace != null -> {
            val prefix = name.prefix!!
            if (prefix == "xmlns") {
                throw EditFailureException(EditFailure.ReservedPrefix(prefix))
            }
            if (prefix == "xml" && name.namespace != XML_NAMESPACE_URI) {
                throw EditFailureException(EditFailure.UnboundPrefix(prefix))
            }
            val bound = element.scope.bindings()
                .asReversed()
                .firstOrNull { it.prefix == prefix }
                ?.uri
            if (bound != name.namespace) {
                throw EditFailureException(EditFailure.UnboundPrefix(prefix))
            }
        }
    }
}

/** The expanded name promised by name facts, when resolvable
 * (edit.rs). */
private fun Document.expandedNameForFacts(
    name: NameFacts,
    element: XmlElementData,
): ExpandedName? {
    val uri = name.namespace ?: return null
    if (name.prefix == "xml") {
        return ExpandedName(XML_NAMESPACE_URI, name.local)
    }
    val bound = element.scope.bindings()
        .asReversed()
        .firstOrNull { it.prefix == name.prefix }
        ?.uri
    if (bound != uri) {
        throw EditFailureException(EditFailure.UnboundPrefix(name.prefix ?: ""))
    }
    return ExpandedName(uri, name.local)
}

/** Rejects an attribute whose expanded name already exists on the element
 * (edit.rs). */
private fun Document.rejectDuplicateAttribute(element: XmlElementData, name: NameFacts) {
    val promised = expandedNameForFacts(name, element) ?: return
    if (element.attributes
            .mapNotNull { it.expanded }
            .any { it == promised }
    ) {
        throw EditFailureException(EditFailure.DuplicateExpandedAttribute)
    }
}

private fun leadingWhitespaceStart(source: ByteArray, start: Int): Int {
    var cursor = start
    while (cursor > 0 &&
        (source[cursor - 1] == ' '.code.toByte() || source[cursor - 1] == '\t'.code.toByte() ||
            source[cursor - 1] == '\r'.code.toByte() || source[cursor - 1] == '\n'.code.toByte())
    ) {
        cursor -= 1
    }
    return cursor
}

/** Iterators over the document's occurrence families (edit.rs). */
private fun Document.attributes(): List<XmlAttributeData> =
    nodes.flatMap { content ->
        if (content is XmlContent.Element) content.data.attributes else emptyList()
    }

private fun Document.texts(): List<XmlTextData> =
    nodes.mapNotNull { content ->
        (content as? XmlContent.Text)?.data
    }

private fun Document.cdatas(): List<XmlCdataData> =
    nodes.mapNotNull { content ->
        (content as? XmlContent.Cdata)?.data
    }

private fun Document.comments(): List<XmlCommentData> =
    nodes.mapNotNull { content ->
        (content as? XmlContent.Comment)?.data
    }

private fun Document.pis(): List<XmlPiData> =
    nodes.mapNotNull { content ->
        (content as? XmlContent.ProcessingInstruction)?.data
    }

private fun Document.elements(): List<Pair<Int, XmlElementData>> =
    nodes.mapIndexedNotNull { index, content ->
        if (content is XmlContent.Element) index to content.data else null
    }

/** Patch limits derived from the parse limits (edit.rs). */
private fun sourcePatchLimits(limits: XmlParseLimits, operationCount: Int): SourcePatchLimits =
    SourcePatchLimits(
        source = consema.document.SourceLimits(
            maxRawBytes = limits.common.maxSourceBytes,
            maxDecodedUtf8Bytes = limits.maxDecodedUtf8Bytes,
            maxDecodedScalars = limits.maxDecodedScalars,
        ),
        maxReplacements = operationCount.coerceAtLeast(1),
        maxPatchBytes = saturatedMultiply(limits.common.maxSourceBytes, 2),
    )

private fun saturatedMultiply(left: Int, right: Int): Int =
    if (left > Int.MAX_VALUE / right) Int.MAX_VALUE else left * right

/** Deterministically ordered audit metadata (edit.rs). */
private fun operationMetadata(transaction: EditTransaction): Map<String, String> =
    transaction.operations.mapIndexed { index, operation ->
        "operation.$index" to operation.operationId()
    }.toMap()

/** Content-free operation summaries (edit.rs). */
private fun operationSummaries(transaction: EditTransaction): List<EditOperationSummary> =
    transaction.operations.map { operation ->
        val (id, arguments) = when (operation) {
            is EditOperation.ReplaceText -> operation.operationIdUnversioned() to
                mapOf("text_bytes" to operation.text.length.toString())

            is EditOperation.InsertAttribute -> operation.operationIdUnversioned() to
                mapOf(
                    "name_bytes" to operation.name.spelling().length.toString(),
                    "value_bytes" to operation.value.length.toString(),
                )

            is EditOperation.RemoveAttribute ->
                operation.operationIdUnversioned() to emptyMap()

            is EditOperation.RenameAttribute -> operation.operationIdUnversioned() to
                mapOf("name_bytes" to operation.name.spelling().length.toString())

            is EditOperation.SetAttributeValue -> operation.operationIdUnversioned() to
                mapOf("value_bytes" to operation.value.length.toString())

            is EditOperation.InsertElement -> operation.operationIdUnversioned() to
                mapOf(
                    "name_bytes" to operation.name.spelling().length.toString(),
                    "content_bytes" to (operation.content ?: "").length.toString(),
                )

            is EditOperation.RemoveElement ->
                operation.operationIdUnversioned() to emptyMap()

            is EditOperation.RenameElement -> operation.operationIdUnversioned() to
                mapOf("name_bytes" to operation.name.spelling().length.toString())
        }
        EditOperationSummary.new(FormatOperationId(id, 1), arguments)
    }
