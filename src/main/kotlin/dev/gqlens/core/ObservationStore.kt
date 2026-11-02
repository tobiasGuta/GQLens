package dev.gqlens.core

/** Non-sensitive provenance only. Message IDs are Burp-session-local, not durable links. */
data class ObservationContext(
    val tool: String,
    val messageId: Int,
    val observedAtMillis: Long = System.currentTimeMillis()
)

data class ObservationRow(
    val endpoint: String,
    val kind: String,
    val operation: String,
    val fingerprint: String,
    val fieldPaths: List<String>,
    val seen: Int,
    val firstSeenMillis: Long = 0,
    val lastSeenMillis: Long = 0,
    val lastTool: String = "UNKNOWN",
    val lastMessageId: Int? = null,
    val lastHttpStatus: Int? = null,
    val lastResponseShape: String = "NOT_OBSERVED",
    val responsesSeen: Int = 0
)

/**
 * Bounded, synchronized, metadata-only inventory.
 * Keeps at most [limit] distinct observations and 512 outstanding HTTP correlations.
 * It never retains request/response bodies or authentication data.
 */
class ObservationStore(private val limit: Int = 2000) {
    private data class PendingLink(val rowKey: String, val batchIndex: Int)

    private val rows = LinkedHashMap<String, ObservationRow>()
    private val pending = LinkedHashMap<String, MutableList<PendingLink>>()
    private var rejected = 0L

    @Synchronized
    fun record(observation: Observation, context: ObservationContext? = null): Boolean {
        val key = listOf(observation.endpoint, observation.kind, observation.operation, observation.fingerprint)
            .joinToString("\u0000")
        val old = rows[key]
        if (old == null && rows.size >= limit) {
            rejected++
            return false
        }
        val now = context?.observedAtMillis ?: System.currentTimeMillis()
        rows[key] = if (old == null) {
            ObservationRow(
                observation.endpoint, observation.kind, observation.operation,
                observation.fingerprint, observation.fieldPaths, 1,
                firstSeenMillis = now,
                lastSeenMillis = now,
                lastTool = context?.tool ?: "UNKNOWN",
                lastMessageId = context?.messageId
            )
        } else old.copy(
            fieldPaths = (old.fieldPaths + observation.fieldPaths).distinct().take(500),
            seen = if (old.seen == Int.MAX_VALUE) old.seen else old.seen + 1,
            lastSeenMillis = now,
            lastTool = context?.tool ?: old.lastTool,
            lastMessageId = context?.messageId ?: old.lastMessageId
        )
        if (context != null) {
            val id = correlationKey(context)
            val links = pending.getOrPut(id) { mutableListOf() }
            val link = PendingLink(key, observation.batchIndex)
            if (link !in links) links.add(link)
            while (pending.size > 512) pending.remove(pending.keys.first())
        }
        return true
    }

    /** Correlate responses by Burp's request/response message ID and tool, not URL alone. */
    @Synchronized
    fun recordResponse(
        context: ObservationContext,
        httpStatus: Int,
        shapes: List<ResponseAnalyzer.Shape>
    ): Boolean {
        val links = pending.remove(correlationKey(context)) ?: return false
        var changed = false
        for (link in links) {
            val old = rows[link.rowKey] ?: continue
            val shape = shapes.getOrNull(link.batchIndex)?.name ?: "UNMATCHED_BATCH_RESULT"
            rows[link.rowKey] = old.copy(
                lastHttpStatus = httpStatus,
                lastResponseShape = shape,
                responsesSeen = if (old.responsesSeen == Int.MAX_VALUE) old.responsesSeen
                    else old.responsesSeen + 1
            )
            changed = true
        }
        return changed
    }

    @Synchronized
    fun snapshot(): List<ObservationRow> = rows.values.toList()

    @Synchronized
    fun rejectedCount(): Long = rejected

    @Synchronized
    fun clear() {
        rows.clear()
        pending.clear()
        rejected = 0
    }

    private fun correlationKey(context: ObservationContext): String =
        context.tool + "\u0000" + context.messageId
}
