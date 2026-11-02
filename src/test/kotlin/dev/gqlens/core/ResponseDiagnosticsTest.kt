package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResponseDiagnosticsTest {
    @Test fun recordsOpaqueServerCodesAndStructuralMetadataWithoutMessagesOrPaths() {
        val body = """{"errors":[
            {"message":"secret email and token","locations":[{"line":2,"column":5}],
             "path":["viewer","secretName"],"extensions":{"code":"SOME_SERVER_CODE","token":"private"}},
            {"message":"another secret","extensions":{"code":"SOME_SERVER_CODE"}},
            {"message":"unsafe value","extensions":{"code":"secret@domain.test"}}
        ],"data":{"viewer":{"name":"private"}}}"""
        val summary = ResponseDiagnostics.inspect("application/graphql-response+json", body).single()
        assertEquals(3, summary.errors)
        assertTrue(summary.hasData)
        assertTrue(summary.hasLocations)
        assertTrue(summary.hasPaths)
        assertEquals(listOf("SOME_SERVER_CODE"), summary.codes)
        assertFalse(summary.toString().contains("secretName"))
        assertFalse(summary.toString().contains("private"))
        assertFalse(summary.toString().contains("email"))
        assertFalse(summary.toString().contains("token"))
    }

    @Test fun correlatesEachBatchIndexAndAvoidsDuplicatingData() {
        val body = """[{"errors":[{"message":"bad","extensions":{"code":"FIRST"}}]},
            {"data":{"ok":true}},
            {"errors":[{"message":"x","extensions":{"code":"THIRD"}}]}]"""
        val events = ResponseDiagnostics.inspect("application/json", body)
        assertEquals(3, events.size)
        assertEquals(listOf("FIRST"), events[0].codes)
        assertEquals(0, events[1].errors)
        assertTrue(events[1].hasData)
        assertEquals(listOf("THIRD"), events[2].codes)
    }

    @Test fun oversizedMalformedAndUnrecognizedContentFailClosed() {
        assertTrue(ResponseDiagnostics.inspect("application/json", "x".repeat(300000)).single().truncated)
        assertTrue(ResponseDiagnostics.inspect("application/json", "{").isEmpty())
        assertTrue(ResponseDiagnostics.inspect("text/html", "<html>nope</html>").isEmpty())
    }

    @Test fun boundsErrorCountAndDoesNotStoreArbitraryExtensionFields() {
        val errors = (1..80).joinToString(",") {
            """{"message":"private-$it","extensions":{"code":"CODE_$it","unknown":"sensitive"}}"""
        }
        val result = ResponseDiagnostics.inspect("application/json", """{"errors":[$errors]}""").single()
        assertEquals(64, result.errors)
        assertTrue(result.truncated)
        assertTrue(result.codes.size <= 12)
        assertFalse(result.toString().contains("sensitive"))
    }
}
