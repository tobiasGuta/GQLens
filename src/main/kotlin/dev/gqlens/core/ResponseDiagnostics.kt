package dev.gqlens.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * GraphQL response metadata only. No error messages, paths, data values, locations,
 * HTTP headers, cookies or extension objects survive parsing.
 *
 * There are intentionally NO hardcoded application/server error categories.
 * The spec permits arbitrary extensions; only identifier-shaped codes supplied
 * by the server are retained as opaque labels, never mapped to vulnerability types.
 */
object ResponseDiagnostics {
    private val mapper = ObjectMapper()
    private const val MAX_CHARS = 262_144
    private const val MAX_BATCH = 32
    private const val MAX_ERRORS = 64
    private val safeCode = Regex("[A-Z][A-Z0-9_]{0,47}")

    data class Summary(
        val errors: Int = 0,
        val hasData: Boolean = false,
        val hasLocations: Boolean = false,
        val hasPaths: Boolean = false,
        val codes: List<String> = emptyList(),
        val truncated: Boolean = false
    )

    fun inspect(contentType: String?, body: String): List<Summary> {
        if (body.length > MAX_CHARS) return listOf(Summary(truncated = true))
        val trimmed = body.trimStart()
        if (!contentType.orEmpty().contains("json", ignoreCase = true) &&
            !trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        val root = try { mapper.readTree(body) ?: return emptyList() }
            catch (_: Exception) { return emptyList() }
        return if (root.isArray) root.take(MAX_BATCH).map(::parse) else listOf(parse(root))
    }

    private fun parse(root: JsonNode): Summary {
        if (!root.isObject) return Summary()
        val errors = root.path("errors")
        if (!errors.isArray) return Summary(hasData = root.has("data"))
        val codes = linkedSetOf<String>()
        var withLocations = false
        var withPaths = false
        for ((index, error) in errors.withIndex()) {
            if (index >= MAX_ERRORS) break
            if (!error.isObject) continue
            withLocations = withLocations || error.path("locations").isArray
            withPaths = withPaths || error.path("path").isArray
            val code = error.path("extensions").path("code")
            if (codes.size < 12 && code.isTextual && safeCode.matches(code.asText())) {
                codes.add(code.asText())
            }
        }
        return Summary(
            errors = errors.size().coerceAtMost(MAX_ERRORS),
            hasData = root.has("data"),
            hasLocations = withLocations,
            hasPaths = withPaths,
            codes = codes.toList(),
            truncated = errors.size() > MAX_ERRORS
        )
    }
}
