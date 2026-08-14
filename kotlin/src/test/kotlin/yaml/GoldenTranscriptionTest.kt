// Golden transcriptions of conformance/vectors/yaml-v1.json cases.
//
// Each test transcribes one vector case (input.source / expected.*) VERBATIM
// from conformance/vectors/yaml-v1.json and asserts the language-neutral
// facts the Rust/Go differential runners assert
// (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/yaml_v1.rs for scalar profiles,
// :385-405 for syntax facts, :407-460 for materialization, :470-530 for
// edits). The case id is cited on every test.
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package yaml

import consema.document.FormationStatus
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.ProfileId
import consema.yaml.EditFailure
import consema.yaml.EditFailureException
import consema.yaml.EditTransactionBuilder
import consema.yaml.RepresentationPolicy
import consema.yaml.YamlProfile
import consema.yaml.YamlScalarKind
import consema.yaml.YamlSyntaxKind
import consema.yaml.commit
import consema.yaml.materializeGraph
import consema.yaml.materializeValue
import consema.yaml.parse
import consema.yaml.projectGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoldenTranscriptionTest {

    /** Vector case profile.yaml12-scalars (yaml-v1.json:5-9): YAML 1.2 Core
     * resolves yes/017/0o17 as String/Integer/Integer and keeps 1:02:03 and
     * 2001-12-15 as strings with exact canonical content. */
    @Test
    fun profileYaml12Scalars() {
        val document = parse(
            "[yes, 017, 0o17, 1:02:03, 2001-12-15]".toByteArray(Charsets.UTF_8),
            YamlProfile.Yaml12CoreV1,
        )
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val root = document.document(0)!!.root()
        val items = (0 until root.sequenceLen()!!).map { root.sequenceItem(it)!!.node().scalar()!! }
        assertEquals(
            listOf("String", "Integer", "Integer", "String", "String"),
            items.map { it.kind().name },
        )
        assertEquals(
            listOf("yes", "17", "15", "1:02:03", "2001-12-15"),
            items.map { it.canonical() },
        )
    }

    /** Vector case profile.yaml11-scalars (yaml-v1.json:10-14): the frozen
     * 1.1 forms resolve yes -> Boolean, 017 -> octal Integer 15, 0o17 stays
     * String, 1:02:03 -> sexagesimal Integer 3723, and 2001-12-15 ->
     * Timestamp. */
    @Test
    fun profileYaml11Scalars() {
        val document = parse(
            "%YAML 1.1\n---\n[yes, 017, 0o17, 1:02:03, 2001-12-15]\n".toByteArray(Charsets.UTF_8),
            YamlProfile.Yaml11CompatV1,
        )
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val root = document.document(0)!!.root()
        val items = (0 until root.sequenceLen()!!).map { root.sequenceItem(it)!!.node().scalar()!! }
        assertEquals(
            listOf("Boolean", "Integer", "String", "Integer", "Timestamp"),
            items.map { it.kind().name },
        )
        assertEquals(
            listOf("true", "15", "0o17", "3723", "2001-12-15"),
            items.map { it.canonical() },
        )
        assertTrue(items[4].kind() == YamlScalarKind.Timestamp)
    }

    /** Vector case syntax.styles-and-trivia (yaml-v1.json:31-34): the
     * lossless scanner classifies every presentation style and marker with
     * exactly 48 pieces, including the required kinds (the block scalar
     * content regions own their trailing line breaks). */
    @Test
    fun syntaxStylesAndTrivia() {
        val source = "--- # doc\n" +
            "plain: text\n" +
            "single: 'x'\n" +
            "double: \"y\"\n" +
            "literal: |-\n" +
            "  a\n" +
            "folded: >+\n" +
            "  b\n" +
            "flow: [one, {k: v}]\n" +
            "...\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val kinds = document.losslessSyntaxKinds()
        assertEquals(48, kinds.size)
        for (required in listOf(
            YamlSyntaxKind.DocumentStart,
            YamlSyntaxKind.Comment,
            YamlSyntaxKind.PlainScalar,
            YamlSyntaxKind.SingleQuotedScalar,
            YamlSyntaxKind.DoubleQuotedScalar,
            YamlSyntaxKind.LiteralBlockHeader,
            YamlSyntaxKind.FoldedBlockHeader,
            YamlSyntaxKind.BlockScalarContent,
            YamlSyntaxKind.FlowSequenceStart,
            YamlSyntaxKind.FlowMappingStart,
            YamlSyntaxKind.DocumentEnd,
        )) {
            assertTrue(required in kinds, "missing $required")
        }
    }

    /** Vector case materialization.graph-cycle-flow (yaml-v1.json:96-99):
     * the shared self-cycle materializes byte-exactly with the
     * deterministic `&g0` anchor and the terminating `*g0` alias. */
    @Test
    fun materializationGraphCycleFlow() {
        val request = MaterializationRequest.new(
            ProfileId("yaml.1.2-core", 1),
            MaterializationStyleId("yaml.canonical-flow", 1),
        )
        val document = parse("&root [one, *root]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val graph = document.projectGraph()
        val graphResult = materializeGraph(graph, request)
        val complete = graphResult as consema.yaml.GraphMaterializationResult.Complete
        assertEquals(
            "--- &g0 !!seq [!!str \"one\", *g0]\n",
            complete.materialization.document.render().toString(Charsets.UTF_8),
        )
    }

    /** Vector case materialization.value-flow (yaml-v1.json:100-104): a
     * PortableValue Object materializes byte-exactly with explicit standard
     * tags and the explicit-key mapping syntax. */
    @Test
    fun materializationValueFlow() {
        val request = MaterializationRequest.new(
            ProfileId("yaml.1.2-core", 1),
            MaterializationStyleId("yaml.canonical-flow", 1),
        )
        val value = consema.core.PvObject(
            listOf(
                consema.core.Entry(
                    "a",
                    consema.core.PvArray(
                        listOf(
                            consema.core.PvInteger(java.math.BigInteger.ONE),
                            consema.core.PvBoolean(true),
                        ),
                    ),
                ),
            ),
        )
        val result = materializeValue(value, request)
        val complete = result as MaterializationResult.Complete
        assertEquals(
            "--- !!map {? !!str \"a\" : !!seq [!!int \"1\", !!bool \"true\"]}\n",
            complete.materialization.document.render().toString(Charsets.UTF_8),
        )
    }

    /** Vector case edit.scalar-atomic (yaml-v1.json:106-109): a semantic
     * scalar replacement preserves the plain style and untouched bytes
     * byte-exactly with exactly one replacement. */
    @Test
    fun editScalarAtomic() {
        val source = "# keep\na: 1\nb: two\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val value = document.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .semanticScalar(
                value.nodeRef(),
                consema.core.PvInteger(java.math.BigInteger.valueOf(2)),
                RepresentationPolicy.PreserveCompatible,
            )
            .build()
        val commit = document.commit(transaction)
        assertEquals("# keep\na: 2\nb: two\n", commit.document.render().toString(Charsets.UTF_8))
        assertEquals(1, commit.sourcePatch.replacements().size)
    }

    /** Vector case edit.anchor-rename (yaml-v1.json:110-114): renaming an
     * anchor updates its exact dependent alias in one transaction. */
    @Test
    fun editAnchorRename() {
        val source = "first: &x [one]\ncopy: *x\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val anchor = document.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .renameAnchor(anchor.anchorNodeRef()!!, "renamed")
            .build()
        val commit = document.commit(transaction)
        assertEquals(
            "first: &renamed [one]\ncopy: *renamed\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(2, commit.sourcePatch.replacements().size)
    }

    /** Vector case edit.structural-insert (yaml-v1.json:115-119): canonical
     * flow fragments insert into flow collections with the exact
     * `, fragment` separator and explicit-key mapping syntax. */
    @Test
    fun editStructuralInsert() {
        val source = "seq: [one, two]\nmap: {a: 1}\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val root = document.document(0)!!.root()
        val seq = root.mappingEntry(0)!!.value()
        val map = root.mappingEntry(1)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .insertSequenceElement(
                seq.nodeRef(),
                consema.core.PvBoolean(true),
                consema.document.AssociationPlacement.Before(seq.sequenceItem(1)!!.nodeRef()),
            )
            .insertMappingEntry(
                map.nodeRef(),
                consema.core.PvString("b"),
                consema.core.PvInteger(java.math.BigInteger.valueOf(2)),
                consema.document.AssociationPlacement.End,
            )
            .build()
        val commit = document.commit(transaction)
        assertEquals(
            "seq: [one, !!bool \"true\", two]\nmap: {a: 1, ? !!str \"b\" : !!int \"2\"}\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** Vector case edit.anchor-dependency (yaml-v1.json:120-124): removing
     * the anchored definition while a live alias remains is rejected with
     * yaml.edit.anchor-dependency@1 (RFC 0007 §12 anchor-safe rules). */
    @Test
    fun editAnchorDependency() {
        val source = "seq:\n  - &x one\ncopy: *x\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val seq = document.document(0)!!.root().mappingEntry(0)!!.value()
        val element = seq.sequenceItem(0)!!
        val transaction = EditTransactionBuilder.new(document)
            .removeSequenceElement(element.nodeRef())
            .build()
        val error = assertFailsWith<EditFailureException> { document.commit(transaction) }
        assertEquals(EditFailure.AnchorDependency, error.failure)
        assertEquals("yaml.edit.anchor-dependency@1", error.failure.code)
    }
}
