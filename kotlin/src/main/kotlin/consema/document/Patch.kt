// Verifiable raw-byte patches between immutable source snapshots.
//
// Data authority (language-neutral sources first):
//   - RFC 0003 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md:250-292)
//     freezes the core.source-patch@1 facts: base_digest, target_digest,
//     encoding facts, ordered replacements, metadata; each replacement has
//     old_start/old_end/original/replacement/redact flags; old ranges are
//     half-open, ordered, and non-overlapping; zero-width insertions are
//     permitted but two replacements may not target the same insertion point;
//     base digest, encoding facts, every original-byte precondition, and the
//     computed target digest must match; any mismatch fails atomically.
//   - conformance/vectors/source-v1.json cases source.patch.* (lines 120-172)
//     pin the success bytes and the rejection codes.
//   - https://github.com/consema/consema-rs/blob/main/consema-document/src/source_patch.rs:1-566 pins the shapes and
//     the error-code mapping (source_patch.rs:434-458).
//   - consema-go/go/document/source_patch.go is a cross-reference only.
//
// The registered codes (error_registry.rs:381-405; ErrorRegistry.kt:238-242):
//   core.source.patch-base-mismatch@1
//   core.source.patch-original-mismatch@1
//   core.source.patch-target-mismatch@1
//   core.source.encoding-conflict@1      (EncodingMismatch + wrapped conflicts)
//   core.source.resource-limit@1         (patch limits + wrapped source limits)
//   core.source.unsupported-bom@1        (wrapped)
//   core.source.invalid-sequence@1       (wrapped)
//   core.protocol.invalid-value@1        (InvalidReplacement, ReplacementOrder,
//                                        DuplicateInsertion, ChangeSetMismatch;
//                                        error_registry.rs:87)
//
// SourcePatch derivation from document-level change facts (SourcePatch::
// derive, source_patch.rs:143-205) consumes ChangeSet, which is not shipped
// in Kotlin (recorded gap, six-repo audit G090; the consema.properties
// package carries the ChangeSet-shaped facts instead).

package consema.document

import java.util.Collections

/**
 * Resource bounds for constructing or applying one source patch
 * (source_patch.rs:8-27).
 */
data class SourcePatchLimits(
    /** Limits for the resulting source snapshot. */
    val source: SourceLimits,
    /** Maximum number of ordered replacements. */
    val maxReplacements: Int,
    /** Maximum sum of original and replacement payload bytes. */
    val maxPatchBytes: Int,
) {
    companion object {
        /** The frozen defaults (source_patch.rs:19-27): default source
         * limits, 100,000 replacements, 128 MiB patch bytes. */
        val default = SourcePatchLimits(
            source = SourceLimits.default,
            maxReplacements = 100_000,
            maxPatchBytes = 128 shl 20,
        )
    }
}

/**
 * One raw-byte precondition and replacement in a source patch (RFC 0003
 * §10; source_patch.rs:29-131). [original] must appear byte-for-byte at the
 * old range of the base source; after application the target bytes are
 * exactly [replacement]. Redaction flags control review/log presentation,
 * not the bytes required for application (RFC 0003 §10).
 */
