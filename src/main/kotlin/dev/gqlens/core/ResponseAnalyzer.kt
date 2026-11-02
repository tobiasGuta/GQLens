package dev.gqlens.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Classifies already received GraphQL responses. Nothing here makes an HTTP request,
 * retains a response body, or records application values or error messages.
 */
object ResponseAnalyzer {
    private const val MAX_RESPONSE_CHARS = 262_144
    private const val MAX_BATCH = 32
    private val mapper = ObjectMapper()

    enum class Shape {
        DATA, ERRORS, PARTIAL_DATA, NULL_DATA, UNRECOGNIZED_JSON, NON_JSON, OVERSIZED
    }

    /** Ordered shapes; JSON array indices match the originating request batch indices. */
    fun classify(contentType: String?, body: String): List<Shape> {
        if (body.length > MAX_RESPONSE_CHARS) return listOf(Shape.OVERSIZED)
        val trimmed = body.trimStart()
        if (!contentType.orEmpty().contains("json", ignoreCase = true) &&
            !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return listOf(Shape.NON_JSON)
        }
        val root: JsonNode = try { mapper.readTree(body) ?: return listOf(Shape.NON_JSON) }
            catch (_: Exception) { return listOf(Shape.NON_JSON) }
        return when {
            root.isArray -> root.take(MAX_BATCH).map(::shape)
            else -> listOf(shape(root))
        }
    }

    private fun shape(value: JsonNode): Shape {
        if (!value.isObject) return Shape.UNRECOGNIZED_JSON
        val dataPresent = value.has("data") && !value.path("data").isNull
        val errors = value.path("errors")
        val errorsPresent = errors.isArray && errors.size() > 0
        return when {
            dataPresent && errorsPresent -> Shape.PARTIAL_DATA
            errorsPresent -> Shape.ERRORS
            dataPresent -> Shape.DATA
            value.has("data") -> Shape.NULL_DATA
            else -> Shape.UNRECOGNIZED_JSON
        }
    }
}
