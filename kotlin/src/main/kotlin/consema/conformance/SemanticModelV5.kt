// The `consema.semantic-model-v5.conformance@1` suite runner
// (conformance/vectors/semantic-model-v5.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/semantic_model_v5.rs (the
// per-case dispatch and every assertion are transcribed from the Rust
// handlers); the vector file itself drives every input and expectation
// (conformance/README.md rules 3-4). The registries, protocol envelope,
// and canonical JSON/PVCE transports are the Kotlin consema.protocol
// package; the graph model and PGCE/1 codec are the consema.graph package.
// consema-go/go/protocol is a cross-reference only.
//
// The registry cases, the portable-graph dual transport (wire value
// construction plus the real envelope and codec round-trips), the v4
// unknown-contract rejection, the truncated-PVCE rejection, and the nested
// error-code registry cases run here. All payload record types of the v5
// contracts (PortableGraphMessage, GraphQueryResultMessage,
// GraphProjectionResultMessage, GraphProvenanceMapMessage,
// YamlQueryResultMessage, and the Completion record) ship in the Kotlin
// protocol package; every case runs (the runner records zero skips).

package consema.conformance

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import consema.graph.Builder
import consema.graph.Graph
import consema.graph.GraphException
import consema.graph.MappingEntry
import consema.graph.NodeId
import consema.graph.NodeKind
import consema.graph.encodePgce
import consema.graph.PgceLimits
import consema.protocol.Completion
import consema.protocol.CompletionStatus
import consema.protocol.ContractId
import consema.protocol.ContractRegistry
import consema.protocol.ContractRegistryVersion
import consema.protocol.Domains
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.GraphProvenanceEntryMessage
import consema.protocol.GraphProvenanceMapMessage
import consema.protocol.GraphProvenanceRelationMessage
import consema.protocol.GraphProjectedLocationMessage
import consema.protocol.GraphProjectionResultMessage
import consema.protocol.GraphQueryMatchMessage
import consema.protocol.GraphQueryResultMessage
import consema.protocol.GraphSourceOriginMessage
import consema.protocol.PortableGraphMessage
import consema.protocol.ProtocolErrorKind
import consema.protocol.ProtocolException
import consema.protocol.ProtocolLimits
import consema.protocol.ProtocolMessage
import consema.protocol.RegistryManifest
import consema.protocol.Roles
import consema.protocol.YamlMatchLocator
import consema.protocol.YamlQueryResultMessage
import consema.protocol.decodeJson
import consema.protocol.decodePvce
import consema.protocol.encodeJson
import consema.protocol.encodePvce
import java.math.BigInteger

