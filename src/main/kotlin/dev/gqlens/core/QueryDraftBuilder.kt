package dev.gqlens.core

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.parser.Parser

/**
 * Produces local, read-only drafts from complete introspected QUERY signatures.
 * It never issues requests, creates Repeater tabs, guesses required values, or
 * generates mutation operations.
 */
object QueryDraftBuilder {
    data class RequiredVariable(val name: String, val type: String)
    data class Draft(
        val document: String,
        val requiredVariables: List<RequiredVariable>,
        val note: String
    ) {
        /** Deliberately empty variable map; required user input is never guessed. */
        fun jsonBody(): String = ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(linkedMapOf("query" to document, "variables" to emptyMap<String, Any>()))
    }

    private val validName = Regex("[_A-Za-z][_0-9A-Za-z]{0,127}")
    private val typeLiteral = Regex("[_A-Za-z0-9!\\[\\]]{1,160}")

    fun build(kind: String, field: SchemaRecon.FieldSignature?): Draft? {
        if (kind != "QUERY" || field == null || !field.complete ||
            !validName.matches(field.name)) return null
        val output = field.type ?: return null
        if (!validName.matches(output.namedType) ||
            output.namedKind !in setOf("OBJECT", "INTERFACE", "UNION", "SCALAR", "ENUM")) return null
        // All declared arguments must be valid; otherwise an omitted required
        // argument could yield an apparently usable but invalid document.
        if (field.args.distinctBy { it.name }.size != field.args.size ||
            field.args.any { !validName.matches(it.name) || !typeLiteral.matches(it.type.display) }) return null
        val required = field.args.filter { it.required }
        val declarations = if (required.isEmpty()) "" else required.joinToString(", ", "(", ")") {
            "$" + it.name + ": " + it.type.display
        }
        val call = field.name + if (required.isEmpty()) "" else required.joinToString(
            ", ", "(", ")"
        ) { it.name + ": $" + it.name }
        val objectSelection = output.namedKind in setOf("OBJECT", "INTERFACE", "UNION")
        val selection = if (objectSelection) {
            "  $call {\n    __typename\n  }"
        } else "  $call"
        val document = "query GQLensPreview$declarations {\n$selection\n}"
        try { Parser().parseDocument(document) } catch (_: Exception) { return null }
        return Draft(document, required.map { RequiredVariable(it.name, it.type.display) },
            if (required.isEmpty()) "No required variables. Draft for manual review only."
            else "Required variables are not populated. Supply authorized values manually.")
    }
}
