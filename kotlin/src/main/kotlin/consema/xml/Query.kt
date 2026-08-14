// XML native and lossless syntax query execution (RFC 0012 §8).
//
// Data authority:
//   - RFC 0012 §8 (https://github.com/consema/consema/blob/main/docs/rfcs/0012-xml-1.0-safe-profile-v1.md:284-312):
//     domains `xml.native-semantic-query@1` and `xml.lossless-syntax-query@1`;
//     native order is document order; attributes and namespace declarations
//     preserve source order; child content preserves mixed-content order;
//     descendant traversal is bounded pre-order; no query resolves a URI,
//     evaluates XPath, loads a schema, or expands application data.
//   - conformance/vectors/xml-1-0-safe-v1.json cases xml.syntax-query.* and
//     xml.native-query.* pin the operator spellings, the match order, and
//     the ordinal facts (the conformance runner build_filters,
//     https://github.com/consema/consema-rs/blob/main/consema-conformance/src/xml_v1.rs:231-258).
//   - https://github.com/consema/consema-rs/blob/main/consema-xml/src/query.rs is the byte-arbitration authority:
//     XmlReferenceKind (query.rs:20-29), XmlMatch (query.rs:31-165), the
//     operator table (query.rs:583-619), the per-operator semantics
//     (query.rs:624-1376), selection (query.rs:251-269, 337-355), and the
//     syntax-piece input construction (query.rs:305-330).
//   - The operator validation tables live in the protocol package
//     (kotlin/src/main/kotlin/consema/protocol/QueryValidate.kt:253-326) and already pin the
//     xml operator roles; execution below dispatches on the operator IDs.
//
// Kotlin-idiomatic design: execution throws the protocol package's typed
// [consema.protocol.QueryFailureException] carrying the registered code
// (the json family pattern, kotlin/src/main/kotlin/consema/json/Query.kt:141-152); the cursor
// terminal contract (RFC 0003 §9) is a synchronous complete result list
// here, with the terminal state always Completed after a successful
// execution.

package consema.xml

import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.Span
import consema.protocol.ExecutableQuery
import consema.protocol.OperatorCall
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.protocol.QuerySelection
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Query resource limits (consema-core query.rs:2967-2981; the json family
 * transcription kotlin/src/main/kotlin/consema/json/Query.kt:44-56). */
data class QueryLimits(
    /** Maximum operator steps. */
    val maxSteps: Int,
    /** Maximum complete results buffered by an operator. */
    val maxResults: Int,
) {
    companion object {
        /** The frozen defaults (query.rs:2974-2981): 100,000 steps and
         * 100,000 results. */
        val default = QueryLimits(maxSteps = 100_000, maxResults = 100_000)
    }
}

/** Cooperative cancellation flag (query.rs:2984-2993). */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)

    /** Whether execution was cancelled. */
    fun isCancelled(): Boolean = cancelled.get()

    /** Requests cancellation; queries check the flag at operator steps. */
    fun cancel() {
        cancelled.set(true)
    }
}

/** One XML reference occurrence kind (query.rs:20-29). */
enum class XmlReferenceKind {
    /** Decimal or hexadecimal character reference. */
    Character,

    /** One of the five predefined entity references. */
    Predefined,

    /** An admitted internal general entity reference. */
    General,
}

/**
 * Owned snapshot-bound XML native semantic query match (query.rs:31-165).
 * The sealed hierarchy makes exhaustive `when` over every match kind
 * mandatory.
 */
sealed class XmlMatch {
    /** Complete XML document. */
    data class Document(val node: NodeRef) : XmlMatch()

    /** The XML declaration. */
    data class Declaration(
        val node: NodeRef,
        val version: String,
        val encoding: String?,
        val standalone: Boolean?,
    ) : XmlMatch()

    /** DOCTYPE occurrence. */
    data class Doctype(
        val node: NodeRef,
        val name: String,
    ) : XmlMatch()

    /** One prolog or epilog occurrence. */
    data class PrologItem(
        val node: NodeRef,
        /** Kind: `processing-instruction` or `comment`. */
        val kind: String,
    ) : XmlMatch()

    /** One element occurrence. */
    data class Element(
        val node: NodeRef,
        val parent: NodeRef?,
        val prefix: String?,
        val local: String,
        val namespace: String?,
        val namespaceError: Boolean,
    ) : XmlMatch()

    /** One attribute association. */
    data class Attribute(
        val node: NodeRef,
        val element: NodeRef,
        val prefix: String?,
        val local: String,
        val namespace: String?,
        val value: String,
    ) : XmlMatch()

