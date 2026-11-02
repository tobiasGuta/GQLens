package dev.gqlens.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Standards-based, bounded schema discovery. No application-specific field names,
 * mutation execution, guessing, fuzzing or generated follow-up traffic.
 */
object SchemaRecon {
    enum class Mode(val title: String) {
        ROOT_FIELDS("Root operations"),
        TYPE_FIELDS("Schema types and fields")
    }

    private val mapper = ObjectMapper()
    const val MAX_RESPONSE_BYTES = 524_288
    private const val MAX_TYPES = 512
    private const val MAX_FIELDS_PER_TYPE = 128
    private const val MAX_TOTAL_FIELDS = 4096

    // Introspection uses the spec's __Type.kind/name/ofType modifiers. Depth is
    // bounded (rather than querying a recursive fragment, which GraphQL prohibits).
    private fun typeRefSelection(depth: Int): String =
        "kind name" + if (depth <= 0) "" else
            " ofType { " + typeRefSelection(depth - 1) + " }"

    private val typeRef = typeRefSelection(7)
    private val fieldSelection = """
        name
        type { $typeRef }
        args { name defaultValue type { $typeRef } }
    """.trimIndent()

    /** No guessed application-specific names or non-introspection execution. */
    private val rootQuery = """
        query GQLensSchemaRoots {
          __schema {
            queryType { name fields { $fieldSelection } }
            mutationType { name fields { $fieldSelection } }
            subscriptionType { name fields { $fieldSelection } }
          }
        }
    """.trimIndent()

    private val typesQuery = """
        query GQLensSchemaTypes {
          __schema {
            queryType { name }
            mutationType { name }
            subscriptionType { name }
            types {
              name
              kind
              fields(includeDeprecated: true) { $fieldSelection }
              inputFields { name type { $typeRef } }
              enumValues(includeDeprecated: true) { name }
            }
          }
        }
    """.trimIndent()

    fun document(mode: Mode): String = when (mode) {
        Mode.ROOT_FIELDS -> rootQuery
        Mode.TYPE_FIELDS -> typesQuery
    }

    fun requestBody(mode: Mode): String =
        mapper.writeValueAsString(mapOf("query" to document(mode)))

    data class TypeSignature(val display: String, val namedType: String, val namedKind: String)
    data class ArgumentSignature(val name: String, val type: TypeSignature, val hasDefault: Boolean) {
        val required: Boolean get() = type.display.endsWith("!") && !hasDefault
    }
    data class FieldSignature(
        val name: String, val type: TypeSignature?, val args: List<ArgumentSignature> = emptyList()
    ) {
        val display: String get() = name +
            (if (args.isNotEmpty()) args.joinToString(", ", "(", ")") {
                it.name + ": " + it.type.display +
                    if (it.hasDefault) " = default" else ""
            } else "") +
            (type?.let { ": " + it.display } ?: "")
    }
    data class TypeSummary(
        val name: String, val kind: String, val fields: List<String>,
        val details: List<FieldSignature> = emptyList()
    )
    data class Result(
        val mode: Mode,
        val httpStatus: Int?,
        val outcome: String,
        val rootTypes: List<TypeSummary> = emptyList(),
        val types: List<TypeSummary> = emptyList(),
        val truncated: Boolean = false,
        /** Actual root type names supplied by the server, keyed by GraphQL operation kind. */
        val rootTypeNames: Map<String, String> = emptyMap()
    ) {
        val allTypes: List<TypeSummary> get() = if (mode == Mode.ROOT_FIELDS) rootTypes else types
    }

