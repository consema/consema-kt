// Mirrors the local kotlin/build/verify/lib/TestShim.kt artifact so the
// direct-K2JVMCompiler CI jobs (.github/workflows/ci-kotlin.yml) can compile
// and run the unit tests without local-only, gitignored files.
package kotlin.test
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Test
