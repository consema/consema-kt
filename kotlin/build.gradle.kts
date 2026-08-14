// Consema Kotlin implementation.
// Authority: https://github.com/consema/consema/blob/main/docs/multi-language-implementation-plan.md §0.2/§1: Kotlin 2.2.0
// on JVM 17, single module, zero third-party runtime dependencies (the
// runtime classpath carries only kotlin-stdlib + its transitive
// org.jetbrains:annotations, injected by the Kotlin Gradle plugin; every
// declared configuration is test-scoped, mirroring the go.mod zero-require
// precedent).
plugins {
    kotlin("jvm") version "2.2.0"
    `maven-publish`
    `signing`
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

// Line-coverage gate (P2 item "Coverage", kotlin/README.md): kover wires
// the IntelliJ engine into the JUnit test run and verifies the 60% line
// threshold (the documented initial gate; tightened as coverage improves).
// The report tasks are `koverHtmlReport` / `koverXmlReport`
// (build/reports/kover/); the threshold is enforced by `koverVerify`
// (wired into `check`).
kover {
    reports {
        verify {
            rule {
                minBound(60)
            }
        }
    }
}

// Maven Central publishing artifacts: sources jar (the Kotlin sources) and
// javadoc jar (empty — no dokka wiring; see the publication block).
val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    // The Kotlin-only module has no Java sources, so the java plugin's
    // javadoc task produces an empty output; the jar satisfies the portal's
    // javadoc-artifact requirement.
    from(tasks.named("javadoc"))
}

// Maven Central publishing (top-tier bar; credentials are user-side actions,
// see RELEASING.md §2). Publishing target: the Sonatype Central Portal via
// its compatibility staging endpoint
// (https://ossrh-staging-api.central.sonatype.com/service/local/staging/
// deploy/maven2/ — the portal's replacement for the retired OSSRH staging
// URLs, which the maven-publish file-by-file flow still speaks) with HTTP
// Basic auth (username = portal user-token name, password = token value);
// staging/publishing is handled by the portal.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // Maven Central requires sources and javadoc jars beside the
            // main artifact (portal validation; the javadoc jar is empty for
            // this Kotlin-only module — no dokka wiring, recorded gap).
            artifact(sourcesJar)
            artifact(javadocJar)
            pom {
                name.set("Consema Kotlin SDK")
                description.set("Kotlin implementation of the language-neutral Consema configuration-processing contracts (RFC 0002/0003/0004/0006 contract family; authority: https://github.com/consema/consema/tree/main/docs/rfcs)")
                url.set("https://github.com/consema/consema-kt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/consema/consema-kt.git")
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
            // The Central Portal's compatibility staging endpoint (the
            // replacement for the retired s01.oss.sonatype.org staging URL;
            // the portal processes the uploaded component and publishes it
            // to Maven Central).
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
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