/** Runs the `consema.semantic-model-v5.conformance@1` suite. */
fun runSemanticModelV5(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runSemanticModelV5Case(case)
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

private fun runSemanticModelV5Case(case: CaseData) {
    when (case.id) {
        "registry.v5-manifest" -> registryV5Manifest(case)
        "registry.v1-v4-frozen" -> registryFrozen(case)
        "registry.v5-additive-contracts" -> registryAdditions(case)
        "registry.v5-error-codes" -> registryErrorCodes(case)
        "portable-graph.dual-transport" -> portableGraphTransport(case)
        "portable-graph.reject-disagreement" -> portableGraphDisagreement(case)
        "portable-graph.reject-node-limit" -> portableGraphLimit(case)
        "graph-query.node-roundtrip",
        "graph-query.sequence-roundtrip",
        "graph-query.mapping-roundtrip",
        "graph-query.reject-dangling-association",
        -> graphQueryCase(case)
        "graph-provenance.reject-order" -> graphProvenanceOrder(case)
        "graph-projection.roundtrip",
        "graph-projection.reject-out-of-range",
        -> graphProjectionCase(case)
        "yaml-query.native-roles",
        "yaml-query.syntax-roundtrip",
        -> yamlQueryRoundtrip(case)
        "yaml-query.reject-domain-role" -> yamlQueryDomainRejection(case)
        "yaml-query.reject-process-local" -> yamlQueryProcessLocal(case)
        "protocol.v4-reject-v5-contract" -> protocolV4Rejection(case)
        "protocol.v5-nested-error-code" -> protocolNestedError(case)
        "protocol.reject-truncated-pvce" -> protocolTruncatedPvce(case)
        "protocol.reject-unknown-payload-field" -> protocolUnknownField(case)
        else -> fail("runner does not recognize published case")
    }
}

// ---------------------------------------------------------------------------
// Registry facts (semantic_model_v5.rs).
// ---------------------------------------------------------------------------

private fun registryV5Manifest(case: CaseData) {
    val manifest = RegistryManifest.of(
        5,
        ContractRegistry.forVersion(ContractRegistryVersion.V5),
        ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V5),
    )
    val decoded = decodeManifest(manifest.toValue())
    val semanticModel = expectedString(case, "semantic_model") ?: fail("missing expected.semantic_model")
    val contractCount = expectedLong(case, "contract_count") ?: fail("missing expected.contract_count")
    val errorCodeCount = expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")
    ensure(
        manifest.semanticModel.schema() == semanticModel &&
            manifest.contracts.size.toLong() == contractCount &&
            manifest.errorCodes.size.toLong() == errorCodeCount &&
            decoded.semanticModel == manifest.semanticModel &&
            decoded.contracts == manifest.contracts &&
            decoded.errorCodes == manifest.errorCodes,
    )
}

private fun registryFrozen(case: CaseData) {
    val contractCounts = expectedLongList(case, "contract_counts")
    val errorCounts = expectedLongList(case, "error_code_counts")
    for (version in 1..4) {
        val manifest = RegistryManifest.of(
            version,
            ContractRegistry.forVersion(ContractRegistryVersion.entries[version - 1]),
            ErrorCodeRegistry.forVersion(ErrorRegistryVersion.entries[version - 1]),
        )
        ensure(
            manifest.semanticModel.version == version &&
                manifest.contracts.size.toLong() == contractCounts[version - 1] &&
                manifest.errorCodes.size.toLong() == errorCounts[version - 1] &&
                !manifest.isCurrent() &&
                registryManifestRoundtrips(manifest),
        )
    }
}

private fun registryManifestRoundtrips(manifest: RegistryManifest): Boolean =
    try {
        val decoded = RegistryManifest.fromValue(manifest.toValue())
        decoded.semanticModel == manifest.semanticModel &&
            decoded.contracts == manifest.contracts &&
            decoded.errorCodes == manifest.errorCodes
    } catch (e: ProtocolException) {
        false
    }

private fun decodeManifest(value: PortableValue): RegistryManifest =
    try {
        RegistryManifest.fromValue(value)
    } catch (e: ProtocolException) {
        fail("registry manifest decode failed: ${e.kind.code} at ${e.path}")
    }

private fun registryAdditions(case: CaseData) {
    val v4 = ContractRegistry.forVersion(ContractRegistryVersion.V4)
    val v5 = ContractRegistry.forVersion(ContractRegistryVersion.V5)
    val actual = v5.contracts()
        .filter { candidate ->
            v4.contracts().none { old -> old.id == candidate.id && old.version == candidate.version }
        }
        .map { "${it.id}@${it.version}" }
    val expected = expectedStringList(case, "contracts")
    ensure(actual == expected)
}

private fun registryErrorCodes(case: CaseData) {
    val v4 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V4)
    val v5 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V5)
    val additions = v5.codes().filter { !v4.contains(it.code) }.map { it.code }
    val expected = expectedStringList(case, "new_codes")
    val errorCodeCount = expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")
    ensure(v5.codes().size.toLong() == errorCodeCount && additions == expected)
}

// ---------------------------------------------------------------------------
// Portable-graph dual transport (semantic_model_v5.rs).
// ---------------------------------------------------------------------------

