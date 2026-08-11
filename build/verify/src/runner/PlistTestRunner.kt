// Temporary test runner for the L3 plist gate (verification scaffolding in
// kotlin/build/verify only — not part of the repository sources).

import java.lang.reflect.Method

fun main() {
    val classes = listOf(
        "plist.EditTest",
        "plist.ConversionTest",
        "plist.GoldenTranscriptionTest",
        "plist.BinaryHardeningTest",
        "plist.XmlBinaryEquivalenceTest",
    )
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()
    for (name in classes) {
        val clazz = Class.forName(name)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val methods: List<Method> = clazz.methods.filter { it.isAnnotationPresent(kotlin.test.Test::class.java) }
        for (method in methods) {
            try {
                method.invoke(instance)
                passed++
                println("PASS $name.${method.name}")
            } catch (t: Throwable) {
                failed++
                val cause = t.cause ?: t
                println("FAIL $name.${method.name}: $cause")
                failures.add("$name.${method.name}: $cause")
            }
        }
    }
    println("=== $passed passed, $failed failed ===")
    if (failures.isNotEmpty()) {
        println("Failures:")
        failures.forEach { println("  $it") }
    }
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
