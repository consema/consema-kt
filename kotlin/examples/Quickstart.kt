// Consema SDK quickstart example (Kotlin): one JSON document through the
// parse -> query -> edit -> render chain — the root README "快速开始" snippet
// as a committed, CI-gated example (the snippet there and this file must
// stay in sync; the CI examples job compiles and runs this file).
//
// Scenario: parse `{"a":1,"b":{"c":2}}` under `json.strict` losslessly
// (render() is byte-identical to the source), read `b.c` from the native
// semantic tree, semantically replace `b.c` with 42
// (CanonicalForProfile — bytes outside the edit stay untouched), and
// render the edited document.
//
// Run from the kotlin/ directory of the consema-kt repository (the
// repository root holds the language-neutral docs; the Kotlin sources live
// under kotlin/ — from the repository root the paths below do not exist).
// kotlinc must compile the main sources and the example together;
// -J-Xmx2g is required — the default 512 MiB heap exhausts on the
// 163-main-file K2 compile, see kotlin/gradle.properties:
//   kotlinc -J-Xmx2g -jvm-target 17 -d out src/main/kotlin examples/Quickstart.kt
//   java -cp "out;<kotlinc>\lib\kotlin-stdlib.jar" consema.examples.QuickstartKt
//
// CI gate: .github/workflows/ci-kotlin.yml (examples job) compiles the main
// sources + SdkChain.kt + Quickstart.kt together and runs both mains.
package consema.examples

import consema.core.PvInteger
import consema.document.ProfileId
import consema.json.EditTransactionBuilder
import consema.json.JsonValue
import consema.json.RepresentationPolicy
import consema.json.SemanticAvailability
import consema.json.commit
import consema.parseDocument
import java.math.BigInteger

// 原生语义树成员查找（查询助手；完整操作符查询见 SdkChain 示例）。
fun member(value: JsonValue, name: String): JsonValue = when (val availability = value.objectMembers()) {
    is SemanticAvailability.Available -> {
        val members = availability.value ?: error("not an object")
        members.firstOrNull { m ->
            when (val n = m.name()) {
                is SemanticAvailability.Available -> n.value == name
                is SemanticAvailability.Unavailable -> false
            }
        }?.value() ?: error("member '$name' not found")
    }
    is SemanticAvailability.Unavailable -> error("semantics unavailable: ${availability.reason}")
}

fun main() {
    // 1. parse：json.strict 无损解析，render() 与源字节逐字节一致
    val document = parseDocument("""{"a":1,"b":{"c":2}}""".toByteArray(), ProfileId("json.strict", 1))
    val json = document.asJson() ?: error("not JSON")
    // 2. query：原生语义树读 `b.c`
    val c = member(member(json.root(), "b"), "c")
    // 3. edit：`b.c` 语义替换为 42（CanonicalForProfile），编辑外字节原样保留
    val transaction = EditTransactionBuilder.new(json)
        .semanticScalar(c.nodeRef(), PvInteger(BigInteger.valueOf(42)), RepresentationPolicy.CanonicalForProfile)
        .build()
    val edited = json.commit(transaction).document
    // 4. render：输出 {"a":1,"b":{"c":42}}
    println(String(edited.render(), Charsets.UTF_8))
}
