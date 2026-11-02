package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EvidenceCorrelationTest {
    private val endpoint = "https://example.org/graphql"

    @Test
    fun onlySelectedOperationIsObserved() {
        val document = "query Profile { viewer { id } } query Billing { invoices { total } }"
        val url = "$endpoint?query=" +
            URLEncoder.encode(document, StandardCharsets.UTF_8) + "&operationName=Profile"
        val results = GraphqlAnalyzer.extract("GET", url, null, "")
        assertEquals(1, results.size)
        assertEquals("Profile", results.single().operation)
        assertEquals(listOf("viewer", "viewer.id"), results.single().fieldPaths)
        assertFalse(results.single().fieldPaths.contains("invoices"))
        assertTrue(GraphqlAnalyzer.extract(
            "POST", endpoint, "application/json",
            """{"query":"query Profile { viewer { id } } query Billing { invoices { total } }"}"""
        ).isEmpty())
        assertTrue(GraphqlAnalyzer.extract(
            "POST", endpoint, "application/json",
            """{"query":"query Profile { viewer { id } } query Billing { invoices { total } }","operationName":"Missing"}"""
        ).isEmpty())
    }

    @Test
    fun batchCorrelatesByIndexRatherThanConflatingResponses() {
        val json = """[
            {"query":"query Alpha { a }","operationName":"Alpha"},
            {"query":"mutation Beta { b }","operationName":"Beta"}
        ]"""
        val observations = GraphqlAnalyzer.extract("POST", endpoint, "application/json", json)
        assertEquals(listOf(0, 1), observations.map { it.batchIndex })
        val store = ObservationStore()
        val context = ObservationContext("PROXY", 42, 1234)
        observations.forEach { assertTrue(store.record(it, context)) }
        val shapes = ResponseAnalyzer.classify(
            "application/json",
            """[{"data":{"a":1}},{"errors":[{"message":"SECRET-MESSAGE"}]}]"""
        )
        assertTrue(store.recordResponse(context, 200, shapes))
        val rows = store.snapshot().associateBy { it.operation }
        assertEquals("DATA", rows.getValue("Alpha").lastResponseShape)
        assertEquals("ERRORS", rows.getValue("Beta").lastResponseShape)
        assertEquals(200, rows.getValue("Beta").lastHttpStatus)
        assertEquals(1, rows.getValue("Alpha").responsesSeen)
        assertFalse(store.toString().contains("SECRET-MESSAGE"))
        assertFalse(rows.toString().contains("SECRET-MESSAGE"))
        assertFalse(store.recordResponse(context, 200, shapes))
    }

    @Test
    fun preventsCrossToolAndMessageMisattribution() {
        val store = ObservationStore()
        val observation = Observation(endpoint, "QUERY", "Me", "abc", listOf("me"))
        val context = ObservationContext("PROXY", 10, 1000)
        store.record(observation, context)
        assertFalse(store.recordResponse(
            ObservationContext("REPEATER", 10), 403, listOf(ResponseAnalyzer.Shape.ERRORS)
        ))
        assertFalse(store.recordResponse(
            ObservationContext("PROXY", 11), 403, listOf(ResponseAnalyzer.Shape.ERRORS)
        ))
        assertNull(store.snapshot().single().lastHttpStatus)
        assertTrue(store.recordResponse(context, 200, listOf(ResponseAnalyzer.Shape.DATA)))
        assertEquals("DATA", store.snapshot().single().lastResponseShape)
    }

    @Test
    fun parsesResponseShapeWithoutRetainingErrorValues() {
        assertEquals(ResponseAnalyzer.Shape.PARTIAL_DATA,
            ResponseAnalyzer.classify("application/json",
                """{"data":{"viewer":null},"errors":[{"message":"PRIVATE"}]}""").single())
        assertEquals(ResponseAnalyzer.Shape.NULL_DATA,
            ResponseAnalyzer.classify("application/json", """{"data":null}""").single())
        assertEquals(ResponseAnalyzer.Shape.UNRECOGNIZED_JSON,
            ResponseAnalyzer.classify("application/json", """{"login":"ok"}""").single())
        assertEquals(ResponseAnalyzer.Shape.NON_JSON,
            ResponseAnalyzer.classify("text/html", "<html>no</html>").single())
        assertEquals(ResponseAnalyzer.Shape.OVERSIZED,
            ResponseAnalyzer.classify("application/json", "x".repeat(300_000)).single())
    }

    @Test
    fun capacityRejectionAndClearResetCorrelation() {
        val store = ObservationStore(1)
        val context = ObservationContext("PROXY", 20, 100)
        assertTrue(store.record(Observation(endpoint, "QUERY", "A", "a", listOf("a")), context))
        assertFalse(store.record(Observation(endpoint, "QUERY", "B", "b", listOf("b")), context))
        assertEquals(1L, store.rejectedCount())
        store.clear()
        assertEquals(0L, store.rejectedCount())
        assertFalse(store.recordResponse(context, 200, listOf(ResponseAnalyzer.Shape.DATA)))
        assertTrue(store.snapshot().isEmpty())
    }
}
