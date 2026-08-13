// Shared test helpers for the HCL family tests: value-record construction
// and the golden-source transcription constants.
//
// Data authority: conformance/vectors/hcl-v1.json (the case inputs are
// transcribed verbatim; the case id is cited on every use). This file runs
// in the verified toolchain gate (kotlin-gates gradlew test / the scripts/
// kotlin-verify-*.ps1 direct path): the toolchain is verified and this file
// is executed.

package hcl

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import java.math.BigInteger

/** One `hcl.body@1` record with the given items. */
fun bodyRecord(items: List<PortableValue>): PvObject =
    PvObject(
        listOf(
            Entry("record", PvString("hcl.body@1")),
            Entry("items", PvArray(items)),
        ),
    )

/** One attribute item of the value-record spelling pinned by the vectors. */
fun attributeItem(name: String, value: PortableValue): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("attribute")),
            Entry("name", PvString(name)),
            Entry("value", value),
        ),
    )

/** One block item of the value-record spelling pinned by the vectors. */
fun blockItem(type: String, labels: List<String>, body: PvObject): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("block")),
            Entry("type", PvString(type)),
            Entry("labels", PvArray(labels.map { PvString(it) })),
            Entry("body", body),
        ),
    )

/** The value-record spelling of one string value. */
fun stringValue(text: String): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("string")),
            Entry("text", PvString(text)),
        ),
    )

/** The value-record spelling of one integer value. */
fun integerValue(value: Long): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("integer")),
            Entry("value", PvInteger(BigInteger.valueOf(value))),
        ),
    )

/** The value-record spelling of one real value: the canonical decimal
 * `coefficient × 10^exponent` (RFC 0014 §6) of one decimal spelling. */
fun realValue(value: String): PvObject {
    val dot = value.indexOf('.')
    val digits = value.removeRange(dot, dot + 1)
    val exponent = -(value.length - dot - 1)
    return PvObject(
        listOf(
            Entry("kind", PvString("real")),
            Entry(
                "value",
                PvDecimal.of(BigInteger(digits), BigInteger.valueOf(exponent.toLong())),
            ),
        ),
    )
}

/** The value-record spelling of one boolean value. */
fun booleanValue(value: Boolean): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("boolean")),
            Entry("value", PvBoolean(value)),
        ),
    )

/** The value-record spelling of the null value. */
fun nullValue(): PvObject = PvObject(listOf(Entry("kind", PvString("null"))))

/** The value-record spelling of one tuple value. */
fun tupleValue(elements: List<PortableValue>): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("tuple")),
            Entry("elements", PvArray(elements)),
        ),
    )

/** The value-record spelling of one object value with ordered entries. */
fun objectValue(entries: List<Pair<String, PortableValue>>): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("object")),
            Entry("entries", entryMapping(entries)),
        ),
    )

/** The value-record spelling of one expression value. */
fun expressionValue(kind: String, text: String): PvObject =
    PvObject(
        listOf(
            Entry("kind", PvString("expression")),
            Entry(
                "expression",
                PvObject(
                    listOf(
                        Entry("record", PvString("hcl.expression@1")),
                        Entry("kind", PvString(kind)),
                        Entry("text", PvString(text)),
                    ),
                ),
            ),
        ),
    )

/** The raw typed member spelling the projection publishes (RFC 0014 §9). */
fun rawInteger(value: Long): PvInteger = PvInteger(BigInteger.valueOf(value))

/** Golden sources transcribed from conformance/vectors/hcl-v1.json. */
object Golden {
    /** hcl.native-formation.body-basic (hcl-v1.json:9-20). */
    const val BODY_BASIC: String =
        "region = \"us-east-1\"\n\n" +
            "server \"web\" \"1\" {\n  port = 8080\n}\n\n" +
            "plain {\n  x = 1\n}\n\n" +
            "oneline { y = 2 }\n\n" +
            "shared = 1\nshared \"b\" {\n  z = 3\n}"

