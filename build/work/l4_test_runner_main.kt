import java.lang.reflect.Method
fun main() {
    val classes = listOf(
        "consema.conformance.ConformanceRunnerTest",
        "consema.core.PvceGoldenTest",
        "consema.graph.PgceGoldenTest",
        "consema.protocol.RegistryTest",
        "consema.protocol.TransportTest",
        "document.DigestTest",
        "document.EditPlanTest",
        "document.LimitsTest",
        "document.MaterializationTest",
        "document.PatchTest",
        "document.SourceTest",
        "document.StructuralTest",
        "document.TestHex",
        "document.UntouchedProofTest",
        "hcl.EditTest",
        "hcl.ExpressionSyntaxTest",
        "hcl.FormationGoldenTest",
        "hcl.FormationMatrixTest",
        "hcl.MaterializationTest",
        "hcl.NoEvaluationTest",
        "hcl.ProjectionTest",
        "hcl.QueryTest",
        "hcl.TestFixtures",
        "hcl.TfvarsTest",
        "ini.DuplicateGroupTest",
        "ini.EditTest",
        "ini.FormationGoldenTest",
        "ini.MaterializationTest",
        "ini.ProfileDialectTest",
        "ini.ProjectionTest",
        "ini.QueryTest",
        "json.EditRoundTripTest",
        "json.FormationClosureTest",
        "json.GoldenTranscriptionTest",
        "json.ProjectionFidelityTest",
        "json.QueryTest",
        "plist.BinaryHardeningTest",
        "plist.ConversionTest",
        "plist.EditTest",
        "plist.GoldenTranscriptionTest",
        "plist.XmlBinaryEquivalenceTest",
        "properties.DialectAndEncodingTest",
        "properties.EditTest",
        "properties.GoldenTranscriptionTest",
        "properties.MaterializationTest",
        "properties.ProjectionTest",
        "properties.QueryTest",
        "properties.ResourceTest",
        "toml.TestFixtures",
        "toml.TomlEditTest",
        "toml.TomlFormationTest",
        "toml.TomlMaterializationTest",
        "toml.TomlOperationsTest",
        "toml.TomlProjectionTest",
        "toml.TomlQueryTest",
        "xml.EditTest",
        "xml.FormationGoldenTest",
        "xml.MaterializationTest",
        "xml.ProjectionTest",
        "xml.QueryTest",
        "xml.SecurityAndSpanTest",
        "yaml.EditTest",
        "yaml.FormationTest",
        "yaml.GoldenTranscriptionTest",
        "yaml.ProjectionFidelityTest",
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
