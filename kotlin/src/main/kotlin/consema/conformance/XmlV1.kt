// The `consema.xml-1-0-safe.conformance@1` suite runner
// (conformance/vectors/xml-1-0-safe-v1.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/xml_v1.rs (the per-case
// dispatch is transcribed from the Rust handlers); the vector file itself
// drives every input and expectation (conformance/README.md rules 3-4).
// consema-go/go/conformance is a cross-reference only.

package consema.conformance

import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvObject
import consema.core.PvString
import consema.document.FormationStatus
import consema.document.MaterializationException
import consema.document.MaterializationFailureKind
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QuerySelection
import consema.xml.AttributePlacement
import consema.xml.CancellationToken
import consema.xml.ContentPlacement
import consema.xml.EditTransactionBuilder
import consema.xml.NameFacts
import consema.xml.ProjectionRequest
import consema.xml.ProjectionResult
import consema.xml.QueryLimits
import consema.xml.XmlContent
import consema.xml.XmlEncodingSelection
import consema.xml.XmlFormationException
import consema.xml.XmlMatch
import consema.xml.XmlParseLimits
import consema.xml.XmlProfile
import consema.xml.commit
import consema.xml.executeXmlQuery
import consema.xml.executeXmlSyntaxQuery
import consema.xml.materialize
import consema.xml.parse
import consema.xml.project

/** Runs the `consema.xml-1-0-safe.conformance@1` suite. */
fun runXml10SafeV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runXml10SafeV1Case(case)
            passed.add(case.id)
        } catch (e: CaseFailureException) {
            failed.add(CaseFailure(case.id, e.message ?: "expected behavior did not match"))
        }
    }
    return SuiteReport(
        suite = data.suite,
        semanticModel = data.semanticModel,
        expectedCases = data.cases.size,
        passed = passed,
        skipped = skipped,
        failed = failed,
    )
}

private fun runXml10SafeV1Case(case: CaseData) {
    when (case.capability) {
        "xml.formation@1" -> runXmlFormation(case)
        "xml.syntax-query@1" -> runXmlSyntaxQuery(case)
        "xml.native-query@1" -> runXmlNativeQuery(case)
        "xml.projection@1" -> runXmlProjection(case)
        "xml.materialization@1" -> runXmlMaterialization(case)
        "xml.edit@1" -> runXmlEdit(case)
        // Every published limit vector is formation-class today
        // (xml_v1.rs): the vocabulary expresses
        // input.amplification_ratio and input.max_mixed_content_items, both
        // resolved into the formation contract.
        "xml.limit@1" -> runXmlFormation(case)
        else -> fail("runner does not recognize published case")
    }
}

// ---------------------------------------------------------------------------
// Formation
// ---------------------------------------------------------------------------

private fun runXmlFormation(case: CaseData) {
    val document = xmlFormDocument(case)
    val status = expectedString(case, "status") ?: fail("missing expected.status")
    val actualStatus = when (document.formationStatus()) {
        FormationStatus.Complete -> "Complete"
        FormationStatus.Recovered -> "Recovered"
    }
    ensure(actualStatus == status)
    if (status == "Complete") {
        expectedString(case, "render")?.let { render ->
            ensure(String(document.render(), Charsets.UTF_8) == render)
        }
        expectedString(case, "render_hex")?.let { hex ->
            ensure(toHex(document.render()) == hex)
        }
    }
    expectedString(case, "diagnostic")?.let { diagnostic ->
        ensure(document.diagnostics().any { it.code == diagnostic })
    }
}

private fun xmlFormDocument(case: CaseData): consema.xml.Document {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val bytes: ByteArray = when (inputString(case, "encoding")) {
        "utf16le-bom" -> utf16LeBytes(source)
        else -> source.toByteArray(Charsets.UTF_8)
    }
    return try {
        parse(
            bytes,
            XmlProfile.SafeV1,
            XmlEncodingSelection.ProfileDefault,
            xmlParseLimits(case),
        )
    } catch (e: XmlFormationException) {
        fail("xml formation failed: ${e.code}")
    }
}