    /** hcl.native-formation.comments (hcl-v1.json:21-32). */
    const val COMMENTS: String =
        "# leading hash\na = 1 // trailing slash\nb = 2 /* inline */\n" +
            "c = 3 /* spans\nlines */\nd = 4 # comment terminates the attribute\n"

    /** hcl.native-formation.heredoc (hcl-v1.json:57-68). */
    const val HEREDOC: String =
        "plain = <<EOT\nalpha\nbeta\nEOT\n" +
            "indented = <<-EOT\n    one\n      two\n    EOT\n" +
            "notclosing = <<EOT\nEOT has content\nEOT\n" +
            "trimmed = <<EOT\ntail\nEOT  \n"

    /** hcl.native-formation.templates (hcl-v1.json:69-80). */
    const val TEMPLATES: String =
        "a = \"plain\"\n" +
            "b = \"esc: \\n \\t \\\" \\\\\"\n" +
            "c = \"uni: \\u0041 \\U0001F600\"\n" +
            "d = \"interp: \${x}\"\n" +
            "e = \"strips: \${~ x ~}\"\n" +
            "f = \"if: %{ if x }yes%{ endif }\"\n" +
            "g = \"for2: %{ for k, v in m }\${k}%{ endfor }\"\n" +
            "h = \"for1: %{ for x in list }\${x}%{ endfor }\"\n" +
            "i = \"lit: \$\${x} %%{y}\"\n"

    /** hcl.native-formation.expression-matrix (hcl-v1.json:45-56). */
    const val EXPRESSION_MATRIX: String =
        "int = 42\nreal = 1.5\nexp = 1e3\nneg = -7\nyes = true\nno = false\n" +
            "nil = null\nstr = \"hello\"\nescaped = \"line\\nbreak\"\n" +
            "interp = \"value: \${name}\"\ncall = max(1, 2, 3)\nv = my_var\n" +
            "bin = 1 + 2 * 3\ncmp = a == b\nlogic = a && b || !c\n" +
            "cond = x ? \"yes\" : \"no\"\ntup = [1, \"two\", true]\n" +
            "obj = {key = 1, \"quoted\" = 2}\nparen = (1 + 2) * 3\n"

    /** hcl.native-formation.production-shape (hcl-v1.json:494-504). */
    const val PRODUCTION_SHAPE: String =
        "terraform {\n  required_version = \">= 1.5\"\n}\n\n" +
            "variable \"region\" {\n  type    = string\n  default = \"us-east-1\"\n}\n\n" +
            "locals {\n  common_tags = {\n    Env = \"prod\"\n  }\n}\n\n" +
            "resource \"aws_instance\" \"web\" {\n" +
            "  ami           = \"ami-0abcdef1234567890\"\n" +
            "  instance_type = \"t3.micro\"\n  count         = 2\n" +
            "  tags          = local.common_tags\n}\n\n" +
            "module \"vpc\" {\n  source  = \"./modules/vpc\"\n" +
            "  cidr    = \"10.0.0.0/16\"\n  enabled = true\n}\n"

    /** hcl.tfvars-formation.production-shape (hcl-v1.json:554-564). */
    const val TFVARS_PRODUCTION: String =
        "# Production-shaped terraform.tfvars fixture\n" +
            "region = \"us-east-1\"\ninstance_type = \"t3.micro\"\n" +
            "ami = \"ami-0abcdef1234567890\"\ncount = 2\nmonitoring = true\n" +
            "tags = {\n  Name = \"web-server\"\n  Env  = \"prod\"\n}\n" +
            "security_groups = [\n  \"sg-0123456789abcdef0\",\n  \"sg-1123456789abcdef0\",\n]\n" +
            "launch_template = {\n  id      = \"lt-0123456789abcdef0\"\n  version = 1\n}\n"

