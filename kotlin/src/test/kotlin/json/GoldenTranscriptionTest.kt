// Golden transcriptions of conformance/vectors/json-family-v2.json cases.
//
// Each test transcribes one vector case (input.source / expected.*) VERBATIM
// from conformance/vectors/json-family-v2.json and asserts the language-
// neutral facts the Rust/Go differential runners assert
// (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/json_family_v2.rs for parse cases,
// :582-602 for materialization cases). The case id is cited on every test.
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.

package json

import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvNull
import consema.core.PvString
import consema.document.FormationStatus
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.json.JsonProfile
import consema.json.JsonSyntaxKind
import consema.json.SemanticAvailability
import consema.json.materializationFailureName
import consema.json.materialize
import consema.json.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldenTranscriptionTest {

    /** Vector case json5.parse.full-surface (json-family-v2.json:6-10):
     * BOM, line comment, unquoted/escaped identifiers, JSON5 numbers,
     * non-finite literals, trailing comma; Complete with the exact member
     * names and kinds and the lossless syntax kinds Bom/LineComment/
     * Identifier. */
    @Test
    fun json5ParseFullSurface() {
        val source = "﻿{ // lead\n" +
            "unquoted:'value',\\u0061:.5,hex:+0X10,trail:1.,exp:1.e+2," +
            "truth:true,nil:null,inf:-Infinity,nan:+NaN,}"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        assertEquals(
            "Object",
            (document.root().kind() as SemanticAvailability.Available).value.name,
        )

        val members = (document.root().objectMembers() as SemanticAvailability.Available).value!!
        assertEquals(
            listOf("unquoted", "a", "hex", "trail", "exp", "truth", "nil", "inf", "nan"),
            members.map { (it.name() as SemanticAvailability.Available).value },
        )
        assertEquals(
            listOf(
                "String", "Decimal", "Integer", "Decimal", "Decimal",
                "Boolean", "Null", "BinaryFloat64", "BinaryFloat64",
            ),
            members.map { (it.value().kind() as SemanticAvailability.Available).value.name },
        )
        val kinds = document.losslessSyntaxKinds()
        assertTrue(JsonSyntaxKind.Bom in kinds)
        assertTrue(JsonSyntaxKind.LineComment in kinds)
        assertTrue(JsonSyntaxKind.Identifier in kinds)
    }

    /** Vector case json5.parse.string-extensions (json-family-v2.json:18-22):
     * single quotes, \x \v \0 identity escapes, line continuation, and a
     * surrogate pair decode to the exact element strings. */
    @Test
    fun json5ParseStringExtensions() {
        val source = "['single','\\x41','\\v','\\0','\\q','line\\\nnext','\\uD83D\\uDE00']"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        val elements = (document.root().arrayElements() as SemanticAvailability.Available).value!!
        assertEquals(
            listOf("single", "A", "\u000b", "\u0000", "q", "linenext", "😀"),
            elements.map { (it.value().asString() as SemanticAvailability.Available).value },
        )
    }

    /** Vector case json5.number.positive-infinity (json-family-v2.json:90-94):
     * +Infinity is BinaryFloat64 with the frozen bits 7ff0000000000000
     * (RFC 0005 §6). */
    @Test
    fun json5NumberPositiveInfinity() {
        val document = parse("+Infinity".toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(
            "7ff0000000000000",
            "%016x".format(
                (document.root().asBinaryFloat64() as SemanticAvailability.Available).value!!,
            ),
        )
    }

    /** Vector case json5.number.huge-hex-exact (json-family-v2.json:102-106):
     * hexadecimal integers convert exactly without host rounding. */
    @Test
    fun json5NumberHugeHexExact() {
        val document = parse(
            "0xFFFFFFFFFFFFFFFFFFFFFFFF".toByteArray(Charsets.UTF_8),
            JsonProfile.Json5StandardV1,
        )
        assertEquals(
            "79228162514264337593543950335",
            (document.root().asInteger() as SemanticAvailability.Available).value!!.toString(),
        )
    }

    /** Vector case json5.materialize.canonical-specials (json-family-v2.json:
 *): the four frozen non-finite spellings and the canonical
     * U+2028 escape, byte-exact under json5.canonical-compact with newline
     * None (the conformance runner's materialization request,
     * json_family_v2.rs). */
    @Test
    fun json5MaterializeCanonicalSpecials() {
        val request = MaterializationRequest.new(
            ProfileId("json5.standard", 1),
            MaterializationStyleId("json5.canonical-compact", 1),
        ).withNewline(NewlinePolicy.None)
        val input = PvArray(
            listOf(
                PvBinaryFloat64(java.lang.Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)),
                PvBinaryFloat64(java.lang.Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY)),
                PvBinaryFloat64(java.lang.Double.doubleToRawLongBits(Double.NaN)),
                PvBinaryFloat64(java.lang.Double.doubleToRawLongBits(-Double.NaN)),
                PvString("a\u2028b"),
            ),
        )
        val result = materialize(input, request)
        val complete = result as MaterializationResult.Complete
        assertEquals(
            "[Infinity,-Infinity,NaN,-NaN,\"a\\u2028b\"]",
            complete.materialization.document.render().toString(Charsets.UTF_8),
        )
    }

    /** Vector case json5.materialize.reject-finite-binary (json-family-v2.json:
 *): a finite binary64 bit pattern is Unrepresentable under
     * ExactOnly (RFC 0004 §3, RFC 0005 §9). */
    @Test
    fun json5MaterializeRejectsFiniteBinary() {
        val request = MaterializationRequest.new(
            ProfileId("json5.standard", 1),
            MaterializationStyleId("json5.canonical-compact", 1),
        ).withNewline(NewlinePolicy.None)
        val result = materialize(PvBinaryFloat64(0L), request)
        val failed = result as MaterializationResult.Failed
        assertEquals("Unrepresentable", materializationFailureName(failed.attempt.failure.kind))
    }

    /** Vector case json5.materialize.reject-profile-style-mismatch
     * (json-family-v2.json:150-154): the json5 profile rejects the strict
     * style ID with UnsupportedStyle. */
    @Test
    fun json5MaterializeRejectsProfileStyleMismatch() {
        val request = MaterializationRequest.new(
            ProfileId("json5.standard", 1),
            MaterializationStyleId("json.canonical-compact", 1),
        ).withNewline(NewlinePolicy.None)
        val result = materialize(PvNull, request)
        val failed = result as MaterializationResult.Failed
        assertEquals("UnsupportedStyle", materializationFailureName(failed.attempt.failure.kind))
    }
}
