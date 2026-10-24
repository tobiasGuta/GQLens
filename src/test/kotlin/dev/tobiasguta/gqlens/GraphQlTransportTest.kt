package dev.tobiasguta.gqlens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphQlTransportTest {
    @Test fun `parses POST JSON and does not use variables`() {
        val input = """{"query":"query Viewer { viewer { id } }","variables":{"token":"DO_NOT_STORE"}}"""
        assertEquals(listOf("query Viewer { viewer { id } }"),
            GraphQlTransport.extract("POST", "/api", "application/json", input))
    }

    @Test fun `parses GraphQL GET query`() {
        assertEquals(listOf("{viewer{id}}"),
            GraphQlTransport.extract("GET", "/graphql?query=%7Bviewer%7Bid%7D%7D&variables=%7B%7D", "", ""))
    }

    @Test fun `parses raw application graphql`() {
        assertEquals(listOf("{ ping }"),
            GraphQlTransport.extract("POST", "/graphql", "application/graphql; charset=utf-8", "{ ping }"))
    }

    @Test fun `parses bounded JSON batch`() {
        assertEquals(listOf("{a}", "{b}"),
            GraphQlTransport.extract("POST", "/graphql", "application/json",
                """[{"query":"{a}"},{"query":"{b}"}]"""))
    }

    @Test fun `ignores malformed and persisted-query-only requests`() {
        assertTrue(GraphQlTransport.extract("POST", "/graphql", "application/json", "{").isEmpty())
        assertTrue(GraphQlTransport.extract("POST", "/graphql", "application/json",
            """{"extensions":{"persistedQuery":{"sha256Hash":"a"}}}""").isEmpty())
    }

    @Test fun `rejects oversized requests`() {
        assertTrue(GraphQlTransport.extract("POST", "/graphql", "application/graphql",
            "x".repeat(256 * 1024 + 1)).isEmpty())
    }
}
