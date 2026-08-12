// Safe internal DTD/entity boundary (RFC 0012 §3).
//
// Data authority:
//   - RFC 0012 §3 (docs/rfcs/0012-xml-1.0-safe-profile-v1.md:83-130): no
//     DOCTYPE or an internal-only DOCTYPE with a bounded subset; the five
//     predefined entities are always available with their XML meanings;
//     internal general entity names are unique; a declaration cannot
//     override a predefined entity; expansion is guarded before and during
//     allocation by declaration count, reference count, reference depth,
//     replacement bytes/scalars, total expanded bytes/scalars, and the
//     amplification ratio; limits apply across the whole document.
//   - crates/consema-xml/src/entity.rs:9-40 (PredefinedEntity and the frozen
//     PREDEFINED_ENTITIES table), entity.rs:42-49 (predefined_value),
//     entity.rs:51-59 (is_xml_char), entity.rs:61-89 (ReplacementError and
//     validate_replacement_text), entity.rs:91-123 (ExpansionBreach and
//     EntityExpansionLimits), entity.rs:125-208 (EntityExpansionState
//     accounting).
//   - go/xml/entity.go is a cross-reference only.

package consema.xml

/** One predefined XML entity (entity.rs:9-16). */
data class PredefinedEntity(
    /** Entity name without the `&` and `;`. */
    val name: String,
    /** Replacement character data. */
    val value: String,
)

/** The five predefined entities, always available with their XML meanings
 * (entity.rs:18-40). */
val PREDEFINED_ENTITIES: List<PredefinedEntity> = listOf(
    PredefinedEntity("lt", "<"),
    PredefinedEntity("gt", ">"),
    PredefinedEntity("amp", "&"),
    PredefinedEntity("apos", "'"),
    PredefinedEntity("quot", "\""),
)

/** Returns the replacement value of a predefined entity by exact name
 * (entity.rs:42-49). */
fun predefinedValue(name: String): String? =
    PREDEFINED_ENTITIES.firstOrNull { it.name == name }?.value

/** Returns whether `c` is a legal XML 1.0 character (entity.rs:51-59). */
fun isXmlChar(c: Char): Boolean =
    when (val value = c.code) {
        0x09, 0x0A, 0x0D -> true
        in 0x20..0xD7FF -> true
        in 0xE000..0xFFFD -> true
        in 0x0001_0000..0x0010_FFFF -> true
        else -> false
    }

/** Replacement-text validation failure (entity.rs:61-72). */
sealed class ReplacementError {
    /** The replacement text contains `<`, which would create entity-generated
     * markup. */
    data object ContainsMarkup : ReplacementError()

    /** The replacement text contains an illegal XML 1.0 character. */
    data class IllegalCharacter(val scalar: Int) : ReplacementError()
}

/**
 * Validates one internal general entity value (entity.rs:74-89). An admitted
 * value may contain character data, character references, predefined entity
 * references, or references to another admitted internal general entity, but
 * never `<`.
 */
fun validateReplacementText(text: String): ReplacementError? {
    if (text.contains('<')) {
        return ReplacementError.ContainsMarkup
    }
    for (c in text) {
        if (!isXmlChar(c)) {
            return ReplacementError.IllegalCharacter(c.code)
        }
    }
    return null
}

/** Entity expansion breach category (entity.rs:91-106). */
enum class ExpansionBreach {
    /** Too many entity declarations. */
    DeclarationLimit,

    /** Too many entity references. */
    ReferenceLimit,

    /** Reference expansion depth exceeded. */
    DepthLimit,

    /** Expanded bytes exceed the document-wide budget. */
    ExpandedBytes,

    /** Expanded scalars exceed the document-wide budget. */
    ExpandedScalars,

    /** Expanded/declared byte amplification exceeds the ratio. */
    Amplification;

    /** The frozen recovery code of this breach (parser.rs:1751-1757). */
    fun code(): String =
        if (this == Amplification) {
            "xml.entity.amplification@1"
        } else {
            "xml.entity.limit@1"
        }
}

