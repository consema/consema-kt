import java.lang.reflect.Method
fun main() {
    val classes = listOf(
        "consema.CapabilityParityTest",
        "consema.conformance.ConformanceRunnerTest",
        "consema.ConvertFacadeTest",
        "consema.core.PvceGoldenTest",
        "consema.DocumentFacadeTest",
        "consema.graph.PgceGoldenTest",
        "consema.ini.IniQuoteStyle",
        "consema.protocol.RecordsV5V6Test",
        "consema.protocol.RegistryTest",
        "consema.protocol.TransportTest",
        "consema.RegistryFacadeTest",
        "document.DigestTest",
        "document.EditPlanTest",
        "document.LimitsTest",
        "document.MaterializationTest",
        "document.PatchTest",
        "document.SourceTest",
        "document.StructuralTest",
        "document.UntouchedProofTest",
        "hcl.EditTest",
        "hcl.ExpressionSyntaxTest",
        "hcl.FormationGoldenTest",
        "hcl.FormationMatrixTest",
        "hcl.MaterializationTest",
        "hcl.NoEvaluationTest",
        "hcl.ProjectionTest",
        "hcl.QueryTest",
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
    var skipped = 0
    val failures = mutableListOf<String>()
    for (name in classes) {
        val clazz = try {
            Class.forName(name)
        } catch (e: Throwable) {
            skipped++
            println("SKIP $name (class not loadable: $e)")
            continue
        }
        val methods: List<Method> = clazz.methods.filter { it.isAnnotationPresent(kotlin.test.Test::class.java) }
        if (methods.isEmpty()) {
            skipped++
            println("SKIP $name (no test methods)")
            continue
        }
        val instance = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            skipped++
            println("SKIP $name (no default constructor: $e)")
            continue
        }
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
    println("=== $passed passed, $failed failed, $skipped skipped-classes ===")
    if (failures.isNotEmpty()) {
        println("Failures:")
        failures.forEach { println("  $it") }
    }
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