    /** One namespace binding association. */
    data class NamespaceBinding(
        val node: NodeRef,
        val element: NodeRef,
        val prefix: String?,
        val uri: String,
    ) : XmlMatch()

    /** One text occurrence. */
    data class Text(
        val node: NodeRef,
        val parent: NodeRef?,
        val semantic: String,
    ) : XmlMatch()

    /** One CDATA occurrence. */
    data class Cdata(
        val node: NodeRef,
        val parent: NodeRef?,
        val text: String,
    ) : XmlMatch()

    /** One comment occurrence. */
    data class Comment(
        val node: NodeRef,
        val parent: NodeRef?,
        val text: String,
    ) : XmlMatch()

    /** One processing instruction. */
    data class ProcessingInstruction(
        val node: NodeRef,
        val parent: NodeRef?,
        val target: String,
        val content: String?,
    ) : XmlMatch()

    /** One reference occurrence inside text. */
    data class Reference(
        val node: NodeRef,
        val text: NodeRef,
        val parent: NodeRef?,
        val kind: XmlReferenceKind,
        val name: String,
        val resolved: String,
    ) : XmlMatch()

    /** One recovered error region. */
    data class ErrorRegion(
        val node: NodeRef,
        val span: Span,
    ) : XmlMatch()

    internal fun identity(): NodeRef =
        when (this) {
            is Document -> node
            is Declaration -> node
            is Doctype -> node
            is PrologItem -> node
            is Element -> node
            is Attribute -> node
            is NamespaceBinding -> node
            is Text -> node
            is Cdata -> node
            is Comment -> node
            is ProcessingInstruction -> node
            is Reference -> node
            is ErrorRegion -> node
        }
}

/** Owned snapshot-bound XML lossless syntax query match (query.rs:187-220). */
data class XmlSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val node: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: XmlSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/**
 * Executes a validated XML native semantic query against one immutable
 * snapshot (query.rs:222-249). The document node is the first standard
 * result; it must not bypass result limits. The domain binding rejects
 * other domains with a DomainMismatch failure.
 */
fun executeXmlQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<XmlMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "xml.native-semantic-query" ||
        definition.domain.version != 1
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = Context(document, limits, cancellation)
    context.step(1)
    val input = listOf(XmlMatch.Document(document.nodeRef()))
    val matches = executeExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/** Executes a validated XML lossless syntax query in raw source order
 * (query.rs:285-335). */
fun executeXmlSyntaxQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<XmlSyntaxMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "xml.lossless-syntax-query" ||
        definition.domain.version != 1
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = Context(document, limits, cancellation)
    val pieces = document.losslessStructuralIndex().pieces()
    context.step(pieces.size)
    val kinds = document.losslessSyntaxKinds()
    val input = pieces.indices.map { ordinal ->
        XmlSyntaxMatch(
            node = document.authority.nodeRef(ordinal.toLong(), NodeRole.XmlSyntaxPiece),
            span = pieces[ordinal].span,
            kind = kinds[ordinal],
            ordinal = ordinal,
        )
    }
    val matches = executeSyntaxExpression(definition.expression, input, context)
    return applySyntaxSelection(matches, definition.selection)
}

/** Applies the validated cardinality selection (query.rs:251-269). */
private fun applySelection(values: List<XmlMatch>, selection: QuerySelection): List<XmlMatch> =
    when (selection) {
        QuerySelection.All -> values
        QuerySelection.First -> values.take(1)
        QuerySelection.Last -> listOfNotNull(values.lastOrNull())
        QuerySelection.ZeroOrOne -> {
            if (values.size <= 1) {
                values
            } else {
                throw QueryFailureException(
                    QueryFailureKind.CARDINALITY_VIOLATION,
                    argument = selection.wireName,
                )
            }
        }
        QuerySelection.RequireOne -> {
            if (values.size == 1) {
                values
            } else {
                throw QueryFailureException(
                    QueryFailureKind.CARDINALITY_VIOLATION,
                    argument = selection.wireName,
                )
            }
        }
    }

/** Applies the validated cardinality selection to syntax matches
 * (query.rs:337-355). */
private fun applySyntaxSelection(
    values: List<XmlSyntaxMatch>,
    selection: QuerySelection,
): List<XmlSyntaxMatch> =
    when (selection) {
        QuerySelection.All -> values
        QuerySelection.First -> values.take(1)
        QuerySelection.Last -> listOfNotNull(values.lastOrNull())
        QuerySelection.ZeroOrOne -> {
            if (values.size <= 1) {
                values
            } else {
                throw QueryFailureException(
                    QueryFailureKind.CARDINALITY_VIOLATION,
                    argument = selection.wireName,
                )
            }
        }
        QuerySelection.RequireOne -> {
            if (values.size == 1) {
                values
            } else {
                throw QueryFailureException(
                    QueryFailureKind.CARDINALITY_VIOLATION,
                    argument = selection.wireName,
                )
            }
        }
    }