private fun portableGraphTransport(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val pgce = encodePgce(graph)
    val pgceHex = expectedString(case, "pgce_hex") ?: fail("missing expected.pgce_hex")
    ensure(toHex(pgce) == pgceHex)
    val payload = portableGraphValue(graph, pgce)
    val registry = ContractRegistry.forVersion(ContractRegistryVersion.V5)
    val message = transport { ProtocolMessage.of(ContractId("core.portable-graph", 1), payload, registry) }
    val limits = ProtocolLimits.default
    val json = transport { encodeJson(message.toValue(), limits) }
    val pvce = transport { encodePvce(message.toValue(), limits) }
    val jsonRoundtrip = transport { ProtocolMessage.fromValue(decodeJson(json, limits), registry) }
    val pvceRoundtrip = transport { ProtocolMessage.fromValue(decodePvce(pvce, limits), registry) }
    val jsonDigest = expectedString(case, "json_sha256") ?: fail("missing expected.json_sha256")
    val pvceDigest = expectedString(case, "pvce_sha256") ?: fail("missing expected.pvce_sha256")
    ensure(
        messageEquals(jsonRoundtrip, message) &&
            messageEquals(pvceRoundtrip, message) &&
            sha256Hex(json) == jsonDigest &&
            sha256Hex(pvce) == pvceDigest,
    )
}

/** The core.portable-graph@1 record rejection cases (semantic_model_v5.rs
 *): a readable/PGCE disagreement and an explicit node limit. */
