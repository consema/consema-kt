// Protocol transport resource limits.
//
// Data authority: consema-rs/consema-protocol/src/limits.rs (defaults at
// limits.rs:20-31). consema-go/go/protocol/limits.go is a cross-reference.

package consema.protocol

/**
 * The resource limits shared by the canonical JSON and PVCE/1 protocol
 * transports. The zero value rejects every operation; use
 * [ProtocolLimits.default].
 */
data class ProtocolLimits(
    /** Maximum encoded transport bytes. */
    val maxBytes: Int,
    /** Maximum nested PortableValue depth. */
    val maxDepth: Int,
    /** Maximum total PortableValue nodes. */
    val maxNodes: Int,
    /** Maximum entries in one container. */
    val maxContainerEntries: Int,
    /** Maximum one String, Bytes, key, or identifier payload. */
    val maxBlobBytes: Int,
    /** Maximum magnitude bytes for an arbitrary integer. */
    val maxIntegerBytes: Int,
) {
    companion object {
        /** The frozen defaults (64 MiB bytes, depth 256, 1,000,000 nodes,
         * 1,000,000 container entries, 64 MiB blob, 1024 integer bytes). */
        val default = ProtocolLimits(
            maxBytes = 64 shl 20,
            maxDepth = 256,
            maxNodes = 1_000_000,
            maxContainerEntries = 1_000_000,
            maxBlobBytes = 64 shl 20,
            maxIntegerBytes = 1024,
        )
    }
}
