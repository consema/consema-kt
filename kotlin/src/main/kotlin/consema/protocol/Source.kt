// The wire forms of the source records carried by the CLI machine payloads.
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-protocol/src/source.rs (core.source-
// encoding@1 at source.rs; the source-patch@2 encoding facts at
// source.rs; the patch record at source.rs) and
// https://github.com/consema/consema-rs/blob/main/consema-document/src/source.rs (the Windows code-page
// registry). The document milestone (L1) owns the full source model; this
// package validates the record structure and carries the facts so the
// batch-plan record can round-trip them. consema-go/go/protocol/records_source.go is a
// cross-reference.

package consema.protocol

import consema.core.PvBytes
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue

/** The resource bounds of source snapshots (document source.rs). */
data class SourceLimits(
    /** Maximum retained raw bytes. */
    val maxRawBytes: Int,
    /** Maximum decoded UTF-8 bytes. */
    val maxDecodedUtf8Bytes: Int,
    /** Maximum decoded Unicode scalar values. */
    val maxDecodedScalars: Int,
) {
    companion object {
        /** The frozen defaults (64 MiB raw, 128 MiB decoded, 64 MiB
         * scalars; document source.rs). */
        val default = SourceLimits(
            maxRawBytes = 64 shl 20,
            maxDecodedUtf8Bytes = 128 shl 20,
            maxDecodedScalars = 64 shl 20,
        )
    }
}

/** The resource bounds of source patches (document source_patch.rs). */
data class SourcePatchLimits(
    /** The limits for the resulting source snapshot. */
    val source: SourceLimits,
    /** The maximum number of ordered replacements. */
    val maxReplacements: Int,
    /** The maximum total patch bytes. */
    val maxPatchBytes: Int,
) {
    companion object {
        /** The frozen defaults (document source_patch.rs). */
        val default = SourcePatchLimits(
            source = SourceLimits.default,
            maxReplacements = 100_000,
            maxPatchBytes = 128 shl 20,
        )
    }
}

/**
 * The wire form of one core.source-encoding@1 record
 * (protocol/src/source.rs): the kind spelling and the optional
 * Windows code page.
 */
data class SourceEncoding(
    /** The frozen kind spelling: Binary, Utf8, Utf16Le, Utf16Be, Latin1,
     * or WindowsCodePage. */
    val kind: String,
    /** The numeric code page of WindowsCodePage records. */
    val windowsCodePage: Int?,
) {
    /** Strictly decodes one core.source-encoding@1 record
     * (protocol/src/source.rs). */
    companion object {
        fun fromValue(value: PortableValue, path: String): SourceEncoding {
            val fields = schemaFields(
                value,
                "core.source-encoding@1",
                listOf("schema", "kind", "windows_code_page"),
                path,
            )
            val kind = stringOf(fields[1], "$path.kind")
            val codePage = if (fields[2] is PvNull) {
                null
            } else {
                unsigned32(fields[2], "$path.windows_code_page")
            }
            when (kind) {
                "Binary", "Utf8", "Utf16Le", "Utf16Be", "Latin1" -> {
                    if (codePage != null) {
                        throw invalid("$path.windows_code_page", "non-Windows encoding requires null")
                    }
                }
                "WindowsCodePage" -> {
                    if (codePage == null) {
                        throw invalid("$path.windows_code_page", "Windows code page requires a number")
                    }
                    if (windowsCodePageFromNumber(codePage) == null) {
                        throw invalid("$path.windows_code_page", "unsupported Windows code page")
                    }
                }
                else -> throw invalid("$path.kind", "unknown source encoding kind")
            }
            return SourceEncoding(kind, codePage)
        }
    }

    /** Encodes one core.source-encoding@1 record. */
    fun toValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry("schema", PvString("core.source-encoding@1")),
            consema.core.Entry("kind", PvString(kind)),
            consema.core.Entry(
                "windows_code_page",
                if (windowsCodePage == null) PvNull else integerValue(windowsCodePage.toLong().toULong()),
            ),
        ),
    )
}

