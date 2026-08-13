// Shared golden fixtures transcribed from the shared conformance assets.
//
// Data authority: conformance/fixtures/toml/*.toml (the files consumed by
// the toml-v1.json vectors) and the corpus Cargo.toml (toml.corpus.cargo-
// manifest). The loader prefers the real shared files (byte-exact); the
// embedded constants are byte-for-byte transcriptions used when the shared
// tree is not reachable from the test working directory. The L5 conformance
// runner reads the shared vector files directly; these are the L1
// intent-document stand-ins.

package toml

import java.io.File

private val FIXTURE_DIR = "conformance/fixtures/toml"
private val FIXTURE_DIRS = buildList {
    add(File(FIXTURE_DIR))
    add(File("../$FIXTURE_DIR"))
    add(File("../../$FIXTURE_DIR"))
    // The CONSEMA_REPO root, when set (the repository-relative rule of the
    // runner; no machine-coupled path is baked in).
    System.getenv("CONSEMA_REPO")?.takeIf { it.isNotBlank() }?.let {
        add(File(File(it), FIXTURE_DIR))
    }
}

/**
 * The shared fixture files end with one blank line; trimIndent removes the
 * trailing blank line, so the exact transcription re-appends the final
 * newline.
 */
private fun transcribed(text: String): ByteArray =
    (text.trimIndent() + "\n").toByteArray(Charsets.UTF_8)

/** Loads one shared fixture, preferring the real file. */
internal fun fixtureBytes(name: String, fallback: ByteArray): ByteArray {
    for (dir in FIXTURE_DIRS) {
        val file = File(dir, name)
        if (file.isFile) {
            return file.readBytes()
        }
    }
    return fallback
}

/** conformance/fixtures/toml/all-values.toml (320 bytes). */
internal val ALL_VALUES_TOML: ByteArray = fixtureBytes(
    "all-values.toml",
    """
    title = "Consema fixture"
    enabled = true
    integer = 42
    hex = 0xDEAD_BEEF
    float = -0.0
    positive_infinity = inf
    not_a_number = nan
    local_date = 1979-05-27
    local_time = 07:32:00.123456789
    local_date_time = 1979-05-27T07:32:00
    offset_date_time = 1979-05-27T07:32:00-07:00
    ports = [8000, 8001, 8002]
    point = { x = 1, y = 2 }

    """.let { transcribed(it) },
)

/** conformance/fixtures/toml/trivia-and-strings.toml (256 bytes). */
internal val TRIVIA_AND_STRINGS_TOML: ByteArray = fixtureBytes(
    "trivia-and-strings.toml",
    """
    # Leading comment must survive.
    basic = "quote: \"; slash: \\; tab: \t"
    literal = 'C:\Users\name'
    multiline = ${"\"\"\""}
    The quick brown fox \
      jumps over the lazy dog.${"\"\"\""}
    literal_multiline = '''
    first line
    second line'''
    array = [
      1, # first
      2, # second
    ]

    """.let { transcribed(it) },
)

/** conformance/fixtures/toml/application.toml (541 bytes). */
internal val APPLICATION_TOML: ByteArray = fixtureBytes(
    "application.toml",
    """
    # A realistic service configuration exercising logical and syntactic tables.
    service.name = "catalog"
    service.environment = "production"
    service.listen = { host = "0.0.0.0", port = 8080 }

    [database]
    url = "postgres://db.internal/catalog"
    pool_size = 32
    timeouts = [1.0, 5.0, 30.0]

    [observability.logs]
    level = "info"
    json = true

    [[upstreams]]
    name = "inventory-a"
    endpoints = ["https://inventory-a.internal"]

    [[upstreams]]
    name = "inventory-b"
    endpoints = ["https://inventory-b.internal/primary", "https://inventory-b.internal/backup"]

    """.let { transcribed(it) },
)

/** conformance/fixtures/toml/invalid-duplicate.toml (32 bytes). */
internal val INVALID_DUPLICATE_TOML: ByteArray = fixtureBytes(
    "invalid-duplicate.toml",
    "name = \"first\"\nname = \"second\"\n".toByteArray(Charsets.UTF_8),
)

/** conformance/fixtures/toml/pyproject.toml (524 bytes). */
internal val PYPROJECT_TOML: ByteArray = fixtureBytes(
    "pyproject.toml",
    """
    [build-system]
    requires = ["hatchling>=1.25"]
    build-backend = "hatchling.build"

    [project]
    name = "consema-client"
    version = "0.2.0"
    description = "A realistic PEP 621 fixture"
    requires-python = ">=3.11"
    dependencies = [
      "httpx>=0.27,<1",
      "pydantic>=2.8,<3",
    ]

    [project.optional-dependencies]
    test = ["pytest>=8", "pytest-cov>=5"]

    [tool.pytest.ini_options]
    addopts = "-ra --strict-markers"
    testpaths = ["tests"]

    [tool.ruff]
    line-length = 100
    target-version = "py311"

    [tool.ruff.lint]
    select = ["E", "F", "I", "UP"]

    """.let { transcribed(it) },
)

/** The corpus Cargo.toml (toml.corpus.cargo-manifest): the committed
 * fixture conformance/fixtures/toml/Cargo.toml (single authority since the
 * six-repo split; the workspace root no longer carries a Cargo.toml). */
internal val CORPUS_CARGO_TOML: ByteArray = fixtureBytes(
    "Cargo.toml",
    """
    [workspace]
    resolver = "3"
    members = [
        "consema-core",
        "consema-pvce",
        "consema-graph",
        "consema-document",
        "consema-json",
        "consema-toml",
        "consema-yaml",
        "consema-ini",
        "consema-properties",
        "consema-protocol",
        "consema-xml",
        "consema-plist",
        "consema-hcl",
        "consema-conformance",
        "consema",
    ]

    [workspace.dependencies]
    encoding_rs = { version = "=0.8.35", default-features = false, features = ["alloc"] }
    sha2 = { version = "=0.11.0", default-features = false }
    toml_edit = { version = "=0.22.27", default-features = false, features = ["parse"] }
    saphyr-parser = { version = "=0.0.11", default-features = false }
    unicode-id-start = "=1.4.0"
    unicode-ident = "=1.0.24"
    xmlparser = { version = "=0.13.6", default-features = false }

    [workspace.package]
    version = "1.0.0-rc.1"
    edition = "2024"
    rust-version = "1.85"
    license = "MIT"

    [workspace.lints.rust]
    unsafe_code = "forbid"
    missing_docs = "warn"

    [workspace.lints.clippy]
    all = { level = "warn", priority = -1 }
    pedantic = { level = "warn", priority = -1 }
    module_name_repetitions = "allow"
    cast_possible_truncation = "allow"
    cast_possible_wrap = "allow"
    doc_markdown = "allow"
    missing_errors_doc = "allow"
    missing_panics_doc = "allow"
    needless_pass_by_value = "allow"
    too_many_lines = "allow"

    """.let { transcribed(it) },
)

