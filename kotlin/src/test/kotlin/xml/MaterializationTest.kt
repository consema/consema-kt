// Golden materialization transcriptions from conformance/vectors/
// xml-1-0-safe-v1.json (cases xml.materialization.*).
//
// Data authority: the canonical `xml.safe-canonical-document@1` style
// (RFC 0012 §10; https://github.com/consema/consema-rs/blob/main/consema-xml/src/materialization.rs) and the vector
// renders, transcribed verbatim. The failure spelling `"invalid-record"`
// is the runner mapping of InvalidRequest (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/
// xml_v1.rs).
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package xml

import consema.core.PvArray
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PvBoolean
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.ProfileId
import consema.xml.materialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MaterializationTest {

    private fun request(): MaterializationRequest =
        MaterializationRequest.new(
            ProfileId("xml.1.0-safe", 1),
            MaterializationStyleId("xml.safe-canonical-document", 1),
        )

    private fun expandedName(namespace: String?, local: String): PvObject =
        PvObject(
            listOf(
                consema.core.Entry(
                    "namespace",
                    if (namespace == null) PvNull else PvString(namespace),
                ),
                consema.core.Entry("local", PvString(local)),
            ),
        )

    @Test
    fun `canonical round trip reproduces the vector render`() {
        // Case xml.materialization.canonical-round-trip
        // (xml-1-0-safe-v1.json:352-388). The expected render includes the
        // final LF of the canonical style.
        val record = PvObject(
            listOf(
                consema.core.Entry("record", PvString("xml.element-tree@1")),
                consema.core.Entry(
                    "root",
                    PvObject(
                        listOf(
                            consema.core.Entry("expanded-name", expandedName(null, "root")),
                            consema.core.Entry(
                                "attributes",
                                PvArray(
                                    listOf(
                                        PvObject(
                                            listOf(
                                                consema.core.Entry(
                                                    "expanded-name",
                                                    expandedName(null, "a"),
                                                ),
                                                consema.core.Entry("value", PvString("1")),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            consema.core.Entry(
                                "content",
                                PvArray(
                                    listOf(
                                        PvObject(
                                            listOf(
                                                consema.core.Entry("kind", PvString("text")),
                                                consema.core.Entry(
                                                    "fragments",
                                                    PvArray(
                                                        listOf(
                                                            PvObject(
                                                                listOf(
                                                                    consema.core.Entry(
                                                                        "kind",
                                                                        PvString("literal"),
                                                                    ),
                                                                    consema.core.Entry(
                                                                        "text",
                                                                        PvString("t"),
                                                                    ),
                                                                ),
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = materialize(record, request())
        val complete = assertIs<MaterializationResult.Complete<consema.xml.Document>>(result).materialization
        assertEquals(
            "<root a=\"1\">t</root>\n",
            String(complete.document.render(), Charsets.UTF_8),
        )
    }

    @Test
    fun `content is escaped under the canonical style`() {
        // Case xml.materialization.escapes-content (xml-1-0-safe-v1.json).
        val record = PvObject(
            listOf(
                consema.core.Entry("record", PvString("xml.element-tree@1")),
                consema.core.Entry(
                    "root",
                    PvObject(
                        listOf(
                            consema.core.Entry("expanded-name", expandedName(null, "root")),
                            consema.core.Entry(
                                "content",
                                PvArray(
                                    listOf(
                                        PvObject(
                                            listOf(
                                                consema.core.Entry("kind", PvString("text")),
                                                consema.core.Entry(
                                                    "fragments",
                                                    PvArray(
                                                        listOf(
                                                            PvObject(
                                                                listOf(
                                                                    consema.core.Entry(
                                                                        "kind",
                                                                        PvString("literal"),
                                                                    ),
                                                                    consema.core.Entry(
                                                                        "text",
                                                                        PvString("a < b & c"),
                                                                    ),
                                                                ),
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = materialize(record, request())
        val complete = assertIs<MaterializationResult.Complete<consema.xml.Document>>(result).materialization
        assertEquals(
            "<root>a &lt; b &amp; c</root>\n",
            String(complete.document.render(), Charsets.UTF_8),
        )
    }

    @Test
    fun `invalid record is rejected with the vector failure spelling`() {
        // Case xml.materialization.invalid-record-rejected
        // (xml-1-0-safe-v1.json:418-435).
        val record = PvObject(
            listOf(
                consema.core.Entry("record", PvString("xml.something-else@1")),
                consema.core.Entry(
                    "root",
                    PvObject(
                        listOf(
                            consema.core.Entry("expanded-name", expandedName(null, "root")),
                        ),
                    ),
                ),
            ),
        )
        val result = materialize(record, request())
        val failed = assertIs<MaterializationResult.Failed>(result).attempt
        assertEquals("invalid-record", failureSpelling(failed.failure))
    }

    @Test
    fun `declaration facts round trip through the canonical style`() {
        // RFC 0012 §10: the style deterministically chooses declaration
        // spelling; UTF-8 output with the declaration reproduced verbatim.
        val record = PvObject(
            listOf(
                consema.core.Entry("record", PvString("xml.element-tree@1")),
                consema.core.Entry(
                    "declaration",
                    PvObject(
                        listOf(
                            consema.core.Entry("version", PvString("1.0")),
                            consema.core.Entry("encoding", PvString("UTF-8")),
                            consema.core.Entry("standalone", PvBoolean(true)),
                        ),
                    ),
                ),
                consema.core.Entry(
                    "root",
                    PvObject(
                        listOf(
                            consema.core.Entry("expanded-name", expandedName(null, "root")),
                        ),
                    ),
                ),
            ),
        )
        val result = materialize(record, request())
        val complete = assertIs<MaterializationResult.Complete<consema.xml.Document>>(result).materialization
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><root/>\n",
            String(complete.document.render(), Charsets.UTF_8),
        )
    }

    private fun failureSpelling(failure: consema.document.MaterializationException): String =
        when (failure.kind) {
            consema.document.MaterializationFailureKind.INVALID_REQUEST -> "invalid-record"
            consema.document.MaterializationFailureKind.UNSUPPORTED_PROFILE -> "unsupported-profile"
            consema.document.MaterializationFailureKind.UNSUPPORTED_STYLE -> "unsupported-style"
            consema.document.MaterializationFailureKind.UNSUPPORTED_ENCODING -> "unsupported-encoding"
            consema.document.MaterializationFailureKind.UNSUPPORTED_NEWLINE -> "unsupported-newline"
            consema.document.MaterializationFailureKind.UNREPRESENTABLE -> "unrepresentable"
            consema.document.MaterializationFailureKind.RESOURCE_LIMIT -> "resource-limit"
            consema.document.MaterializationFailureKind.FORMATION_FAILED -> "formation-failed"
        }
}
