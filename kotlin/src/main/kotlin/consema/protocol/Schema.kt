// Fixed-field record decoding helpers.
//
// Data authority: the fixed-field record discipline of the protocol
// contracts (crates/consema-protocol/src/schema.rs:16-70 as the Rust
// cross-reference): an Object record must declare exactly the schema's
// fields in canonical order, with the schema discriminator first for
// schema-bearing records. go/protocol/schema.go is a cross-reference.

package consema.protocol

import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import java.math.BigInteger

/**
 * Strictly validates a fixed-field object record: the value must be an
 * Object, every field must be declared by the schema, every declared field
 * must be present, and the fields must appear exactly in the canonical
 * order. Returns the field values in schema order.
 */
internal fun exactFields(value: PortableValue, expected: List<String>, path: String): List<PortableValue> {
    val objectValue = value as? PvObject
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected Object")
    val names = objectValue.entries().map { it.key }
    val values = objectValue.entries().map { it.value }
    for (name in names) {
        if (name !in expected) {
            throw protocolError(
                ProtocolErrorKind.UNKNOWN_FIELD,
                "$path.$name",
                "field is not declared by the fixed schema",
            )
        }
    }
    for (name in expected) {
        if (name !in names) {
            throw protocolError(
                ProtocolErrorKind.MISSING_FIELD,
                "$path.$name",
                "required field is absent",
            )
        }
    }
    if (names != expected) {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, path, "fields are not in canonical order")
    }
    return values
}

/**
 * Validates a fixed-field record whose first field is the schema
 * discriminator and returns all field values.
 */
internal fun schemaFields(
    value: PortableValue,
    schema: String,
    expected: List<String>,
    path: String,
): List<PortableValue> {
    val fields = exactFields(value, expected, path)
    val observed = stringOf(fields[0], "$path.schema")
    if (observed != schema) {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, "$path.schema", "expected $schema")
    }
    return fields
}

/** Reads a String field (crate::schema::string). */
internal fun stringOf(value: PortableValue, path: String): String {
    val text = value as? PvString
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected String")
    return text.value
}

/** Reads a Boolean field (crate::schema::boolean). */
internal fun booleanOf(value: PortableValue, path: String): Boolean {
    val boolean = value as? PvBoolean
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected Boolean")
    return boolean.value
}

/** Reads a Sequence field (crate::schema::sequence). */
internal fun sequenceOf(value: PortableValue, path: String): List<PortableValue> {
    val array = value as? consema.core.PvArray
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected Sequence")
    return array.items()
}

/** Reads an Integer field that must fit an unsigned 32-bit range
 * (crate::schema::unsigned_u32). */
internal fun unsigned32(value: PortableValue, path: String): Int {
    val integer = value as? PvInteger
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected Integer")
    val number = integer.value
    if (number.signum() < 0 || number.bitLength() > 32) {
        throw protocolError(ProtocolErrorKind.INVALID_VALUE, path, "expected an unsigned 32-bit Integer")
    }
    return number.toInt()
}

/** Reads an Integer field that must fit an unsigned 64-bit range
 * (crate::schema::unsigned_u64). */
internal fun unsigned64(value: PortableValue, path: String): ULong {
    val integer = value as? PvInteger
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected Integer")
    val number = integer.value
    if (number.signum() < 0 || number.bitLength() > 64) {
        throw protocolError(ProtocolErrorKind.INVALID_VALUE, path, "expected an unsigned 64-bit Integer")
    }
    return number.toLong().toULong()
}

/** Builds the Integer record for a u64 (crate::schema::integer_u64). */
internal fun integerValue(value: ULong): PortableValue =
    PvInteger(BigInteger(value.toString()))

/** Encodes an optional string as String or Null (crate::schema::nullable_string). */
internal fun nullableString(value: String?): PortableValue =
    if (value == null) PvNull else PvString(value)

/** Decodes an optional string: Null yields null, any other value must be a
 * String (crate::schema::optional_string). */
internal fun optionalString(value: PortableValue, path: String): String? {
    if (value is PvNull) {
        return null
    }
    return stringOf(value, path)
}

/** Reports whether the slice contains the element. */
internal fun containsString(slice: List<String>, element: String): Boolean = element in slice