class SourceReplacement private constructor(
    /** Inclusive start raw byte. */
    val oldStart: Int,
    /** Exclusive end raw byte. */
    val oldEnd: Int,
    private val original: ByteArray,
    private val replacement: ByteArray,
    /** Whether review/debug presentation hides the original bytes. */
    val redactOriginal: Boolean,
    /** Whether review/debug presentation hides the replacement bytes. */
    val redactReplacement: Boolean,
) {
    companion object {
        /** Creates one half-open raw-byte replacement (source_patch.rs:42-57). */
        fun new(
            oldStart: Int,
            oldEnd: Int,
            original: ByteArray,
            replacement: ByteArray,
        ): SourceReplacement = SourceReplacement(oldStart, oldEnd, original, replacement, false, false)
    }

    /** Controls whether the original bytes are hidden in review/debug
     * presentation (source_patch.rs:60-64). */
    fun withOriginalRedacted(redacted: Boolean): SourceReplacement =
        SourceReplacement(oldStart, oldEnd, original, replacement, redacted, redactReplacement)

    /** Controls whether replacement bytes are hidden in review/debug
     * presentation (source_patch.rs:66-71). */
    fun withReplacementRedacted(redacted: Boolean): SourceReplacement =
        SourceReplacement(oldStart, oldEnd, original, replacement, redactOriginal, redacted)

    /** Exact bytes required at the old range; returns a defensive copy. */
    fun original(): ByteArray = original.copyOf()

    /** Exact bytes written in place of the old range; returns a defensive
     * copy. */
    fun replacement(): ByteArray = replacement.copyOf()

    internal fun originalBytes(): ByteArray = original

    internal fun replacementBytes(): ByteArray = replacement

    override fun equals(other: Any?): Boolean =
        other is SourceReplacement &&
            oldStart == other.oldStart &&
            oldEnd == other.oldEnd &&
            original.contentEquals(other.original) &&
            replacement.contentEquals(other.replacement) &&
            redactOriginal == other.redactOriginal &&
            redactReplacement == other.redactReplacement

    override fun hashCode(): Int {
        var result = oldStart
        result = 31 * result + oldEnd
        result = 31 * result + original.contentHashCode()
        result = 31 * result + replacement.contentHashCode()
        result = 31 * result + redactOriginal.hashCode()
        result = 31 * result + redactReplacement.hashCode()
        return result
    }

    override fun toString(): String {
        val renderedOriginal = if (redactOriginal) "<redacted>" else hex(original)
        val renderedReplacement = if (redactReplacement) "<redacted>" else hex(replacement)
        return "SourceReplacement(oldStart=$oldStart, oldEnd=$oldEnd, " +
            "original=$renderedOriginal, replacement=$renderedReplacement, " +
            "redactOriginal=$redactOriginal, redactReplacement=$redactReplacement)"
    }
}

/**
 * Immutable, transferable facts needed to verify one raw source transition
 * (RFC 0003 §10; source_patch.rs:133-365).
 *
 * SourcePatch is not ChangeSet, semantic diff, merge, fuzzy patch,
 * file-system write, or permission to alter a stale snapshot (RFC 0003 §10).
 */