/** Execution context carrying limits and cancellation (query.rs:371-482). */
private class Context(
    val document: Document,
    val limits: QueryLimits,
    val cancellation: CancellationToken,
) {
    var steps: Int = 0

    fun step(results: Int) {
        if (cancellation.isCancelled()) {
            throw QueryFailureException(QueryFailureKind.CANCELLED)
        }
        steps += 1
        if (steps > limits.maxSteps || results > limits.maxResults) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
    }

    fun push(output: MutableList<XmlMatch>, value: XmlMatch) {
        val observed = output.size + 1
        if (observed > limits.maxResults) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
        output.add(value)
    }

    fun pushSyntax(output: MutableList<XmlSyntaxMatch>, value: XmlSyntaxMatch) {
        val observed = output.size + 1
        if (observed > limits.maxResults) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
        output.add(value)
    }

    fun append(output: MutableList<XmlMatch>, values: List<XmlMatch>) {
        val observed = output.size + values.size
        if (observed > limits.maxResults) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
        output.addAll(values)
    }

    fun appendSyntax(output: MutableList<XmlSyntaxMatch>, values: List<XmlSyntaxMatch>) {
        val observed = output.size + values.size
        if (observed > limits.maxResults) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
        output.addAll(values)
    }

    fun elementData(index: Int): XmlElementData = document.elementData(index)

    fun elementMatch(index: Int): XmlMatch {
        val data = elementData(index)
        return XmlMatch.Element(
            node = nodeRef(index, NodeRole.XmlElement),
            parent = parentOf(index),
            prefix = data.qname.prefix,
            local = data.qname.local,
            namespace = data.expanded?.namespace,
            namespaceError = data.hasNamespaceError,
        )
    }

    fun parentOf(index: Int): NodeRef? =
        document.parentOf(index)?.let { nodeRef(it, NodeRole.XmlElement) }

    fun nodeRef(index: Int, role: NodeRole): NodeRef =
        document.authority.nodeRef(index.toLong(), role)

    fun prologItem(item: XmlPrologItem): XmlMatch? {
        val (node, kind) = when (item) {
            is XmlPrologItem.ProcessingInstruction -> Pair(
                nodeRef(item.data.ordinal.toInt(), NodeRole.XmlProcessingInstruction),
                "processing-instruction",
            )
            is XmlPrologItem.Comment -> Pair(
                nodeRef(item.data.ordinal.toInt(), NodeRole.XmlComment),
                "comment",
            )
            is XmlPrologItem.Declaration,
            is XmlPrologItem.Doctype,
            is XmlPrologItem.Bom,
            is XmlPrologItem.Whitespace,
            -> return null
        }
        return XmlMatch.PrologItem(node, kind)
    }
}

private fun executeExpression(
    expression: QueryExpression,
    input: List<XmlMatch>,
    context: Context,
): List<XmlMatch> =
    when (expression.kind) {
        consema.protocol.ExpressionKind.Input -> input
        consema.protocol.ExpressionKind.Apply -> {
            val appliedInput = executeExpression(expression.input!!, input, context)
            applyOperator(expression.operator!!, appliedInput, context)
        }
        consema.protocol.ExpressionKind.Concat -> {
            val output = ArrayList<XmlMatch>()
            for (branch in expression.branches) {
                val values = executeExpression(branch, input, context)
                context.append(output, values)
                context.step(output.size)
            }
            output
        }
        consema.protocol.ExpressionKind.StructureOrderMerge -> {
            val output = ArrayList<XmlMatch>()
            for (branch in expression.branches) {
                val values = executeExpression(branch, input, context)
                context.append(output, values)
            }
            output.sortBy(::sourceOrder)
            context.step(output.size)
            output
        }
    }

