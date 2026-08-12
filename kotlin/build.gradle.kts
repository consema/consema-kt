// Consema Kotlin implementation — L0 scaffold.
// Authority: docs/multi-language-implementation-plan.md §0.2/§1: Kotlin 2.2.0
// on JVM 17, single module, zero runtime dependencies (test frameworks
// excepted, mirroring the go.mod zero-require precedent).
plugins {
    kotlin("jvm") version "2.2.0"
}

// rootProject version (rides the release train; CI check-version-consistency
// asserts README.md parity).
version = "1.0.0-rc.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // kotlin("test") maps to kotlin.test assertions; JUnit5 is the runner.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