private fun utf16LeBytes(source: String): ByteArray {
    val bytes = ByteArray(2 + source.length * 2)
    bytes[0] = 0xFF.toByte()
    bytes[1] = 0xFE.toByte()
    for (i in source.indices) {
        val unit = source[i].code
        bytes[2 + i * 2] = (unit and 0xff).toByte()
        bytes[2 + i * 2 + 1] = ((unit ushr 8) and 0xff).toByte()
    }
    return bytes
}

/** Formation-class limit facts of one vector case (xml_v1.rs). */
private fun xmlParseLimits(case: CaseData): XmlParseLimits {
    var limits = XmlParseLimits.default
    longField(case.input, "amplification_ratio")?.let {
        limits = limits.copy(maxEntityAmplificationRatio = it)
    }
    longField(case.input, "max_mixed_content_items")?.let {
        limits = limits.copy(maxMixedContentItems = it.toInt())
    }
    return limits
}

// ---------------------------------------------------------------------------
// Query
// ---------------------------------------------------------------------------

private fun runXmlSyntaxQuery(case: CaseData) {
    val document = xmlFormDocument(case)
    ensure(document.formationStatus() == FormationStatus.Complete)
    val executable = xmlExecutable(xmlFilterExpression(case), Domains.xmlLosslessSyntaxV1())
    val matches = try {
        executeXmlSyntaxQuery(executable, document)
    } catch (e: QueryFailureException) {
        fail("xml syntax query: ${e.kind.code}")
    }
    val expectedMatches = expectedSequence(case, "matches") ?: fail("missing expected.matches")
    ensure(matches.size == expectedMatches.size)
    for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
        val kind = stringField(expectedMatch, "kind") ?: fail("missing expected match kind")
        ensure(actual.kind.asStr() == kind)
        stringField(expectedMatch, "text")?.let { text ->
            val raw = document.source().rawBytes()
                .copyOfRange(actual.span.startByte, actual.span.endByte)
            ensure(decodeXmlSpanText(document, raw) == text)
        }
    }
}

private fun runXmlNativeQuery(case: CaseData) {
    val document = xmlFormDocument(case)
    ensure(document.formationStatus() == FormationStatus.Complete)
    val executable = xmlExecutable(xmlFilterExpression(case), Domains.xmlNativeV1())
    val matches = try {
        executeXmlQuery(executable, document)
    } catch (e: QueryFailureException) {
        fail("xml native query: ${e.kind.code}")
    }
    val expectedMatches = expectedSequence(case, "matches") ?: fail("missing expected.matches")
    ensure(matches.size == expectedMatches.size)
    for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
        stringField(expectedMatch, "local")?.let { local ->
            val actualLocal = when (actual) {
                is XmlMatch.Element -> actual.local
                is XmlMatch.Attribute -> actual.local
                else -> fail("unexpected match kind")
            }
            ensure(actualLocal == local)
        }
        stringField(expectedMatch, "value")?.let { value ->
            val actualValue = (actual as? XmlMatch.Attribute)?.value
                ?: fail("expected attribute match")
            ensure(actualValue == value)
        }
    }
}

/** Builds the ordered filter chain (xml_v1.rs). */
private fun xmlFilterExpression(case: CaseData): QueryExpression {
    val filters = inputSequence(case, "filters") ?: fail("missing input.filters")
    var expression = QueryExpression(ExpressionKind.Input)
    for (filter in filters) {
        val operator = stringField(filter, "operator") ?: fail("missing filter.operator")
        val call = OperatorCall(operator, 1)
        stringField(filter, "argument")?.let { argument ->
            when (operator) {
                "xml.syntax-kind-is" -> call.withArgument("kind", PvString(argument))
                "xml.syntax-text-equals" -> call.withArgument("text", PvString(argument))
                else -> call.withArgument("argument", PvString(argument))
            }
        }
        expression = expression.then(call)
    }
    return expression
}

