// Strict PortableValue equality and deterministic hashing.
//
// Data authority: RFC 0016 §4.1 (docs/rfcs/0016-go-api-mapping-v1.md:151-154):
// strict equality is kind identity plus canonical content equality, order-
// dependent for containers; Hash is consistent with Equal and order-
// dependent. conformance/vectors/v1.json pins the contract
// (value.decimal-normalization: "1.00" == "10e-1"; value.float-signed-zero:
// +0.0 != -0.0). The hashing contract (FNV-1a over the canonical PVCE/1
// bytes) follows go/core/equal.go:127-138 as cross-reference.
//
// Kotlin-idiomatic design: top-level functions [equal] and [hash] (the
// canonical contract), total over nulls, never recursing through container
// edges by pointer identity.

package consema.core

/**
 * Reports strict PortableValue equality (RFC 0016 §4.1): kind identity plus
 * canonical content equality. Objects compare entry-by-entry in stored order
 * (keys and values); entry mappings compare association-by-association in
 * stored order (keys and values, duplicates included); arrays compare
 * item-by-item in stored order. [equal] is total: it never throws and never
 * silently accepts an unknown kind (the closed sealed hierarchy cannot carry
 * one). [equal](null, null) is true; [equal](null, x) is false for any
 * non-null x.
 */
fun equal(a: PortableValue?, b: PortableValue?): Boolean {
    if (a == null || b == null) {
        return a === b
    }
    return when (a) {
        is PvNull -> b is PvNull
        is PvBoolean -> b is PvBoolean && a.value == b.value
        is PvString -> b is PvString && a.value == b.value
        is PvInteger -> b is PvInteger && a.value == b.value
        is PvDecimal -> b is PvDecimal && a.coefficient == b.coefficient && a.exponent == b.exponent
        is PvBinaryFloat32 -> b is PvBinaryFloat32 && a.bits == b.bits
        is PvBinaryFloat64 -> b is PvBinaryFloat64 && a.bits == b.bits
        is PvBytes -> b is PvBytes && a.content().contentEquals(b.content())
        is PvDate -> b is PvDate && a.year == b.year && a.month == b.month && a.day == b.day
        is PvTime ->
            b is PvTime &&
                a.hour == b.hour &&
                a.minute == b.minute &&
                a.second == b.second &&
                equal(a.fractionalSecond, b.fractionalSecond)
        is PvLocalDateTime -> b is PvLocalDateTime && equal(a.date, b.date) && equal(a.time, b.time)
        is PvOffsetDateTime ->
            b is PvOffsetDateTime && equal(a.local, b.local) && a.offsetSeconds == b.offsetSeconds
        is PvEntryMapping -> {
            if (b !is PvEntryMapping || a.size() != b.size()) {
                false
            } else {
                var same = true
                for (i in a.entries.indices) {
                    if (!equal(a.entries[i].key, b.entries[i].key) ||
                        !equal(a.entries[i].value, b.entries[i].value)
                    ) {
                        same = false
                        break
                    }
                }
                same
            }
        }
        is PvArray -> {
            if (b !is PvArray || a.size() != b.size()) {
                false
            } else {
                var same = true
                for (i in a.items.indices) {
                    if (!equal(a.items[i], b.items[i])) {
                        same = false
                        break
                    }
                }
                same
            }
        }
        is PvObject -> {
            if (b !is PvObject || a.size() != b.size()) {
                false
            } else {
                var same = true
                for (i in a.entries.indices) {
                    if (a.entries[i].key != b.entries[i].key ||
                        !equal(a.entries[i].value, b.entries[i].value)
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

// FNV-1a 64-bit constants (the standard FNV-1a parameters used by
// go/core/equal.go via hash/fnv).
private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
private const val FNV_PRIME: ULong = 0x100000001b3uL

/**
 * Returns a deterministic 64-bit hash consistent with [equal]: equal values
 * always hash equal, and the hash is order-dependent (objects and arrays
 * hash by ordered content). It is defined as FNV-1a over the canonical
 * PVCE/1 encoding of the value, so [equal](a, b) holds exactly when the
 * encoded bytes of a and b are identical. [hash](null) is 0.
 */
fun hash(value: PortableValue?): ULong {
    val bytes = try {
        encodePvce(value ?: return 0uL)
    } catch (e: PvceException) {
        return 0uL
    }
    var h = FNV_OFFSET_BASIS
    for (octet in bytes) {
        // Zero-extend the octet (Byte.toULong sign-extends), then XOR.
        h = h xor (octet.toULong() and 0xffuL)
        h = h * FNV_PRIME
    }
    return h
}
