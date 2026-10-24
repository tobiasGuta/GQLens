package dev.tobiasguta.gqlens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphQlAnalyzerTest {
    @Test fun `extracts operation and nested fields without aliases`() {
        val doc = """query GetUser(${'$'}id: ID!) { someone: user(id: ${'$'}id) { id profile { email } } }"""
        val result = GraphQlAnalyzer.analyze(doc).single()
        assertEquals("QUERY", result.kind)
        assertEquals("GetUser", result.name)
        assertEquals(listOf("user", "user.id", "user.profile", "user.profile.email"), result.fieldPaths)
    }

    @Test fun `expands fragments and inline fragments`() {
        val doc = """query Q { viewer { ...UserFields ... on User { name } } }
            fragment UserFields on User { id }"""
        assertEquals(listOf("viewer", "viewer.id", "viewer.name"),
            GraphQlAnalyzer.analyze(doc).single().fieldPaths)
    }

    @Test fun `tracks mutations without executing them`() {
        val result = GraphQlAnalyzer.analyze("mutation Change { updateProfile { id } }").single()
        assertEquals("MUTATION", result.kind)
        assertTrue("updateProfile.id" in result.fieldPaths)
    }

    @Test fun `rejects malformed syntax`() {
        assertTrue(GraphQlAnalyzer.analyze("query Nope {").isEmpty())
    }

    @Test fun `terminates fragment cycles`() {
        val doc = """query Q { viewer { ...A } }
            fragment A on User { id ...B }
            fragment B on User { name ...A }"""
        val fields = GraphQlAnalyzer.analyze(doc).single().fieldPaths
        assertTrue("viewer.id" in fields)
        assertTrue("viewer.name" in fields)
    }
}
}id: ID!) { someone: user(id: ${') { id profile { email } } }"""
        val result = GraphQlAnalyzer.analyze(doc).single()
        assertEquals("QUERY", result.kind)
        assertEquals("GetUser", result.name)
        assertEquals(listOf("user", "user.id", "user.profile", "user.profile.email"), result.fieldPaths)
    }

    @Test fun `expands fragments and inline fragments`() {
        val doc = """query Q { viewer { ...UserFields ... on User { name } } }
            fragment UserFields on User { id }"""
        assertEquals(listOf("viewer", "viewer.id", "viewer.name"),
            GraphQlAnalyzer.analyze(doc).single().fieldPaths)
    }

    @Test fun `tracks mutations without executing them`() {
        val result = GraphQlAnalyzer.analyze("mutation Change { updateProfile { id } }").single()
        assertEquals("MUTATION", result.kind)
        assertTrue("updateProfile.id" in result.fieldPaths)
    }

    @Test fun `rejects malformed syntax`() {
        assertTrue(GraphQlAnalyzer.analyze("query Nope {").isEmpty())
    }

    @Test fun `terminates fragment cycles`() {
        val doc = """query Q { viewer { ...A } }
            fragment A on User { id ...B }
            fragment B on User { name ...A }"""
        val fields = GraphQlAnalyzer.analyze(doc).single().fieldPaths
        assertTrue("viewer.id" in fields)
        assertTrue("viewer.name" in fields)
    }
}
}id) { id profile { email } } }"""
        val result = GraphQlAnalyzer.analyze(doc).single()
        assertEquals("QUERY", result.kind)
        assertEquals("GetUser", result.name)
        assertEquals(listOf("user", "user.id", "user.profile", "user.profile.email"), result.fieldPaths)
    }

    @Test fun `expands fragments and inline fragments`() {
        val doc = """query Q { viewer { ...UserFields ... on User { name } } }
            fragment UserFields on User { id }"""
        assertEquals(listOf("viewer", "viewer.id", "viewer.name"),
            GraphQlAnalyzer.analyze(doc).single().fieldPaths)
    }

    @Test fun `tracks mutations without executing them`() {
        val result = GraphQlAnalyzer.analyze("mutation Change { updateProfile { id } }").single()
        assertEquals("MUTATION", result.kind)
        assertTrue("updateProfile.id" in result.fieldPaths)
    }

    @Test fun `rejects malformed syntax`() {
        assertTrue(GraphQlAnalyzer.analyze("query Nope {").isEmpty())
    }

    @Test fun `terminates fragment cycles`() {
        val doc = """query Q { viewer { ...A } }
            fragment A on User { id ...B }
            fragment B on User { name ...A }"""
        val fields = GraphQlAnalyzer.analyze(doc).single().fieldPaths
        assertTrue("viewer.id" in fields)
        assertTrue("viewer.name" in fields)
    }
}
