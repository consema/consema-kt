// The `consema.plist.conformance@1` suite runner
// (conformance/vectors/plist-v1.json).
//
// Data authority: crates/consema-conformance/src/plist_v1.rs (the per-case
// dispatch is transcribed from the Rust handlers); the vector file itself
// drives every input and expectation (conformance/README.md rules 3-4).
//
// The suite covers both profiles (plist.xml@1, plist.binary@1): formation
// (XML and binary native facts, binary structure facts), native-semantic and
// binary-structure queries, value-tree and require-object projection,
// materialization (xml-canonical, binary-canonical, normalization,
// conversion), conversion between the representations, and structural edit
// with the untouched-byte proof, patch replay, and reparse closure.

package consema.conformance

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat32
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvDecimal
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import consema.document.FormationStatus
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.plist.BinaryTrailerFacts
import consema.plist.CancellationToken
import consema.plist.CollisionPolicy
import consema.plist.ConversionFailureException
import consema.plist.DictPlacement
import consema.plist.Document
import consema.plist.EditCommit
import consema.plist.EditFailureException
import consema.plist.EditPath
import consema.plist.EditPathStep
import consema.plist.EditTransaction
import consema.plist.EditTransactionBuilder
import consema.plist.EditValue
import consema.plist.PlistAccessException
import consema.plist.PlistBinaryMatch
import consema.plist.PlistData
import consema.plist.PlistDate
import consema.plist.PlistDictEntry
import consema.plist.PlistEncodingSelection
import consema.plist.PlistFormationException
import consema.plist.PlistKey
import consema.plist.PlistMatch
import consema.plist.PlistParseLimits
import consema.plist.PlistProfile
import consema.plist.PlistQueryOutcome
import consema.plist.PlistReal
import consema.plist.PlistString
import consema.plist.PlistStringStatus
import consema.plist.PlistUid
import consema.plist.PlistValue
import consema.plist.PlistValueKind
import consema.plist.ProjectionEventKind
import consema.plist.ProjectionRequest
import consema.plist.ProjectionResult
import consema.plist.QueryLimits
import consema.plist.TypedValue
import consema.plist.commit
import consema.plist.convertTo
import consema.plist.executePlistBinaryQuery
import consema.plist.executePlistNativeQuery
import consema.plist.materializationFailureCode
import consema.plist.materialize
import consema.plist.parse
import consema.plist.project
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.ExecutableQuery
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QuerySelection

/** Runs the `consema.plist.conformance@1` suite. */
fun runPlistV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runPlistV1Case(runner, case)
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