/**
 * Resolves one numeric code page only when source contract v2 publishes it
 * (document source.rs).
 */
fun windowsCodePageFromNumber(number: Int): SourceEncoding? {
    if (number !in intArrayOf(
            874, 932, 936, 949, 950, 1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258, 65001,
        )
    ) {
        return null
    }
    return SourceEncoding("WindowsCodePage", number)
}

/**
 * The source-patch@2 encoding facts record (protocol/src/source.rs).
 * The semantic consistency checks of the facts (BOM policy, code-page
 * registration, selected-encoding reconciliation) belong to the document
 * milestone; this package validates the record structure and carries the
 * facts.
 */
data class EncodingFacts(
    /** The encoding assumed without declarations. */
    val profileDefault: SourceEncoding?,
    /** "DetectUnicode" or "TreatAsContent". */
    val bomPolicy: String,
    /** The detected byte-order mark: "Utf8", "Utf16Le", or "Utf16Be"; null
     * when none. */
    val bom: String?,
    /** The encoding declared by the source; null when none. */
    val declaration: SourceEncoding?,
    /** The encoding requested by the caller; null when none. */
    val callerOverride: SourceEncoding?,
    /** The encoding actually selected. */
    val selected: SourceEncoding?,
) {
    /** Encodes the source-patch@2 encoding facts record. */
    fun toValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry(
                "profile_default",
                if (profileDefault == null) PvNull else profileDefault.toValue(),
            ),
            consema.core.Entry("bom_policy", PvString(bomPolicy)),
            consema.core.Entry("bom", nullableString(bom)),
            consema.core.Entry(
                "declaration",
                if (declaration == null) PvNull else declaration.toValue(),
            ),
            consema.core.Entry(
                "caller_override",
                if (callerOverride == null) PvNull else callerOverride.toValue(),
            ),
            consema.core.Entry(
                "selected",
                if (selected == null) PvNull else selected.toValue(),
            ),
        ),
    )

    /** Strictly decodes the source-patch@2 encoding facts record. */
    companion object {
        fun fromValue(value: PortableValue, path: String): EncodingFacts {
            val fields = exactFields(
                value,
                listOf("profile_default", "bom_policy", "bom",
                    "declaration", "caller_override", "selected"),
                path,
            )
            val profileDefault = if (fields[0] is PvNull) {
                null
            } else {
                SourceEncoding.fromValue(fields[0], "$path.profile_default")
            }
            val bomPolicy = stringOf(fields[1], "$path.bom_policy")
            val bom = optionalString(fields[2], "$path.bom")
            val declaration = if (fields[3] is PvNull) {
                null
            } else {
                SourceEncoding.fromValue(fields[3], "$path.declaration")
            }
            val callerOverride = if (fields[4] is PvNull) {
                null
            } else {
                SourceEncoding.fromValue(fields[4], "$path.caller_override")
            }
            val selected = if (fields[5] is PvNull) {
                null
            } else {
                SourceEncoding.fromValue(fields[5], "$path.selected")
            }
            return EncodingFacts(profileDefault, bomPolicy, bom, declaration, callerOverride, selected)
        }
    }
}

/**
 * One structural replacement of a wire source patch
 * (consema-document source_patch.rs).
 */
data class SourceReplacement(
    /** The inclusive start offset of the replaced range. */
    val oldStart: ULong,
    /** The exclusive end offset of the replaced range. */
    val oldEnd: ULong,
    /** The exact original bytes of the replaced range. */
    val original: ByteArray,
    /** The exact new bytes. */
    val replacement: ByteArray,
    /** Reports whether the original is a redaction-sensitive value. */
    val redactOriginal: Boolean,
    /** Reports whether the replacement is a redaction-sensitive value. */
    val redactReplacement: Boolean,
)

/**
 * The wire form of a source patch (core.source-patch@2 record;
 * protocol/src/source.rs). The document milestone owns
 * the applied patch type; this package carries the transferable
 * verification facts so the batch-plan record can round-trip them.
 */
