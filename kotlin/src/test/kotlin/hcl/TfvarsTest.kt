// `hcl.tfvars@1` dialect coverage (RFC 0014 §5) transcribed from
// conformance/vectors/hcl-v1.json hcl.tfvars-formation.* cases.
//
// The tfvars profile is `hcl.native@1` under one structural restriction:
// the top-level body admits attributes only, never blocks. Terraform's
// static-only evaluation rule and undeclared-variable rejection are
// application-layer policy and are never replicated at formation (RFC 0014
// §5, hard gate 3).
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package hcl

import consema.document.FormationStatus
import consema.hcl.HclProfile
import consema.hcl.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TfvarsTest {

    private fun tfvars(source: String) =
        parse(source.toByteArray(Charsets.UTF_8), HclProfile.TFVARS_V1)

    private fun hasCode(document: consema.hcl.HclDocument, code: String): Boolean =
        document.diagnostics().any { it.code == code }

    /** Vector case hcl.tfvars-formation.attributes-only (hcl-v1.json:506-
     * 516): attribute-only documents are Complete with the byte-exact
     * render. */
    @Test
    fun attributesOnly() {
        val source = "region = \"us-east-1\"\ncount = 3\nratio = 0.5\nenabled = true\n" +
            "tags = [\"a\", \"b\"]\nlabels = {\n  env = \"prod\"\n}\n"
        val document = tfvars(source)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        assertEquals(
            listOf("region", "count", "ratio", "enabled", "tags", "labels"),
            document.rootBody().attributes().map { it.name() },
        )
        assertTrue(document.rootBody().blocks().isEmpty())
    }

    /** Vector case hcl.tfvars-formation.block-rejected (hcl-v1.json:518-
     * 528): a top-level block makes formation Recovered with one
     * `hcl.tfvars.block-not-allowed@1` diagnostic, and the rejected block
     * remains a native item of the Recovered document (RFC 0014 §5, §3). */
    @Test
    fun blockRejected() {
        val source = "region = \"us-east-1\"\nblock \"x\" {\n  a = 1\n}\n"
        val document = tfvars(source)

        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(hasCode(document, "hcl.tfvars.block-not-allowed@1"))
        // The rejected block stays a native item (RFC 0014 §3, §7).
        assertEquals(1, document.rootBody().blocks().size)
        assertEquals("block", document.rootBody().blocks()[0].blockType())
        // The tfvars gate never contributes an error region (RFC 0014 §5).
        assertEquals(0, document.errorRegions().size)
    }

    /** Vector case hcl.tfvars-formation.expression-grammar-full (hcl-v1.json:
 *): the full native expression grammar is admitted inside tfvars
     * values — function calls, traversals, and interpolations are native
     * facts, never evaluated (RFC 0014 §5). */
    @Test
    fun expressionGrammarFull() {
        val source = "computed = max(1, 2)\nref = var.other\njoined = \"prefix-\${var.suffix}\"\n"
        val document = tfvars(source)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        assertEquals(3, document.rootBody().size())
    }

    /** Vector case hcl.tfvars-formation.duplicate-attribute (hcl-v1.json:
 *): the per-body duplicate-attribute rule applies unchanged. */
    @Test
    fun duplicateAttribute() {
        val document = tfvars("a = 1\na = 2\n")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(hasCode(document, "hcl.parse.duplicate-attribute@1"))
    }

    /** Vector case hcl.tfvars-formation.production-shape (hcl-v1.json:554-
     * 564): a production-shaped terraform.tfvars fixture forms Complete. */
    @Test
    fun productionShape() {
        val document = tfvars(Golden.TFVARS_PRODUCTION)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(Golden.TFVARS_PRODUCTION, document.render().toString(Charsets.UTF_8))
        assertEquals(
            listOf("region", "instance_type", "ami", "count", "monitoring", "tags", "security_groups", "launch_template"),
            document.rootBody().attributes().map { it.name() },
        )
    }

    /** RFC 0014 §5 closure: a Complete tfvars document has no nested body,
     * because nested bodies exist only inside blocks. */
    @Test
    fun completeTfvarsHasNoNestedBodies() {
        val document = tfvars("a = 1\nb = [1, 2]\nc = { x = \"y\" }\n")
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.rootBody().blocks().isEmpty())
    }
}