private fun runPlistV1Case(runner: Runner, case: CaseData) {
    when (case.id) {
        "plist.xml-formation.all-value-types",
        "plist.xml-formation.doctype-exact",
        "plist.xml-formation.duplicate-keys",
        "plist.xml-formation.real-special-values",
        "plist.xml-formation.string-text-facts",
        "plist.xml-formation.trailing-content",
        "plist.xml-formation.utf16le-input",
        -> runXmlFormation(case)
        "plist.xml-formation.doctype-violations",
        "plist.xml-formation.root-contracts",
        "plist.xml-formation.unknown-element",
        "plist.xml-formation.integer-matrix",
        "plist.xml-formation.date-matrix",
        "plist.xml-formation.base64-matrix",
        "plist.xml-formation.key-pair",
        "plist.xml-formation.empty-value-matrix",
        -> runXmlFormation(case)
        "plist.binary-formation.minimal-document",
        "plist.binary-formation.all-types-document",
        "plist.binary-formation.shared-reference",
        "plist.binary-formation.duplicate-keys",
        -> runBinaryFormation(case)
        "plist.binary-formation.integer-width-matrix",
        "plist.binary-formation.strings-matrix",
        "plist.binary-formation.uid-matrix",
        "plist.binary-formation.rejected-markers",
        "plist.binary-formation.header-and-trailer",
        "plist.binary-formation.offset-and-reference",
        "plist.binary-formation.extended-size-and-cycle",
        "plist.binary-formation.value-integrity",
        -> runBinaryFormation(case)
        "plist.query.dict-entries-order",
        "plist.query.typed-accessors",
        "plist.query.binary-structure",
        -> runQuery(case)
        "plist.projection.value-tree-record",
        "plist.projection.require-object-policies",
        "plist.projection.atomic-failures",
        -> runProjection(case)
        "plist.materialization.xml-canonical-text",
        "plist.materialization.binary-canonical-hex",
        "plist.materialization.normalization-and-conversion",
        "plist.materialization.fractional-date-policy",
        "plist.materialization.old-record-shape-rejected",
        -> runMaterialization(case)
        "plist.edit.xml-six-operations",
        "plist.edit.binary-structural",
        "plist.edit.conflicts",
        -> runEdit(case)
        "plist.conversion.xml-to-binary-round-trip",
        "plist.conversion.binary-to-xml-round-trip",
        "plist.conversion.uid-inexpressible-to-xml",
        "plist.conversion.duplicate-keys-preserved",
        -> runConversion(case)
        else -> fail("runner does not recognize published case")
    }
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private fun statusName(status: FormationStatus): String =
    when (status) {
        FormationStatus.Complete -> "Complete"
        FormationStatus.Recovered -> "Recovered"
    }

private fun expectedStringField(expected: PortableValue?, name: String): String? =
    stringField(expected, name)

private fun expectedBooleanField(expected: PortableValue?, name: String): Boolean? =
    booleanField(expected, name)

private fun expectedLongField(expected: PortableValue?, name: String): Long? =
    longField(expected, name)

private fun expectedSequenceField(expected: PortableValue?, name: String): List<PortableValue>? =
    sequenceField(expected, name)

private fun expectedF64Field(expected: PortableValue?, name: String): Double? =
    expectedF64(objectField(expected, name))

/** Exact double of one expected numeric fact (binary float or decimal). */
private fun expectedF64(value: PortableValue?): Double? =
    when (value) {
        is PvBinaryFloat64 -> value.toFloat()
        is PvBinaryFloat32 -> value.toFloat().toDouble()
        is PvDecimal -> decimalToF64(value)
        else -> null
    }

/** Converts one exact decimal to its double value; null when the coefficient
 * or exponent exceeds the exact Long range. */
private fun decimalToF64(decimal: PvDecimal): Double? {
    val coefficient = runCatching { decimal.coefficient.toLong() }.getOrNull() ?: return null
    val exponent = runCatching { decimal.exponent.toLong() }.getOrNull() ?: return null
    var value = coefficient.toDouble()
    when {
        exponent > 0 -> value *= Math.pow(10.0, exponent.toDouble().coerceAtMost(308.0))
        exponent < 0 -> value /= Math.pow(10.0, (-exponent).toDouble().coerceAtMost(308.0))
    }
    return value
}

/** Exact bit equality of two doubles; every published numeric fact is an
 * exactly representable value, so bit equality is the strict comparison. */
private fun bitsEqual(left: Double, right: Double): Boolean =
    java.lang.Double.doubleToRawLongBits(left) == java.lang.Double.doubleToRawLongBits(right)

private fun assertStrings(actual: List<String>, expected: List<PortableValue>, what: String) {
    ensure(actual.size == expected.size)
    for ((actualItem, expectedItem) in actual.zip(expected)) {
        val expectedText = (expectedItem as? PvString)?.value ?: fail("$what must be a string")
        ensure(actualItem == expectedText)
    }
}

private fun assertU64Field(expected: PortableValue?, name: String, actual: Long) {
    expectedLongField(expected, name)?.let { expectedValue ->
        ensure(actual == expectedValue)
    }
}

/** One vector fact from a value or its `input` member. */
private fun vectorField(value: PortableValue?, name: String): PortableValue? =
    objectField(value, name) ?: objectField(objectField(value, "input"), name)

private fun profileOf(value: PortableValue?): PlistProfile =
    when ((vectorField(value, "profile") as? PvString)?.value) {
        "plist.xml@1" -> PlistProfile.XmlV1
        "plist.binary@1" -> PlistProfile.BinaryV1
        null -> fail("missing profile")
        else -> fail("unknown profile")
    }

/** Raw source bytes of one vector input or sample: `source` (with the
 * optional `utf16le-bom` encoding) for the XML profile, `hex` for the binary
 * profile. */
private fun sourceBytes(value: PortableValue?): ByteArray = when (profileOf(value)) {
    PlistProfile.BinaryV1 -> {
        val text = (vectorField(value, "hex") as? PvString)?.value ?: fail("missing input.hex")
        decodeHex(text) ?: fail("invalid hex")
    }
    PlistProfile.XmlV1 -> {
        val source = (vectorField(value, "source") as? PvString)?.value ?: fail("missing input.source")
        if ((vectorField(value, "encoding") as? PvString)?.value == "utf16le-bom") {
            val bytes = ByteArray(source.length * 2 + 2)
            bytes[0] = 0xFF.toByte()
            bytes[1] = 0xFE.toByte()
            for ((index, unit) in source.withIndex()) {
                val code = unit.code
                bytes[index * 2 + 2] = (code and 0xFF).toByte()
                bytes[index * 2 + 3] = ((code ushr 8) and 0xFF).toByte()
            }
            bytes
        } else {
            source.toByteArray(Charsets.UTF_8)
        }
    }
}

/** Forms one document from a case-level input or one sample descriptor. */
private fun formValue(value: PortableValue?): Document =
    try {
        parse(
            sourceBytes(value),
            profileOf(value),
            PlistEncodingSelection.ProfileDefault,
            PlistParseLimits.default,
        )
    } catch (e: PlistFormationException) {
        fail("formation failed: ${e.code}")
    }

private fun formCase(case: CaseData): Document = formValue(case.input)

/** One sample's profile: samples without their own `profile` fact inherit the
 * case-level input profile. */
private fun sampleProfile(case: CaseData, sample: PortableValue): PlistProfile =
    when ((objectField(sample, "profile") as? PvString)?.value) {
        "plist.xml@1" -> PlistProfile.XmlV1
        "plist.binary@1" -> PlistProfile.BinaryV1
        null -> profileOf(case.input)
        else -> fail("unknown profile")
    }

private fun formSample(case: CaseData, sample: PortableValue): Document {
    val profile = sampleProfile(case, sample)
    val bytes = when (profile) {
        PlistProfile.BinaryV1 -> {
            val text = (objectField(sample, "hex") as? PvString)?.value ?: fail("missing sample hex")
            decodeHex(text) ?: fail("invalid hex")
        }
        PlistProfile.XmlV1 -> {
            (objectField(sample, "source") as? PvString)?.value
                ?.toByteArray(Charsets.UTF_8)
                ?: fail("missing sample source")
        }
    }
    return try {
        parse(bytes, profile, PlistEncodingSelection.ProfileDefault, PlistParseLimits.default)
    } catch (e: PlistFormationException) {
        fail("formation failed: ${e.code}")
    }
}

/** Asserts the `expected.status` and optional `expected.diagnostic` facts. */
private fun assertExpectedStatus(document: Document, expected: PortableValue?) {
    expectedStringField(expected, "status")?.let { status ->
        ensure(statusName(document.formationStatus()) == status)
    }
    expectedStringField(expected, "diagnostic")?.let { diagnostic ->
        ensure(document.diagnostics().any { it.code == diagnostic })
    }
}

private fun rootValue(document: Document): PlistValue = document.root()

private fun dictEntriesOf(value: PlistValue): List<PlistDictEntry> =
    value.dictEntries() ?: fail("expected dict")

private fun dictKeysOf(value: PlistValue): List<String> =
    dictEntriesOf(value).map { entry ->
        entry.key()?.toUnicode() ?: fail("key not unicode")
    }

private fun entryByKey(root: PlistValue, name: String): PlistValue {
    for (entry in dictEntriesOf(root)) {
        if (entry.key()?.toUnicode() == name) {
            return entry.value()
        }
    }
    fail("dict entry $name not found")
}

private fun duplicateGroupsOf(entries: List<PlistDictEntry>): Int {
    val counts = HashMap<String, Int>()
    for (entry in entries) {
        val key = entry.key()?.toUnicode() ?: continue
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts.values.count { it > 1 }
}

private fun valueKindName(value: PlistValue): String? = value.kind()?.kindName()

private fun valueText(value: PlistValue): String? = value.asString()?.toUnicode()

private fun valueInteger(value: PlistValue): Long? = value.asInteger()

private fun valueReal(value: PlistValue): Double? = value.asReal()?.asDouble()

private fun valueBoolean(value: PlistValue): Boolean? = value.asBoolean()

private fun valueDataHex(value: PlistValue): String? =
    value.asData()?.bytes()?.let { toHex(it) }

private fun valueSeconds(value: PlistValue): Double? = value.asDateSeconds()

/** Compares one native scalar against one expected portable scalar fact. */
private fun compareScalarValue(value: PlistValue, expected: PortableValue) {
    when (expected) {
        is PvString -> ensure(valueText(value) == expected.value)
        is PvInteger -> ensure(valueInteger(value) == expected.value.toLong())
        is PvBoolean -> ensure(valueBoolean(value) == expected.value)
        else -> fail("unsupported expected scalar")
    }
}

// ---------------------------------------------------------------------------
// XML formation
// ---------------------------------------------------------------------------

private fun runXmlFormation(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runXmlFormationSamples(case, samples, expected)
        return
    }
    val document = formCase(case)
    assertExpectedStatus(document, expected)
    if (document.formationStatus() == FormationStatus.Complete) {
        expectedString(case, "render")?.let { render ->
            val actual = document.render().toString(Charsets.UTF_8)
            ensure(actual == render)
        }
        expectedString(case, "render_hex")?.let { hex ->
            ensure(toHex(document.render()) == hex)
        }
        runXmlNativeFacts(document, expected)
    }
}

private fun runXmlFormationSamples(
    case: CaseData,
    samples: List<PortableValue>,
    expected: PortableValue,
) {
    val statuses = expectedSequenceField(expected, "statuses") ?: fail("missing expected.statuses")
    val diagnostics = expectedSequenceField(expected, "diagnostics") ?: fail("missing expected.diagnostics")
    ensure(samples.size == statuses.size && samples.size == diagnostics.size)
    val integers = expectedSequenceField(expected, "integers")
    val seconds = expectedSequenceField(expected, "seconds")
    val dataHexes = expectedSequenceField(expected, "data_hexes")
    val values = expectedSequenceField(expected, "values")
    for ((index, sample) in samples.withIndex()) {
        val document = formSample(case, sample)
        val status = (statuses[index] as? PvString)?.value ?: fail("status must be a string")
        ensure(statusName(document.formationStatus()) == status)
        (diagnostics[index] as? PvString)?.let { code ->
            ensure(document.diagnostics().any { it.code == code.value })
        }
        if (status == "Complete") {
            val root = rootValue(document)
            integers?.let { integersValue ->
                val expectedValue = (integersValue[index] as? PvInteger)?.value?.toLong()
                ensure(valueInteger(root) == expectedValue)
            }
            seconds?.let { secondsValue ->
                val expectedValue = expectedF64(secondsValue[index])
                ensure(valueSeconds(root) == expectedValue)
            }
            dataHexes?.let { dataHexesValue ->
                val expectedValue = (dataHexesValue[index] as? PvString)?.value
                ensure(valueDataHex(root) == expectedValue)
            }
            values?.let { valuesValue ->
                when (val expectedValue = (valuesValue[index] as? PvString)?.value) {
                    // An empty expectation admits both empty strings and
                    // empty data leaves (`<data></data>` and `<string/>`).
                    "" -> {
                        val empty = valueText(root)?.isEmpty() == true ||
                            root.asData()?.bytes()?.isEmpty() == true
                        ensure(empty)
                    }
                    null -> {}
                    else -> ensure(valueText(root) == expectedValue)
                }
            }
        }
    }
}

/** Asserts the native-model facts of one complete XML formation case. */
private fun runXmlNativeFacts(document: Document, expected: PortableValue) {
    val root = rootValue(document)
    expectedStringField(expected, "root_value")?.let { value ->
        ensure(valueText(root) == value)
    }
    expectedStringField(expected, "string_value")?.let { value ->
        ensure(valueText(root) == value)
    }
    expectedSequenceField(expected, "keys")?.let { keys ->
        assertStrings(dictKeysOf(root), keys, "key")
    }
    expectedLongField(expected, "associations")?.let { associations ->
        ensure(dictEntriesOf(root).size.toLong() == associations)
    }
    expectedLongField(expected, "duplicate_groups")?.let { groups ->
        ensure(duplicateGroupsOf(dictEntriesOf(root)).toLong() == groups)
    }
    expectedSequenceField(expected, "values")?.let { values ->
        val entries = dictEntriesOf(root)
        ensure(entries.size == values.size)
        for ((entry, expectedValue) in entries.zip(values)) {
            compareScalarValue(entry.value(), expectedValue)
        }
    }
    expectedLongField(expected, "integer_value")?.let { integer ->
        ensure(valueInteger(entryByKey(root, "count")) == integer)
    }
    expectedLongField(expected, "negative_integer")?.let { integer ->
        ensure(valueInteger(entryByKey(root, "negative")) == integer)
    }
    expectedF64Field(expected, "real_value")?.let { real ->
        ensure(valueReal(entryByKey(root, "ratio")) == real)
    }
    expectedStringField(expected, "data_hex")?.let { hex ->
        ensure(valueDataHex(entryByKey(root, "payload")) == hex)
    }
    expectedF64Field(expected, "date_seconds")?.let { seconds ->
        ensure(valueSeconds(entryByKey(root, "born")) == seconds)
    }
    expectedSequenceField(expected, "bool_values")?.let { booleans ->
        val expected: List<Boolean> = booleans.mapNotNull { (it as? PvBoolean)?.value }
        val actual: List<Boolean> = dictEntriesOf(root).mapNotNull { it.value().asBoolean() }
        ensure(actual == expected)
    }
    expectedSequenceField(expected, "nested_array")?.let { nested ->
        val array = entryByKey(root, "tags").arrayElements() ?: fail("tags must be an array")
        ensure(array.size == nested.size)
        for ((element, expectedElement) in array.zip(nested)) {
            val value = element.value()
            when (expectedElement) {
                is PvString -> ensure(valueText(value) == expectedElement.value)
                is PvObject -> ensure(
                    value.kind() == PlistValueKind.Dict && (value.dictEntries()?.isEmpty() == true),
                )
                else -> fail("unsupported nested expectation")
            }
        }
    }
    (objectField(expected, "string_values") as? PvObject)?.let { mapping ->
        for (entry in mapping.entries()) {
            val value = entryByKey(root, entry.key)
            val expectedText = (entry.value as? PvString)?.value ?: fail("expected string value")
            ensure(valueText(value) == expectedText)
        }
    }
    expectedBooleanField(expected, "line_end_normalized")?.let { normalized ->
        val value = entryByKey(root, "lines")
        val text = valueText(value) ?: fail("lines value missing")
        ensure(text.contains('\r') != normalized)
    }
    val realNeeded = expectedLongField(expected, "real_count") != null ||
        expectedBooleanField(expected, "nan_admitted") != null ||
        expectedBooleanField(expected, "infinities_admitted") != null ||
        expectedF64Field(expected, "exponent_value") != null
    val reals = if (realNeeded) {
        val array = root.arrayElements() ?: fail("root must be an array")
        array.map { it.value() }.filter { it.asReal() != null }
    } else {
        emptyList()
    }
    expectedLongField(expected, "real_count")?.let { count ->
        ensure(reals.size.toLong() == count)
    }
    expectedBooleanField(expected, "nan_admitted")?.let { admitted ->
        val actual = reals.any { it.asReal()?.asDouble()?.isNaN() == true }
        ensure(actual == admitted)
    }
    expectedBooleanField(expected, "infinities_admitted")?.let { admitted ->
        val actual = reals.any { it.asReal()?.asDouble()?.isInfinite() == true }
        ensure(actual == admitted)
    }
    expectedF64Field(expected, "exponent_value")?.let { exponent ->
        val actual = reals.any { bitsEqual(it.asReal()!!.asDouble(), exponent) }
        ensure(actual)
    }
}

// ---------------------------------------------------------------------------
// Binary formation
// ---------------------------------------------------------------------------

private fun runBinaryFormation(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runBinaryFormationSamples(case, samples, expected)
        return
    }
    val document = formCase(case)
    assertExpectedStatus(document, expected)
    document.binaryFacts()?.let { facts ->
        val trailer = facts.trailer
        assertU64Field(expected, "num_objects", trailer.numObjects)
        assertU64Field(expected, "top_object", trailer.topObject)
        assertU64Field(expected, "offset_int_size", trailer.offsetIntSize.toLong())
        assertU64Field(expected, "object_ref_size", trailer.objectRefSize.toLong())
        assertU64Field(expected, "sort_version", trailer.sortVersion.toLong())
        assertU64Field(expected, "offset_table_offset", trailer.offsetTableOffset)
        expectedSequenceField(expected, "refs_of_top")?.let { refsOfTop ->
            val top = trailer.topObject.toInt()
            val refs = facts.refs
                .filter { it.owner == top }
                .sortedBy { it.position }
                .map { it.target.toLong() }
            val expectedRefs = refsOfTop.mapNotNull { (it as? PvInteger)?.value?.toLong() }
            ensure(refs == expectedRefs)
        }
        expectedLongField(expected, "shared_ref_count")?.let { shared ->
            val counts = HashMap<Int, Int>()
            for (reference in facts.refs) {
                counts[reference.target] = (counts[reference.target] ?: 0) + 1
            }
            val sharedCount = counts.values.count { it > 1 }.toLong()
            ensure(sharedCount == shared)
        }
    }
    if (document.formationStatus() == FormationStatus.Complete) {
        runBinaryNativeFacts(document, expected)
    }
}