    fun parse(mode: Mode, httpStatus: Int, responseBody: String): Result {
        if (responseBody.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return Result(mode, httpStatus, "RESPONSE_TOO_LARGE")
        }
        val root: JsonNode = try { mapper.readTree(responseBody) ?: return Result(mode, httpStatus, "NON_JSON") }
            catch (_: Exception) { return Result(mode, httpStatus, "NON_JSON") }
        val schema = root.path("data").path("__schema")
        if (!schema.isObject) {
            val outcome = if (root.path("errors").isArray) "INTROSPECTION_UNAVAILABLE_OR_REJECTED"
                else "NO_SCHEMA_DATA"
            return Result(mode, httpStatus, outcome)
        }

        var fields = 0
        var arguments = 0
        var truncated = false

        fun typeOf(node: JsonNode): TypeSignature? {
            if (!node.isObject) return null
            val wrappers = ArrayList<String>()
            var item = node
            repeat(9) {
                val kind = item.path("kind").asText()
                when (kind) {
                    "NON_NULL", "LIST" -> {
                        wrappers.add(kind)
                        item = item.path("ofType")
                    }
                    "SCALAR", "ENUM", "OBJECT", "INTERFACE", "UNION", "INPUT_OBJECT" -> {
                        val name = item.path("name").takeIf { it.isTextual }?.asText() ?: return null
                        if (!name.matches(Regex("[_A-Za-z][_0-9A-Za-z]{0,127}"))) return null
                        var printed = name
                        for (wrapper in wrappers.asReversed()) {
                            printed = if (wrapper == "LIST") "[$printed]" else "$printed!"
                        }
                        return TypeSignature(printed, name, kind)
                    }
                    else -> return null
                }
            }
            truncated = true
            return null
        }

        fun signature(entry: JsonNode): FieldSignature? {
            val name = entry.path("name").takeIf { it.isTextual }?.asText() ?: return null
            val args = ArrayList<ArgumentSignature>()
            val allArgs = entry.path("args")
            if (allArgs.isArray) {
                for (arg in allArgs) {
                    if (args.size >= 32 || arguments >= 8192) {
                        truncated = true
                        break
                    }
                    val argName = arg.path("name").takeIf { it.isTextual }?.asText() ?: continue
                    if (!argName.matches(Regex("[_A-Za-z][_0-9A-Za-z]{0,127}"))) continue
                    val argType = typeOf(arg.path("type")) ?: continue
                    args.add(ArgumentSignature(argName, argType, !arg.path("defaultValue").isNull &&
                        !arg.path("defaultValue").isMissingNode))
                    arguments++
                }
            }
            return FieldSignature(name, typeOf(entry.path("type")), args)
        }

        fun gather(node: JsonNode): List<String> {
            if (!node.isArray) return emptyList()
            val result = ArrayList<String>()
            for (entry in node) {
                if (fields >= MAX_TOTAL_FIELDS || result.size >= MAX_FIELDS_PER_TYPE) {
                    truncated = true
                    break
                }
                val name = entry.path("name").takeIf { it.isTextual }?.asText() ?: continue
                if (name.length > 256) {
                    truncated = true
                    continue
                }
                result.add(name)
                fields++
            }
            return result
        }

        fun convert(node: JsonNode, includeEnums: Boolean): TypeSummary? {
            if (!node.isObject) return null
            val name = node.path("name").takeIf { it.isTextual }?.asText()?.takeIf { it.length <= 256 }
                ?: return null
            val kind = node.path("kind").takeIf { it.isTextual }?.asText() ?: "OBJECT"
            val names = gather(node.path("fields")) +
                gather(node.path("inputFields")) +
                (if (includeEnums) gather(node.path("enumValues")) else emptyList())
            val named = names.toSet()
            val entries = sequenceOf(node.path("fields"), node.path("inputFields"))
                .filter { it.isArray }.flatMap { it.asSequence() }
            val details = entries.mapNotNull { entry ->
                if (entry.path("name").asText() in named) signature(entry) else null
            }.take(MAX_FIELDS_PER_TYPE * 2).toList()
            return TypeSummary(name, kind.take(32), names.distinct(), details)
        }

        val rootTypeNames = linkedMapOf<String, String>()
        for ((role, field) in listOf("QUERY" to "queryType",
            "MUTATION" to "mutationType", "SUBSCRIPTION" to "subscriptionType")) {
            val name = schema.path(field).path("name")
            if (name.isTextual && name.asText().length <= 256) rootTypeNames[role] = name.asText()
        }
        val roots = listOf("queryType", "mutationType", "subscriptionType")
            .mapNotNull { name -> convert(schema.path(name), false) }
        if (mode == Mode.ROOT_FIELDS) {
            return Result(mode, httpStatus, "SCHEMA_OBSERVED", roots, truncated = truncated,
                rootTypeNames = rootTypeNames)
        }
        val typesArray = schema.path("types")
        if (!typesArray.isArray) return Result(mode, httpStatus, "NO_TYPES_IN_SCHEMA", roots, rootTypeNames = rootTypeNames)
        val types = ArrayList<TypeSummary>()
        for (entry in typesArray) {
            if (types.size >= MAX_TYPES) { truncated = true; break }
            convert(entry, true)?.let { types.add(it) }
        }
        return Result(mode, httpStatus, "SCHEMA_OBSERVED", roots, types, truncated,
            rootTypeNames = rootTypeNames)
    }
}