private fun executeSyntaxExpression(
    expression: QueryExpression,
    input: List<XmlSyntaxMatch>,
    context: Context,
): List<XmlSyntaxMatch> =
    when (expression.kind) {
        consema.protocol.ExpressionKind.Input -> input
        consema.protocol.ExpressionKind.Apply -> {
            val appliedInput = executeSyntaxExpression(expression.input!!, input, context)
            applySyntaxOperator(expression.operator!!, appliedInput, context)
        }
        consema.protocol.ExpressionKind.Concat -> {
            val output = ArrayList<XmlSyntaxMatch>()
            for (branch in expression.branches) {
                val values = executeSyntaxExpression(branch, input, context)
                context.appendSyntax(output, values)
                context.step(output.size)
            }
            output
        }
        consema.protocol.ExpressionKind.StructureOrderMerge -> {
            val output = ArrayList<XmlSyntaxMatch>()
            for (branch in expression.branches) {
                val values = executeSyntaxExpression(branch, input, context)
                context.appendSyntax(output, values)
            }
            output.sortBy { it.ordinal }
            context.step(output.size)
            output
        }
    }

/** Structural identity order of one native match (query.rs:556-576). */
private fun sourceOrder(item: XmlMatch): Int =
    when (item) {
        is XmlMatch.Document -> 0
        is XmlMatch.Declaration,
        is XmlMatch.Doctype,
        is XmlMatch.PrologItem,
        is XmlMatch.Element,
        is XmlMatch.Attribute,
        is XmlMatch.NamespaceBinding,
        is XmlMatch.Text,
        is XmlMatch.Cdata,
        is XmlMatch.Comment,
        is XmlMatch.ProcessingInstruction,
        is XmlMatch.Reference,
        -> item.identity().index.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        is XmlMatch.ErrorRegion -> item.span.startByte
    }

private fun applyOperator(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
): List<XmlMatch> {
    val output = ArrayList<XmlMatch>()
    when (operator.id) {
        "xml.document-root" -> documentRoot(input, context, output)
        "xml.document-declaration" -> documentDeclaration(input, context, output)
        "xml.document-doctype" -> documentDoctype(input, context, output)
        "xml.document-prolog", "xml.document-epilog" ->
            documentPrologEpilog(operator.id, input, context, output)
        "xml.element-children" -> elementChildren(input, context, output)
        "xml.element-child-elements" -> elementChildElements(input, context, output)
        "xml.element-child-text" -> elementChildText(input, context, output)
        "xml.element-child-cdata" -> elementChildCdata(input, context, output)
        "xml.element-child-comments" -> elementChildComments(input, context, output)
        "xml.element-child-pi" -> elementChildPi(input, context, output)
        "xml.element-descendants" -> elementDescendants(input, context, output)
        "xml.element-attributes" -> elementAttributes(input, context, output)
        "xml.element-namespace-bindings", "xml.element-in-scope-namespaces" ->
            namespaceBindings(operator.id, input, context, output)
        "xml.content-parent", "xml.attribute-element", "xml.reference-text" ->
            contentParent(input, context, output)
        "xml.text-references" -> textReferences(input, context, output)
        "xml.name-equals" -> nameEquals(operator, input, context, output)
        "xml.attribute-value-equals" -> attributeValueEquals(operator, input, context, output)
        "xml.pi-target-equals" -> piTargetEquals(operator, input, context, output)
        "xml.reference-kind-is" -> referenceKindIs(operator, input, context, output)
        "xml.reference-name-equals" -> referenceNameEquals(operator, input, context, output)
        "xml.node-kind-is" -> nodeKindIs(operator, input, context, output)
        "core.take" -> take(operator, input, context, output)
        "core.distinct-by-identity" -> distinctByIdentity(input, context, output)
        else -> throw QueryFailureException(
            QueryFailureKind.UNKNOWN_OPERATOR,
            operator = operator.id,
            version = operator.version,
        )
    }
    context.step(output.size)
    return output
}

/** `xml.document-root`: the one document element, when formation proved it
 * (query.rs:624-638). */
private fun documentRoot(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val root = context.document.root()
    if (root != null) {
        for (item in input) {
            if (item is XmlMatch.Document) {
                context.push(output, context.elementMatch(root.index))
            }
        }
    }
}

/** `xml.document-declaration`: the XML declaration, when present
 * (query.rs:640-654). */
private fun documentDeclaration(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Document) {
            val declared = context.document.declaration() ?: continue
            context.push(
                output,
                XmlMatch.Declaration(
                    node = context.nodeRef(1, NodeRole.XmlDeclaration),
                    version = declared.version,
                    encoding = declared.encoding?.second,
                    standalone = declared.standalone?.second,
                ),
            )
        }
    }
}

/** `xml.document-doctype`: the DOCTYPE occurrence, when present
 * (query.rs:656-670). */
private fun documentDoctype(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Document) {
            val doctype = context.document.doctype() ?: continue
            context.push(
                output,
                XmlMatch.Doctype(
                    node = context.nodeRef(2, NodeRole.XmlDoctype),
                    name = doctype.name.qname().asStr(),
                ),
            )
        }
    }
}