/** Asserts the native-model facts of one complete binary formation case. */
private fun runBinaryNativeFacts(document: Document, expected: PortableValue) {
    val root = rootValue(document)
    expectedStringField(expected, "value")?.let { value ->
        ensure(valueText(root) == value)
    }
    expectedStringField(expected, "top_kind")?.let { kind ->
        ensure(valueKindName(root) == kind)
    }
    expectedSequenceField(expected, "keys")?.let { keys ->
        assertStrings(dictKeysOf(root), keys, "key")
    }
    expectedSequenceField(expected, "values")?.let { values ->
        val entries = dictEntriesOf(root)
        ensure(entries.size == values.size)
        for ((entry, expectedValue) in entries.zip(values)) {
            compareScalarValue(entry.value(), expectedValue)
        }
    }
    expectedLongField(expected, "int_value")?.let { value ->
        ensure(valueInteger(entryByKey(root, "int")) == value)
    }
    expectedF64Field(expected, "real_value")?.let { value ->
        ensure(valueReal(entryByKey(root, "real")) == value)
    }
    expectedF64Field(expected, "f32_value")?.let { value ->
        ensure(valueReal(entryByKey(root, "f32")) == value)
    }
    expectedStringField(expected, "data_hex")?.let { hex ->
        ensure(valueDataHex(entryByKey(root, "data")) == hex)
    }
    expectedF64Field(expected, "date_seconds")?.let { value ->
        ensure(valueSeconds(entryByKey(root, "date")) == value)
    }
    expectedF64Field(expected, "fractional_seconds")?.let { value ->
        ensure(valueSeconds(entryByKey(root, "fractional")) == value)
    }
    expectedSequenceField(expected, "bool_values")?.let { booleans ->
        val entry = entryByKey(root, "bool")
        val expectedBools = booleans.mapNotNull { (it as? PvBoolean)?.value }
        // The `bool` entry is either one boolean or an array of booleans.
        val actualBools = entry.arrayElements()?.mapNotNull { it.value().asBoolean() }
            ?: entry.asBoolean()?.let { listOf(it) } ?: emptyList()
        ensure(actualBools == expectedBools)
    }
    expectedSequenceField(expected, "array_elements")?.let { elements ->
        val entry = entryByKey(root, "array")
        val array = entry.arrayElements() ?: fail("array must be an array")
        ensure(array.size == elements.size)
        for ((element, expectedElement) in array.zip(elements)) {
            val expectedInteger = (expectedElement as? PvInteger)?.value?.toLong()
                ?: fail("expected element must be an integer")
            ensure(valueInteger(element.value()) == expectedInteger)
        }
    }
    expectedStringField(expected, "str_value")?.let { value ->
        ensure(valueText(entryByKey(root, "str")) == value)
    }
}