private fun portableGraphDisagreement(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val message = transport { PortableGraphMessage.fromGraph(graph, PgceLimits.default) }
    val value = message.toValue() as? PvObject ?: fail("portable-graph value must be Object")
    val nodes = value.get("nodes") as? PvArray ?: fail("nodes must be Sequence")
    val index = (caseInput(case, "node_index") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.node_index")
    val replacement = inputString(case, "replacement") ?: fail("missing input.replacement")
    val changedNodes = PvArray(
        nodes.items().mapIndexed { ordinal, node ->
            if (ordinal == index) {
                replaceField(node as? PvObject ?: fail("node must be Object"), "canonical_content", PvString(replacement))
            } else {
                node
            }
        },
    )
    val changed = replaceField(value, "nodes", changedNodes)
    val failure = try {
        PortableGraphMessage.fromValue(changed, PgceLimits.default)
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

private fun portableGraphLimit(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val message = transport { PortableGraphMessage.fromGraph(graph, PgceLimits.default) }
    val maxNodes = (caseInput(case, "max_nodes") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.max_nodes")
    val limits = PgceLimits.default.copy(maxNodes = maxNodes)
    val failure = try {
        PortableGraphMessage.fromValue(message.toValue(), limits)
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

/** The graph-query result cases (semantic_model_v5.rs). */
private fun graphQueryCase(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val message = transport { PortableGraphMessage.fromGraph(graph, PgceLimits.default) }
    val role = inputString(case, "role") ?: fail("missing input.role")
    val match = GraphQueryMatchMessage.fromValue(caseInput(case, "match") ?: fail("missing input.match"), "$.match")
    val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
    val accepted = expectedBoolean(case, "accepted") ?: fail("missing expected.accepted")
    try {
        val result = GraphQueryResultMessage.new(
            Domains.portableGraphV1(),
            role,
            message,
            listOf(match),
            completion,
            emptyList(),
        )
        ensure(accepted)
        dualRoundtrip("core.graph-query-result", result.toValue())
    } catch (e: ProtocolException) {
        ensure(!accepted && e.kind.code == (expectedString(case, "code") ?: fail("missing expected.code")))
    }
}

/** The graph provenance order rejection (semantic_model_v5.rs). */
private fun graphProvenanceOrder(case: CaseData) {
    val entries = graphProvenanceEntries(case)
    val failure = try {
        GraphProvenanceMapMessage.new(entries)
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

/** The graph projection result cases (semantic_model_v5.rs). */
private fun graphProjectionCase(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val message = transport { PortableGraphMessage.fromGraph(graph, PgceLimits.default) }
    val provenance = transport { GraphProvenanceMapMessage.new(graphProvenanceEntries(case)) }
    val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
    val accepted = expectedBoolean(case, "accepted") ?: fail("missing expected.accepted")
    try {
        val result = GraphProjectionResultMessage.new(completion, message, provenance, emptyList())
        ensure(accepted)
        dualRoundtrip("core.graph-projection-result", result.toValue())
    } catch (e: ProtocolException) {
        ensure(!accepted && e.kind.code == (expectedString(case, "code") ?: fail("missing expected.code")))
    }
}

/** The yaml-query round-trip cases (semantic_model_v5.rs). */
private fun yamlQueryRoundtrip(case: CaseData) {
    val roles = inputSequence(case, "roles") ?: fail("missing input.roles")
    val sourceId = inputString(case, "source_id") ?: fail("missing input.source_id")
    var count = 0
    for ((ordinal, roleValue) in roles.withIndex()) {
        val role = (roleValue as? PvString)?.value ?: fail("role must be String")
        val domain = if (role == Roles.YAML_SYNTAX_PIECE) {
            Domains.yamlLosslessSyntaxV1()
        } else {
            Domains.yamlNativeV1()
        }
        val locator = transport {
            YamlMatchLocator.new(sourceId, "/nodes/$ordinal", role, ordinal.toULong())
        }
        val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
        val result = transport {
            YamlQueryResultMessage.new(domain, role, listOf(locator), completion, emptyList())
        }
        dualRoundtrip("core.yaml-query-result", result.toValue())
        count += 1
    }
    ensure(count.toLong() == (expectedLong(case, "role_count") ?: fail("missing expected.role_count")))
}

/** The yaml-query domain/role rejection (semantic_model_v5.rs). */
private fun yamlQueryDomainRejection(case: CaseData) {
    val role = inputString(case, "role") ?: fail("missing input.role")
    val locator = transport { YamlMatchLocator.new("sha256:source", "/syntax/0", role, 0uL) }
    val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
    val failure = try {
        YamlQueryResultMessage.new(Domains.yamlNativeV1(), role, listOf(locator), completion, emptyList())
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

/** The yaml-query process-local boundary (semantic_model_v5.rs). */
private fun yamlQueryProcessLocal(case: CaseData) {
    val failure = try {
        YamlMatchLocator.fromProcessLocal()
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

/** The unknown payload field rejection of the registered portable-graph
 * record (semantic_model_v5.rs). */
private fun protocolUnknownField(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val message = transport { PortableGraphMessage.fromGraph(graph, PgceLimits.default) }
    val value = message.toValue() as? PvObject ?: fail("portable-graph value must be Object")
    val entries = value.entries().toMutableList()
    entries.add(Entry("unknown", PvNull))
    val changed = PvObject(entries)
    val failure = try {
        ProtocolMessage.of(
            ContractId("core.portable-graph", 1),
            changed,
            ContractRegistry.forVersion(ContractRegistryVersion.V5),
        )
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

/** Builds the provenance entries of one vector case: every input location
 * with one common origin (semantic_model_v5.rs). */
private fun graphProvenanceEntries(case: CaseData): List<GraphProvenanceEntryMessage> {
    val locations = inputSequence(case, "locations") ?: fail("missing input.locations")
    val sourceId = inputString(case, "source_id") ?: fail("missing input.source_id")
    val nodeLocator = inputString(case, "node_locator") ?: fail("missing input.node_locator")
    val startByte = (caseInput(case, "start_byte") as? PvInteger)?.value?.toLong()?.toULong()
        ?: fail("missing input.start_byte")
    val endByte = (caseInput(case, "end_byte") as? PvInteger)?.value?.toLong()?.toULong()
        ?: fail("missing input.end_byte")
    val relation = when (inputString(case, "relation")) {
        "Direct" -> GraphProvenanceRelationMessage.Direct
        "Reference" -> GraphProvenanceRelationMessage.Reference
        null -> fail("missing input.relation")
        else -> fail("unknown provenance relation")
    }
    val origin = transport {
        GraphSourceOriginMessage.new(sourceId, nodeLocator, startByte, endByte, relation)
    }
    return locations.map { location ->
        GraphProvenanceEntryMessage(
            projected = GraphProjectedLocationMessage.fromValue(location, "$.locations"),
            origins = listOf(origin),
        )
    }
}

/** One dual-transport round trip of a v5-registry payload (semantic_model_v5.rs). */
private fun dualRoundtrip(contractId: String, payload: PortableValue) {
    val registry = ContractRegistry.forVersion(ContractRegistryVersion.V5)
    val message = transport { ProtocolMessage.of(ContractId(contractId, 1), payload, registry) }
    val limits = ProtocolLimits.default
    val json = transport { encodeJson(message.toValue(), limits) }
    val pvce = transport { encodePvce(message.toValue(), limits) }
    val jsonRoundtrip = transport { ProtocolMessage.fromValue(decodeJson(json, limits), registry) }
    val pvceRoundtrip = transport { ProtocolMessage.fromValue(decodePvce(pvce, limits), registry) }
    ensure(
        messageEquals(jsonRoundtrip, message) &&
            messageEquals(pvceRoundtrip, message),
    )
}

/** Replaces one named member of an Object value, preserving the field order. */
private fun replaceField(value: PvObject, name: String, replacement: PortableValue): PvObject =
    PvObject(
        value.entries().map { entry ->
            if (entry.key == name) Entry(name, replacement) else entry
        },
    )

/** Encodes the canonical `core.portable-graph@1` readable value of one
 * graph (portable_graph.rs): canonical wire IDs, the exact PGCE
 * bytes, and the fixed field order. */
private fun portableGraphValue(graph: Graph, pgce: ByteArray): PortableValue {
    val order = ArrayList<NodeId>()
    val canonical = HashMap<NodeId, Int>()
    val stack = ArrayDeque<NodeId>()
    for (index in graph.roots().indices.reversed()) {
        stack.addLast(graph.roots()[index])
    }
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (canonical.containsKey(id)) {
            continue
        }
        canonical[id] = order.size
        order.add(id)
        val node = graph.node(id) ?: fail("completed graph node resolved as null")
        when (node.kind) {
            NodeKind.Scalar -> {}
            NodeKind.Sequence -> {
                val items = node.sequenceItems() ?: fail("sequence kind has no items")
                for (index in items.indices.reversed()) {
                    stack.addLast(items[index])
                }
            }
            NodeKind.Mapping -> {
                val entries = node.mappingEntries() ?: fail("mapping kind has no entries")
                for (index in entries.indices.reversed()) {
                    stack.addLast(entries[index].value)
                    stack.addLast(entries[index].key)
                }
            }
        }
    }
    val roots = PvArray(graph.roots().map { integerValue(canonical[it] ?: fail("root has no canonical ID")) })
    val nodes = PvArray(order.mapIndexed { index, id ->
        val node = graph.node(id) ?: fail("completed graph node resolved as null")
        when (node.kind) {
            NodeKind.Scalar -> PvObject(
                listOf(
                    Entry("id", integerValue(index)),
                    Entry("kind", PvString("Scalar")),
                    Entry("tag", PvString(node.tag)),
                    Entry("canonical_content", PvString(node.scalarContent() ?: fail("scalar kind has no content"))),
                ),
            )
            NodeKind.Sequence -> PvObject(
                listOf(
                    Entry("id", integerValue(index)),
                    Entry("kind", PvString("Sequence")),
                    Entry("tag", PvString(node.tag)),
                    Entry(
                        "items",
                        PvArray((node.sequenceItems() ?: fail("sequence kind has no items"))
                            .map { integerValue(canonical[it] ?: fail("item has no canonical ID")) }),
                    ),
                ),
            )
            NodeKind.Mapping -> PvObject(
                listOf(
                    Entry("id", integerValue(index)),
                    Entry("kind", PvString("Mapping")),
                    Entry("tag", PvString(node.tag)),
                    Entry(
                        "entries",
                        PvArray((node.mappingEntries() ?: fail("mapping kind has no entries")).map { entry ->
                            PvObject(
                                listOf(
                                    Entry("key", integerValue(canonical[entry.key] ?: fail("key has no canonical ID"))),
                                    Entry("value", integerValue(canonical[entry.value] ?: fail("value has no canonical ID"))),
                                ),
                            )
                        }),
                    ),
                ),
            )
        }
    })
    return PvObject(
        listOf(
            Entry("schema", PvString("core.portable-graph@1")),
            Entry("encoding", PvString("PGCE/1")),
            Entry("roots", roots),
            Entry("nodes", nodes),
            Entry("pgce", PvBytes.of(pgce)),
        ),
    )
}

// ---------------------------------------------------------------------------
// Protocol envelope cases (semantic_model_v5.rs).
// ---------------------------------------------------------------------------

private fun protocolV4Rejection(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val payload = portableGraphValue(graph, encodePgce(graph))
    val failure = try {
        ProtocolMessage.of(
            ContractId("core.portable-graph", 1),
            payload,
            ContractRegistry.forVersion(ContractRegistryVersion.V4),
        )
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

private fun protocolTruncatedPvce(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val payload = portableGraphValue(graph, encodePgce(graph))
    val message = transport {
        ProtocolMessage.of(
            ContractId("core.portable-graph", 1),
            payload,
            ContractRegistry.forVersion(ContractRegistryVersion.V5),
        )
    }
    val bytes = transport { encodePvce(message.toValue(), ProtocolLimits.default) }
    val truncate = (caseInput(case, "truncate_bytes") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.truncate_bytes")
    val truncated = bytes.copyOf((bytes.size - truncate).coerceAtLeast(0))
    val failure = try {
        decodePvce(truncated, ProtocolLimits.default)
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind == ProtocolErrorKind.INVALID_PVCE && failure.kind.code == expectedCode)
}

private fun protocolNestedError(case: CaseData) {
    val code = inputString(case, "failure_code") ?: fail("missing input.failure_code")
    val v4Code = expectedString(case, "v4_code") ?: fail("missing expected.v4_code")
    val v4 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V4)
    val v5 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V5)
    val v4Rejection = try {
        v4.validate(code)
        null
    } catch (e: ProtocolException) {
        e
    }
    try {
        v5.validate(code)
    } catch (e: ProtocolException) {
        fail("v5 registry rejected a published code: ${e.kind.code}")
    }
    val registry = ContractRegistry.forVersion(ContractRegistryVersion.V5)
    val message = transport {
        ProtocolMessage.of(
            ContractId("core.completion", 1),
            completionValue("Failed", 1, 0, code),
            registry,
        )
    }
    val decoded = transport { ProtocolMessage.fromValue(message.toValue(), registry) }
    ensure(
        v4Rejection != null &&
            v4Rejection.kind.code == v4Code &&
            messageEquals(decoded, message),
    )
}

/** Runs one protocol operation; a transport or record failure fails the
 * case instead of escaping the suite loop. */
private fun <T> transport(block: () -> T): T =
    try {
        block()
    } catch (e: ProtocolException) {
        fail("protocol operation failed: ${e.kind.code} at ${e.path}")
    }

// ---------------------------------------------------------------------------
// Shared helpers.
// ---------------------------------------------------------------------------

/** The fixed `core.completion@1` value (execution.rs). */
private fun completionValue(status: String, processed: Long, produced: Long, failureCode: String?): PortableValue =
    PvObject(
        listOf(
            Entry("schema", PvString("core.completion@1")),
            Entry("status", PvString(status)),
            Entry("processed", integerValue(processed)),
            Entry("produced", integerValue(produced)),
            Entry("limit_name", PvNull),
            Entry("failure_code", if (failureCode == null) PvNull else PvString(failureCode)),
        ),
    )

private fun messageEquals(left: ProtocolMessage, right: ProtocolMessage): Boolean =
    left.contract == right.contract && equal(left.payload, right.payload)

private fun integerValue(value: Int): PvInteger = PvInteger(BigInteger.valueOf(value.toLong()))

private fun integerValue(value: Long): PvInteger = PvInteger(BigInteger.valueOf(value))

private fun expectedLongList(case: CaseData, name: String): List<Long> =
    expectedSequence(case, name)?.map {
        (it as? PvInteger)?.value?.toLong() ?: fail("expected.$name item must be Integer")
    } ?: fail("missing expected.$name")

private fun expectedStringList(case: CaseData, name: String): List<String> =
    expectedSequence(case, name)?.map {
        (it as? PvString)?.value ?: fail("expected.$name item must be String")
    } ?: fail("missing expected.$name")

private fun graphFromInput(case: CaseData, name: String): Graph =
    graphFromValue(caseInput(case, name) ?: fail("missing input.$name"))

/** Builds one PortableGraph from the language-neutral vector descriptor
 * (semantic_model_v5.rs): a `{nodes, roots}` object whose node
 * records carry `kind`/`tag` and `content`, `items`, or `entries`. */
private fun graphFromValue(value: PortableValue): Graph {
    val fields = value as? PvObject ?: fail("graph must be Object")
    val nodeValues = (fields.get("nodes") as? PvArray)?.items() ?: fail("graph.nodes must be Sequence")
    val rootValues = (fields.get("roots") as? PvArray)?.items() ?: fail("graph.roots must be Sequence")
    val builder = Builder.newBuilder()
    val ids = ArrayList<NodeId>(nodeValues.size)
    for (index in nodeValues.indices) {
        ids.add(tryReserve(builder))
    }
    for ((index, nodeValue) in nodeValues.withIndex()) {
        val node = nodeValue as? PvObject ?: fail("graph node must be Object")
        val kind = (node.get("kind") as? PvString)?.value ?: fail("graph node kind must be String")
        val tag = (node.get("tag") as? PvString)?.value ?: fail("graph node tag must be String")
        when (kind) {
            "Scalar" -> {
                val content = (node.get("content") as? PvString)?.value
                    ?: fail("scalar node content must be String")
                tryDefine { builder.defineScalar(ids[index], tag, content) }
            }
            "Sequence" -> {
                val items = (node.get("items") as? PvArray)?.items() ?: fail("sequence.items must be Sequence")
                tryDefine { builder.defineSequence(ids[index], tag, items.map { graphReference(it, ids) }) }
            }
            "Mapping" -> {
                val entries = (node.get("entries") as? PvArray)?.items() ?: fail("mapping.entries must be Sequence")
                val mappingEntries = entries.map { entry ->
                    val entryFields = entry as? PvObject ?: fail("mapping entry must be Object")
                    MappingEntry(
                        graphReference(entryFields.get("key") ?: fail("mapping entry key missing"), ids),
                        graphReference(entryFields.get("value") ?: fail("mapping entry value missing"), ids),
                    )
                }
                tryDefine { builder.defineMapping(ids[index], tag, mappingEntries) }
            }
            else -> fail("unknown graph node kind")
        }
    }
    for (root in rootValues) {
        tryDefine { builder.pushRoot(graphReference(root, ids)) }
    }
    return try {
        builder.build()
    } catch (e: GraphException) {
        fail("graph build failed: ${e.code}")
    }
}

private fun tryReserve(builder: Builder): NodeId =
    try {
        builder.reserveNode()
    } catch (e: GraphException) {
        fail("graph node reservation failed: ${e.code}")
    }

private fun tryDefine(definition: () -> Unit) {
    try {
        definition()
    } catch (e: GraphException) {
        fail("graph definition failed: ${e.code}")
    }
}

private fun graphReference(value: PortableValue, ids: List<NodeId>): NodeId {
    val index = (value as? PvInteger)?.value?.toInt() ?: fail("graph reference must be an Integer")
    return ids.getOrNull(index) ?: fail("graph reference out of range")
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
