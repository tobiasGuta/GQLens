package dev.gqlens.core

import graphql.parser.Parser

/**
 * Generates query text only. Does not contact a target, create a Repeater tab,
 * insert guessed values, execute mutations or retain sensitive variables.
 */
object QueryDraftBuilder {
    data class RequiredVariable(val name: String, val type: String)
    data class Draft(
        val document: String,
        val requiredVariables: List<RequiredVariable>,
        val note: String
    )

    private val validName = Regex("[_A-Za-z][_0-9A-Za-z]{0,127}")

    /**
     * A query operation is generated only from a real introspected root signature.
     * Required arguments are represented as variables without fabricated values.
     * Optional arguments are omitted. Object/interface/union outputs select only
     * the standard __typename meta-field; scalar and enum outputs select the leaf.
     */
    fun build(kind: String, field: SchemaRecon.FieldSignature?): Draft? {
        if (kind != "QUERY" || field == null || !validName.matches(field.name)) return null
        val output = field.type ?: return null
        if (!validName.matches(output.namedType)) return null
        if (output.namedKind !in setOf("OBJECT", "INTERFACE", "UNION", "SCALAR", "ENUM")) return null
        val required = field.args.filter { it.required }
        if (required.any { !validName.matches(it.name) ||
                !it.type.display.matches(Regex("[_A-Za-z0-9!\\[\\]]{1,160}")) }) {
            return null
        }
        val declarations = if (required.isEmpty()) "" else required.joinToString(
            ", ", "(", ")"
        ) { "$" + it.name + ": " + it.type.display }
        val call = if (required.isEmpty()) field.name else field.name +
            required.joinToString(", ", "(", ")") { it.name + ": $" + it.name }
        val selection = if (output.namedKind in setOf("OBJECT", "INTERFACE", "UNION"))
            " { __typename }" else ""
        val document = "query GQLensPreview$declarations { $call$selection }"
        // Fail closed if introspected names create a syntactically invalid document.
        try { Parser().parseDocument(document) } catch (_: Exception) { return null }
        return Draft(
            document,
            required.map { RequiredVariable(it.name, it.type.display) },
            if (required.isEmpty())
                "No required variables. Document is generated for manual review only."
            else "Required variable values are intentionally not generated; supply authorized values manually."
        )
    }
}