/** Whether the root scalar object of one binary document carries a
 * non-minimal width fact (integers and UIDs, RFC 0013 §5.3, §5.8). */
private fun widthNonMinimalObserved(document: Document, root: PlistValue): Boolean? {
    val marker = document.binaryFacts()?.objects?.firstOrNull()?.marker ?: return null
    val integer = root.asInteger()
    if (integer != null) {
        val width = 1 shl (marker and 0x0F)
        val minimal = when {
            integer < 0 -> 8
            integer <= 0xFF -> 1
            integer <= 0xFFFF -> 2
            integer <= 0xFFFF_FFFFL -> 4
            else -> 8
        }
        return width > minimal
    }
    val uid = root.asUid()
    if (uid != null) {
        val width = (marker and 0x0F) + 1
        val value = uid.toLong()
        val minimal = when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFF_FFFF -> 3
            else -> 4
        }
        return width > minimal
    }
    return null
}

private fun stringStatusName(status: PlistStringStatus): String =
    when (status) {
        PlistStringStatus.WellFormedUnicode -> "WellFormedUnicode"
        PlistStringStatus.UnpairedSurrogate -> "UnpairedSurrogate"
    }

private fun runBinaryFormationSamples(
    case: CaseData,
    samples: List<PortableValue>,
    expected: PortableValue,
) {
    val statuses = expectedSequenceField(expected, "statuses") ?: fail("missing expected.statuses")
    val diagnostics = expectedSequenceField(expected, "diagnostics") ?: fail("missing expected.diagnostics")
    ensure(samples.size == statuses.size && samples.size == diagnostics.size)
    val integers = expectedSequenceField(expected, "integers")
    val strings = expectedSequenceField(expected, "strings")
    val uids = expectedSequenceField(expected, "uids")
    val documents = ArrayList<Document>(samples.size)
    for ((index, sample) in samples.withIndex()) {
        val document = formSample(case, sample)
        val status = (statuses[index] as? PvString)?.value ?: fail("status must be a string")
        ensure(statusName(document.formationStatus()) == status)
        (diagnostics[index] as? PvString)?.let { code ->
            ensure(document.diagnostics().any { it.code == code.value })
        }
        if (status == "Complete") {
            val root = rootValue(document)
            integers?.let { integersValue ->
                val expectedValue = (integersValue[index] as? PvInteger)?.value?.toLong()
                ensure(valueInteger(root) == expectedValue)
            }
            strings?.let { stringsValue ->
                val expectedValue = (stringsValue[index] as? PvString)?.value
                ensure(valueText(root) == expectedValue)
            }
            uids?.let { uidsValue ->
                val expectedValue = (uidsValue[index] as? PvInteger)?.value?.toLong()
                ensure(root.asUid()?.toLong() == expectedValue)
            }
        }
        documents.add(document)
    }
    expectedBooleanField(expected, "non_minimal_width_observed")?.let { observed ->
        val actual = documents.any { document ->
            try {
                widthNonMinimalObserved(document, rootValue(document)) == true
            } catch (e: PlistAccessException) {
                false
            }
        }
        ensure(actual == observed)
    }
    if (expectedStringField(expected, "unpaired_utf16be_hex") != null ||
        expectedStringField(expected, "unpaired_status") != null
    ) {
        val unpaired = documents.firstOrNull { document ->
            document.hasNativeValue() &&
                rootValue(document).asString()?.status == PlistStringStatus.UnpairedSurrogate
        } ?: fail("no unpaired-surrogate sample")
        val string = rootValue(unpaired).asString() ?: fail("root is not a string")
        expectedStringField(expected, "unpaired_utf16be_hex")?.let { hex ->
            ensure(toHex(string.utf16beBytes()) == hex)
        }
        expectedStringField(expected, "unpaired_status")?.let { status ->
            ensure(stringStatusName(string.status) == status)
        }
    }
    expectedBooleanField(expected, "sort_version_one_accepted")?.let { accepted ->
        val actual = documents.any { document ->
            document.formationStatus() == FormationStatus.Complete &&
                document.binaryFacts()?.trailer?.sortVersion == 1
        }
        ensure(actual == accepted)
    }
    if (expectedLongField(expected, "extended_array_length") != null ||
        expectedBooleanField(expected, "extended_count_is_object") != null
    ) {
        val document = documents.firstOrNull { it.formationStatus() == FormationStatus.Complete }
            ?: fail("no complete sample")
        val root = rootValue(document)
        expectedLongField(expected, "extended_array_length")?.let { length ->
            val array = root.arrayElements() ?: fail("root must be an array")
            ensure(array.size.toLong() == length)
        }
        expectedBooleanField(expected, "extended_count_is_object")?.let { countIsObject ->
            val facts = document.binaryFacts() ?: fail("missing binary facts")
            val marker = facts.objects.firstOrNull()?.marker ?: fail("missing object 0")
            val extended = (marker and 0x0F) == 0x0F
            ensure(extended == countIsObject)
        }
    }
}

// ---------------------------------------------------------------------------
// Query
// ---------------------------------------------------------------------------

private fun capabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

/** Builds the frozen operator vocabulary from one vector filter list. */
private fun buildFilters(filters: List<PortableValue>): List<OperatorCall> =
    filters.map { filter ->
        val operator = (objectField(filter, "operator") as? PvString)?.value
            ?: fail("missing filter.operator")
        val at = operator.lastIndexOf('@')
        if (at < 0) fail("operator lacks version: $operator")
        val name = operator.substring(0, at)
        val version = operator.substring(at + 1).toIntOrNull()
            ?: fail("invalid operator version: $operator")
        val call = OperatorCall(name, version)
        (objectField(filter, "argument") as? PvString)?.let { argument ->
            when (name) {
                "plist.dict-key-equals" -> call.withArgument("key", PvString(argument.value))
                "plist.value-type-is" -> call.withArgument("kind", PvString(argument.value))
                else -> call.withArgument("argument", PvString(argument.value))
            }
        }
        call
    }

private fun bindQuery(definition: QueryDefinition): ExecutableQuery =
    try {
        definition.validate().let { ExecutableQuery.bind(it, capabilities()) }
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }

private fun executeNative(
    document: Document,
    calls: List<OperatorCall>,
): PlistQueryOutcome<PlistMatch> {
    var expression = QueryExpression(consema.protocol.ExpressionKind.Input)
    for (call in calls) {
        expression = expression.then(call)
    }
    val definition = QueryDefinition(QueryDomain("plist.native-semantic-query", 1))
        .withExpression(expression)
        .withSelection(QuerySelection.All)
    val executable = bindQuery(definition)
    return executePlistNativeQuery(
        executable,
        document,
        QueryLimits.default,
        CancellationToken(),
    )
}

private fun runQuery(case: CaseData) {
    when (inputString(case, "domain")) {
        "plist.native-semantic-query@1" -> runNativeQuery(case)
        "plist.binary-structure-query@1" -> runBinaryStructureQuery(case)
        null -> fail("missing input.domain")
        else -> fail("unknown query domain")
    }
}

private fun entryKeyText(document: Document, entry: PlistMatch.DictEntry): String =
    document.dictEntryEntity(entry.index).let { entity ->
        (document.valueEntity(entity.keyIndex).native as? consema.plist.NativeValue.StringV)
            ?.string?.toUnicode()
            ?: fail("key not unicode")
    }

private fun entryValueKind(document: Document, entry: PlistMatch.DictEntry): String? =
    document.valueEntity(entry.value.index.toInt()).native?.kind()?.kindName()

