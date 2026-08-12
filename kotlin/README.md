# Consema Kotlin implementation

The Kotlin/JVM implementation of the language-neutral Consema
configuration-processing contracts (RFC 0016; equal footing with
Rust/Go/TS/Python per the 2026-08-11 owner decision). Zero third-party
runtime dependencies (build.gradle.kts keeps the runtime classpath empty —
all dependencies are test-scoped) and never imports or calls the other
implementations.

## Verify

CI drives kotlin-gates through the committed Gradle wrapper (gradle 8.14:
`.\gradlew.bat test koverVerify` — the kover 60% line-coverage gate, landed
b640af6), and keeps kotlin-conformance / kotlin-differential on the direct
JVM K2JVMCompiler (they rely on the one-module compile + shim + golden-env
pattern; ci-kotlin.yml). Two equivalent paths exist locally:

Gradle path (wrapper committed 2026-08-12):

```
./gradlew build          # compile + full unit-test suite (547 tests) + kover 60% verify
./gradlew koverHtmlReport koverXmlReport   # coverage reports (build/reports/kover/)
```

The conformance/differential tests resolve `conformance/*` and
`docs/fc-manifest-0.13.0.json` from `CONSEMA_REPO` (the pattern of
ci-kotlin.yml's provision step): set it to a checkout of the consema spec
repository, or the 10 such tests fail with "repository root not found" —
same environment requirement as the direct-compile path, only without the
reflective runner's SKIP logic.

Direct-K2JVMCompiler path (what kotlin-conformance / kotlin-differential
run; the kotlin-gates pattern before b640af6):

```
# compile + run every @Test via the kotlin.test shim (kotlin/verify/TestShim.kt)
# + reflective runner — the pattern of ci-kotlin.yml (kotlin-gates job)
powershell -File ../scripts/kotlin-verify-byte-parity.ps1
powershell -File ../scripts/kotlin-verify-normalized-differential.ps1
powershell -File ../scripts/kotlin-verify-protocol-exchange.ps1
```

## Coverage

Landing check (2026-08-12, Gradle wrapper exploration): `./gradlew
koverReport` produces HTML/XML reports (build/reports/kover/) and the
`koverVerify` task enforces the documented 60% line threshold (kover 0.9.9,
bound configured in build.gradle.kts). Measured with all 547 tests green
(CONSEMA_REPO set): **line 77.6%** (40247/51861) · instruction 74.5% ·
branch 55.6% · method 86.8% · class 87.5%. The 60% gate passes with
comfortable headroom. The gate is live in CI: kotlin-gates runs
`.\gradlew.bat test koverVerify` since b640af6 (kover 0.9.9, 60% minimum,
build.gradle.kts:44-52). The Knit-style doc-example gate remains deferred
(tracked in the ci-kotlin.yml header). The ci-kotlin.yml header's
"coverage 待 wrapper 落地后补" note is now superseded by this section.

## Gradle wrapper exploration (2026-08-12)

Feasibility verdict: **viable.** The wrapper (gradle 8.14; `gradlew`,
`gradlew.bat`, `gradle/wrapper/`) was generated and committed;
`./gradlew build` resolves build.gradle.kts, compiles all 234 sources
(163 main + 71 test), runs the 547-test JUnit suite green, and passes the
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
  one module — the 547-green suite shows no dependence on the merged
  visibility.
- **CI switch (landed)**: kotlin-gates switched to the committed Gradle
  wrapper (b640af6: `.\gradlew.bat test koverVerify` — the kover 60%
  coverage gate — plus the zero-dependency assertion); the Kotlin
  dependency audit and dependabot gradle resolution were rewired to the
  wrapper afterwards (9a64d85; .github/dependabot.yml now tracks the
  gradle ecosystem at /kotlin). kotlin-conformance / kotlin-differential
  keep the direct-K2JVMCompiler path (one-module compile + shim +
  golden-env pattern, ci-kotlin.yml) — the recommendation above, executed.

## Conformance

18 suites / 508 cases / aggregate digest `35bebc8d…` are pinned in
`src/test/kotlin/consema/conformance/ConformanceRunnerTest.kt` (508 passed /
0 skipped / 0 failed explicitly asserted); 508/508 pass in CI
(ci-kotlin.yml, kotlin-conformance job).

## References

- Language plan: `docs/multi-language-implementation-plan.md` (L0-L5 closed
  for all three new languages, 2026-08-12)
- CI and cross-language verification design: `docs/five-language-ci-design.md`
