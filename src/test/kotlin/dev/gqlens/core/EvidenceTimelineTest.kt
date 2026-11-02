package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EvidenceTimelineTest {
    private val operation = Observation(
        "https://example.org/graphql", "QUERY", "CurrentUser", "abc123", listOf("viewer.id")
    )

    @Test
    fun retainsIndividualResponseOutcomesInsteadOfOnlyTheLastResult() {
        val store = ObservationStore()
        val first = ObservationContext("REPEATER", 1, 1000)
        val second = ObservationContext("REPEATER", 2, 2000)
        assertTrue(store.record(operation, first))
        assertTrue(store.record(operation, second))
        assertTrue(store.recordResponse(first, 200, listOf(ResponseAnalyzer.Shape.DATA)))
        assertTrue(store.recordResponse(second, 403, listOf(ResponseAnalyzer.Shape.ERRORS)))
        val row = store.snapshot().single()
        assertEquals(2, row.seen)
        assertEquals(2, row.responsesSeen)
        assertEquals(403, row.lastHttpStatus)
        val timeline = store.evidenceFor(row)
        assertEquals(2, timeline.size)
        assertEquals(listOf(2, 1), timeline.map { it.messageId })
        assertEquals(listOf(403, 200), timeline.map { it.httpStatus })
        assertEquals(listOf("ERRORS", "DATA"), timeline.map { it.responseShape })
        assertEquals(listOf(2000L, 1000L), timeline.map { it.observedAtMillis })
    }

    @Test
    fun eventsAreBoundedAndStillOnlyContainStructuralMetadata() {
        val store = ObservationStore(limit = 10, evidenceLimit = 2)
        for (id in 1..5) {
            assertTrue(store.record(operation, ObservationContext("PROXY", id, id.toLong())))
        }
        val row = store.snapshot().single()
        assertEquals(5, row.seen)
        assertEquals(3L, store.evictedEvidenceCount())
        assertEquals(listOf(5, 4), store.evidenceFor(row).map { it.messageId })
        assertFalse(store.evidenceFor(row).toString().contains("private-data"))
        store.clear()
        assertEquals(0, store.snapshot().size)
        assertEquals(0L, store.evictedEvidenceCount())
        assertTrue(store.evidenceFor(row).isEmpty())
    }

    @Test
    fun batchEntriesRemainSeparateEvenWhenOperationIsIdentical() {
        val store = ObservationStore()
        val context = ObservationContext("PROXY", 40, 4000)
        store.record(operation.copy(batchIndex = 0), context)
        store.record(operation.copy(batchIndex = 1), context)
        assertTrue(store.recordResponse(
            context, 200, listOf(ResponseAnalyzer.Shape.DATA, ResponseAnalyzer.Shape.ERRORS)
        ))
        val events = store.evidenceFor(store.snapshot().single())
        assertEquals(2, events.size)
        assertEquals("ERRORS", events.first { it.batchIndex == 1 }.responseShape)
        assertEquals("DATA", events.first { it.batchIndex == 0 }.responseShape)
    }

    @Test
    fun responsesDoNotMatchOtherToolsOrMessageIds() {
        val store = ObservationStore()
        val context = ObservationContext("REPEATER", 99, 9000)
        store.record(operation, context)
        assertFalse(store.recordResponse(
            ObservationContext("PROXY", 99), 200, listOf(ResponseAnalyzer.Shape.DATA)
        ))
        assertFalse(store.recordResponse(
            ObservationContext("REPEATER", 98), 200, listOf(ResponseAnalyzer.Shape.DATA)
        ))
        assertEquals("PENDING", store.evidenceFor(store.snapshot().single()).single().responseShape)
    }
}