private fun duplicateKeyGroups(document: Document, matches: List<PlistMatch>): Int {
    val counts = HashMap<String, Int>()
    for (item in matches) {
        if (item is PlistMatch.DictEntry) {
            val key = entryKeyText(document, item)
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts.values.count { it > 1 }
}

/** Asserts typed matches against `{kind, value|seconds}` facts. */
private fun assertTypedMatches(
    matches: List<PlistMatch>,
    expectedMatches: List<PortableValue>,
    document: Document,
) {
    ensure(matches.size == expectedMatches.size)
    for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
        val expectedKind = (objectField(expectedMatch, "kind") as? PvString)?.value
            ?: fail("missing expected match kind")
        val value = actual as? PlistMatch.Value ?: fail("match without value payload")
        ensure(value.kind?.kindName() == expectedKind)
        (objectField(expectedMatch, "value") as? PvInteger)?.let { expectedValue ->
            val typed = value.typed as? TypedValue.Integer ?: fail("typed match integer missing")
            ensure(typed.value == expectedValue.value.toLong())
        }
        (objectField(expectedMatch, "seconds") as? PvBinaryFloat64)?.let { expectedSeconds ->
            val typed = value.typed as? TypedValue.Date ?: fail("typed match date missing")
            ensure(bitsEqual(typed.seconds, expectedSeconds.toFloat()))
        }
    }
}

private fun runNativeQuery(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runNativeQuerySamples(case, samples, expected)
        return
    }
    val document = formCase(case)
    if (document.formationStatus() != FormationStatus.Complete) {
        fail("native-query input must form completely")
    }
    val filters = inputSequence(case, "filters") ?: fail("missing input.filters")
    val calls = buildFilters(filters)
    val outcome = executeNative(document, calls)
    val completed = outcome as? PlistQueryOutcome.Completed ?: fail("execution failed")
    val terminal = expectedString(case, "terminal") ?: fail("missing expected.terminal")
    ensure("Completed" == terminal)
    expectedSequence(case, "keys")?.let { keys ->
        val actual = completed.matches.filterIsInstance<PlistMatch.DictEntry>()
            .map { entryKeyText(document, it) }
        assertStrings(actual, keys, "key")
    }
    expectedSequence(case, "value_types")?.let { valueTypes ->
        val actual = completed.matches.filterIsInstance<PlistMatch.DictEntry>()
            .map { entryValueKind(document, it) }
        val expectedTypes = valueTypes.mapNotNull { (it as? PvString)?.value }
        ensure(actual == expectedTypes)
    }
    expectedLong(case, "duplicate_groups")?.let { groups ->
        ensure(duplicateKeyGroups(document, completed.matches).toLong() == groups)
    }
}

private fun runNativeQuerySamples(
    case: CaseData,
    samples: List<PortableValue>,
    expected: PortableValue,
) {
    val document = formCase(case)
    if (document.formationStatus() != FormationStatus.Complete) {
        fail("native-query input must form completely")
    }
    val terminals = expectedSequenceField(expected, "terminals") ?: fail("missing expected.terminals")
    ensure(samples.size == terminals.size)
    val mismatchCode = expectedStringField(expected, "mismatch_code")
    val integerMatches = expectedSequenceField(expected, "integer_matches")
    val dateMatches = expectedSequenceField(expected, "date_matches")
    for ((index, sample) in samples.withIndex()) {
        val filters = (objectField(sample, "filters") as? PvArray)?.items()
            ?: fail("missing sample filters")
        val lastOperator = (filters.lastOrNull()?.let { objectField(it, "operator") as? PvString }?.value)
            ?: ""
        val calls = buildFilters(filters)
        val terminal = (terminals[index] as? PvString)?.value ?: fail("terminal must be a string")
        when (terminal) {
            "Completed" -> {
                val outcome = executeNative(document, calls)
                val completed = outcome as? PlistQueryOutcome.Completed ?: fail("execution failed")
                if (lastOperator == "plist.value-as-integer@1") {
                    integerMatches?.let { assertTypedMatches(completed.matches, it, document) }
                } else if (lastOperator == "plist.value-as-date@1") {
                    dateMatches?.let { assertTypedMatches(completed.matches, it, document) }
                }
            }
            "Failed" -> {
                val outcome = executeNative(document, calls)
                val failed = outcome as? PlistQueryOutcome.Failed ?: fail("execution must fail")
                val expectedCode = mismatchCode ?: fail("missing mismatch_code")
                ensure(failed.code == expectedCode)
            }
            else -> fail("unknown terminal $terminal")
        }
    }
}

/** Executes one validated binary-structure query against one document. */
private fun executeBinaryStructure(
    calls: List<OperatorCall>,
    document: Document,
): PlistQueryOutcome<PlistBinaryMatch> {
    var expression = QueryExpression(consema.protocol.ExpressionKind.Input)
    for (call in calls) {
        expression = expression.then(call)
    }
    val definition = QueryDefinition(QueryDomain("plist.binary-structure-query", 1))
        .withExpression(expression)
        .withSelection(QuerySelection.All)
    val executable = bindQuery(definition)
    return executePlistBinaryQuery(
        executable,
        document,
        QueryLimits.default,
        CancellationToken(),
    )
}

private fun runBinaryStructureQuery(case: CaseData) {
    val document = formCase(case)
    if (document.formationStatus() != FormationStatus.Complete) {
        fail("binary-structure-query input must form completely")
    }
    val filters = inputSequence(case, "filters") ?: fail("missing input.filters")
    val calls = buildFilters(filters)
    val expected = case.expected ?: fail("missing expected")
    val terminal = expectedString(case, "terminal") ?: fail("missing expected.terminal")
    // Composition: the full chain validates, binds, and executes before any
    // fact is asserted.
    val outcome = executeBinaryStructure(calls, document)
    val completed = outcome as? PlistQueryOutcome.Completed ?: fail("execution failed")
    ensure("Completed" == terminal)
    // Facts: every structure operator projects its document-level fact set
    // once from any binary-structure input match, so each filter is also
    // executed standalone and its facts collected.
    var trailer: BinaryTrailerFacts? = null
    val objects = mutableListOf<Pair<Int, Int>>()
    val offsets = mutableListOf<Pair<Int, Int>>()
    var topMarker: Int? = null
    var topRefs: List<Int> = emptyList()
    for (call in calls) {
        val standalone = executeBinaryStructure(listOf(call), document)
        val matches = (standalone as? PlistQueryOutcome.Completed)?.matches ?: fail("standalone execution failed")
        for (item in matches) {
            when (item) {
                is PlistBinaryMatch.Trailer -> trailer = item.facts
                is PlistBinaryMatch.Object -> objects.add(item.index to item.marker)
                is PlistBinaryMatch.Offset -> offsets.add(item.index to item.offset)
                is PlistBinaryMatch.TopObject -> {
                    topMarker = item.topObject.marker
                    topRefs = item.refs.map { it.second }
                }
                else -> {}
            }
        }
    }
    val trailerFacts = trailer ?: fail("missing trailer facts match")
    assertU64Field(expected, "num_objects", trailerFacts.numObjects)
    assertU64Field(expected, "top_object", trailerFacts.topObject)
    assertU64Field(expected, "offset_int_size", trailerFacts.offsetIntSize.toLong())
    assertU64Field(expected, "object_ref_size", trailerFacts.objectRefSize.toLong())
    assertU64Field(expected, "sort_version", trailerFacts.sortVersion.toLong())
    assertU64Field(expected, "offset_table_offset", trailerFacts.offsetTableOffset)
    objects.sortBy { it.first }
    offsets.sortBy { it.first }
    expectedSequence(case, "object_offsets")?.let { objectOffsets ->
        val expectedOffsets = objectOffsets.mapNotNull { (it as? PvInteger)?.value?.toLong() }
        val actual = offsets.map { it.second.toLong() }
        ensure(actual == expectedOffsets)
    }
    expectedSequence(case, "markers")?.let { markers ->
        val expectedMarkers = markers.mapNotNull { (it as? PvString)?.value }
        val actual = objects.map { "%02x".format(it.second) }
        ensure(actual.size == expectedMarkers.size &&
            actual.zip(expectedMarkers).all { (actual, expected) -> actual == expected })
    }
    expectedString(case, "top_marker")?.let { marker ->
        val actual = topMarker ?: fail("missing top-object match")
        ensure("%02x".format(actual) == marker)
    }
    expectedSequence(case, "top_refs")?.let { refs ->
        val expectedRefs = refs.mapNotNull { (it as? PvInteger)?.value?.toLong() }
        val actual = topRefs.map { it.toLong() }
        ensure(actual == expectedRefs)
    }
}

