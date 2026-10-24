package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GraphqlAnalyzerTest {
    @Test
    fun parsesNestedOperationsAndFragmentsWithoutStoringValues() {
        val query = """
            query CurrentUser(\$id: ID!) {
              viewer: user(id: \$id) { ...Basic }
            }
            fragment Basic on User { id profile { email } }
        """.trimIndent()
        val body = """{"query":${jsonQuote(query)},"variables":{"id":"PRIVATE-ID"}}"""
        val result = GraphqlAnalyzer.extract("POST", "https://example.org/graphql?token=SECRET", "application/json", body)
        assertEquals(1, result.size)
        assertEquals("https://example.org/graphql", result[0].endpoint)
        assertEquals("QUERY", result[0].kind)
        assertEquals("CurrentUser", result[0].operation)
        assertTrue(result[0].fieldPaths.contains("user.profile.email"))
        assertFalse(result.toString().contains("SECRET"))
        assertFalse(result.toString().contains("PRIVATE-ID"))
    }

    @Test
    fun handlesBatchAndMutations() {
        val body = """[{"query":"query A { ping }"},{"query":"mutation B { updateName { ok } }"}]"""
        val result = GraphqlAnalyzer.extract("POST", "https://example.org/api", "application/json", body)
        assertEquals(listOf("QUERY", "MUTATION"), result.map { it.kind })
    }

    @Test
    fun recognizesGetAndFormData() {
        val query = "query Me { me { id } }"
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val get = GraphqlAnalyzer.extract("GET", "https://example.org/graphql?query=" + encoded, null, "")
        val post = GraphqlAnalyzer.extract("POST", "https://example.org/graphql",
            "application/x-www-form-urlencoded", "query=" + encoded)
        assertEquals(get.first().fieldPaths, post.first().fieldPaths)
    }

    @Test
    fun persistedHashIsNotTreatedAsARecoveredSchema() {
        val hash = "a".repeat(64)
        val body = """{"extensions":{"persistedQuery":{"sha256Hash":"$hash"}}}"""
        val result = GraphqlAnalyzer.extract("POST", "https://example.org/graphql", "application/json", body)
        assertEquals("PERSISTED", result.single().kind)
        assertTrue(result.single().fieldPaths.isEmpty())
    }

    @Test
    fun ignoresUnrelatedAndMalformedTraffic() {
        assertTrue(GraphqlAnalyzer.extract("POST", "https://example.org/api", "application/json", """{"login":"demo"}""").isEmpty())
        assertTrue(GraphqlAnalyzer.extract("POST", "https://example.org/graphql", "application/json", "{broken").isEmpty())
        assertTrue(GraphqlAnalyzer.extract("POST", "https://example.org/graphql", "application/graphql", "not graphql").isEmpty())
        assertTrue(GraphqlAnalyzer.extract("POST", "https://example.org/graphql", "application/graphql", "x".repeat(300_000)).isEmpty())
    }

    @Test
    fun capsStorageAndDeduplicates() {
        val store = ObservationStore(1)
        val item = Observation("https://example.org/graphql", "QUERY", "A", "abc", listOf("ping"))
        assertTrue(store.record(item))
        assertTrue(store.record(item))
        assertEquals(2, store.snapshot().single().seen)
        assertFalse(store.record(item.copy(operation = "B")))
        assertEquals(1, store.snapshot().size)
        store.clear()
        assertTrue(store.snapshot().isEmpty())
    }

    private fun jsonQuote(input: String): String = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input)
}