    /** hcl.query.native-body-walk (hcl-v1.json:566-572). */
    const val QUERY_NATIVE: String =
        "region = \"us-east-1\"\nserver \"web\" {\n  port = 8080\n}\ncount = 3\n"

    /** hcl.projection.literal-complete-record (hcl-v1.json:889-894). */
    const val PROJECTION_RECORD: String =
        "name = \"consema\"\ncount = 42\nratio = 1.50\nbig = 1e3\nsmall = 15e-1\n" +
            "enabled = true\nnothing = null\ntags = [\"a\", \"b\"]\n" +
            "labels = { env = \"prod\" }\ndups = { a = 1, a = 2 }\n" +
            "numkeys = { 1 = \"one\", 2 = \"two\" }\nnested = { \"x\" = { y = [1, 2] } }\n"

    /** hcl.materialization.canonical-document (hcl-v1.json:1153-1282). */
    const val CANONICAL_RENDER: String =
        "name = \"hello\"\nescaped = \"a\\nb\\t\\\"c\\\\d\"\ncount = 42\nratio = 1.5\n" +
            "enabled = true\nnothing = null\ntags = [\n  \"a\",\n  \"b\"\n]\n" +
            "labels = {\n  env = \"prod\"\n}\nempty_tuple = []\nempty_obj = {}\n" +
            "server \"web\" \"1\" {\n  port = 8080\n}\n"

    /** hcl.edit.attribute-operations (hcl-v1.json:1462-1466). */
    const val EDIT_ATTRIBUTES_SOURCE: String = "region = \"us-east-1\"\ncount = 2\nenabled = true\n"

    /** hcl.edit.block-operations (hcl-v1.json:1506-1510). */
    const val EDIT_BLOCKS_SOURCE: String = "server \"web\" {\n  port = 8080\n}\n"

    /** hcl.edit.dry-run-equivalence (hcl-v1.json:2047-2051). */
    const val EDIT_DRY_RUN_SOURCE: String = "region = \"us-east-1\"\ncount = 2\nenabled = true\n"

    /** The projected `hcl.body@1` record of [PROJECTION_RECORD] with the
     * raw typed members of RFC 0014 §9. */
    fun projectionRecord(): PvObject = bodyRecord(
        listOf(
            attributeItem("name", PvString("consema")),
            attributeItem("count", rawInteger(42)),
            attributeItem("ratio", PvDecimal.of(BigInteger("15"), BigInteger("-1"))),
            attributeItem("big", rawInteger(1000)),
            attributeItem("small", PvDecimal.of(BigInteger("15"), BigInteger("-1"))),
            attributeItem("enabled", PvBoolean(true)),
            attributeItem("nothing", PvNull),
            attributeItem("tags", PvArray(listOf(PvString("a"), PvString("b")))),
            attributeItem("labels", objectRaw(listOf("env" to PvString("prod")))),
            attributeItem("dups", objectRaw(listOf("a" to rawInteger(1), "a" to rawInteger(2)))),
            attributeItem("numkeys", objectRaw(listOf("1" to PvString("one"), "2" to PvString("two")))),
            attributeItem(
                "nested",
                objectRaw(
                    listOf(
                        "x" to objectRaw(listOf("y" to PvArray(listOf(rawInteger(1), rawInteger(2))))),
                    ),
                ),
            ),
        ),
    )

    /** One raw object member with ordered entries (EntryMapping on the
     * wire; the raw typed member form of RFC 0014 §9). */
    fun objectRaw(entries: List<Pair<String, PortableValue>>): PvObject =
        PvObject(
            listOf(
                Entry("kind", PvString("object")),
                Entry("entries", entryMapping(entries)),
            ),
        )
}

/** One ordered string-key EntryMapping with duplicate preservation. */
fun entryMapping(entries: List<Pair<String, PortableValue>>): consema.core.PvEntryMapping {
    val builder = consema.core.EntryMappingBuilder()
    for ((key, value) in entries) {
        builder.push(PvString(key), value)
    }
    return builder.build()
}