class SourcePatch private constructor(
    /** Required base content identity. */
    val baseDigest: ContentDigest,
    /** Required result content identity. */
    val targetDigest: ContentDigest,
    /** Encoding facts that both base and result must reproduce. */
    val encodingFacts: EncodingFacts,
    private val replacements: List<SourceReplacement>,
    private val metadata: Map<String, String>,
) {
    companion object {
        /**
         * Creates a patch from externally supplied facts after structural
         * and resource validation (source_patch.rs:207-224).
         */
        fun new(
            baseDigest: ContentDigest,
            targetDigest: ContentDigest,
            encoding: EncodingFacts,
            replacements: List<SourceReplacement>,
            metadata: Map<String, String>,
            limits: SourcePatchLimits = SourcePatchLimits.default,
        ): SourcePatch {
            validateReplacements(replacements, limits)
            return SourcePatch(baseDigest, targetDigest, encoding, replacements, sortedMetadata(metadata))
        }

        /**
         * Builds a self-consistent patch against one immutable base snapshot
         * (source_patch.rs:226-251): the target bytes are computed by
         * applying the replacements, re-resolved under the base encoding
         * facts, and the resulting encoding facts must equal the base facts.
         */
        fun create(
            base: SourceSnapshot,
            replacements: List<SourceReplacement>,
            metadata: Map<String, String>,
            limits: SourcePatchLimits = SourcePatchLimits.default,
        ): SourcePatch {
            validateReplacements(replacements, limits)
            val targetBytes = applyReplacements(base.rawBytes(), replacements, limits)
            val target = try {
                SourceSnapshot.fromRaw(targetBytes, base.encodingFacts.resolutionRequest(), limits.source)
            } catch (e: SourceException) {
                throw SourcePatchException.wrapSource(e)
            }
            if (target.encodingFacts != base.encodingFacts) {
                throw SourcePatchException(SourcePatchErrorKind.ENCODING_MISMATCH)
            }
            return SourcePatch(
                base.digest,
                target.digest,
                base.encodingFacts,
                replacements,
                sortedMetadata(metadata),
            )
        }
    }

    /** Ordered non-overlapping replacements. */
    fun replacements(): List<SourceReplacement> = replacements

    /** Deterministically ordered audit metadata, which never affects
     * application (RFC 0003 §10). */
    fun metadata(): Map<String, String> = metadata

    /**
     * Applies all facts atomically and returns a new immutable snapshot only
     * on complete success (source_patch.rs:253-280; RFC 0003 §10): base
     * digest, encoding facts, every original-byte precondition, and the
     * computed target digest must match; any mismatch fails atomically and
     * returns no new SourceSnapshot.
     */
    fun apply(
        base: SourceSnapshot,
        limits: SourcePatchLimits = SourcePatchLimits.default,
    ): SourceSnapshot {
        validateReplacements(replacements, limits)
        if (base.digest != baseDigest) {
            throw SourcePatchException(SourcePatchErrorKind.BASE_MISMATCH)
        }
        if (base.encodingFacts != encodingFacts) {
            throw SourcePatchException(SourcePatchErrorKind.ENCODING_MISMATCH)
        }
        val targetBytes = applyReplacements(base.rawBytes(), replacements, limits)
        val target = try {
            SourceSnapshot.fromRaw(targetBytes, encodingFacts.resolutionRequest(), limits.source)
        } catch (e: SourceException) {
            throw SourcePatchException.wrapSource(e)
        }
        if (target.encodingFacts != encodingFacts) {
            throw SourcePatchException(SourcePatchErrorKind.ENCODING_MISMATCH)
        }
        if (target.digest != targetDigest) {
            throw SourcePatchException(SourcePatchErrorKind.TARGET_MISMATCH)
        }
        return target
    }

    override fun equals(other: Any?): Boolean =
        other is SourcePatch &&
            baseDigest == other.baseDigest &&
            targetDigest == other.targetDigest &&
            encodingFacts == other.encodingFacts &&
            replacements == other.replacements &&
            metadata == other.metadata

    override fun hashCode(): Int {
        var result = baseDigest.hashCode()
        result = 31 * result + targetDigest.hashCode()
        result = 31 * result + encodingFacts.hashCode()
        result = 31 * result + replacements.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    /**
     * Marks every replacement payload for redacted review/debug
     * presentation (source_patch.rs:312-336). Exact bytes remain present
     * for digest and original-byte precondition checks.
     */
    fun withAllReplacementsRedacted(
        redactOriginal: Boolean,
        redactReplacement: Boolean,
    ): SourcePatch {
        val redacted = replacements.map {
            it.withOriginalRedacted(redactOriginal).withReplacementRedacted(redactReplacement)
        }
        return SourcePatch(baseDigest, targetDigest, encodingFacts, redacted, metadata)
    }

    /**
     * Marks one exact replacement payload for redacted review/debug
     * presentation (source_patch.rs:338-364).
     */
    fun withReplacementRedacted(
        index: Int,
        redactOriginal: Boolean,
        redactReplacement: Boolean,
    ): SourcePatch {
        if (index < 0 || index >= replacements.size) {
            throw SourcePatchRedactionException(index)
        }
        val redacted = replacements.toMutableList()
        redacted[index] = replacements[index]
            .withOriginalRedacted(redactOriginal)
            .withReplacementRedacted(redactReplacement)
        return SourcePatch(baseDigest, targetDigest, encodingFacts, redacted, metadata)
    }
}

/** Review-redaction selection failure; patch bytes and application facts are
 * unchanged (source_patch.rs:367-377). */
class SourcePatchRedactionException(val index: Int) :
    Exception("patch: unknown replacement index $index for redaction")

/** Stable source patch construction or application failure kinds with their
 * frozen registered codes (source_patch.rs:387-459). */
enum class SourcePatchErrorKind(val code: String) {
    /** Replacement start followed its end or its original byte count
     * disagreed with its range. */
    INVALID_REPLACEMENT("core.protocol.invalid-value@1"),

    /** Replacement order was not canonical or two old ranges overlapped. */
    REPLACEMENT_ORDER("core.protocol.invalid-value@1"),

    /** Two replacements targeted the same zero-width insertion point. */
    DUPLICATE_INSERTION("core.protocol.invalid-value@1"),

    /** A document-level source edit disagrees with its snapshots or
     * replacement bytes (unreachable: ChangeSet is not shipped in Kotlin —
     * recorded gap, six-repo audit G090). */
    CHANGE_SET_MISMATCH("core.protocol.invalid-value@1"),

    /** Base raw bytes do not have the declared digest. */
    BASE_MISMATCH("core.source.patch-base-mismatch@1"),

    /** Base bytes in one range do not equal the declared precondition. */
    ORIGINAL_MISMATCH("core.source.patch-original-mismatch@1"),

    /** Computed result bytes do not have the declared digest. */
    TARGET_MISMATCH("core.source.patch-target-mismatch@1"),

    /** Base or resulting encoding facts disagree with the patch. */
    ENCODING_MISMATCH("core.source.encoding-conflict@1"),

    /** Resulting bytes failed source construction with an encoding conflict
     * (Source(SourceError::EncodingConflict), source_patch.rs:442-444). */
    SOURCE_ENCODING_CONFLICT("core.source.encoding-conflict@1"),

    /** A patch count, byte, output, or allocation bound was exceeded, or the
     * resulting source exceeded a bound
     * (ResourceLimit / Source(ResourceLimit | OffsetOverflow),
     * source_patch.rs:445-448). */
    SOURCE_RESOURCE_LIMIT("core.source.resource-limit@1"),

    /** Resulting bytes begin with an unsupported byte-order mark
     * (Source(SourceError::UnsupportedBom), source_patch.rs:449). */
    SOURCE_UNSUPPORTED_BOM("core.source.unsupported-bom@1"),

    /** Resulting bytes are invalid for the selected encoding
     * (Source(InvalidUtf8 | InvalidSequence), source_patch.rs:450-452). */
    SOURCE_INVALID_SEQUENCE("core.source.invalid-sequence@1"),
    ;
}

/**
 * The typed source patch construction or application failure. The stable
 * [code] is always the registered code (RFC 0016 §6); [index] names the
 * offending zero-based replacement where applicable; [cause] carries the
 * wrapped source failure for the SOURCE_* kinds.
 */
class SourcePatchException(
    val kind: SourcePatchErrorKind,
    val index: Int = -1,
    override val cause: SourceException? = null,
) : Exception(
    "patch: ${kind.code}" +
        (if (index >= 0) " at replacement $index" else "") +
        (if (cause != null) ": ${cause.code}" else ""),
    cause,
) {
    /** The frozen registered code of the failure. */
    val code: String
        get() = kind.code

    companion object {
        /** Wraps a source-construction failure with the frozen code mapping
         * of source_patch.rs:442-451. */
        internal fun wrapSource(error: SourceException): SourcePatchException =
            when (error.kind) {
                SourceErrorKind.INVALID_UTF8, SourceErrorKind.INVALID_SEQUENCE ->
                    SourcePatchException(SourcePatchErrorKind.SOURCE_INVALID_SEQUENCE, cause = error)

                SourceErrorKind.ENCODING_CONFLICT ->
                    SourcePatchException(SourcePatchErrorKind.SOURCE_ENCODING_CONFLICT, cause = error)

                SourceErrorKind.UNSUPPORTED_BOM ->
                    SourcePatchException(SourcePatchErrorKind.SOURCE_UNSUPPORTED_BOM, cause = error)

                SourceErrorKind.RESOURCE_LIMIT, SourceErrorKind.OFFSET_OVERFLOW ->
                    SourcePatchException(SourcePatchErrorKind.SOURCE_RESOURCE_LIMIT, cause = error)
            }
    }
}

/**
 * Validates the structural and resource facts of one replacement list
 * (source_patch.rs:469-512): half-open ordered non-overlapping ranges,
 * original byte count equal to the range width, no duplicate zero-width
 * insertion point, and the patch-byte sum within [limits].
 */
private fun validateReplacements(
    replacements: List<SourceReplacement>,
    limits: SourcePatchLimits,
) {
    checkPatchLimit("patch-replacements", replacements.size, limits.maxReplacements)
    var patchBytes = 0
    var previous: SourceReplacement? = null
    for ((index, replacement) in replacements.withIndex()) {
        // Negative offsets are impossible in the Rust usize surface; Kotlin
        // Ints require the explicit guard (source_patch.rs:480-485).
        if (replacement.oldStart < 0 || replacement.oldEnd < 0 ||
            replacement.oldStart > replacement.oldEnd ||
            replacement.originalBytes().size != replacement.oldEnd - replacement.oldStart
        ) {
            throw SourcePatchException(SourcePatchErrorKind.INVALID_REPLACEMENT, index)
        }
        previous?.let {
            if (replacement.oldStart == replacement.oldEnd &&
                it.oldStart == it.oldEnd &&
                replacement.oldStart == it.oldStart
            ) {
                throw SourcePatchException(SourcePatchErrorKind.DUPLICATE_INSERTION, index)
            }
            if (replacement.oldStart < it.oldStart ||
                (replacement.oldStart == it.oldStart && replacement.oldEnd <= it.oldEnd) ||
                replacement.oldStart < it.oldEnd
            ) {
                throw SourcePatchException(SourcePatchErrorKind.REPLACEMENT_ORDER, index)
            }
        }
        patchBytes = checkedAddPatch(patchBytes, replacement.originalBytes().size, limits)
        patchBytes = checkedAddPatch(patchBytes, replacement.replacementBytes().size, limits)
        checkPatchLimit("patch-bytes", patchBytes, limits.maxPatchBytes)
        previous = replacement
    }
}

private fun checkedAddPatch(left: Int, right: Int, limits: SourcePatchLimits): Int {
    if (left > Int.MAX_VALUE - right) {
        throw SourcePatchException(
            SourcePatchErrorKind.SOURCE_RESOURCE_LIMIT,
            cause = SourceException(SourceErrorKind.OFFSET_OVERFLOW, "patch: byte sum overflow"),
        )
    }
    return left + right
}

private fun checkPatchLimit(name: String, observed: Int, limit: Int) {
    if (observed > limit) {
        throw SourcePatchException(
            SourcePatchErrorKind.SOURCE_RESOURCE_LIMIT,
            cause = SourceException(
                SourceErrorKind.RESOURCE_LIMIT,
                "patch: $name limit reached ($observed > $limit)",
                name = name,
                observed = observed,
                limit = limit,
            ),
        )
    }
}

/**
 * Applies replacements to one base byte buffer with every original-byte
 * precondition and the target size limits checked (source_patch.rs:514-554).
 */
private fun applyReplacements(
    base: ByteArray,
    replacements: List<SourceReplacement>,
    limits: SourcePatchLimits,
): ByteArray {
    var targetLen = base.size
    for ((index, replacement) in replacements.withIndex()) {
        if (replacement.oldEnd > base.size ||
            !base.copyOfRange(replacement.oldStart, replacement.oldEnd)
                .contentEquals(replacement.originalBytes())
        ) {
            throw SourcePatchException(SourcePatchErrorKind.ORIGINAL_MISMATCH, index)
        }
        targetLen = targetLen - replacement.originalBytes().size + replacement.replacementBytes().size
        checkSourceLimit("target-raw-bytes", targetLen, limits.source.maxRawBytes)
    }
    val target = ByteArray(targetLen)
    var cursor = 0
    var out = 0
    for (replacement in replacements) {
        val keep = replacement.oldStart - cursor
        System.arraycopy(base, cursor, target, out, keep)
        out += keep
        val replacementBytes = replacement.replacementBytes()
        System.arraycopy(replacementBytes, 0, target, out, replacementBytes.size)
        out += replacementBytes.size
        cursor = replacement.oldEnd
    }
    System.arraycopy(base, cursor, target, out, base.size - cursor)
    return target
}

private fun checkSourceLimit(name: String, observed: Int, limit: Int) {
    if (observed > limit) {
        throw SourcePatchException(
            SourcePatchErrorKind.SOURCE_RESOURCE_LIMIT,
            cause = SourceException(
                SourceErrorKind.RESOURCE_LIMIT,
                "patch: $name limit reached ($observed > $limit)",
                name = name,
                observed = observed,
                limit = limit,
            ),
        )
    }
}

/** Deterministically ordered audit metadata (the Rust BTreeMap semantics,
 * source_patch.rs:140). */
private fun sortedMetadata(metadata: Map<String, String>): Map<String, String> =
    Collections.unmodifiableMap(metadata.toSortedMap())

private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val value = bytes[i].toInt() and 0xff
        hex[i * 2] = digits[value ushr 4]
        hex[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}
