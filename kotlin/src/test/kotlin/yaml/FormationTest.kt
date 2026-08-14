// YAML formation tests: profile directives, source encodings, empty and
// multi-document streams, anchors/aliases, recovery-free fatal failures, and
// the security limits (deep nesting, alias bombs, and count limits never
// return partial documents).
//
// Data authority: RFC 0007 §3-§4, §8, §13 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-
// profiles-and-safety-v1.md) and the vector cases
// stream.empty, stream.multi-document, formation.undefined-alias,
// source.utf16le-bom, resource.parse-source-bytes
// (conformance/vectors/yaml-v1.json:15-29, 41-44, 126-129).
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package yaml

import consema.document.FormationStatus
import consema.document.ParseLimits
import consema.yaml.YamlFormationException
import consema.yaml.YamlProfile
import consema.yaml.YamlSyntaxKind
import consema.yaml.parse
import consema.yaml.projectGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormationTest {

    /** Vector case stream.empty (yaml-v1.json:20-24): an empty stream has
     * zero documents and zero aliases. */
    @Test
    fun emptyStream() {
        val document = parse(ByteArray(0), YamlProfile.Yaml12CoreV1)
        assertEquals(0, document.documentCount())
        assertEquals(0, document.aliasCount())
        assertEquals(FormationStatus.Complete, document.formationStatus())
    }

    /** Vector case stream.multi-document (yaml-v1.json:25-29): documents
     * are independent; aliases resolve within their own document; the
     * stream renders byte-identically. */
    @Test
    fun multiDocumentStream() {
        val source = "---\n&a [one, *a]\n---\n{k: v}\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        assertEquals(2, document.documentCount())
        assertEquals(1, document.aliasCount())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        assertEquals("a", document.alias(0)!!.name())
        assertEquals(
            document.document(0)!!.root().nodeRef(),
            document.alias(0)!!.target().nodeRef(),
        )
    }

    /** Vector case source.utf16le-bom (yaml-v1.json:15-19): a UTF-16LE BOM
     * selects the encoding, the BOM stays part of the raw source, and the
     * stream still forms one document. */
    @Test
    fun utf16LeBomSource() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xfe.toByte(),
            'a'.code.toByte(), 0,
            ':'.code.toByte(), 0,
            ' '.code.toByte(), 0,
            '1'.code.toByte(), 0,
            '\n'.code.toByte(), 0,
        )
        val document = parse(bytes, YamlProfile.Yaml12CoreV1)
        assertEquals(1, document.documentCount())
        assertEquals(
            consema.document.SourceEncoding.Utf16Le,
            document.source().encodingFacts.selected,
        )
        assertEquals(bytes.toList(), document.render().toList())
        assertEquals("\uFEFFa: 1\n", document.source().decodedText())
    }

    /** Vector case formation.undefined-alias (yaml-v1.json:41-44): an
     * undefined alias fails formation with yaml.parse.syntax@1 (the
     * reference backend rejects unknown anchors at parse time). */
    @Test
    fun undefinedAliasIsSyntaxFailure() {
        val error = assertFailsWith<YamlFormationException> {
            parse("[*missing]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        }
        assertEquals("yaml.parse.syntax@1", error.code)
    }

    /** RFC 0007 §5: a conflicting %YAML version directive is fatal with
     * yaml.profile.version-directive@1 before any grammar work. */
    @Test
    fun versionDirectiveConflictIsFatal() {
        val error = assertFailsWith<YamlFormationException> {
            parse(
                "%YAML 1.1\n---\nyes\n".toByteArray(Charsets.UTF_8),
                YamlProfile.Yaml12CoreV1,
            )
        }
        assertEquals("yaml.profile.version-directive@1", error.code)

        val accepted = parse(
            "%YAML 1.1\n---\nyes\n".toByteArray(Charsets.UTF_8),
            YamlProfile.Yaml11CompatV1,
        )
        assertEquals(1, accepted.documentCount())
        val root = accepted.document(0)!!.root()
        assertEquals("true", root.scalar()!!.canonical())
    }

    /** RFC 0007 §13 and the reference backend: deep nesting exceeds the
     * frozen nesting-depth limit and fails with core.parse.resource-limit@1;
     * no partial document exists. */
    @Test
    fun deepNestingHitsDepthLimit() {
        val error = assertFailsWith<YamlFormationException> {
            parse(
                "[[x]]".toByteArray(Charsets.UTF_8),
                YamlProfile.Yaml12CoreV1,
                ParseLimits(
                    maxSourceBytes = 64 shl 20,
                    maxNestingDepth = 1,
                    maxTokenCount = 2_000_000,
                    maxNodeCount = 1_000_000,
                    maxDiagnostics = 10_000,
                ),
            )
        }
        assertEquals("core.parse.resource-limit@1", error.code)
        assertEquals("nesting-depth", error.name)
    }

    /** RFC 0007 §13: an alias bomb (many aliases referencing one anchor)
     * is bounded by the syntax-event and native-node limits; the parser and
     * graph projection never expand aliases, so the bomb stays a small
     * shared graph instead of an exponential tree. */
    @Test
    fun aliasBombIsBoundedAndNeverExpanded() {
        // 100 aliases referencing one anchor stay within the default limits
        // because aliases are one edge each; the graph has exactly one
        // node and 100 shared edges.
        val bomb = "&x [" + (0 until 100).joinToString(", ") { "*x" } + "]\n"
        val document = parse(bomb.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        assertEquals(100, document.aliasCount())
        val graph = document.projectGraph()
        assertEquals(1, graph.nodeCount())
        assertEquals(100, graph.edgeCount())

        // A bounded token budget rejects a large alias stream atomically
        // (the backend syntax-event limit, backend.rs).
        val limited = assertFailsWith<YamlFormationException> {
            parse(
                bomb.toByteArray(Charsets.UTF_8),
                YamlProfile.Yaml12CoreV1,
                ParseLimits(
                    maxSourceBytes = 64 shl 20,
                    maxNestingDepth = 256,
                    maxTokenCount = 16,
                    maxNodeCount = 1_000_000,
                    maxDiagnostics = 10_000,
                ),
            )
        }
        assertEquals("core.parse.resource-limit@1", limited.code)
    }

    /** RFC 0007 §8: self- and mutual cycles formed by backward aliases are
     * valid YAML and compose to shared graph identity without expansion. */
    @Test
    fun cyclesComposeToSharedIdentity() {
        val document = parse("&self [*self]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val root = document.document(0)!!.root()
        assertEquals("self", root.anchor())
        assertEquals(1, root.sequenceLen())
        assertEquals(root.nodeRef(), root.sequenceItem(0)!!.node().nodeRef())
        val graph = document.projectGraph()
        assertEquals(root.nodeRef(), document.alias(0)!!.target().nodeRef())
        assertEquals(1, graph.nodeCount())
        assertEquals(1, graph.edgeCount())
    }

    /** RFC 0007 §3: an unterminated flow collection is a syntax failure
     * with yaml.parse.syntax@1 (the backend marker position is reported). */
    @Test
    fun unterminatedCollectionIsSyntaxFailure() {
        val error = assertFailsWith<YamlFormationException> {
            parse("[unterminated".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        }
        assertEquals("yaml.parse.syntax@1", error.code)
    }

    /** RFC 0007 §5: quoted scalars are always strings, never schema
     * keywords, for both profiles (the quoted-always-wins rule). */
    @Test
    fun quotedKeywordsAreExactStrings() {
        for (keyword in listOf("", "~", "null", "true", "yes", "on", "017", "0o17",
            "1:02:03", "2001-12-15", ".inf", ".nan", "0x1F", "1e3", "1.5")
        ) {
            for (profile in YamlProfile.entries) {
                for (quote in listOf("'", "\"")) {
                    val document = parse(
                        "$quote$keyword$quote\n".toByteArray(Charsets.UTF_8),
                        profile,
                    )
                    val scalar = document.document(0)!!.root().scalar()!!
                    assertEquals(
                        "String",
                        scalar.kind().name,
                        "quoted $keyword under $profile",
                    )
                    assertEquals(keyword, scalar.decoded())
                    assertEquals(keyword, scalar.canonical())
                }
            }
        }
    }

    /** RFC 0007 §7: the regression case keeps node-property characters
     * inside multiline plain scalar text (vector
     * regression.plain-property-characters, yaml-v1.json:136-139). */
    @Test
    fun plainPropertyCharactersStayScalarText() {
        val source = "---\nk:#foo\n &a !t s\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val scalar = document.document(0)!!.root().scalar()!!
        assertEquals("k:#foo &a !t s", scalar.decoded())
        assertEquals("k:#foo &a !t s", scalar.canonical())
        assertEquals(0, document.aliasCount())
        assertTrue(YamlSyntaxKind.Anchor !in document.losslessSyntaxKinds())
        assertTrue(YamlSyntaxKind.Tag !in document.losslessSyntaxKinds())
    }

    /** RFC 0007 §3: source-size overflow is fatal before formation with
     * core.parse.resource-limit@1 (vector resource.parse-source-bytes,
     * yaml-v1.json:126-129). */
    @Test
    fun sourceBytesLimitIsFatal() {
        val error = assertFailsWith<YamlFormationException> {
            parse(
                "a: 1\n".toByteArray(Charsets.UTF_8),
                YamlProfile.Yaml12CoreV1,
                ParseLimits(
                    maxSourceBytes = 4,
                    maxNestingDepth = 256,
                    maxTokenCount = 2_000_000,
                    maxNodeCount = 1_000_000,
                    maxDiagnostics = 10_000,
                ),
            )
        }
        assertEquals("core.parse.resource-limit@1", error.code)
        assertEquals("source-bytes", error.name)
    }

    /** Audit gap (fixture yaml/github-actions-ci.yaml): flow indicators
     * (`{`/`}`) are plain-scalar content in block context, so
     * `runs-on: ${{ matrix.os }}` forms one scalar and the stream
     * round-trips byte-exactly (Rust/Go/TS/Py close the fixture).
     * saphyr/libyaml only treat `,[]{}` as indicators in flow context. */
    @Test
    fun blockPlainScalarKeepsFlowIndicators() {
        val source = "jobs:\n  test:\n    runs-on: \${{ matrix.os }}\n    strategy:\n      matrix:\n        os: [ubuntu-latest, windows-latest]\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        val root = document.document(0)!!.root()
        val runsOn = root.mappingEntry(0)!!
            .value().mappingEntry(0)!!
            .value().mappingEntry(0)!!
        assertEquals("\${{ matrix.os }}", runsOn.value().scalar()!!.decoded())
    }

    /** Audit gap (fixture yaml/anchor-heavy.yaml): an alias used as a
     * mapping value inside a block-sequence item (`- name: ingest` /
     * `settings: *defaults`) resolves against the anchors of the same
     * document. The block-sequence item loop must step past the line
     * indentation to the next `-` indicator, or the sequence ends after
     * the first item and the remainder mis-parses as a new document whose
     * anchor table was reset. */
    @Test
    fun aliasAsMappingValueInBlockSequenceItem() {
        val source = "defaults: &defaults\n  retries: 3\n\nworkers:\n  - name: ingest\n    settings: *defaults\n  - name: export\n    settings: *defaults\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(2, document.aliasCount())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        // The second item's alias resolves to the same anchored node.
        val workers = document.document(0)!!.root().mappingEntry(1)!!
        val exportSettings = workers.value().sequenceItem(1)!!
            .node().mappingEntry(1)!!
        val defaults = document.document(0)!!.root().mappingEntry(0)!!
        assertEquals(
            defaults.value().nodeRef(),
            exportSettings.value().nodeRef(),
        )
    }
}
