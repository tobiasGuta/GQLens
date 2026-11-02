package dev.gqlens.core

/**
 * A transient transport envelope. Callers must not retain it or keep a reference
 * to raw request/response bodies. Only parsed metadata enters ObservationStore.
 */
data class HistoricalExchange(
    val proxyId: Int,
    val observedAtMillis: Long,
    val inScope: Boolean,
    val method: String,
    val url: String,
    val contentType: String?,
    val requestBody: String,
    val status: Int?,
    val responseContentType: String?,
    val responseBody: String?
)

data class HistoryImportReport(
    val inspected: Int = 0,
    val importedExchanges: Int = 0,
    val importedOperations: Int = 0,
    val skippedDuplicates: Int = 0,
    val skippedOutOfScope: Int = 0,
    val skippedUnsupported: Int = 0,
    val skippedOversized: Int = 0,
    val rejectedByStore: Int = 0
)

/** No networking. Explicit user-triggered, bounded, in-scope, metadata-only import. */
class HistoryImporter(private val store: ObservationStore) {
    fun import(entries: Iterable<HistoricalExchange>, maxEntries: Int): HistoryImportReport {
        require(maxEntries in 1..1000)
        var inspected = 0
        var exchanges = 0
        var operations = 0
        var duplicates = 0
        var scope = 0
        var unsupported = 0
        var oversized = 0
        var rejected = 0
        for (entry in entries) {
            if (inspected >= maxEntries) break
            inspected++
            if (!entry.inScope) { scope++; continue }
            if (store.hasProxyMessage(entry.proxyId)) { duplicates++; continue }
            if (entry.requestBody.length > 262_144 || entry.url.length > 262_144 ||
                (entry.responseBody?.length ?: 0) > 262_144) {
                oversized++
                continue
            }
            val parsed = GraphqlAnalyzer.extract(entry.method, entry.url,
                entry.contentType, entry.requestBody)
            if (parsed.isEmpty()) { unsupported++; continue }
            val context = ObservationContext("PROXY", entry.proxyId, entry.observedAtMillis)
            var accepted = 0
            for (observation in parsed) {
                if (store.record(observation, context)) accepted++ else rejected++
            }
            if (accepted == 0) continue
            exchanges++
            operations += accepted
            if (entry.status != null && entry.responseBody != null) {
                val shapes = ResponseAnalyzer.classify(
                    entry.responseContentType, entry.responseBody)
                val diagnostics = ResponseDiagnostics.inspect(
                    entry.responseContentType, entry.responseBody)
                store.recordResponse(context, entry.status, shapes, diagnostics)
            }
        }
        return HistoryImportReport(inspected, exchanges, operations, duplicates,
            scope, unsupported, oversized, rejected)
    }
}
