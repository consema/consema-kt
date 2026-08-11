// Temporary debug harness (build/work only).
import consema.document.SourceEncoding
import consema.properties.ProjectionRequest
import consema.properties.ProjectionResult
import consema.properties.parseReader
import consema.properties.project

fun main() {
    val input = "a\\ key=one\\\n two\\u0021\na\\ key=last\n".toByteArray(Charsets.UTF_8)
    val doc = parseReader(input, SourceEncoding.Utf8)
    println("PARSE OK props=${doc.propertyEntities.size}")
    val result = doc.project(ProjectionRequest.bestExactEntryMapping())
    when (result) {
        is ProjectionResult.Complete -> println("PROJECTION COMPLETE")
        is ProjectionResult.Failed -> {
            println("PROJECTION FAILED")
            result.attempt.diagnostics.forEach { println("  diag: ${it.code}") }
        }
    }
}
