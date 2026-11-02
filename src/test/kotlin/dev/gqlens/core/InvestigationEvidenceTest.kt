package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvestigationEvidenceTest {
    private fun observation(endpoint: String, field: String, fingerprint: String = field) =
        Observation(endpoint, "QUERY", "Find", fingerprint, listOf(field, "$field.id"))

    @Test fun fieldLinksOnlyExactRootSelectionsAndEndpointWithMatchingResponse() {
        val store = ObservationStore()
        val endpoint = "https://api.example.test/graphql"
        val a = ObservationContext("REPEATER", 21, 1000)
        val b = ObservationContext("PROXY", 22, 2000)
        val other = ObservationContext("REPEATER", 23, 3000)
        store.record(observation(endpoint, "user"), a)
        store.record(observation(endpoint, "me"), b)
        store.record(observation("https://other.test/graphql", "user"), other)
        val diagnostics = ResponseDiagnostics.inspect("application/json",
            """{"errors":[{"message":"private","extensions":{"code":"FIELD_REJECTED"}}]}""")
        assertTrue(store.recordResponse(a, 200, listOf(ResponseAnalyzer.Shape.ERRORS), diagnostics))
        val matches = store.evidenceForField(endpoint, "QUERY", "user")
        assertEquals(1, matches.size)
        assertEquals(21, matches.single().messageId)
        assertEquals(listOf("FIELD_REJECTED"), matches.single().diagnostics?.codes)
        assertTrue(store.evidenceForField(endpoint, "QUERY", "id").isEmpty())
        assertTrue(store.evidenceForField(endpoint, "MUTATION", "user").isEmpty())
    }

    @Test fun internalReconCannotMasqueradeAsAppFieldEvidence() {
        val store = ObservationStore()
        val endpoint = "https://api.example.test/graphql"
        store.record(observation(endpoint, "user", "a"),
            ObservationContext("GQLENS_RECON", 4))
        store.record(observation(endpoint, "user", "b"),
            ObservationContext("REPEATER", 5))
        assertEquals(listOf(5), store.evidenceForField(endpoint, "QUERY", "user")
            .map { it.messageId })
    }

    @Test fun matchingBatchEventsKeepIndependentDiagnostics() {
        val store = ObservationStore()
        val endpoint = "https://api.example.test/graphql"
        val context = ObservationContext("REPEATER", 10)
        val op = observation(endpoint, "me")
        store.record(op.copy(batchIndex = 0), context)
        store.record(op.copy(batchIndex = 1), context)
        val diag = ResponseDiagnostics.inspect("application/json",
            """[{"errors":[{"message":"x","extensions":{"code":"FIRST"}}]},
                {"errors":[{"message":"y","extensions":{"code":"SECOND"}}]}]""")
        store.recordResponse(context, 200,
            listOf(ResponseAnalyzer.Shape.ERRORS, ResponseAnalyzer.Shape.ERRORS), diag)
        val events = store.evidenceForField(endpoint, "QUERY", "me")
        assertEquals(2, events.size)
        assertEquals("FIRST", events.first { it.batchIndex == 0 }.diagnostics?.codes?.single())
        assertEquals("SECOND", events.first { it.batchIndex == 1 }.diagnostics?.codes?.single())
    }
}