/** `xml.document-prolog` / `xml.document-epilog`: ordered prolog or epilog
 * occurrences that publish a match (processing instruction and comment)
 * (query.rs:672-695). */
private fun documentPrologEpilog(
    id: String,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val items = if (id == "xml.document-prolog") {
        context.document.prolog()
    } else {
        context.document.epilog()
    }
    for (item in input) {
        if (item is XmlMatch.Document) {
            for (prolog in items) {
                context.prologItem(prolog)?.let { context.push(output, it) }
            }
        }
    }
}

/** `xml.element-children`: every child content occurrence, mixed order
 * (query.rs:696-723). */
private fun elementChildren(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            for (child in context.elementData(index).children) {
                val matchItem = when (val content = context.document.nodes[child]) {
                    is XmlContent.Element -> context.elementMatch(child)
                    is XmlContent.Text -> textMatch(context, child, item.node)
                    is XmlContent.Cdata -> cdataMatch(context, child, item.node)
                    is XmlContent.Comment -> commentMatch(context, child, item.node)
                    is XmlContent.ProcessingInstruction -> piMatch(context, child, item.node)
                    is XmlContent.ErrorRegion -> XmlMatch.ErrorRegion(
                        node = context.nodeRef(child, NodeRole.XmlErrorRegion),
                        span = content.data.span,
                    )
                }
                context.push(output, matchItem)
            }
        }
    }
}

/** One child text occurrence match (query.rs:725-735). */
private fun textMatch(context: Context, index: Int, parent: NodeRef): XmlMatch {
    val data = (context.document.nodes[index] as XmlContent.Text).data
    return XmlMatch.Text(
        node = context.nodeRef(index, NodeRole.XmlText),
        parent = parent,
        semantic = textSemantic(data),
    )
}

/** One child CDATA occurrence match (query.rs:737-747). */
private fun cdataMatch(context: Context, index: Int, parent: NodeRef): XmlMatch {
    val data = (context.document.nodes[index] as XmlContent.Cdata).data
    return XmlMatch.Cdata(
        node = context.nodeRef(index, NodeRole.XmlCdata),
        parent = parent,
        text = data.text,
    )
}

/** One child comment occurrence match (query.rs:749-759). */
private fun commentMatch(context: Context, index: Int, parent: NodeRef): XmlMatch {
    val data = (context.document.nodes[index] as XmlContent.Comment).data
    return XmlMatch.Comment(
        node = context.nodeRef(index, NodeRole.XmlComment),
        parent = parent,
        text = data.text,
    )
}

/** One child processing-instruction match (query.rs:761-772). */
private fun piMatch(context: Context, index: Int, parent: NodeRef): XmlMatch {
    val data = (context.document.nodes[index] as XmlContent.ProcessingInstruction).data
    return XmlMatch.ProcessingInstruction(
        node = context.nodeRef(index, NodeRole.XmlProcessingInstruction),
        parent = parent,
        target = data.target,
        content = data.content?.second,
    )
}

/** `xml.element-child-elements`: child element occurrences only
 * (query.rs:774-792). */
private fun elementChildElements(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            for (child in context.elementData(index).children) {
                if (context.document.nodes[child] is XmlContent.Element) {
                    context.push(output, context.elementMatch(child))
                }
            }
        }
    }
}

/** `xml.element-child-text`: child text occurrences only (query.rs:794-812). */
private fun elementChildText(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            for (child in context.elementData(index).children) {
                if (context.document.nodes[child] is XmlContent.Text) {
                    context.push(output, textMatch(context, child, item.node))
                }
            }
        }
    }
}

/** `xml.element-child-cdata`: child CDATA occurrences only (query.rs:814-832). */
private fun elementChildCdata(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            for (child in context.elementData(index).children) {
                if (context.document.nodes[child] is XmlContent.Cdata) {
                    context.push(output, cdataMatch(context, child, item.node))
                }
            }
        }
    }
}

/** `xml.element-child-comments`: child comment occurrences only
 * (query.rs:834-852). */
private fun elementChildComments(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            for (child in context.elementData(index).children) {
                if (context.document.nodes[child] is XmlContent.Comment) {
                    context.push(output, commentMatch(context, child, item.node))
                }
            }
        }
    }
}

/** `xml.element-child-pi`: child processing-instruction occurrences only
 * (query.rs:854-875). */
private fun elementChildPi(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            for (child in context.elementData(index).children) {
                if (context.document.nodes[child] is XmlContent.ProcessingInstruction) {
                    context.push(output, piMatch(context, child, item.node))
                }
            }
        }
    }
}