/** Entity expansion limits derived from [XmlParseLimits] (entity.rs:108-123). */
data class EntityExpansionLimits(
    /** Maximum entity declarations. */
    val maxDeclarations: Int,
    /** Maximum entity references. */
    val maxReferences: Int,
    /** Maximum reference expansion depth. */
    val maxExpansionDepth: Int,
    /** Maximum expanded bytes across the whole document. */
    val maxExpandedBytes: Int,
    /** Maximum expanded scalars across the whole document. */
    val maxExpandedScalars: Int,
    /** Maximum expanded/declared byte amplification ratio. */
    val maxAmplificationRatio: Long,
)

/**
 * Document-wide entity expansion accounting (entity.rs:125-145). Counters
 * apply across the whole document, not independently per reference, so an
 * attack cannot split its budget across references.
 */
class EntityExpansionState(
    /** Collected internal general entity declarations. */
    var declarations: Int = 0,
    /** Total references resolved. */
    var references: Int = 0,
    /** Sum of declared replacement bytes. */
    var declaredBytes: Int = 0,
    /** Sum of replacement scalars over all declarations. */
    var declaredScalars: Int = 0,
    /** Total expanded bytes emitted. */
    var expandedBytes: Int = 0,
    /** Total expanded scalars emitted. */
    var expandedScalars: Int = 0,
    /** Current reference nesting depth. */
    var expansionDepth: Int = 0,
) {
    /**
     * Records one collected declaration with its replacement text size
     * (entity.rs:147-168). Returns the breach, or null when admitted.
     */
    fun recordDeclaration(
        replacementBytes: Int,
        replacementScalars: Int,
        limits: EntityExpansionLimits,
    ): ExpansionBreach? {
        if (declarations >= limits.maxDeclarations) {
            return ExpansionBreach.DeclarationLimit
        }
        declarations += 1
        declaredBytes = saturatedAdd(declaredBytes, replacementBytes)
        declaredScalars = saturatedAdd(declaredScalars, replacementScalars)
        return null
    }

    /**
     * Enters one reference expansion and accounts its resolved size
     * (entity.rs:169-197). Returns the breach, or null when admitted.
     */
    fun enterReference(
        expandedBytesCount: Int,
        expandedScalarCount: Int,
        limits: EntityExpansionLimits,
    ): ExpansionBreach? {
        if (references >= limits.maxReferences) {
            return ExpansionBreach.ReferenceLimit
        }
        if (expansionDepth >= limits.maxExpansionDepth) {
            return ExpansionBreach.DepthLimit
        }
        references += 1
        expansionDepth += 1
        expandedBytes = saturatedAdd(expandedBytes, expandedBytesCount)
        expandedScalars = saturatedAdd(expandedScalars, expandedScalarCount)
        if (expandedBytes > limits.maxExpandedBytes) {
            return ExpansionBreach.ExpandedBytes
        }
        if (expandedScalars > limits.maxExpandedScalars) {
            return ExpansionBreach.ExpandedScalars
        }
        if (expandedBytes > amplificationBound(limits)) {
            return ExpansionBreach.Amplification
        }
        return null
    }

    /** Leaves one completed reference expansion (entity.rs:199-202). */
    fun leaveReference() {
        expansionDepth = (expansionDepth - 1).coerceAtLeast(0)
    }

    private fun amplificationBound(limits: EntityExpansionLimits): Int {
        val ratio = if (limits.maxAmplificationRatio > Int.MAX_VALUE.toLong()) {
            Int.MAX_VALUE
        } else {
            limits.maxAmplificationRatio.toInt()
        }
        if (declaredBytes > Int.MAX_VALUE / ratio) {
            return Int.MAX_VALUE
        }
        return declaredBytes * ratio
    }

    override fun equals(other: Any?): Boolean =
        other is EntityExpansionState &&
            declarations == other.declarations &&
            references == other.references &&
            declaredBytes == other.declaredBytes &&
            declaredScalars == other.declaredScalars &&
            expandedBytes == other.expandedBytes &&
            expandedScalars == other.expandedScalars &&
            expansionDepth == other.expansionDepth

    override fun hashCode(): Int {
        var result = declarations
        result = 31 * result + references
        result = 31 * result + declaredBytes
        result = 31 * result + declaredScalars
        result = 31 * result + expandedBytes
        result = 31 * result + expandedScalars
        result = 31 * result + expansionDepth
        return result
    }
}

private fun saturatedAdd(left: Int, right: Int): Int =
    if (left > Int.MAX_VALUE - right) Int.MAX_VALUE else left + right
