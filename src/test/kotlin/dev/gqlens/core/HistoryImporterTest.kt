package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryImporterTest {
    private val endpoint = "https://example.test/graphql"
    private fun entry(
        id: Int, inScope: Boolean = true, body: String =
            """{"query":"query Current { me { id } }"}""",
        status: Int? = 200, response: String? =
            """{"errors":[{"message":"private value","extensions":{"code":"DENIED"}}]}"""
    ) = HistoricalExchange(id, 123456L, inScope, "POST", endpoint,
        "application/json", body, status, "application/json", response)

    @Test fun importsOnlyInScopeGraphqlAndResponseMetadata() {
        val store = ObservationStore()
        val result = HistoryImporter(store).import(listOf(
            entry(1), entry(2, false), entry(3, body = "{}"), entry(4)
        ), 4)
        assertEquals(4, result.inspected)
        assertEquals(2, result.importedExchanges)
        assertEquals(2, result.importedOperations)
        assertEquals(1, result.skippedOutOfScope)
        assertEquals(1, result.skippedUnsupported)
        assertEquals(1, store.snapshot().size)
        val event = store.evidenceSnapshot().first()
        assertEquals("PROXY", event.tool)
        assertEquals("ERRORS", event.responseShape)
        assertEquals(listOf("DENIED"), event.diagnostics?.codes)
        assertFalse(event.toString().contains("private value"))
    }

    @Test fun repeatingImportDoesNotDoubleCountHistoryOrLiveProxyTraffic() {
        val store = ObservationStore()
        val importer = HistoryImporter(store)
        assertEquals(1, importer.import(listOf(entry(20)), 100).importedExchanges)
        val repeat = importer.import(listOf(entry(20)), 100)
        assertEquals(0, repeat.importedExchanges)
        assertEquals(1, repeat.skippedDuplicates)
        assertEquals(1, store.snapshot().single().seen)
    }

    @Test fun respectsBoundsOversizeAndNoNetworkPaths() {
        val store = ObservationStore()
        val records = listOf(
            entry(5, response = "x".repeat(300000)),
            entry(6), entry(7)
        )
        val result = HistoryImporter(store).import(records, 2)
        assertEquals(2, result.inspected)
        assertEquals(1, result.skippedOversized)
        assertEquals(1, result.importedExchanges)
        assertEquals(listOf(6), store.evidenceSnapshot().map { it.messageId })
        assertThrows(IllegalArgumentException::class.java) {
            importerInvalidLimit(store)
        }
    }

    private fun importerInvalidLimit(store: ObservationStore) {
        HistoryImporter(store).import(emptyList(), 1001)
    }

    @Test fun tracksBatchedOperationsByOriginalIndex() {
        val body = """[
            {"query":"query One { me { id } }"},
            {"query":"query Two { organization { id } }"}
        ]"""
        val response = """[
            {"errors":[{"message":"no","extensions":{"code":"FIRST"}}]},
            {"data":{"organization":{"id":"private"}}}
        ]"""
        val store = ObservationStore()
        val report = HistoryImporter(store).import(
            listOf(entry(42, body = body, response = response)), 1)
        assertEquals(2, report.importedOperations)
        val events = store.evidenceSnapshot().sortedBy { it.batchIndex }
        assertEquals(2, events.size)
        assertEquals(listOf("ERRORS", "DATA"), events.map { it.responseShape })
        assertEquals(listOf("FIRST"), events[0].diagnostics?.codes)
        assertEquals(0, events[1].diagnostics?.errors)
    }
}
