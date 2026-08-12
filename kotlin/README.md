# Consema Kotlin implementation

The Kotlin/JVM implementation of the language-neutral Consema
configuration-processing contracts (RFC 0016; equal footing with
Rust/Go/TS/Python per the 2026-08-11 owner decision). Zero third-party
runtime dependencies (build.gradle.kts keeps the runtime classpath empty —
all dependencies are test-scoped) and never imports or calls the other
implementations.

## Verify

The repository has no Gradle wrapper (design §7.3; a later L0-batch item),
so verification drives the direct JVM K2JVMCompiler exactly like the
committed scripts and CI:

```
# compile + run every @Test via the kotlin.test shim (kotlin/verify/TestShim.kt)
# + reflective runner — the pattern of ci-kotlin.yml (kotlin-gates job)
powershell -File ../scripts/kotlin-verify-byte-parity.ps1
powershell -File ../scripts/kotlin-verify-normalized-differential.ps1
powershell -File ../scripts/kotlin-verify-protocol-exchange.ps1
```

## Coverage (P2)

Line coverage is deferred to P2. The repository has no Gradle wrapper
(design §7.3; a later L0-batch item) and drives the direct JVM
K2JVMCompiler, so there is no Gradle/JaCoCo or kover path to produce a line
coverage report today (the ci-kotlin.yml header carries the standing
"coverage 待 wrapper 落地后补" note). A coverage gate (initial threshold 60%,
tightened as coverage improves) and the Knit-style doc-example gate land
together with the Gradle wrapper.

## Conformance

18 suites / 508 cases / aggregate digest `35bebc8d…` are pinned in
`src/test/kotlin/consema/conformance/ConformanceRunnerTest.kt` (508 passed /
0 skipped / 0 failed explicitly asserted); 508/508 pass in CI
(ci-kotlin.yml, kotlin-conformance job).

## References

- Language plan: `docs/multi-language-implementation-plan.md` (L0-L5 closed
  for all three new languages, 2026-08-12)
- CI and cross-language verification design: `docs/five-language-ci-design.md`