private fun xmlExecutable(expression: QueryExpression, domain: QueryDomain): ExecutableQuery {
    val capabilities = CapabilitySet()
    capabilities.insert(CapabilityId("core.query.ordered-results", 1))
    return try {
        QueryDefinition(domain)
            .withExpression(expression)
            .withSelection(QuerySelection.All)
            .validate()
            .let { ExecutableQuery.bind(it, capabilities) }
    } catch (e: QueryFailureException) {
        fail("xml query validation: ${e.kind.code}")
    }
}

/** Decodes one raw-byte span under the selected source encoding
 * (xml_v1.rs). */
private fun decodeXmlSpanText(document: consema.xml.Document, bytes: ByteArray): String =
    when (document.source().encodingFacts.selected) {
        SourceEncoding.Utf8 -> String(bytes, Charsets.UTF_8)
        SourceEncoding.Utf16Le -> decodeXmlUtf16(bytes, littleEndian = true)
        SourceEncoding.Utf16Be -> decodeXmlUtf16(bytes, littleEndian = false)
        else -> fail("syntax-query text assertions do not support the selected source encoding")
    }

private fun decodeXmlUtf16(bytes: ByteArray, littleEndian: Boolean): String {
    if (bytes.size % 2 != 0) {
        fail("UTF-16 span has odd byte length")
    }
    val content = when {
        littleEndian && bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
            bytes.copyOfRange(2, bytes.size)
        !littleEndian && bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
            bytes.copyOfRange(2, bytes.size)
        else -> bytes
    }
    val units = CharArray(content.size / 2)
    for (i in units.indices) {
        val high = content[i * 2].toInt() and 0xff
        val low = content[i * 2 + 1].toInt() and 0xff
        units[i] = if (littleEndian) {
            ((low shl 8) or high).toChar()
        } else {
            ((high shl 8) or low).toChar()
        }
    }
    return String(units)
}

// ---------------------------------------------------------------------------
// Projection
// ---------------------------------------------------------------------------

private fun runXmlProjection(case: CaseData) {
    val document = xmlFormDocument(case)
    val record = case.expected as? PvObject ?: fail("expected must be an object")
    expectedString(case, "failure")?.let { failure ->
        val failed = document.project(ProjectionRequest.elementTree()) as? ProjectionResult.Failed
            ?: fail("projection must fail")
        val code = failed.attempt.diagnostics.firstOrNull()?.code
            ?: fail("projection failure without diagnostics")
        ensure(code == failure)
        return
    }
    val projection = document.project(ProjectionRequest.elementTree()) as? ProjectionResult.Complete
        ?: fail("projection must complete")
    val projected = projection.projection.value as? PvObject ?: fail("record object")
    stringField(record, "record")?.let { recordId ->
        val actual = (projected.get("record") as? PvString)?.value ?: fail("missing record field")
        ensure(actual == recordId)
    }
    val rootValue = projected.get("root") as? PvObject ?: fail("missing root")
    stringField(record, "root_local")?.let { rootLocal ->
        val name = rootValue.get("expanded-name") as? PvObject ?: fail("missing expanded-name")
        val local = (name.get("local") as? PvString)?.value ?: fail("missing expanded-name.local")
        ensure(local == rootLocal)
    }
    stringField(record, "root_namespace")?.let { rootNamespace ->
        val name = rootValue.get("expanded-name") as? PvObject ?: fail("missing expanded-name")
        val namespace = (name.get("namespace") as? PvString)?.value
            ?: fail("missing expanded-name.namespace")
        ensure(namespace == rootNamespace)
    }
    stringField(record, "root_attribute_value")?.let { attributeValue ->
        val attributes = (rootValue.get("attributes") as? PvArray)?.items()
            ?: fail("missing attributes")
        val value = stringField(attributes.firstOrNull(), "value") ?: fail("missing attribute value")
        ensure(value == attributeValue)
    }
    (sequenceField(record, "content_kinds"))?.let { contentKinds ->
        val content = (rootValue.get("content") as? PvArray)?.items() ?: fail("missing content")
        ensure(content.size == contentKinds.size)
        for ((item, expectedKind) in content.zip(contentKinds)) {
            val expectedKindText = (expectedKind as? PvString)?.value
                ?: fail("content kind must be a string")
            val actualKind = if (objectField(item, "expanded-name") != null) {
                "element"
            } else {
                stringField(item, "kind") ?: fail("missing content kind")
            }
            ensure(actualKind == expectedKindText)
        }
    }
}

