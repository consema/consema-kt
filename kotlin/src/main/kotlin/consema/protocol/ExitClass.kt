// The frozen CLI exit classes and the pure error classification.
//
// Data authority: RFC 0015 §5 (docs/rfcs/0015-cli-machine-protocol-and-
// batch-apply-v1.md) — the six exit classes, their codes (0-5), and the
// stable mapping from error families to classes. ClassifyErrorCode is a
// pure function; the CLI applies the mapped code only (RFC 0016 §6: "the
// SDK itself never classifies"). go/protocol/exit_class.go is a
// cross-reference.

package consema.protocol

/**
 * One of the six frozen CLI exit classes (RFC 0015 §5.1).
 */
enum class ExitClass(val wireName: String) {
    /**
     * The command completed and produced its full result. A Recovered state
     * report, an ambiguity fact report, an unauthorized-loss report, and a
     * plan manifest with per-file `failed` entries are all complete results.
     */
    Success("success"),

    /**
     * Argument or syntax error (unknown command, unknown argument, rejected
     * abbreviation, missing or invalid `--format`, missing `--profile` on a
     * parse-class command, `--apply` without a prior plan, invalid
     * `--redact-keys` pattern).
     */
    Usage("usage"),

    /**
     * The operation failed on the data itself (FatalFormationFailure
     * including core.source.* diagnostics, an encoding source-contract
     * conflict, an unresolvable ambiguity, a strict request/plan decode
     * failure, or an input-file read failure).
     */
    Data("data"),

    /** Any resource budget was exceeded (SDK limits, CLI file-size/batch/
     * manifest limits, or a ResourceLimit raised while decoding a
     * request). */
    Limit("limit"),

    /**
     * A write precondition failed (stale base digest, original-bytes
     * mismatch, edit conflict, permission/disk failure, read-only target,
     * symlink-policy rejection, an apply item that cannot continue after an
     * interruption, or a user interrupt signal).
     */
    Precondition("precondition"),

    /**
     * An unclassified internal error (a bug; the diagnostic template must
     * name the command, the involved file, and the diagnostic code).
     */
    Internal("internal"),
}

/** The frozen process exit code for the class (RFC 0015 §5.1 classification
 * table). Codes 6-255 are reserved and never produced by v1. */
fun ExitClass.exitCode(): Int = when (this) {
    ExitClass.Success -> 0
    ExitClass.Usage -> 1
    ExitClass.Data -> 2
    ExitClass.Limit -> 3
    ExitClass.Precondition -> 4
    ExitClass.Internal -> 5
}

/** Parses one canonical envelope name into the closed class set. */
fun parseExitClass(name: String): ExitClass? =
    ExitClass.entries.firstOrNull { it.wireName == name }

/**
 * Classifies one stable error code into its frozen exit class. The mapping
 * is the exhaustive family table of RFC 0015 §5.2:
 *
 *   - cli.usage.* -> Usage (1)
 *   - cli.data.* and cli.detection.* (ambiguity) -> Data (2)
 *   - cli.limit.* and any *-resource-limit@1 (core or format-local) ->
 *     Limit (3)
 *   - cli.write.*, cli.interrupted.signal@1, the
 *     core.source.patch-*-mismatch@1 precondition family, and core.edit.*
 *     conflicts -> Precondition (4)
 *   - cli.internal.unclassified@1 -> Internal (5)
 *   - core.protocol.* strict-decode failures -> Data (2), with
 *     core.protocol.resource-limit@1 overridden to Limit
 *   - core.source.* diagnostics carried by FatalFormationFailure -> Data (2)
 *   - any code outside these frozen families -> Data (2): the operation did
 *     not produce a complete result. Format-layer codes pass through
 *     unchanged; they never invent new classes.
 *
 * Report-as-result outcomes (Recovered state reports, ambiguity fact
 * reports, unauthorized-loss reports) classify as Success (0) at the
 * outcome level, not through error codes.
 */
fun classifyErrorCode(code: String): ExitClass = when {
    code.startsWith("cli.usage.") -> ExitClass.Usage
    code.startsWith("cli.data.") || code.startsWith("cli.detection.") -> ExitClass.Data
    code.startsWith("cli.limit.") -> ExitClass.Limit
    code.startsWith("cli.write.") || code.startsWith("cli.interrupted.") -> ExitClass.Precondition
    code.startsWith("cli.internal.") -> ExitClass.Internal
    code.endsWith(".resource-limit@1") -> ExitClass.Limit
    code.startsWith("core.source.patch-") && code.endsWith("-mismatch@1") -> ExitClass.Precondition
    code.startsWith("core.edit.") -> ExitClass.Precondition
    else -> ExitClass.Data
}
