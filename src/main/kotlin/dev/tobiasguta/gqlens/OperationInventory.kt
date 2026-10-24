package dev.tobiasguta.gqlens

import java.net.URI

data class InventoryItem(
    val endpoint: String,
    val kind: String,
    val name: String,
    val fields: List<String>,
    val fingerprint: String,
    val sightings: Int
)

/**
 * Bounded, synchronized, metadata-only in-memory inventory.
 * No request/response object, document text, cookie, or variable is retained.
 */
class OperationInventory(private val capacity: Int = 1000) {
    private val operations = linkedMapOf<String, InventoryItem>()
    private var revision = 0L

    @Synchronized fun add(endpoint: String, observed: ObservedOperation) {
        val key = "$endpoint\u0000${observed.fingerprint}"
        val prior = operations[key]
        if (prior != null) {
            operations[key] = prior.copy(sightings = if (prior.sightings == Int.MAX_VALUE) Int.MAX_VALUE else prior.sightings + 1)
        } else {
            if (operations.size >= capacity) return
            operations[key] = InventoryItem(endpoint, observed.kind, observed.name, observed.fieldPaths, observed.fingerprint, 1)
        }
        revision++
    }

    @Synchronized fun snapshot(): Pair<Long, List<InventoryItem>> = revision to operations.values.toList()

    @Synchronized fun clear() {
        operations.clear()
        revision++
    }

    companion object {
        /** Keep the origin and path, but never query parameters or credentials. */
        fun safeEndpoint(url: String): String? {
            return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val rawPath = (uri.rawPath ?: "/").ifBlank { "/" }
            if (rawPath.length > 2048) return null
            val normalizedPath = rawPath.split('/').joinToString("/") { segment ->
                if (segment.length > 48 || UUID_SEGMENT.matches(segment) || NUMERIC_SEGMENT.matches(segment)) ":id" else segment
            }
            "$scheme://$host$port$normalizedPath"
        } catch (_: Exception) { null }
        }

        private val UUID_SEGMENT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}")
        private val NUMERIC_SEGMENT = Regex("\\d{5,}")
    }
}