/** `xml.element-descendants`: bounded pre-order traversal with an explicit
 * stack; the input element itself is never included (query.rs:877-903). */
private fun elementDescendants(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val stack = ArrayList<Int>()
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            stack.add(index)
            while (stack.isNotEmpty()) {
                val current = stack.removeAt(stack.size - 1)
                for (child in context.elementData(current).children.asReversed()) {
                    if (context.document.nodes[child] is XmlContent.Element) {
                        stack.add(child)
                    }
                }
                if (current != index) {
                    context.push(output, context.elementMatch(current))
                }
            }
        }
    }
}

/** `xml.element-attributes`: ordered attributes, excluding declarations
 * (query.rs:905-921). */
private fun elementAttributes(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            for (attribute in context.elementData(index).attributes) {
                context.push(output, attributeMatch(attribute, item.node, context))
            }
        }
    }
}

/** `xml.element-namespace-bindings` / `xml.element-in-scope-namespaces`:
 * local declarations, or the full ancestry-derived chain oldest first
 * (query.rs:923-959). */
private fun namespaceBindings(
    id: String,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Element) {
            val index = nodeToIndex(item.node) ?: continue
            if (id == "xml.element-in-scope-namespaces") {
                // Ancestry-derived in-scope bindings, oldest declaration
                // first, each with its true origin.
                val chain = ArrayList<Int>()
                var current: Int? = index
                while (current != null) {
                    chain.add(current)
                    current = context.document.parentOf(current)
                }
                for (at in chain.asReversed()) {
                    val element = context.nodeRef(at, NodeRole.XmlElement)
                    for (binding in context.elementData(at).namespaces) {
                        context.push(output, namespaceBindingMatch(binding, element, context))
                    }
                }
            } else {
                for (binding in context.elementData(index).namespaces) {
                    context.push(output, namespaceBindingMatch(binding, item.node, context))
                }
            }
        }
    }
}

/** One namespace binding match on one owning element (query.rs:961-976). */
private fun namespaceBindingMatch(
    binding: XmlNamespaceBindingData,
    element: NodeRef,
    context: Context,
): XmlMatch =
    XmlMatch.NamespaceBinding(
        node = context.nodeRef(binding.ordinal.toInt(), NodeRole.XmlNamespaceBinding),
        element = element,
        prefix = binding.prefix,
        uri = binding.uri,
    )

/** `xml.content-parent` / `xml.attribute-element` / `xml.reference-text`:
 * one step back to the owning element (query.rs:978-1004). */
private fun contentParent(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        when (item) {
            is XmlMatch.Attribute -> context.push(output, elementFromNode(context, item.element))
            is XmlMatch.NamespaceBinding ->
                context.push(output, elementFromNode(context, item.element))
            is XmlMatch.Text,
            is XmlMatch.Cdata,
            is XmlMatch.Comment,
            is XmlMatch.ProcessingInstruction,
            is XmlMatch.Element,
            is XmlMatch.Reference,
            -> {
                val parent = when (item) {
                    is XmlMatch.Text -> item.parent
                    is XmlMatch.Cdata -> item.parent
                    is XmlMatch.Comment -> item.parent
                    is XmlMatch.ProcessingInstruction -> item.parent
                    is XmlMatch.Element -> item.parent
                    is XmlMatch.Reference -> item.parent
                    else -> null
                }
                if (parent != null) {
                    context.push(output, elementFromNode(context, parent))
                }
            }
            else -> {}
        }
    }
}

/** `xml.text-references`: the ordered reference occurrences of one text
 * (query.rs:1006-1053). */
private fun textReferences(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    for (item in input) {
        if (item is XmlMatch.Text) {
            val index = nodeToIndex(item.node) ?: continue
            val data = (context.document.nodes[index] as? XmlContent.Text)?.data ?: continue
            for ((ordinal, fragment) in data.fragments.withIndex()) {
                val (kind, name, resolved) = when (fragment) {
                    is ReferenceFragment.CharacterReference -> Triple(
                        XmlReferenceKind.Character,
                        "&#x${fragment.resolved.code.toString(16).uppercase()};",
                        fragment.resolved.toString(),
                    )
                    is ReferenceFragment.PredefinedEntity -> Triple(
                        XmlReferenceKind.Predefined,
                        fragment.name,
                        fragment.resolved,
                    )
                    is ReferenceFragment.GeneralEntity -> Triple(
                        XmlReferenceKind.General,
                        fragment.name,
                        fragment.resolved,
                    )
                    is ReferenceFragment.Literal -> continue
                }
                context.push(
                    output,
                    XmlMatch.Reference(
                        node = context.nodeRef(ordinal, NodeRole.XmlEntityReference),
                        text = item.node,
                        parent = item.parent,
                        kind = kind,
                        name = name,
                        resolved = resolved,
                    ),
                )
            }
        }
    }
}

