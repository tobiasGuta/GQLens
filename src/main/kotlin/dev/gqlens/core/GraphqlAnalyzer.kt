package dev.gqlens.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.Selection
import graphql.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class Observation(
    val endpoint: String,
    val kind: String,
    val operation: String,
    val fingerprint: String,
    val fieldPaths: List<String>
)

/**
 * Parses observed requests only. It never sends traffic and never stores the query text,
 * variables, argument values, authorization headers or response bodies.
 */
object GraphqlAnalyzer {
    private val mapper = ObjectMapper()
    private const val MAX_BODY_CHARS = 262_144
    private const val MAX_QUERY_CHARS = 131_072
    private const val MAX_FIELDS = 500
    private const val MAX_DEPTH = 32
    private const val MAX_BATCH = 32

    fun extract(method: String, url: String, contentType: String?, body: String): List<Observation> {
        val endpoint = endpointWithoutSecrets(url) ?: return emptyList()
        if (body.length > MAX_BODY_CHARS || url.length > MAX_BODY_CHARS) return emptyList()
        val candidates = mutableListOf<Pair<String?, String?>>()
        val ct = contentType.orEmpty().lowercase()

        if (method.equals("GET", ignoreCase = true)) {
            val parameters = try { params(URI(url).rawQuery.orEmpty()) } catch (_: Exception) { emptyMap() }
            candidates.add(parameters["query"] to persistedHash(parameters["extensions"]))
        } else if (method.equals("POST", ignoreCase = true)) {
            val trimmed = body.trimStart()
            when {
                ct.contains("application/graphql") -> candidates.add(body to null)
                ct.contains("application/x-www-form-urlencoded") -> {
                    val parameters = params(body)
                    candidates.add(parameters["query"] to persistedHash(parameters["extensions"]))
                }
                (ct.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) -> {
                    try {
                        val root = mapper.readTree(body)
                        val values: List<JsonNode> = when {
                            root.isObject -> listOf(root)
                            root.isArray -> root.take(MAX_BATCH)
                            else -> emptyList()
                        }
                        for (node in values) {
                            if (!node.isObject) continue
                            val query = node.path("query").takeIf { it.isTextual }?.asText()
                            val hash = node.path("extensions").path("persistedQuery")
                                .path("sha256Hash").takeIf { it.isTextual }?.asText()
                                ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                            if (query != null || hash != null) candidates.add(query to hash)
                        }
                    } catch (_: Exception) {
                        // A JSON-shaped non-GraphQL request is not evidence.
                    }
                }
            }
        }

        val observations = mutableListOf<Observation>()
        for ((query, hash) in candidates.take(MAX_BATCH)) {
            if (!query.isNullOrBlank() && query.length <= MAX_QUERY_CHARS) {
                observations.addAll(parseOperations(query, endpoint))
            } else if (query == null && hash != null) {
                observations.add(Observation(endpoint, "PERSISTED", "(hash only)", hash.take(16), emptyList()))
            }
        }
        return observations
    }

    private fun parseOperations(query: String, endpoint: String): List<Observation> {
        val doc: Document = try { Parser().parseDocument(query) } catch (_: Exception) { return emptyList() }
        val fragments = doc.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
        val fingerprint = sha256(query).take(16)
        return doc.definitions.filterIsInstance<OperationDefinition>().take(MAX_BATCH).map { definition ->
            val paths = linkedSetOf<String>()
            visit(definition.selectionSet?.selections.orEmpty(), "", fragments, emptySet(), paths, 0)
            Observation(
                endpoint = endpoint,
                kind = definition.operation.name,
                operation = definition.name?.take(128) ?: "(anonymous)",
                fingerprint = fingerprint,
                fieldPaths = paths.toList()
            )
        }
    }

    private fun visit(
        selections: List<Selection<*>>,
        parent: String,
        fragments: Map<String, FragmentDefinition>,
        activeFragments: Set<String>,
        output: MutableSet<String>,
        depth: Int
    ) {
        if (depth >= MAX_DEPTH || output.size >= MAX_FIELDS) return
        for (selection in selections) {
            if (output.size >= MAX_FIELDS) break
            when (selection) {
                is Field -> {
                    val path = if (parent.isEmpty()) selection.name else parent + "." + selection.name
                    output.add(path)
                    val children = selection.selectionSet?.selections.orEmpty()
                    visit(children, path, fragments, activeFragments, output, depth + 1)
                }
                is InlineFragment ->
                    visit(selection.selectionSet?.selections.orEmpty(), parent, fragments, activeFragments, output, depth + 1)
                is FragmentSpread -> {
                    val name = selection.name
                    val fragment = fragments[name]
                    if (fragment != null && name !in activeFragments) {
                        visit(fragment.selectionSet?.selections.orEmpty(), parent, fragments, activeFragments + name, output, depth + 1)
                    }
                }
            }
        }
    }

    private fun endpointWithoutSecrets(rawUrl: String): String? {
        return try {
        val uri = URI(rawUrl)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val authority = if (uri.port >= 0) host + ":" + uri.port else host
        scheme + "://" + authority + (uri.rawPath?.ifEmpty { "/" } ?: "/")
        } catch (_: Exception) {
            null
        }
    }

    private fun params(encoded: String): Map<String, String> = buildMap {
        for (part in encoded.split("&").take(128)) {
            val pair = part.split("=", limit = 2)
            try {
                val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                if (key in setOf("query", "extensions") && pair.size == 2) {
                    put(key, URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                }
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun persistedHash(raw: String?): String? = try {
        raw?.let {
            mapper.readTree(it).path("persistedQuery").path("sha256Hash")
                .takeIf { node -> node.isTextual }?.asText()
                ?.takeIf { hash -> hash.matches(Regex("[a-fA-F0-9]{64}")) }
        }
    } catch (_: Exception) {
        null
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
