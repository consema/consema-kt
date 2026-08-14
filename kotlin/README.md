# Consema Kotlin implementation

The Kotlin/JVM implementation of the language-neutral Consema
configuration-processing contracts (RFC 0002/0003/0004/0006 contract
family; authority: the spec repository docs/rfcs/; equal footing with
Rust/Go/TS/Python per the 2026-08-11 owner decision). Zero third-party
runtime dependencies (the runtime classpath carries only the Kotlin
platform — kotlin-stdlib and its transitive org.jetbrains:annotations,
injected by the Kotlin Gradle plugin; every declared configuration is
test-scoped) and never imports or calls the other implementations.

## Verify

CI drives kotlin-gates through the committed Gradle wrapper (gradle 8.14:
`.\gradlew.bat test koverVerify` — the kover 60% line-coverage gate, landed
b640af6), and keeps kotlin-conformance / kotlin-differential on the direct
JVM K2JVMCompiler (they rely on the one-module compile + temp main() runner + golden-env
pattern; ci-kotlin.yml). Two equivalent paths exist locally:

Gradle path (wrapper committed 2026-08-12):

```
./gradlew build          # compile + full unit-test suite (576 tests, 2026-08-15 静态计数：@Test 注解数——计数随测试新增漂移，以最近 CI run 为准) + kover 60% verify
./gradlew koverHtmlReport koverXmlReport   # coverage reports (build/reports/kover/)
```

The conformance/differential tests resolve `conformance/*` and
`docs/fc-manifest-0.13.0.json` from `CONSEMA_REPO` (the pattern of
ci-kotlin.yml's provision step): set it to a root carrying the provisioned
conformance/ and docs/fc-manifest-0.13.0.json (e.g. a checkout of the
consema spec repository, or a provisioned workspace), or the 21 such tests
fail with "repository root not found". The three verify scripts force
CONSEMA_REPO to this repository's root and require conformance/ provisioned
there (their failure message is "differential case file not found").
Same environment requirement as the direct-compile path (the differential
tests' built-in env check prints a documented [SKIP] line and returns when
the golden env vars are absent — the gradle path runs the same checks).

Direct-K2JVMCompiler path (what kotlin-conformance / kotlin-differential
run today; kotlin-gates moved to the Gradle wrapper in b640af6):

```
# compile the tests with the kotlinc-bundled kotlin-test.jar +
# kotlin-test-junit5.jar (+ the provisioned junit-jupiter-api-5.10.2.jar)
# and drive the @Test methods through a temp main() runner — the pattern of
# ci-kotlin.yml and the scripts below (kotlin-conformance compiles
# ConformanceRunnerTest.kt with the same direct driver)
powershell -File ../scripts/kotlin-verify-byte-parity.ps1
powershell -File ../scripts/kotlin-verify-normalized-differential.ps1
powershell -File ../scripts/kotlin-verify-protocol-exchange.ps1
```

## Coverage

Landing check (2026-08-12, Gradle wrapper exploration): `./gradlew
koverHtmlReport koverXmlReport` produces HTML/XML reports (build/reports/kover/) and the
`koverVerify` task enforces the documented 60% line threshold (kover 0.9.9,
bound configured in the build.gradle.kts kover block). Last measured with all 572 tests green
(2026-08-13 静态计数；CONSEMA_REPO set): **line 77.8%** (40385/51934) · instruction 74.6% ·
branch 55.8% · method 86.9% · class 87.6% — a snapshot for the record: the
kover percentages are written into this README by hand and no gate
regenerates them (koverVerify only enforces the 60% threshold), so treat
them as historical, not as a recomputable current-state claim. The 60% gate passes with
comfortable headroom. The gate is live in CI: kotlin-gates runs
`.\gradlew.bat test koverVerify` since b640af6 (kover 0.9.9, 60% minimum,
build.gradle.kts kover block). The Knit-style doc-example gate landed with
the examples job (2026-08-13): the README ```kotlin fence must equal
kotlin/examples/Quickstart.kt after Trim and the header/package-line strip
— the ```text command block is not part of the comparison surface.

## Gradle wrapper exploration (2026-08-12)

Feasibility verdict: **viable.** The wrapper (gradle 8.14; `gradlew`,
`gradlew.bat`, `gradle/wrapper/`) was generated and committed;
`./gradlew build` resolves build.gradle.kts, compiles all 237 sources
(163 main + 74 test), runs the 576-test JUnit suite green (2026-08-15
静态计数), and passes the
60% kover line-coverage verify. Findings that matter for a future CI
switch:

- **kotlin daemon heap is a build-time blocker at the default 512 MiB** —
  the first local compile GC-thrashed for 40+ minutes without completing;
  `kotlin.daemon.jvmargs=-Xmx3g` (committed in kotlin/gradle.properties)
  finishes the same compile in ~2 minutes. CI's direct path already gives
  K2JVMCompiler `-Xmx2g`, so a switch must keep this property.
- **CONSEMA_REPO works unchanged**: kotlin-gates already provisions
  conformance/ + the manifest into the workspace and sets CONSEMA_REPO, so
  `./gradlew test` there needs no new wiring; locally it must be set.
- **Internal visibility is preserved**: gradle compiles main and test as
  separate source sets (friend modules), the direct path compiles them into
  one module — the 576-test green suite (2026-08-15 静态计数) shows no dependence on the merged
  visibility.
- **CI switch (landed)**: kotlin-gates switched to the committed Gradle
  wrapper (b640af6: `.\gradlew.bat test koverVerify` — the kover 60%
  coverage gate — plus the zero-dependency assertion); the Kotlin
  dependency audit and dependabot gradle resolution were rewired to the
  wrapper afterwards (9a64d85; .github/dependabot.yml now tracks the
  gradle ecosystem at /kotlin). kotlin-conformance / kotlin-differential
  keep the direct-K2JVMCompiler path (one-module compile + temp main()
  runner + golden-env pattern, ci-kotlin.yml) — the recommendation above, executed.

## Conformance

18 suites / 519 cases / aggregate digest `cfd6e296…` are pinned in the
kotlin-conformance CI job (ci-kotlin.yml, literal digest step) and asserted
in `src/test/kotlin/consema/conformance/ConformanceRunnerTest.kt` (519 passed /
0 skipped / 0 failed explicitly asserted); 519/519 pass in CI
(ci-kotlin.yml, kotlin-conformance job).

## References

- Language plan: https://github.com/consema/consema/blob/main/docs/multi-language-implementation-plan.md (L0-L4 closed
  for all three new languages; the L5 differential steps — byte parity /
  normalized differential / protocol exchange — closed 2026-08-12, while
  the shared-conformance leg (five-language-ci-design.md §3.6/§7.2,
  scripts/kotlin-verify-shared-conformance.ps1) stays deferred — not
  committed, ci-kotlin.yml header note; wave-4 D6-04 wording)
- CI and cross-language verification design: https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md
