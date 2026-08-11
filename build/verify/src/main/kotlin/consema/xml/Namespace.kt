// Namespace-aware expanded names and immutable binding scope (RFC 0012 §5).
//
// Data authority:
//   - RFC 0012 §5 (docs/rfcs/0012-xml-1.0-safe-profile-v1.md:168-226):
//     prefix spelling is source representation; expanded-name equality
//     compares the namespace URI and local name, never the prefix;
//     namespace declarations are ordered native associations; resolution
//     follows Namespaces in XML 1.0 Third Edition without URI fetch or
//     normalization; `xml` is permanently bound to its standard URI;
//     `xmlns` is reserved and cannot be rebound; the default namespace
//     applies to element names, not unprefixed attributes; namespace scope
//     is immutable ancestry-derived data.
//   - crates/consema-xml/src/namespace.rs:9-13 (the two frozen URIs),
//     namespace.rs:15-57 (QName, ExpandedName), namespace.rs:59-89
//     (Binding, NamespaceError), namespace.rs:91-219 (NamespaceScope
//     declare/resolve rules and the declaration expanded-name rule).
//   - go/xml/namespace.go is a cross-reference only.
//
// Kotlin-idiomatic design: immutable data classes; NamespaceScope appends
// bindings to a child scope instead of mutating, so the ancestry chain of
// one element is never shared mutable state (namespace.rs:91-99).

package consema.xml

/** Standard URI permanently bound to the `xml` prefix (namespace.rs:10). */
const val XML_NAMESPACE_URI: String = "http://www.w3.org/XML/1998/namespace"

/** URI of the reserved `xmlns` prefix (namespace.rs:12). */
const val XMLNS_NAMESPACE_URI: String = "http://www.w3.org/2000/xmlns/"

/** One lexical QName with its source-derived parts (namespace.rs:15-39). */
data class QName(
    /** Prefix spelling before the colon, when present. */
    val prefix: String?,
    /** Local name after the colon, or the whole name when unprefixed. */
    val local: String,
) {
    /** Full lexical spelling `prefix:local` or `local` (namespace.rs:31-38). */
    fun asStr(): String =
        if (prefix == null) local else "$prefix:$local"
}

/** Resolved expanded name = `{ namespace URI or none, local name }`
 * (namespace.rs:41-57). */
data class ExpandedName(
    /** Namespace URI, or null for an unprefixed attribute or an unbound
     * default namespace. */
    val namespace: String?,
    /** Local name. */
    val local: String,
)

/** One in-scope namespace binding (namespace.rs:59-67). */
data class Binding(
    /** Bound prefix; null is the default namespace. */
    val prefix: String?,
    /** Namespace URI. */
    val uri: String,
)

/** Namespace resolution failure (namespace.rs:68-89). */
sealed class NamespaceError {
    /** A prefixed name has no in-scope binding. */
    data class UnboundPrefix(val prefix: String) : NamespaceError()

    /** `xmlns` or another reserved prefix was used as an ordinary name or a
     * declaration prefix. */
    data class ReservedPrefix(val prefix: String) : NamespaceError()

    /** The `xml` prefix was declared to a non-standard URI. */
    data class IllegalXmlRebinding(val uri: String) : NamespaceError()

    /** The `xmlns` URI was bound as the default namespace. */
    data object IllegalDefaultXmlns : NamespaceError()

    /** The stable diagnostic code of this failure (parser.rs:130-137). */
    fun code(): String =
        when (this) {
            is UnboundPrefix -> "xml.namespace.unbound-prefix@1"
            is ReservedPrefix -> "xml.namespace.reserved-prefix@1"
            is IllegalXmlRebinding -> "xml.namespace.xml-rebinding@1"
            IllegalDefaultXmlns -> "xml.namespace.default-xmlns@1"
        }
}

/**
 * Immutable, ancestry-derived namespace scope (namespace.rs:91-99). A scope
 * is never mutated in place: declaring a binding appends to a new child
 * scope, so the immutable ancestry chain of a tree is preserved.
 */
class NamespaceScope private constructor(private val bindings: List<Binding>) {
    companion object {
        /** Creates an empty scope holding only the permanent `xml` binding
         * rule (namespace.rs:102-108). */
        fun new(): NamespaceScope = NamespaceScope(emptyList())

        /**
         * Expanded name of a namespace declaration attribute itself:
         * `xmlns` is `{ xmlns-URI, "xmlns" }` and `xmlns:p` is
         * `{ xmlns-URI, "p" }`, used for attribute-uniqueness checks
         * (namespace.rs:168-179).
         */
        fun declarationExpandedName(prefix: String?): ExpandedName =
            ExpandedName(XMLNS_NAMESPACE_URI, prefix ?: "xmlns")
    }

    /** All in-scope bindings in declaration order; a null prefix is the
     * default namespace (namespace.rs:110-115). */
    fun bindings(): List<Binding> = bindings

    /**
     * Appends one namespace declaration and returns the child scope
     * (namespace.rs:117-144). The `xmlns` prefix can never be declared, the
     * `xml` prefix can only be declared to its standard URI, and the `xmlns`
     * URI cannot become the default namespace.
     */
    fun declare(prefix: String?, uri: String): NamespaceScope {
        if (uri == XMLNS_NAMESPACE_URI && prefix == null) {
            throw NamespaceException(NamespaceError.IllegalDefaultXmlns)
        }
        if (prefix != null) {
            if (prefix == "xmlns") {
                throw NamespaceException(NamespaceError.ReservedPrefix(prefix))
            }
            if (prefix == "xml" && uri != XML_NAMESPACE_URI) {
                throw NamespaceException(NamespaceError.IllegalXmlRebinding(uri))
            }
        }
        return NamespaceScope(bindings + Binding(prefix, uri))
    }

    /** Resolves an element name: the default namespace applies
     * (namespace.rs:146-155). */
    fun resolveElement(qname: QName): ExpandedName =
        if (qname.prefix == null) {
            ExpandedName(lookupDefault(), qname.local)
        } else {
            resolvePrefixed(qname, qname.prefix)
        }

    /** Resolves an attribute name: the default namespace never applies
     * (namespace.rs:157-166). */
    fun resolveAttribute(qname: QName): ExpandedName =
        if (qname.prefix == null) {
            ExpandedName(null, qname.local)
        } else {
            resolvePrefixed(qname, qname.prefix)
        }

    private fun lookupDefault(): String? =
        bindings.asReversed().firstOrNull { it.prefix == null }?.uri

    private fun resolvePrefixed(qname: QName, prefix: String): ExpandedName {
        if (prefix == "xml") {
            return ExpandedName(XML_NAMESPACE_URI, qname.local)
        }
        if (prefix == "xmlns") {
            throw NamespaceException(NamespaceError.ReservedPrefix(prefix))
        }
        val uri = bindings.asReversed().firstOrNull { it.prefix == prefix }?.uri
            ?: throw NamespaceException(NamespaceError.UnboundPrefix(prefix))
        return ExpandedName(uri, qname.local)
    }

    override fun equals(other: Any?): Boolean =
        other is NamespaceScope && bindings == other.bindings

    override fun hashCode(): Int = bindings.hashCode()

    override fun toString(): String = "NamespaceScope(bindings=$bindings)"
}

/** The typed namespace resolution failure; [error] is the stable
 * language-neutral fact, [error.code] the frozen diagnostic code
 * (parser.rs:130-137). */
class NamespaceException(val error: NamespaceError) :
    Exception("namespace: ${error.code()}")