/** `xml.name-equals`: original-spelling or expanded-name comparison
 * (query.rs:1055-1114). */
private fun nameEquals(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val expectedPrefix = stringArgument(operator, "prefix")
    val expectedLocal = stringArgument(operator, "local")
    val expectedNamespace = stringArgument(operator, "namespace")
    val comparison = stringArgument(operator, "comparison")
    for (item in input) {
        val matches = when (item) {
            is XmlMatch.Element -> {
                if (comparison == "OriginalExact") {
                    (item.prefix ?: "") == expectedPrefix && item.local == expectedLocal
                } else if (comparison == "Expanded" && !item.namespaceError) {
                    (item.namespace ?: "") == expectedNamespace && item.local == expectedLocal
                } else {
                    false
                }
            }
            is XmlMatch.Attribute -> {
                if (comparison == "OriginalExact") {
                    (item.prefix ?: "") == expectedPrefix && item.local == expectedLocal
                } else if (comparison == "Expanded") {
                    (item.namespace ?: "") == expectedNamespace && item.local == expectedLocal
                } else {
                    false
                }
            }
            else -> false
        }
        if (matches) {
            context.push(output, item)
        }
    }
}

/** `xml.attribute-value-equals`: CDATA-normalized value equality
 * (query.rs:1116-1132). */
private fun attributeValueEquals(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val expected = stringArgument(operator, "value")
    for (item in input) {
        if (item is XmlMatch.Attribute && item.value == expected) {
            context.push(output, item)
        }
    }
}

/** `xml.pi-target-equals`: processing-instruction target equality
 * (query.rs:1134-1153). */
private fun piTargetEquals(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val expected = stringArgument(operator, "target")
    for (item in input) {
        if (item is XmlMatch.ProcessingInstruction && item.target == expected) {
            context.push(output, item)
        }
    }
}

/** `xml.reference-kind-is`: reference kind equality (query.rs:1155-1177). */
private fun referenceKindIs(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val expected = when (stringArgument(operator, "kind")) {
        "Character" -> XmlReferenceKind.Character
        "Predefined" -> XmlReferenceKind.Predefined
        "General" -> XmlReferenceKind.General
        else -> throw QueryFailureException(
            QueryFailureKind.INVALID_ARGUMENT,
            operator = operator.id,
            argument = "kind",
        )
    }
    for (item in input) {
        if (item is XmlMatch.Reference && item.kind == expected) {
            context.push(output, item)
        }
    }
}

/** `xml.reference-name-equals`: reference name equality (query.rs:1179-1195). */
private fun referenceNameEquals(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val expected = stringArgument(operator, "name")
    for (item in input) {
        if (item is XmlMatch.Reference && item.name == expected) {
            context.push(output, item)
        }
    }
}

/** `xml.node-kind-is`: match-kind filter over mixed output (query.rs:1197-1228). */
private fun nodeKindIs(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val expected = stringArgument(operator, "kind")
    for (item in input) {
        val kind = when (item) {
            is XmlMatch.Document -> "document"
            is XmlMatch.Declaration -> "declaration"
            is XmlMatch.Doctype -> "doctype"
            is XmlMatch.PrologItem -> "prolog-item"
            is XmlMatch.Element -> "element"
            is XmlMatch.Attribute -> "attribute"
            is XmlMatch.NamespaceBinding -> "namespace-binding"
            is XmlMatch.Text -> "text"
            is XmlMatch.Cdata -> "cdata"
            is XmlMatch.Comment -> "comment"
            is XmlMatch.ProcessingInstruction -> "processing-instruction"
            is XmlMatch.Reference -> "reference"
            is XmlMatch.ErrorRegion -> "error-region"
        }
        if (kind == expected) {
            context.push(output, item)
        }
    }
}

/** `core.take`: the first `count` input items (query.rs:1230-1245). */
private fun take(
    operator: OperatorCall,
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val count = integerArgument(operator, "count")
    for (item in input.take(count)) {
        context.push(output, item)
    }
}

/** `core.distinct-by-identity`: first occurrence of every identity
 * (query.rs:1247-1260). */
