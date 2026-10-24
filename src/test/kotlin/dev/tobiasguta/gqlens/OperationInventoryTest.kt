package dev.tobiasguta.gqlens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OperationInventoryTest {
    @Test fun `deduplicates identical operations and counts sightings`() {
        val store = OperationInventory()
        val op = ObservedOperation("QUERY", "Q", listOf("viewer", "viewer.id"), "digest:Q:QUERY")
        store.add("https://example.org/graphql", op)
        store.add("https://example.org/graphql", op)
        assertEquals(2, store.snapshot().second.single().sightings)
        store.clear()
        assertEquals(0, store.snapshot().second.size)
    }

    @Test fun `enforces inventory cap`() {
        val store = OperationInventory(capacity = 1)
        store.add("https://example.org/a", ObservedOperation("QUERY", "A", listOf("a"), "a"))
        store.add("https://example.org/b", ObservedOperation("QUERY", "B", listOf("b"), "b"))
        assertEquals(1, store.snapshot().second.size)
    }

    @Test fun `strips query tokens and replaces long identifiers`() {
        assertEquals("https://example.org/graphql/:id",
            OperationInventory.safeEndpoint("https://example.org/graphql/123456?token=secret"))
        assertNull(OperationInventory.safeEndpoint("file:///tmp/secret"))
    }
}
