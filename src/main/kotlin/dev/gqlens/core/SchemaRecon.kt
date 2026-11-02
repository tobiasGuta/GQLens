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

    /** These are GraphQL spec introspection fields, not guessed application fields. */
    private val rootQuery = """
        query GQLensSchemaRoots {
          __schema {
            queryType { name fields { name } }
            mutationType { name fields { name } }
            subscriptionType { name fields { name } }
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
              fields(includeDeprecated: true) { name }
              inputFields { name }
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

    data class TypeSummary(val name: String, val kind: String, val fields: List<String>)
    data class Result(
        val mode: Mode,
        val httpStatus: Int?,
        val outcome: String,
        val rootTypes: List<TypeSummary> = emptyList(),
        val types: List<TypeSummary> = emptyList(),
        val truncated: Boolean = false
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
        var truncated = false

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
            return TypeSummary(name, kind.take(32), names.distinct())
        }

        val roots = listOf("queryType", "mutationType", "subscriptionType")
            .mapNotNull { name -> convert(schema.path(name), false) }
        if (mode == Mode.ROOT_FIELDS) {
            return Result(mode, httpStatus, "SCHEMA_OBSERVED", roots, truncated = truncated)
        }
        val typesArray = schema.path("types")
        if (!typesArray.isArray) return Result(mode, httpStatus, "NO_TYPES_IN_SCHEMA", roots)
        val types = ArrayList<TypeSummary>()
        for (entry in typesArray) {
            if (types.size >= MAX_TYPES) { truncated = true; break }
            convert(entry, true)?.let { types.add(it) }
        }
        return Result(mode, httpStatus, "SCHEMA_OBSERVED", roots, types, truncated)
    }
}