private fun distinctByIdentity(
    input: List<XmlMatch>,
    context: Context,
    output: MutableList<XmlMatch>,
) {
    val seen = HashSet<NodeRef>()
    for (item in input) {
        if (seen.add(item.identity())) {
            context.push(output, item)
        }
    }
}

private fun elementFromNode(context: Context, node: NodeRef): XmlMatch {
    val index = nodeToIndex(node)
    if (index != null) {
        return context.elementMatch(index)
    }
    // The root document element is addressed through the root handle.
    context.document.root()?.let { return context.elementMatch(it.index) }
    return XmlMatch.Document(context.document.nodeRef())
}

private fun attributeMatch(
    attribute: XmlAttributeData,
    element: NodeRef,
    context: Context,
): XmlMatch =
    XmlMatch.Attribute(
        node = context.nodeRef(attribute.ordinal.toInt(), NodeRole.XmlAttribute),
        element = element,
        prefix = attribute.qname.prefix,
        local = attribute.qname.local,
        namespace = attribute.expanded?.namespace,
        value = attribute.normalizedValue,
    )

private fun nodeToIndex(node: NodeRef): Int? =
    node.index.takeIf { it in 0 until Int.MAX_VALUE.toLong() }?.toInt()

private fun applySyntaxOperator(
    operator: OperatorCall,
    input: List<XmlSyntaxMatch>,
    context: Context,
): List<XmlSyntaxMatch> {
    val output = ArrayList<XmlSyntaxMatch>()
    when (operator.id) {
        "xml.syntax-kind-is" -> {
            val expected = XmlSyntaxKind.fromName(stringArgument(operator, "kind"))
                ?: throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            for (item in input) {
                if (item.kind == expected) {
                    context.pushSyntax(output, item)
                }
            }
        }
        "xml.syntax-text-equals" -> {
            val expected = stringArgument(operator, "text")
            for (item in input) {
                if (decodedSpanText(context.document, item.span) == expected) {
                    context.pushSyntax(output, item)
                }
            }
        }
        "core.take" -> {
            val count = integerArgument(operator, "count")
            for (item in input.take(count)) {
                context.pushSyntax(output, item)
            }
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            for (item in input) {
                if (seen.add(item.node)) {
                    context.pushSyntax(output, item)
                }
            }
        }
        else -> throw QueryFailureException(
            QueryFailureKind.UNKNOWN_OPERATOR,
            operator = operator.id,
            version = operator.version,
        )
    }
    context.step(output.size)
    return output
}

/** Raw decoded text of one syntax piece span, decoded under the selected
 * source encoding (the conformance runner xml_v1.rs:306-323 decodes UTF-16
 * spans endianness-aware; query.rs:1378-1381 is the UTF-8 authority). */
private fun decodedSpanText(document: Document, span: Span): String {
    val bytes = document.source.rawBytes().copyOfRange(span.startByte, span.endByte)
    return when (document.source.encodingFacts.selected) {
        consema.document.SourceEncoding.Utf8 -> String(bytes, StandardCharsets.UTF_8)
        consema.document.SourceEncoding.Utf16Le -> decodeUtf16(bytes, littleEndian = true)
        consema.document.SourceEncoding.Utf16Be -> decodeUtf16(bytes, littleEndian = false)
        else -> String(bytes, StandardCharsets.UTF_8)
    }
}

/** Decodes one raw-byte span under the selected UTF-16 endianness
 * (xml_v1.rs:835-853). */
private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String {
    val content = when {
        littleEndian && bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
            bytes.copyOfRange(2, bytes.size)
        !littleEndian && bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
            bytes.copyOfRange(2, bytes.size)
        else -> bytes
    }
    if (content.size % 2 != 0) {
        return String(bytes, StandardCharsets.UTF_8)
    }
    val units = CharArray(content.size / 2)
    for (i in units.indices) {
        val high = content[i * 2].toInt() and 0xff
        val low = content[i * 2 + 1].toInt() and 0xff
        units[i] = if (littleEndian) {
            (low shl 8 or high).toChar()
        } else {
            (high shl 8 or low).toChar()
        }
    }
    return String(units)
}

private fun stringArgument(operator: OperatorCall, name: String): String =
    (operator.arguments[name] as? consema.core.PvString)?.value
        ?: throw QueryFailureException(
            QueryFailureKind.INVALID_ARGUMENT,
            operator = operator.id,
            argument = name,
        )

private fun integerArgument(operator: OperatorCall, name: String): Int =
    (operator.arguments[name] as? consema.core.PvInteger)?.value?.toInt()
        ?: throw QueryFailureException(
            QueryFailureKind.INVALID_ARGUMENT,
            operator = operator.id,
            argument = name,
        )