// ---------------------------------------------------------------------------
// Materialization
// ---------------------------------------------------------------------------

private fun runXmlMaterialization(case: CaseData) {
    val record = caseInput(case, "record") ?: fail("missing input.record")
    val request = MaterializationRequest.new(
        ProfileId("xml.1.0-safe", 1),
        MaterializationStyleId("xml.safe-canonical-document", 1),
    )
    val result = materialize(record, request)
    expectedString(case, "failure")?.let { failure ->
        val failed = result as? MaterializationResult.Failed ?: fail("materialization must fail")
        ensure(materializationFailureSpelling(failed.attempt.failure) == failure)
        // A failed attempt never claims to have analyzed more input than the
        // request's node budget (xml_v1.rs).
        ensure(failed.attempt.analyzedInputPaths.size <= request.limits.maxInputNodes)
        return
    }
    val complete = result as? MaterializationResult.Complete ?: fail("materialization must complete")
    val render = expectedString(case, "render") ?: fail("missing expected.render")
    ensure(String(complete.materialization.document.render(), Charsets.UTF_8) == render)
}

/** The stable vector spelling of one materialization failure
 * (xml_v1.rs). */
private fun materializationFailureSpelling(failure: MaterializationException): String =
    when (failure.kind) {
        MaterializationFailureKind.INVALID_REQUEST -> "invalid-record"
        MaterializationFailureKind.UNSUPPORTED_PROFILE -> "unsupported-profile"
        MaterializationFailureKind.UNSUPPORTED_STYLE -> "unsupported-style"
        MaterializationFailureKind.UNSUPPORTED_ENCODING -> "unsupported-encoding"
        MaterializationFailureKind.UNSUPPORTED_NEWLINE -> "unsupported-newline"
        MaterializationFailureKind.UNREPRESENTABLE -> "unrepresentable"
        MaterializationFailureKind.RESOURCE_LIMIT -> "resource-limit"
        MaterializationFailureKind.FORMATION_FAILED -> "formation-failed"
    }

// ---------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------

private fun runXmlEdit(case: CaseData) {
    val document = xmlFormDocument(case)
    ensure(document.formationStatus() == FormationStatus.Complete)
    val operations = inputSequence(case, "operations") ?: fail("missing input.operations")
    val builder = EditTransactionBuilder.new(document)
    for (operation in operations) {
        when (stringField(operation, "op")) {
            "replace-text" -> {
                val target = findXmlText(document, occurrenceOrdinal(operation, "text"))
                builder.replaceText(target, stringField(operation, "value") ?: fail("missing value"))
            }
            "insert-attribute" -> {
                val element = stringField(operation, "element") ?: fail("missing element")
                val name = stringField(operation, "name") ?: fail("missing name")
                val value = stringField(operation, "value") ?: fail("missing value")
                val target = findXmlElement(document, element, operationOrdinal(operation))
                val placement = when (stringField(operation, "placement") ?: "End") {
                    "End" -> AttributePlacement.End
                    "Before" -> AttributePlacement.Before(
                        findAnchorAttribute(document, target, anchorName(operation)),
                    )
                    "After" -> AttributePlacement.After(
                        findAnchorAttribute(document, target, anchorName(operation)),
                    )
                    else -> fail("unknown placement")
                }
                builder.insertAttribute(target, NameFacts(null, name, null), value, placement)
            }
            "remove-attribute" -> {
                val name = stringField(operation, "attribute") ?: fail("missing attribute")
                builder.removeAttribute(findXmlAttribute(document, name, operationOrdinal(operation)))
            }
            "rename-attribute" -> {
                val from = stringField(operation, "attribute") ?: fail("missing attribute")
                val to = stringField(operation, "to") ?: fail("missing to")
                builder.renameAttribute(
                    findXmlAttribute(document, from, operationOrdinal(operation)),
                    NameFacts(null, to, null),
                )
            }
            "set-attribute-value" -> {
                val name = stringField(operation, "attribute") ?: fail("missing attribute")
                val value = stringField(operation, "value") ?: fail("missing value")
                builder.setAttributeValue(
                    findXmlAttribute(document, name, operationOrdinal(operation)),
                    value,
                )
            }
            "insert-element" -> {
                val root = document.root()?.nodeRef() ?: fail("missing root")
                val name = stringField(operation, "name") ?: fail("missing name")
                val content = stringField(operation, "content")
                builder.insertElement(root, NameFacts(null, name, null), content, ContentPlacement.End)
            }
            "remove-element" -> {
                val name = stringField(operation, "name") ?: fail("missing name")
                builder.removeElement(findXmlElement(document, name, operationOrdinal(operation)))
            }
            "rename-element" -> {
                val from = stringField(operation, "from") ?: fail("missing from")
                val to = stringField(operation, "to") ?: fail("missing to")
                builder.renameElement(
                    findXmlElement(document, from, operationOrdinal(operation)),
                    NameFacts(null, to, null),
                )
            }
            else -> fail("unknown edit op")
        }
    }
    val commit = try {
        document.commit(builder.build())
    } catch (e: consema.xml.EditFailureException) {
        fail("xml edit commit: ${e.failure.name}")
    }
    val render = expectedString(case, "render") ?: fail("missing expected.render")
    ensure(String(commit.document.render(), Charsets.UTF_8) == render)
}

