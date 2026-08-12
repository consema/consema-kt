// Consema Kotlin implementation — L0 scaffold.
// Authority: docs/multi-language-implementation-plan.md §0.2/§1: Kotlin 2.2.0
// on JVM 17, single module, zero runtime dependencies (test frameworks
// excepted, mirroring the go.mod zero-require precedent).
plugins {
    kotlin("jvm") version "2.2.0"
    `maven-publish`
    `signing`
}

// Maven coordinates: dev.consema:consema-kotlin:<version>.
group = "dev.consema"

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

// Maven Central publishing (top-tier bar; credentials are user-side actions,
// see RELEASING.md §2). Publishing target: the Sonatype Central Portal
// (central.sonatype.com, the 2025+ publishing path) — deploy endpoint with
// HTTP Basic auth (username = portal token name, password = token value);
// staging is handled by the portal. The legacy s01.oss.sonatype.org
// staging workflow is deliberately not wired (new projects no longer get
// legacy staging).
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Consema Kotlin SDK")
                description.set("Kotlin implementation of the language-neutral Consema configuration-processing contracts (RFC 0016; docs/multi-language-implementation-plan.md)")
                url.set("https://github.com/consema/consema-kt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/consema/consema-kt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/consema/consema-kt.git")
                    url.set("https://github.com/consema/consema-kt")
                }
                developers {
                    developer {
                        id.set("consema")
                        name.set("Consema maintainers")
                        url.set("https://github.com/consema")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "central"
            url = uri("https://central.sonatype.com/api/v1/publisher/deploy")
            credentials {
                username = providers.environmentVariable("OSSRH_USERNAME").orElse("").get()
                password = providers.environmentVariable("OSSRH_PASSWORD").orElse("").get()
            }
        }
    }
}

// Signing placeholder: Maven Central requires every artifact to be signed
// with a PGP key. Credentials come from the SIGNING_KEY (ASCII-armored
// private key) / SIGNING_PASSWORD environment variables — set them in CI
// from repository secrets (release.yml maps them). When absent (local
// builds, or CI before the user configures the secrets) signing is skipped
// so `gradle build` keeps working; publishing to the portal then fails at
// the portal's signature check — that is the intended tripwire, the
// release.yml job is documented to require the credentials.
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