// ---------------------------------------------------------------------------
// Projection
// ---------------------------------------------------------------------------

/** Stable kind name of one projected portable value. */
private fun portableKindName(value: PortableValue?): String? =
    when (value) {
        is PvEntryMapping -> "dict"
        is PvArray -> "array"
        is PvString -> "string"
        is PvInteger -> "integer"
        is PvBinaryFloat64, is PvBinaryFloat32 -> "real"
        is PvBoolean -> "boolean"
        is PvBytes -> "data"
        is PvObject -> when {
            objectField(value, "seconds") != null -> "date"
            objectField(value, "uid") != null -> "uid"
            else -> null
        }
        else -> null
    }

/** Asserts one projected leaf against its `{kind, ...}` expectation. */
private fun assertLeaf(actual: PortableValue, expected: PortableValue) {
    val kind = (objectField(expected, "kind") as? PvString)?.value ?: fail("missing leaf kind")
    ensure(portableKindName(actual) == kind)
    when (kind) {
        "string" -> {
            val text = (objectField(expected, "text") as? PvString)?.value ?: fail("missing leaf text")
            ensure((actual as? PvString)?.value == text)
        }
        "integer" -> {
            val expectedValue = (objectField(expected, "value") as? PvInteger)?.value?.toLong()
                ?: fail("missing leaf integer")
            val actualValue = (actual as? PvInteger)?.value?.toLong() ?: fail("actual leaf integer missing")
            ensure(actualValue == expectedValue)
        }
        "real" -> {
            val expectedValue = expectedF64(objectField(expected, "value")) ?: fail("missing leaf real")
            val actualValue = expectedF64(actual) ?: fail("actual leaf real missing")
            ensure(bitsEqual(actualValue, expectedValue))
        }
        "boolean" -> {
            val expectedValue = (objectField(expected, "value") as? PvBoolean)?.value
                ?: fail("missing leaf boolean")
            ensure((actual as? PvBoolean)?.value == expectedValue)
        }
        "data" -> {
            val expectedHex = (objectField(expected, "hex") as? PvString)?.value ?: fail("missing leaf hex")
            val actualHex = (actual as? PvBytes)?.content()?.let { toHex(it) }
                ?: fail("actual leaf data missing")
            ensure(actualHex == expectedHex)
        }
        "date" -> {
            val expectedSeconds = expectedF64(objectField(expected, "seconds")) ?: fail("missing leaf seconds")
            val actualSeconds = expectedF64(objectField(actual, "seconds")) ?: fail("actual leaf date missing")
            ensure(bitsEqual(actualSeconds, expectedSeconds))
        }
        else -> fail("unknown leaf kind $kind")
    }
}

private fun runProjection(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runProjectionSamples(case, samples, expected)
        return
    }
    val document = formCase(case)
    val result = project(document, ProjectionRequest.valueTree())
    val complete = result as? ProjectionResult.Complete ?: fail("projection must complete")
    expectedStringField(expected, "record")?.let { record ->
        val actual = stringField(complete.projection.value, "record") ?: fail("missing record member")
        ensure(actual == record)
    }
    val root = objectField(complete.projection.value, "root") ?: fail("missing root member")
    expectedStringField(expected, "root_kind")?.let { kind ->
        ensure(portableKindName(root) == kind)
    }
    expectedSequenceField(expected, "keys")?.let { keys ->
        val mapping = root as? PvEntryMapping ?: fail("root must be an entry mapping")
        val actual = mapping.entries().mapNotNull { (it.key as? PvString)?.value }
        assertStrings(actual, keys, "key")
    }
    (objectField(expected, "leaves") as? PvObject)?.let { leaves ->
        val mapping = root as? PvEntryMapping ?: fail("root must be an entry mapping")
        for (leaf in leaves.entries()) {
            val entry = mapping.entries().firstOrNull { (it.key as? PvString)?.value == leaf.key }
                ?: fail("leaf entry ${leaf.key} missing")
            assertLeaf(entry.value, leaf.value)
        }
    }
    (objectField(expected, "array_leaves") as? PvObject)?.let { arrayLeaves ->
        val mapping = root as? PvEntryMapping ?: fail("root must be an entry mapping")
        for (leaf in arrayLeaves.entries()) {
            val entry = mapping.entries().firstOrNull { (it.key as? PvString)?.value == leaf.key }
                ?: fail("array leaf entry ${leaf.key} missing")
            val elements = entry.value as? PvArray ?: fail("array leaf must be a sequence")
            val expectedElements = leaf.value as? PvArray ?: fail("expected array leaf must be a sequence")
            ensure(elements.size() == expectedElements.size())
            for ((element, expectedElement) in elements.items().zip(expectedElements.items())) {
                val expectedText = (expectedElement as? PvString)?.value
                    ?: fail("array leaf element must be a string")
                ensure((element as? PvString)?.value == expectedText)
            }
        }
    }
    expectedBooleanField(expected, "association_order_preserved")?.let { preserved ->
        ensure(preserved)
    }
}

