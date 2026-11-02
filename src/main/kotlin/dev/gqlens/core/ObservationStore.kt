package dev.gqlens.core

/** Session-local correlation metadata. Never contains raw HTTP or GraphQL values. */
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
    val responsesSeen: Int = 0,
    /** Whether this operation was also captured outside GQLens's own recon sender. */
    val regularSeen: Int = 0,
    val reconSeen: Int = 0
)

/**
 * A single request observation and (when correlated) its response classification.
 * IDs refer to this in-memory session only; they do not resolve requests outside Burp.
 */
data class EvidenceEvent(
    val id: Long,
    val endpoint: String,
    val kind: String,
    val operation: String,
    val fingerprint: String,
    val batchIndex: Int,
    val tool: String,
    val messageId: Int,
    val observedAtMillis: Long,
    val httpStatus: Int? = null,
    val responseShape: String = "PENDING",
    val diagnostics: ResponseDiagnostics.Summary? = null
)

/** Bounded in-memory operation inventory and per-request evidence history. */
class ObservationStore(
    private val limit: Int = 2000,
    private val evidenceLimit: Int = 3000
) {
    private data class PendingLink(val rowKey: String, val batchIndex: Int, val evidenceId: Long?)

    private val rows = LinkedHashMap<String, ObservationRow>()
    private val evidence = LinkedHashMap<Long, EvidenceEvent>()
    private val pending = LinkedHashMap<String, MutableList<PendingLink>>()
    private var sequence = 0L
    private var rejected = 0L
    private var evictedEvidence = 0L

    private fun key(endpoint: String, kind: String, operation: String, fingerprint: String): String =
        listOf(endpoint, kind, operation, fingerprint).joinToString("\u0000")

    private fun key(row: ObservationRow): String =
        key(row.endpoint, row.kind, row.operation, row.fingerprint)

    private fun correlationKey(context: ObservationContext): String =
        context.tool + "\u0000" + context.messageId

    @Synchronized
    fun record(observation: Observation, context: ObservationContext? = null): Boolean {
        val rowKey = key(observation.endpoint, observation.kind, observation.operation, observation.fingerprint)
        val old = rows[rowKey]
        if (old == null && rows.size >= limit) {
            rejected++
            return false
        }
        val now = context?.observedAtMillis ?: System.currentTimeMillis()
        rows[rowKey] = if (old == null) {
            ObservationRow(
                observation.endpoint, observation.kind, observation.operation,
                observation.fingerprint, observation.fieldPaths, 1,
                firstSeenMillis = now, lastSeenMillis = now,
                lastTool = context?.tool ?: "UNKNOWN",
                lastMessageId = context?.messageId,
                regularSeen = if (context?.tool == "GQLENS_RECON") 0 else 1,
                reconSeen = if (context?.tool == "GQLENS_RECON") 1 else 0
            )
        } else old.copy(
            fieldPaths = (old.fieldPaths + observation.fieldPaths).distinct().take(500),
            seen = if (old.seen == Int.MAX_VALUE) old.seen else old.seen + 1,
            lastSeenMillis = now,
            lastTool = context?.tool ?: old.lastTool,
            lastMessageId = context?.messageId ?: old.lastMessageId,
            regularSeen = old.regularSeen + if (context?.tool == "GQLENS_RECON") 0 else 1,
            reconSeen = old.reconSeen + if (context?.tool == "GQLENS_RECON") 1 else 0
        )
        if (context != null) {
            sequence++
            val event = EvidenceEvent(
                sequence, observation.endpoint, observation.kind,
                observation.operation, observation.fingerprint, observation.batchIndex,
                context.tool, context.messageId, now
            )
            evidence[event.id] = event
            while (evidence.size > evidenceLimit) {
                evidence.remove(evidence.keys.first())
                evictedEvidence++
            }
            val links = pending.getOrPut(correlationKey(context)) { mutableListOf() }
            links.add(PendingLink(rowKey, observation.batchIndex, event.id))
            while (pending.size > 512) pending.remove(pending.keys.first())
        }
        return true
    }

    @Synchronized
    fun hasPending(context: ObservationContext): Boolean =
        pending.containsKey(correlationKey(context))

    /** Correlation is by Burp tool + message ID, never by endpoint alone. */
    @Synchronized
    fun recordResponse(
        context: ObservationContext,
        httpStatus: Int,
        shapes: List<ResponseAnalyzer.Shape>,
        diagnostics: List<ResponseDiagnostics.Summary> = emptyList()
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
            val eventId = link.evidenceId
            if (eventId != null) {
                val event = evidence[eventId]
                if (event != null) evidence[eventId] = event.copy(
                    httpStatus = httpStatus, responseShape = shape,
                    diagnostics = diagnostics.getOrNull(link.batchIndex)
                )
            }
            changed = true
        }
        return changed
    }

    @Synchronized
    fun snapshot(): List<ObservationRow> = rows.values.toList()

    /** Latest individual captures first; bounded independent of total historical traffic. */
    @Synchronized
    fun evidenceFor(row: ObservationRow, maxEvents: Int = 250): List<EvidenceEvent> {
        val rowKey = key(row)
        return evidence.values.asSequence().filter {
            key(it.endpoint, it.kind, it.operation, it.fingerprint) == rowKey
        }.toList().takeLast(maxEvents.coerceIn(1, 1000)).asReversed()
    }

    /**
     * Resolve a schema ROOT field to individual captured request provenance.
     * Use only exact root selection paths, never nested name matches or guessed
     * relationship mapping. Do not show internal GQLens introspection traffic.
     */
    @Synchronized
    fun evidenceForField(
        endpoint: String, kind: String, field: String, limit: Int = 150
    ): List<EvidenceEvent> {
        val matchingKeys = rows.values.asSequence().filter { row ->
            row.endpoint == endpoint && row.kind.equals(kind, true) &&
                row.fieldPaths.any { it == field }
        }.map(::key).toSet()
        if (matchingKeys.isEmpty()) return emptyList()
        return evidence.values.asSequence().filter { event ->
            event.tool != "GQLENS_RECON" &&
                key(event.endpoint, event.kind, event.operation, event.fingerprint) in matchingKeys
        }.toList().takeLast(limit.coerceIn(1, 500)).asReversed()
    }

    @Synchronized fun rejectedCount(): Long = rejected
    @Synchronized fun evictedEvidenceCount(): Long = evictedEvidence

    @Synchronized
    fun clear() {
        rows.clear()
        evidence.clear()
        pending.clear()
        sequence = 0
        rejected = 0
        evictedEvidence = 0
    }
}
