// Focused debug: edit semantic + pvce object vector.
import consema.conformance.parseVectorJson
import consema.document.ParseLimits
import consema.json.JsonProfile
import consema.json.commit

fun main() {
    // 1. Reproduce valueFromInput for {"integer":"200"}
    val vector = parseVectorJson("""{"integer":"200"}""".toByteArray())
    println("vector root: $vector")
    // 2. Edit: parse the source and commit a semantic scalar
    val source = "{ /* lead */ \"a\" : 1 // tail\n}"
    val document = consema.json.parse(source.toByteArray(), JsonProfile.JsoncBoundedV1, ParseLimits.default)
    val member = (document.root().objectMembers() as consema.json.SemanticAvailability.Available).value!![0]
    val builder = consema.json.EditTransactionBuilder.new(document)
    builder.semanticScalar(member.valueNodeRef(), consema.core.PvInteger(java.math.BigInteger("200")), consema.json.RepresentationPolicy.PreserveCompatible)
    try {
        val commit = document.commit(builder.build())
        println("commit ok: ${String(commit.document.render())}")
    } catch (e: Exception) {
        println("commit failed: $e")
    }
    // 3. pvce.object-vector: build the value and encode
    val objectValue = parseVectorJson("""{"a":{"integer":"1"}}""".toByteArray())
    println("object value: $objectValue")
    val hex = consema.conformance.toHex(consema.core.encodePvce(objectValue!!))
    println("pvce hex: $hex expected: 5056434501410a01200201611003010101")
    // 4. protocol roundtrip
    val definition = consema.protocol.QueryDefinition(consema.protocol.QueryDomain("core.portable-value-query", 1))
        .withExpression(
            consema.protocol.QueryExpression(consema.protocol.ExpressionKind.Input)
                .then(consema.protocol.OperatorCall("core.try-sequence-elements", 1)),
        )
        .withSelection(consema.protocol.QuerySelection.First)
    val value = definition.toProtocolValue()
    val decoded = consema.protocol.QueryDefinition.fromProtocolValue(value)
    println("roundtrip equal: ${consema.core.equal(decoded.toProtocolValue(), value)}")
}