private fun runProjectionSamples(
    case: CaseData,
    samples: List<PortableValue>,
    expected: PortableValue,
) {
    val fidelities = expectedSequenceField(expected, "fidelities")
    val codes = expectedSequenceField(expected, "codes")
    val eventsAfterFirst = expectedLongField(expected, "events_after_first") ?: 0L
    var firstCompletedChecked = false
    for ((index, sample) in samples.withIndex()) {
        val document = formSample(case, sample)
        val request = when (stringField(sample, "collision_policy")) {
            "Reject" -> ProjectionRequest.requireObject(CollisionPolicy.Reject)
            "First" -> ProjectionRequest.requireObject(CollisionPolicy.First)
            "Last" -> ProjectionRequest.requireObject(CollisionPolicy.Last)
            else -> ProjectionRequest.valueTree()
        }
        val result = project(document, request)
        fidelities?.let { fidelitiesValue ->
            val expectedFidelity = (fidelitiesValue[index] as? PvString)?.value
                ?: fail("fidelity must be a string")
            val fidelityOk = when {
                result is ProjectionResult.Failed && expectedFidelity == "Failed" -> true
                result is ProjectionResult.Complete &&
                    (expectedFidelity == "Transformed" || expectedFidelity == "Exact") -> true
                else -> false
            }
            ensure(fidelityOk)
        }
        codes?.let { codesValue ->
            (codesValue[index] as? PvString)?.let { expectedCode ->
                val failed = result as? ProjectionResult.Failed ?: fail("projection must fail")
                val code = failed.attempt.failure.code
                ensure(code == expectedCode.value)
            }
        }
        if (result is ProjectionResult.Complete) {
            if (!firstCompletedChecked) {
                firstCompletedChecked = true
                objectField(expected, "first_sample")?.let { firstSampleValue ->
                    val keys = sequenceField(firstSampleValue, "keys")
                        ?: fail("missing first_sample keys")
                    val values = sequenceField(firstSampleValue, "values")
                        ?: fail("missing first_sample values")
                    val obj = result.projection.value as? PvObject
                        ?: fail("require-object projection must be an object")
                    ensure(obj.size() == keys.size)
                    for ((entry, pair) in obj.entries().zip(keys.zip(values))) {
                        val expectedKey = (pair.first as? PvString)?.value
                            ?: fail("expected key must be a string")
                        val expectedValue = (pair.second as? PvString)?.value
                            ?: fail("expected value must be a string")
                        ensure(entry.key == expectedKey)
                        ensure((entry.value as? PvString)?.value == expectedValue)
                    }
                }
                if (eventsAfterFirst > 0) {
                    val events = result.projection.report.events()
                        .count { it.kind == ProjectionEventKind.AssociationDiscarded }
                    ensure(events.toLong() == eventsAfterFirst)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Materialization
// ---------------------------------------------------------------------------

private fun materializationRequest(style: String): MaterializationRequest =
    when (style) {
        "plist.xml-canonical@1" -> MaterializationRequest.new(
            ProfileId("plist.xml", 1),
            MaterializationStyleId("plist.xml-canonical", 1),
        )
        "plist.binary-canonical@1" -> MaterializationRequest.new(
            ProfileId("plist.binary", 1),
            MaterializationStyleId("plist.binary-canonical", 1),
        ).withEncoding(SourceEncoding.Binary).withNewline(NewlinePolicy.None)
        else -> fail("unknown materialization style $style")
    }

/** Counts the scalar (non-container) objects of one binary document. */
private fun scalarObjects(document: Document): Int =
    document.binaryFacts()?.objects?.count { fact ->
        val marker = fact.marker
        marker !in 0xA0..0xAF && marker !in 0xD0..0xDF
    } ?: 0

private fun runMaterialization(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runMaterializationSamples(case, samples, expected)
        return
    }
    val style = inputString(case, "style") ?: fail("missing input.style")
    val record = caseInput(case, "record") ?: fail("missing input.record")
    val request = materializationRequest(style)
    val result = materialize(record, request)
    val complete = result as? MaterializationResult.Complete ?: fail("materialization failed")
    if (expectedBoolean(case, "closure") == true) {
        ensure(complete.materialization.document.formationStatus() == FormationStatus.Complete)
    }
    expectedString(case, "render")?.let { render ->
        val actual = complete.materialization.document.render().toString(Charsets.UTF_8)
        ensure(actual == render)
    }
    expectedString(case, "render_hex")?.let { hex ->
        ensure(toHex(complete.materialization.document.render()) == hex)
    }
}

private fun runMaterializationSamples(
    case: CaseData,
    samples: List<PortableValue>,
    expected: PortableValue,
) {
    val canonicalHex = expectedStringField(expected, "canonical_hex")
    val conversionRender = expectedStringField(expected, "conversion_render")
    val closure = expectedBooleanField(expected, "closure") == true
    val representationChange = expectedBooleanField(expected, "representation_change_reported") == true
    var deduplicated = expectedLongField(expected, "deduplicated_scalars")
    val renders = expectedSequenceField(expected, "renders")
    val codes = expectedSequenceField(expected, "codes")
    val truncationEvents = expectedLongField(expected, "truncation_events") ?: 0L
    for ((index, sample) in samples.withIndex()) {
        val style = stringField(sample, "style") ?: inputString(case, "style")
            ?: fail("missing sample style")
        if (objectField(sample, "record") != null) {
            val recordValue = objectField(sample, "record") ?: fail("missing sample record")
            val record = objectField(sample, "truncate_policy")?.let { policy ->
                PvObject(
                    (recordValue as? PvObject)?.entries().orEmpty() +
                        Entry("truncate_policy", policy),
                )
            } ?: recordValue
            val request = materializationRequest(style)
            when (val result = materialize(record, request)) {
                is MaterializationResult.Complete -> {
                    renders?.let { rendersValue ->
                        val expectedRender = (rendersValue[index] as? PvString)?.value
                            ?: fail("expected render must be a string")
                        val actual = result.materialization.document.render().toString(Charsets.UTF_8)
                        ensure(actual == expectedRender)
                    }
                    if (truncationEvents > 0) {
                        val events = result.materialization.document.diagnostics()
                            .count { it.code == "plist.materialization.fractional-date@1" }
                        ensure(events.toLong() == truncationEvents)
                    }
                    if (closure) {
                        ensure(result.materialization.document.formationStatus() == FormationStatus.Complete)
                    }
                }
                is MaterializationResult.Failed -> {
                    val expectedCode = (codes?.getOrNull(index) as? PvString)?.value
                        ?: fail("materialization must complete")
                    ensure(materializationFailureCode(result.attempt.failure) == expectedCode)
                }
            }
            continue
        }
        // Source-document samples: normalization materializes the projected
        // record directly, conversion crosses the representation boundary.
        val document = formValue(sample)
        if (style == "plist.binary-canonical@1") {
            val projection = project(document, ProjectionRequest.valueTree())
            val completeProjection = projection as? ProjectionResult.Complete
                ?: fail("projection must complete")
            val request = materializationRequest(style)
            val result = materialize(completeProjection.projection.value, request)
            val complete = result as? MaterializationResult.Complete ?: fail("materialization failed")
            canonicalHex?.let { hex ->
                ensure(toHex(complete.materialization.document.render()) == hex)
            }
            deduplicated?.let { expectedCount ->
                val baseScalars = scalarObjects(document)
                val committedScalars = scalarObjects(complete.materialization.document)
                val actual = (baseScalars - committedScalars).toLong().coerceAtLeast(0L)
                ensure(actual == expectedCount)
            }
            if (closure) {
                ensure(complete.materialization.document.formationStatus() == FormationStatus.Complete)
            }
        } else {
            val converted = document.convertTo(PlistProfile.XmlV1, PlistParseLimits.default)
            conversionRender?.let { render ->
                val actual = converted.document.render().toString(Charsets.UTF_8)
                ensure(actual == render)
            }
            if (representationChange) {
                ensure(converted.report.representationChanged())
            }
            if (closure) {
                ensure(converted.document.formationStatus() == FormationStatus.Complete)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Conversion
// ---------------------------------------------------------------------------

private fun runConversion(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val document = formCase(case)
    if (document.formationStatus() != FormationStatus.Complete) {
        fail("conversion input must form completely")
    }
    val target = when (expectedString(case, "target")) {
        "plist.binary@1" -> PlistProfile.BinaryV1
        "plist.xml@1" -> PlistProfile.XmlV1
        null -> fail("missing expected.target")
        else -> fail("unknown target profile")
    }
    val converted = try {
        document.convertTo(target, PlistParseLimits.default)
    } catch (e: ConversionFailureException) {
        val code = expectedString(case, "code") ?: fail("conversion must complete")
        ensure(e.code == code)
        return
    }
    if (expectedString(case, "code") != null) {
        fail("conversion must fail")
    }
    if (expectedBoolean(case, "representation_change_reported") == true) {
        ensure(converted.report.representationChanged())
    }
    if (expectedBoolean(case, "closure") == true) {
        ensure(converted.document.formationStatus() == FormationStatus.Complete)
    }
    if (expectedBoolean(case, "round_trip") == true) {
        // Reparse closure across the boundary: the target converted back
        // under the source profile must carry the exact source native model
        // (compared through the exact value-tree projection).
        val back = converted.document.convertTo(profileOf(case.input), PlistParseLimits.default)
        val first = project(document, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: fail("projection failed")
        val second = project(back.document, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: fail("projection failed")
        ensure(equal(first.projection.value, second.projection.value))
    }
    expectedSequence(case, "dict_keys")?.let { keys ->
        val convertedDocument = converted.document
        assertStrings(dictKeysOf(rootValue(convertedDocument)), keys, "key")
    }
}

// ---------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------

private fun editPath(operation: PortableValue): EditPath {
    val path = sequenceField(operation, "path") ?: fail("missing path")
    val steps = path.map { element ->
        when (element) {
            is PvString -> EditPathStep.DictKey(PlistKey.fromUnicode(element.value), 0)
            is PvInteger -> EditPathStep.ArrayIndex(element.value.toInt())
            else -> fail("path step must be a string or integer")
        }
    }
    return EditPath.new(steps)
}

/** The operation target path: an explicit `path` sequence, or the `dict` /
 * `array` key name of one root-level container. */
private fun operationPath(operation: PortableValue): EditPath {
    if (objectField(operation, "path") != null) {
        return editPath(operation)
    }
    val dictName = stringField(operation, "dict")
    val arrayName = stringField(operation, "array")
    val name = dictName ?: arrayName ?: fail("missing operation path")
    return EditPath.new(listOf(EditPathStep.DictKey(PlistKey.fromUnicode(name), 0)))
}

private fun editValue(value: PortableValue): EditValue =
    when (stringField(value, "kind")) {
        "string" -> EditValue.String(
            PlistString.fromUnicode(stringField(value, "text") ?: fail("missing text")),
        )
        "integer" -> EditValue.Integer(
            (objectField(value, "value") as? PvInteger)?.value?.toLong()
                ?: fail("missing integer value"),
        )
        "real" -> EditValue.Real(
            PlistReal.double(
                java.lang.Double.doubleToRawLongBits(
                    expectedF64(objectField(value, "value")) ?: fail("missing real value"),
                ),
            ),
        )
        "boolean" -> EditValue.BooleanV(
            (objectField(value, "value") as? PvBoolean)?.value ?: fail("missing boolean value"),
        )
        "date" -> EditValue.Date(
            PlistDate.fromSeconds(
                expectedF64(objectField(value, "seconds")) ?: fail("missing date seconds"),
            )?.seconds ?: fail("invalid date seconds"),
        )
        "data" -> EditValue.Data(
            PlistData.fromBytes(
                decodeHex(stringField(value, "hex") ?: fail("missing data hex"))
                    ?: fail("invalid data hex"),
            ),
        )
        "uid" -> {
            val payload = (objectField(value, "value") as? PvInteger)?.value?.toLong()
                ?: fail("missing uid value")
            if (payload !in 0..0xFFFF_FFFFL) fail("uid out of range")
            EditValue.Uid(PlistUid(payload.toInt()))
        }
        else -> fail("unknown value kind")
    }

private fun dictPlacement(operation: PortableValue): DictPlacement =
    when (stringField(operation, "placement") ?: "End") {
        "End" -> DictPlacement.End
        else -> fail("unknown placement")
    }

private fun operationUsize(operation: PortableValue, name: String): Int =
    (objectField(operation, name) as? PvInteger)?.value?.toInt() ?: fail("missing $name")

private fun buildTransaction(document: Document, operations: List<PortableValue>): EditTransaction {
    val builder = EditTransactionBuilder.new(document)
    for (operation in operations) {
        when (stringField(operation, "op")) {
            "plist.edit.set-value@1" -> {
                val path = editPath(operation)
                val value = editValue(objectField(operation, "value") ?: fail("missing value"))
                builder.setValue(path, value)
            }
            "plist.edit.insert-dict-entry@1" -> {
                val path = operationPath(operation)
                val key = PlistKey.fromUnicode(stringField(operation, "key") ?: fail("missing key"))
                val value = editValue(objectField(operation, "value") ?: fail("missing value"))
                builder.insertDictEntry(path, key, value, dictPlacement(operation))
            }
            "plist.edit.remove-dict-entry@1" -> {
                val path = operationPath(operation)
                val key = PlistKey.fromUnicode(stringField(operation, "key") ?: fail("missing key"))
                builder.removeDictEntry(path, key, 0)
            }
            "plist.edit.rename-dict-key@1" -> {
                val path = operationPath(operation)
                val from = PlistKey.fromUnicode(stringField(operation, "from") ?: fail("missing from"))
                val to = PlistKey.fromUnicode(stringField(operation, "to") ?: fail("missing to"))
                builder.renameDictKey(path, from, 0, to)
            }
            "plist.edit.insert-array-element@1" -> {
                val path = operationPath(operation)
                val index = operationUsize(operation, "index")
                val value = editValue(objectField(operation, "value") ?: fail("missing value"))
                builder.insertArrayElement(path, index, value)
            }
            "plist.edit.remove-array-element@1" -> {
                val path = operationPath(operation)
                val index = operationUsize(operation, "index")
                builder.removeArrayElement(path, index)
            }
            else -> fail("unknown edit op")
        }
    }
    return builder.build()
}

/** Reparses one committed document under its own profile. */
private fun reparse(document: Document): Document =
    try {
        parse(
            document.render(),
            if (document.profileId().id == "plist.xml") PlistProfile.XmlV1 else PlistProfile.BinaryV1,
            PlistEncodingSelection.ProfileDefault,
            PlistParseLimits.default,
        )
    } catch (e: PlistFormationException) {
        fail("reparse: ${e.code}")
    }

/** Asserts the vector facts of one committed edit's native model. */
private fun assertEditNative(expected: PortableValue, committed: Document) {
    val root = rootValue(committed)
    expectedStringField(expected, "top_kind")?.let { kind ->
        ensure(valueKindName(root) == kind)
    }
    expectedSequenceField(expected, "dict_a_keys")?.let { keys ->
        val dictA = entryByKey(root, "a")
        assertStrings(dictKeysOf(dictA), keys, "key")
    }
    expectedSequenceField(expected, "dict_a_values")?.let { values ->
        val dictA = entryByKey(root, "a")
        val entries = dictEntriesOf(dictA)
        ensure(entries.size == values.size)
        for ((entry, expectedValue) in entries.zip(values)) {
            compareScalarValue(entry.value(), expectedValue)
        }
    }
    expectedSequenceField(expected, "arr_elements")?.let { elements ->
        val array = entryByKey(root, "arr").arrayElements() ?: fail("arr must be an array")
        ensure(array.size == elements.size)
        for ((element, expectedElement) in array.zip(elements)) {
            compareScalarValue(element.value(), expectedElement)
        }
    }
    expectedSequenceField(expected, "elements")?.let { elements ->
        val array = root.arrayElements() ?: fail("root must be an array")
        ensure(array.size == elements.size)
        for ((element, expectedElement) in array.zip(elements)) {
            compareScalarValue(element.value(), expectedElement)
        }
    }
    expectedSequenceField(expected, "element_kinds")?.let { kinds ->
        val array = root.arrayElements() ?: fail("root must be an array")
        ensure(array.size == kinds.size)
        for ((element, expectedKind) in array.zip(kinds)) {
            val kind = (expectedKind as? PvString)?.value ?: fail("kind must be a string")
            ensure(valueKindName(element.value()) == kind)
        }
    }
}

/** Verifies that every untouched-byte region of one commit is byte-exact. */
private fun assertUntouchedObjectBytes(document: Document, commit: EditCommit) {
    for (region in commit.untouchedProof.regions()) {
        val base = document.source().bytes().copyOfRange(region.oldStart, region.oldEnd)
        val target = commit.document.source().bytes().copyOfRange(region.newStart, region.newEnd)
        ensure(base.contentEquals(target))
    }
}

private fun runEdit(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runEditConflicts(case, samples, expected)
        return
    }
    val document = formCase(case)
    if (document.formationStatus() != FormationStatus.Complete) {
        fail("edit input must form completely")
    }
    val operations = inputSequence(case, "operations") ?: fail("missing input.operations")
    val transaction = buildTransaction(document, operations)
    val commit = try {
        document.commit(transaction)
    } catch (e: EditFailureException) {
        fail("commit failed: ${e.failure.code}")
    }
    val committed = commit.document
    ensure(committed.formationStatus() == FormationStatus.Complete)
    if (expectedBooleanField(expected, "reparse_closure") == true) {
        ensure(reparse(committed).formationStatus() == FormationStatus.Complete)
    }
    if (expectedBooleanField(expected, "patch_replays") == true) {
        val replay = commit.sourcePatch.apply(document.source())
        ensure(replay.bytes().contentEquals(committed.render()))
    }
    if (expectedBooleanField(expected, "untouched_byte_proof") == true ||
        expectedBooleanField(expected, "untouched_object_bytes") == true
    ) {
        try {
            commit.untouchedProof.verify(
                document.source(),
                committed.source(),
                commit.sourcePatch.replacements(),
            )
        } catch (e: Exception) {
            fail("untouched proof: ${e.message}")
        }
    }
    if (expectedBooleanField(expected, "untouched_object_bytes") == true) {
        assertUntouchedObjectBytes(document, commit)
    }
    assertEditNative(expected, committed)
}

private fun runEditConflicts(case: CaseData, samples: List<PortableValue>, expected: PortableValue) {
    val codes = expectedSequenceField(expected, "codes") ?: fail("missing expected.codes")
    val baseUnchanged = expectedBooleanField(expected, "base_unchanged") == true
    ensure(samples.size == codes.size)
    for ((index, sample) in samples.withIndex()) {
        val document = formSample(case, sample)
        val operations = sequenceField(sample, "operations") ?: fail("missing operations")
        val transaction = objectField(sample, "wrong_source")?.let { wrong ->
            // The transaction is bound to another document's snapshot.
            val other = formValue(wrong)
            buildTransaction(other, operations)
        } ?: buildTransaction(document, operations)
        val failure = try {
            document.commit(transaction)
            null
        } catch (e: EditFailureException) {
            e.failure
        }
        val expectedCode = (codes[index] as? PvString)?.value ?: fail("expected code must be a string")
        ensure(failure != null && failure.code == expectedCode)
        if (baseUnchanged) {
            ensure(document.render().contentEquals(document.source().bytes()))
        }
    }
}
