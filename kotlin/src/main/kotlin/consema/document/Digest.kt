// Stable content identity of exact raw source bytes.
//
// Data authority (language-neutral sources first):
//   - RFC 0003 §3 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md):
//     the v1 content digest is SHA-256 over the complete original byte
//     sequence with no decoding, BOM removal, newline normalization, or
//     metadata mixed in; algorithm exactly "sha256", hex exactly 64 lowercase
//     hexadecimal characters.
//   - conformance/vectors/source-v1.json:6-16 (cases source.digest.sha256-
//     empty and source.digest.sha256-abc) pins the golden hex values.
//   - https://github.com/consema/consema-rs/blob/main/consema-document/src/source.rs (ContentDigest) and
//     lib.rs (SnapshotIdentity) pin the shapes; consema-go/go/document/digest.go
//     is a cross-reference only.
//
// Kotlin-idiomatic design: SHA-256 is computed with java.security.
// MessageDigest — the JDK standard library, not a third-party dependency
// (the zero-runtime-dependency policy of https://github.com/consema/consema/blob/main/docs/multi-language-implementation-
// plan.md §0.2 follows the go.mod zero-require precedent; JDK classes are the
// runtime itself, exactly like Python's hashlib).

package consema.document

import java.security.MessageDigest
import kotlin.jvm.JvmInline

/**
 * Stable SHA-256 identity of exact raw source bytes (RFC 0003 §3;
 * source.rs).
 *
 * Equal raw bytes always produce equal content digests across processes and
 * languages. A digest mismatch proves different bytes. Digest equality is
 * not a claim about Profile, encoding, native meaning, or document identity.
 */
class ContentDigest private constructor(private val bytes: ByteArray) {

    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"

        /** Computes the digest of exact raw bytes (source.rs). */
        fun of(bytes: ByteArray): ContentDigest =
            ContentDigest(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** Constructs a digest value from an already decoded 32-byte record
         * (source.rs). */
        fun fromBytes(bytes: ByteArray): ContentDigest {
            require(bytes.size == 32) { "content digest must be exactly 32 bytes" }
            return ContentDigest(bytes.copyOf())
        }
    }

    /** Digest algorithm identifier frozen by the v1 source contract
     * (source.rs; RFC 0003 §3: exactly "sha256"). */
    val algorithm: String
        get() = "sha256"

    /** Exact 32 digest bytes; returns a defensive copy (source.rs). */
    fun bytes(): ByteArray = bytes.copyOf()

    /** Lowercase hexadecimal representation, exactly 64 characters
     * (source.rs; RFC 0003 §3). */
    fun toHex(): String {
        val hex = CharArray(64)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xff
            hex[i * 2] = HEX_DIGITS[value ushr 4]
            hex[i * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(hex)
    }

    override fun equals(other: Any?): Boolean =
        other is ContentDigest && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ContentDigest(${toHex()})"
}

/**
 * Opaque identity of exactly one immutable document snapshot
 * (lib.rs). Fresh for every formed Document: parsing the same bytes
 * twice produces equal content digests and distinct snapshot identities
 * (RFC 0003 §3; conformance/vectors/source-v1.json:18-22, case
 * source.identity.equal-bytes-distinct-snapshots). Never serialized
 * (RFC 0003 §3: "SnapshotIdentity is never serialized").
 */
@JvmInline
value class SnapshotIdentity(val asU64: Long)