data class SourcePatch(
    /** The digest of the exact base bytes the patch applies to. */
    val baseDigest: ContentDigest,
    /** The digest of the exact target bytes after applying. */
    val targetDigest: ContentDigest,
    /** The encoding facts of the patch. */
    val encoding: EncodingFacts,
    /** The ordered structural replacements. */
    val replacements: List<SourceReplacement>,
    /** The deterministic sorted metadata map. */
    val metadata: Map<String, String>,
) {
    /** Encodes the core.source-patch@2 record at the value level with full
     * replacement fidelity. */
    fun toValue(): PortableValue {
        val replacementValues = replacements.map { replacement ->
            PvObject(
                listOf(
                    consema.core.Entry("old_start", integerValue(replacement.oldStart)),
                    consema.core.Entry("old_end", integerValue(replacement.oldEnd)),
                    consema.core.Entry("original", PvBytes.of(replacement.original)),
                    consema.core.Entry("replacement", PvBytes.of(replacement.replacement)),
                    consema.core.Entry("redact_original", consema.core.PvBoolean(replacement.redactOriginal)),
                    consema.core.Entry("redact_replacement", consema.core.PvBoolean(replacement.redactReplacement)),
                ),
            )
        }
        return PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.source-patch@2")),
                consema.core.Entry("base_digest", digestValue(baseDigest)),
                consema.core.Entry("target_digest", digestValue(targetDigest)),
                consema.core.Entry("encoding", encoding.toValue()),
                consema.core.Entry("replacements", consema.core.PvArray(replacementValues)),
                consema.core.Entry("metadata", stringMapObject(metadata)),
            ),
        )
    }

    companion object {
        /** Strictly decodes the core.source-patch@2 record at the value
         * level with full replacement fidelity. */
        fun fromValue(value: PortableValue, path: String, patchLimits: SourcePatchLimits): SourcePatch {
            val fields = schemaFields(
                value,
                "core.source-patch@2",
                listOf("schema", "base_digest", "target_digest", "encoding",
                    "replacements", "metadata"),
                path,
            )
            val baseDigest = parseDigest(fields[1], "$path.base_digest")
            val targetDigest = parseDigest(fields[2], "$path.target_digest")
            val encoding = EncodingFacts.fromValue(fields[3], "$path.encoding")
            val replacementValues = sequenceOf(fields[4], "$path.replacements")
            if (replacementValues.size > patchLimits.maxReplacements) {
                throw resource("$path.source_patch.replacements", "replacement count exceeds configured limit")
            }
            val replacements = replacementValues.mapIndexed { index, replacementValue ->
                val replacementPath = "$path.replacements[$index]"
                val replacementFields = exactFields(
                    replacementValue,
                    listOf("old_start", "old_end", "original", "replacement",
                        "redact_original", "redact_replacement"),
                    replacementPath,
                )
                val oldStart = unsigned64(replacementFields[0], "$replacementPath.old_start")
                val oldEnd = unsigned64(replacementFields[1], "$replacementPath.old_end")
                val original = replacementFields[2] as? PvBytes
                    ?: throw protocolError(
                        ProtocolErrorKind.WRONG_TYPE,
                        "$replacementPath.original",
                        "expected Bytes",
                    )
                val replacement = replacementFields[3] as? PvBytes
                    ?: throw protocolError(
                        ProtocolErrorKind.WRONG_TYPE,
                        "$replacementPath.replacement",
                        "expected Bytes",
                    )
                val redactOriginal = booleanOf(replacementFields[4], "$replacementPath.redact_original")
                val redactReplacement = booleanOf(replacementFields[5], "$replacementPath.redact_replacement")
                if (oldStart > oldEnd || original.content().size.toULong() != oldEnd - oldStart) {
                    throw invalid(replacementPath, "invalid replacement range or original length")
                }
                SourceReplacement(
                    oldStart, oldEnd,
                    original.content(), replacement.content(),
                    redactOriginal, redactReplacement,
                )
            }
            val metadata = stringMapFromObject(fields[5], "$path.metadata")
            return SourcePatch(baseDigest, targetDigest, encoding, replacements, metadata)
        }
    }
}