/** Optional `"ordinal": N` occurrence selector; absent means the first (0). */
private fun operationOrdinal(operation: PortableValue): Long = occurrenceOrdinal(operation, "ordinal")

/** Reads an optional occurrence selector under `name` (xml_v1.rs). */
private fun occurrenceOrdinal(operation: PortableValue, name: String): Long =
    longField(operation, name) ?: 0L

/** The anchor attribute name for a Before/After insertion placement. */
private fun anchorName(operation: PortableValue): String =
    stringField(operation, "anchor") ?: fail("missing anchor")

/** Resolves the `ordinal`-th attribute with `name` in document order. */
private fun findXmlAttribute(document: consema.xml.Document, name: String, ordinal: Long): NodeRef {
    var occurrence = 0L
    for (content in document.nodes()) {
        if (content is XmlContent.Element) {
            for (attribute in content.data.attributes) {
                if (attribute.qname.local == name) {
                    if (occurrence == ordinal) {
                        return document.occurrenceNodeRef(attribute.ordinal, NodeRole.XmlAttribute)
                    }
                    occurrence += 1
                }
            }
        }
    }
    fail("attribute $name occurrence $ordinal not found")
}

/** Resolves the `ordinal`-th element with `name` in document order. */
private fun findXmlElement(document: consema.xml.Document, name: String, ordinal: Long): NodeRef {
    var occurrence = 0L
    for ((index, content) in document.nodes().withIndex()) {
        if (content is XmlContent.Element && content.data.qname.local == name) {
            if (occurrence == ordinal) {
                return document.occurrenceNodeRef(index.toLong(), NodeRole.XmlElement)
            }
            occurrence += 1
        }
    }
    fail("element $name occurrence $ordinal not found")
}

/** Resolves the `ordinal`-th text occurrence in document order. */
private fun findXmlText(document: consema.xml.Document, ordinal: Long): NodeRef {
    var occurrence = 0L
    for (content in document.nodes()) {
        if (content is XmlContent.Text) {
            if (occurrence == ordinal) {
                return document.occurrenceNodeRef(content.data.ordinal, NodeRole.XmlText)
            }
            occurrence += 1
        }
    }
    fail("text occurrence $ordinal not found")
}

/** Resolves one attribute anchor on exactly one element. */
private fun findAnchorAttribute(document: consema.xml.Document, element: NodeRef, name: String): NodeRef {
    val index = element.index.toInt()
    val content = document.nodes()[index]
    val data = (content as? XmlContent.Element)?.data ?: fail("anchor element is not an element")
    for (attribute in data.attributes) {
        if (attribute.qname.local == name) {
            return document.occurrenceNodeRef(attribute.ordinal, NodeRole.XmlAttribute)
        }
    }
    fail("attribute $name not found on element")
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
