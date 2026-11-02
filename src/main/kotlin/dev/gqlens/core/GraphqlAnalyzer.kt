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
    val fieldPaths: List<String>,
    /** Index in a GraphQL JSON request batch, not an execution claim. */
    val batchIndex: Int = 0
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

    private data class Candidate(
        val query: String?,
        val hash: String?,
        val operationName: String?,
        val batchIndex: Int
    )

    fun extract(method: String, url: String, contentType: String?, body: String): List<Observation> {
        val endpoint = endpointWithoutSecrets(url) ?: return emptyList()
        if (body.length > MAX_BODY_CHARS || url.length > MAX_BODY_CHARS) return emptyList()
        val candidates = mutableListOf<Candidate>()
        val ct = contentType.orEmpty().lowercase()

        if (method.equals("GET", ignoreCase = true)) {
            val parameters = try { params(URI(url).rawQuery.orEmpty()) } catch (_: Exception) { emptyMap() }
            candidates.add(Candidate(parameters["query"], persistedHash(parameters["extensions"]), parameters["operationName"], 0))
        } else if (method.equals("POST", ignoreCase = true)) {
            val trimmed = body.trimStart()
            when {
                ct.contains("application/graphql") -> candidates.add(Candidate(body, null, null, 0))
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
                        for ((index, node) in values.withIndex()) {
                            if (!node.isObject) continue
                            val query = node.path("query").takeIf { it.isTextual }?.asText()
                            val hash = node.path("extensions").path("persistedQuery")
                                .path("sha256Hash").takeIf { it.isTextual }?.asText()
                                ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                            val operationName = node.path("operationName")
                                .takeIf { it.isTextual }?.asText()
                            if (query != null || hash != null) {
                                candidates.add(Candidate(query, hash, operationName, index))
                            }
                        }
                    } catch (_: Exception) {
                        // A JSON-shaped non-GraphQL request is not evidence.
                    }
                }
            }
        }

        val observations = mutableListOf<Observation>()
        for (candidate in candidates.take(MAX_BATCH)) {
            val query = candidate.query
            if (!query.isNullOrBlank() && query.length <= MAX_QUERY_CHARS) {
                observations.addAll(
                    parseOperations(query, endpoint, candidate.operationName, candidate.batchIndex)
                )
            } else if (query == null && candidate.hash != null) {
                observations.add(
                    Observation(endpoint, "PERSISTED", candidate.operationName ?: "(hash only)",
                        candidate.hash.take(16), emptyList(), candidate.batchIndex)
                )
            }
        }
        return observations
    }

    private fun parseOperations(
        query: String,
        endpoint: String,
        operationName: String?,
        batchIndex: Int
    ): List<Observation> {
        val doc: Document = try { Parser().parseDocument(query) } catch (_: Exception) { return emptyList() }
        val fragments = doc.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
        val definitions = doc.definitions.filterIsInstance<OperationDefinition>()
        // A document can contain multiple operations, but a request selects at most one.
        // Without operationName a multi-operation document is ambiguous, not evidence
        // that all of its operations were executed.
        val selected = when {
            operationName != null -> definitions.singleOrNull { it.name == operationName }
            definitions.size == 1 -> definitions.single()
            else -> null
        } ?: return emptyList()
        val paths = linkedSetOf<String>()
        visit(selected.selectionSet?.selections.orEmpty(), "", fragments, emptySet(), paths, 0)
        return listOf(
            Observation(
                endpoint = endpoint,
                kind = selected.operation.name,
                operation = selected.name?.take(128) ?: "(anonymous)",
                fingerprint = sha256(query).take(16),
                fieldPaths = paths.toList(),
                batchIndex = batchIndex
            )
        )
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
                if (key in setOf("query", "extensions", "operationName") && pair.size == 2) {
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
