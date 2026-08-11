// Golden transcriptions of conformance/vectors/hcl-v1.json formation cases.
//
// Each test transcribes one vector case (input.source / expected.*) VERBATIM
// from conformance/vectors/hcl-v1.json and asserts the language-neutral
// facts the Rust/Go differential runners assert
// (crates/consema-conformance/src/hcl_v1.rs:412-422: status exact, the
// expected diagnostic code present; the render is byte-exact). The case id
// is cited on every test.
//
// This file is an intent document: the toolchain is not verified yet, so
// these tests pin the intent; they run at the L3 verification gate.

package hcl

import consema.document.FormationStatus
import consema.hcl.HclProfile
import consema.hcl.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FormationGoldenTest {

    /** Vector case hcl.native-formation.body-basic (hcl-v1.json:9-20):
     * attributes, multi-label blocks, one-line blocks, attribute/block name
     * sharing; Complete with the byte-exact render. */
    @Test
    fun bodyBasic() {
        val document = parse(Golden.BODY_BASIC.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(Golden.BODY_BASIC, document.render().toString(Charsets.UTF_8))
        val body = document.rootBody()
        // region, server, plain, oneline, shared attribute, shared block.
        assertEquals(6, body.size())
        val names = body.attributes().map { it.name() }
        assertEquals(listOf("region", "shared"), names)
        val blocks = body.blocks()
        assertEquals(listOf("server", "plain", "oneline", "shared"), blocks.map { it.blockType() })
        assertEquals(listOf("web", "1"), blocks[0].labels().map { it.text() })
        // Both labels are quoted literal strings (RFC 0014 §4.2).
        assertTrue(blocks[0].labels().all { it.quoted() })
        assertEquals(listOf("port"), blocks[0].body().attributes().map { it.name() })
        assertEquals(listOf("y"), blocks[2].body().attributes().map { it.name() })
        assertTrue(document.diagnostics().isEmpty())
    }

    /** Vector case hcl.native-formation.comments (hcl-v1.json:21-32): all
     * four comment forms, comment-as-newline, and an inline comment spanning
     * lines; Complete with the byte-exact render. */
    @Test
    fun comments() {
        val document = parse(Golden.COMMENTS.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(Golden.COMMENTS, document.render().toString(Charsets.UTF_8))
        assertEquals(
            listOf("a", "b", "c", "d"),
            document.rootBody().attributes().map { it.name() },
        )
        val kinds = document.losslessSyntaxKinds()
        assertTrue(consema.hcl.HclSyntaxKind.LineComment in kinds)
        assertTrue(consema.hcl.HclSyntaxKind.InlineComment in kinds)
    }

    /** Vector case hcl.native-formation.heredoc (hcl-v1.json:57-68): both
     * heredoc modes, marker-with-content lines, and TrimSpace closing-line
     * matching; Complete with the byte-exact render. */
    @Test
    fun heredoc() {
        val document = parse(Golden.HEREDOC.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(Golden.HEREDOC, document.render().toString(Charsets.UTF_8))
        assertEquals(
            listOf("plain", "indented", "notclosing", "trimmed"),
            document.rootBody().attributes().map { it.name() },
        )
        val plain = document.rootBody().attributes()[0].expression()
        val kind = plain.kind() as consema.hcl.HclExpressionKind.Template
        assertEquals(consema.hcl.HclHeredocMode.Plain, kind.heredoc!!.mode)
        assertEquals("EOT", kind.heredoc.marker)
        assertEquals(2, kind.parts.size)
        val indented = document.rootBody().attributes()[1].expression()
        val indentedKind = indented.kind() as consema.hcl.HclExpressionKind.Template
        assertEquals(consema.hcl.HclHeredocMode.StripIndent, indentedKind.heredoc!!.mode)
        // `<<-` stripping applies only when the literal value is read: the
        // heredoc content is "    one\n      two\n" and the minimum indent
        // is 4, so the stripped literal is "one\n  two\n" (the content
        // includes the newline before the closing marker line).
        val literal = consema.hcl.literalValue(indented.expressionValue())
        assertEquals(
            consema.hcl.HclLiteralValue.String("one\n  two\n"),
            literal,
        )
        val notclosing = document.rootBody().attributes()[2].expression()
        val notclosingKind = notclosing.kind() as consema.hcl.HclExpressionKind.Template
        // The heredoc content is everything between the introducer newline
        // and the closing marker line, including the final newline.
        assertEquals(
            "EOT has content\n",
            (notclosingKind.parts[0] as consema.hcl.HclTemplatePart.Literal).text,
        )
    }

    /** Vector case hcl.native-formation.templates (hcl-v1.json:69-80):
     * escapes, `\u`/`\U`, interpolations with strip markers, all five
     * directives, the single-identifier for-directive, and the `$${`/`%%{`
     * escapes; Complete with the byte-exact render. */
    @Test
    fun templates() {
        val document = parse(Golden.TEMPLATES.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(Golden.TEMPLATES, document.render().toString(Charsets.UTF_8))
        val attributes = document.rootBody().attributes()
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"), attributes.map { it.name() })

        val b = attributes[1].expression().kind() as consema.hcl.HclExpressionKind.Template
        assertEquals("esc: \n \t \" \\", (b.parts[0] as consema.hcl.HclTemplatePart.Literal).text)

        val c = attributes[2].expression().kind() as consema.hcl.HclExpressionKind.Template
        assertEquals("uni: A 😀", (c.parts[0] as consema.hcl.HclTemplatePart.Literal).text)

        val d = attributes[3].expression().kind() as consema.hcl.HclExpressionKind.Template
        assertTrue(d.parts[1] is consema.hcl.HclTemplatePart.Interpolation)

        val f = attributes[5].expression().kind() as consema.hcl.HclExpressionKind.Template
        val directive = f.parts[1] as consema.hcl.HclTemplatePart.Directive
        assertEquals(consema.hcl.HclDirectiveKind.If::class, directive.kind::class)

        val g = attributes[6].expression().kind() as consema.hcl.HclExpressionKind.Template
        val forDirective = g.parts[1] as consema.hcl.HclTemplatePart.Directive
        val forKind = forDirective.kind as consema.hcl.HclDirectiveKind.For
        assertEquals("k", forKind.intro.key)
        assertEquals("v", forKind.intro.value)

        // The single-identifier for-directive `%{ for x in list }` is valid
        // (RFC 0014 §12 D-7).
        val h = attributes[7].expression().kind() as consema.hcl.HclExpressionKind.Template
        val forOne = (h.parts[1] as consema.hcl.HclTemplatePart.Directive).kind
        assertEquals(null, (forOne as consema.hcl.HclDirectiveKind.For).intro.key)

        val i = attributes[8].expression().kind() as consema.hcl.HclExpressionKind.Template
        val literalI = i.parts[0] as consema.hcl.HclTemplatePart.Literal
        assertEquals("lit: \${x} %{y}", literalI.text)
    }

    /** Vector case hcl.native-formation.expression-matrix (hcl-v1.json:
     * 45-56): every expression family forms Complete with the byte-exact
     * render. */
    @Test
    fun expressionMatrix() {
        val document = parse(Golden.EXPRESSION_MATRIX.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(Golden.EXPRESSION_MATRIX, document.render().toString(Charsets.UTF_8))
        assertEquals(19, document.rootBody().size())
    }

    /** Vector case hcl.native-formation.production-shape (hcl-v1.json:494-
     * 504): a Terraform-shaped `.tf` document forms Complete with the
     * byte-exact render. */
    @Test
    fun productionShape() {
        val document = parse(Golden.PRODUCTION_SHAPE.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(Golden.PRODUCTION_SHAPE, document.render().toString(Charsets.UTF_8))
        val blocks = document.rootBody().blocks()
        assertEquals(listOf("terraform", "variable", "locals", "resource", "module"), blocks.map { it.blockType() })
        assertEquals(listOf("region"), blocks[1].labels().map { it.text() })
        assertEquals(listOf("aws_instance", "web"), blocks[3].labels().map { it.text() })
        val resource = blocks[3]
        assertEquals(
            listOf("ami", "instance_type", "count", "tags"),
            resource.body().attributes().map { it.name() },
        )
    }

    /** Vector case hcl.native-formation.empty-body-eof-termination
     * (hcl-v1.json:1649-1681): an empty source, an EOF-terminated
     * attribute, and EOF-terminated blocks are all Complete. */
    @Test
    fun emptyBodyAndEofTermination() {
        val samples = listOf("", "a = 1", "b {\n}\n", "oneline { y = 2 }")
        val statuses = listOf(
            FormationStatus.Complete,
            FormationStatus.Complete,
            FormationStatus.Complete,
            FormationStatus.Complete,
        )
        for ((source, status) in samples.zip(statuses)) {
            val document = parse(source.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)
            assertEquals(status, document.formationStatus(), "source: $source")
            assertEquals(source, document.render().toString(Charsets.UTF_8))
        }
    }

    /** Vector case hcl.native-formation.constructors (hcl-v1.json:350-361):
     * newline-separated constructors, duplicate object keys, number and
     * quoted keys, `for`-as-key via quoting or parens. */
    @Test
    fun constructors() {
        val source = "nlsep = [\n  1,\n  2,\n]\nobj = {\n  a = 1\n  b = 2\n}\n" +
            "dups = { a = 1, a = 2 }\nnumkey = { 1 = \"one\" }\ncolon = { \"k\" : 3 }\n" +
            "forkey = { \"for\" = 1 }\nparenkey = { (x) = 2 }\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        val dups = document.rootBody().attributes().first { it.name() == "dups" }.expression()
        val dupsKind = dups.kind() as consema.hcl.HclExpressionKind.Object
        assertEquals(2, dupsKind.entries.size)
    }

    /** Vector case hcl.native-formation.for-expressions (hcl-v1.json:362-
     * 372): tuple/object for-expressions with guards and grouping markers
     * are Complete with the byte-exact render. */
    @Test
    fun forExpressions() {
        val source = "ftuple = [for x in list : x * 2]\n" +
            "fobj = {for k, v in map : k => v if v != null}\n" +
            "fgroup = {for k, v in map : k => v...}\n" +
            "fcond = [for x in list : x if x > 0]\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        val fobj = document.rootBody().attributes().first { it.name() == "fobj" }.expression()
        val forKind = fobj.kind() as consema.hcl.HclExpressionKind.ForObject
        assertEquals(false, forKind.grouping)
        assertNotNull(forKind.condition)
        val fgroup = document.rootBody().attributes().first { it.name() == "fgroup" }.expression()
        val groupingKind = fgroup.kind() as consema.hcl.HclExpressionKind.ForObject
        assertEquals(true, groupingKind.grouping)
    }

    /** Vector case hcl.native-formation.traversals-splats (hcl-v1.json:373-
     * 385): traversal roots, attribute/index steps, both splat forms, and
     * keyword traversal roots are Complete. */
    @Test
    fun traversalsAndSplats() {
        val source = "v = foo\nattr = foo.bar\nidx = foo[0]\n" +
            "splat1 = foo.*.bar\nsplat2 = foo[*].bar\nchain = foo[0].bar[*].baz\n" +
            "kwroot = true.bar\nexpridx = foo[1 + 1]\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        val attr = document.rootBody().attributes().first { it.name() == "attr" }.expression()
        val traversal = attr.kind() as consema.hcl.HclExpressionKind.Traversal
        assertEquals(1, traversal.steps.size)
        val splat1 = document.rootBody().attributes().first { it.name() == "splat1" }.expression()
        val splatKind = splat1.kind() as consema.hcl.HclExpressionKind.Traversal
        assertEquals(1, splatKind.steps.size)
    }
}
