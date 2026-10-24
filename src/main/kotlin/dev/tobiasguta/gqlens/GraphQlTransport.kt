package dev.tobiasguta.gqlens

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Extracts only query documents. Variables, operationName, extensions, and
 * authentication material are intentionally discarded.
 */
object GraphQlTransport {
    private const val MAX_REQUEST_CHARS = 256 * 1024
    private const val MAX_BATCH = 20
    private val mapper = ObjectMapper()

    fun extract(method: String, path: String, contentType: String, body: String): List<String> {
        if (body.length > MAX_REQUEST_CHARS || path.length > MAX_REQUEST_CHARS) return emptyList()
        return when (method.uppercase()) {
            "GET" -> extractGet(path)
            "POST" -> extractPost(contentType, body)
            else -> emptyList()
        }
    }

    private fun extractGet(path: String): List<String> {
        val rawQuery = path.substringAfter('?', missingDelimiterValue = "")
        if (rawQuery.isEmpty()) return emptyList()
        for (pair in rawQuery.split('&').take(128)) {
            val index = pair.indexOf('=')
            if (index < 0) continue
            val key = decode(pair.substring(0, index)) ?: continue
            if (key != "query") continue
            val query = decode(pair.substring(index + 1)) ?: return emptyList()
            return if (query.isNotBlank()) listOf(query) else emptyList()
        }
        return emptyList()
    }

    private fun extractPost(contentType: String, body: String): List<String> {
        if (body.isBlank()) return emptyList()
        if (contentType.substringBefore(';').trim().equals("application/graphql", ignoreCase = true)) {
            return listOf(body)
        }
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        val root: JsonNode = try {
            mapper.readTree(body) ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val nodes = if (root.isArray) {
            if (root.size() > MAX_BATCH) return emptyList()
            root.toList()
        } else listOf(root)
        return nodes.mapNotNull { node ->
            node.takeIf { it.isObject }?.get("query")?.takeIf { it.isTextual }?.asText()
                ?.takeIf { it.isNotBlank() && it.length <= MAX_REQUEST_CHARS }
        }
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
