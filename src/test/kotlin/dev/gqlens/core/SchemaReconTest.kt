package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SchemaReconTest {
    @Test
    fun usesStandardIntrospectionWithoutGuessedOrMutationOperations() {
        for (mode in SchemaRecon.Mode.entries) {
            val doc = SchemaRecon.document(mode)
            assertTrue(doc.contains("__schema"))
            assertFalse(doc.contains("mutation "))
            assertFalse(doc.contains("subscription "))
            assertFalse(doc.contains("password"))
            val body = SchemaRecon.requestBody(mode)
            assertTrue(body.contains("\\"query\\""))
        }
    }

    @Test
    fun parsesRootsFromSuccessfulIntrospection() {
        val body = """
            {"data":{"__schema":{
              "queryType":{"name":"RootQuery","fields":[{"name":"viewer"},{"name":"search"}]},
              "mutationType":{"name":"RootMut","fields":[{"name":"editAccount"}]},
              "subscriptionType":null
            }}}
        """.trimIndent()
        val result = SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200, body)
        assertEquals("SCHEMA_OBSERVED", result.outcome)
        assertEquals(listOf("RootQuery", "RootMut"), result.rootTypes.map { it.name })
        assertEquals(listOf("viewer", "search"), result.rootTypes.first().fields)
        assertEquals("editAccount", result.rootTypes.last().fields.single())
        assertFalse(result.truncated)
    }

    @Test
    fun parsesNamedTypesAndInputFieldsFromServerSchema() {
        val body = """
            {"data":{"__schema":{
              "queryType":{"name":"APIQuery"},
              "mutationType":null,
              "subscriptionType":null,
              "types":[
                {"name":"APIQuery","kind":"OBJECT","fields":[{"name":"account"}]},
                {"name":"AccountInput","kind":"INPUT_OBJECT","inputFields":[{"name":"nickname"}]},
                {"name":"Role","kind":"ENUM","enumValues":[{"name":"USER"},{"name":"ADMIN"}]}
              ]
            }}}
        """.trimIndent()
        val result = SchemaRecon.parse(SchemaRecon.Mode.TYPE_FIELDS, 200, body)
        assertEquals("SCHEMA_OBSERVED", result.outcome)
        assertEquals(listOf("account", "nickname", "USER", "ADMIN"),
            result.types.flatMap { it.fields })
        assertEquals(listOf("OBJECT", "INPUT_OBJECT", "ENUM"), result.types.map { it.kind })
    }

    @Test
    fun failsClosedOnErrorsAndNonJsonWithoutRetainingServerMessages() {
        val errorText = "private-api-error-123"
        val errorBody = """{"errors":[{"message":"$errorText"}]}"""
        val blocked = SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 403, errorBody)
        assertEquals("INTROSPECTION_UNAVAILABLE_OR_REJECTED", blocked.outcome)
        assertFalse(blocked.toString().contains(errorText))
        assertEquals("NON_JSON",
            SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 502, "not JSON").outcome)
        assertEquals("RESPONSE_TOO_LARGE", SchemaRecon.parse(
            SchemaRecon.Mode.TYPE_FIELDS, 200, "x".repeat(600_000)
        ).outcome)
    }

    @Test
    fun capsFieldsForLargeButParseableSchema() {
        val fields = (1..200).joinToString(",") { """{"name":"field$it"}""" }
        val body = """{"data":{"__schema":{"queryType":{"name":"Query","fields":[$fields]}}}}"""
        val result = SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200, body)
        assertEquals(128, result.rootTypes.single().fields.size)
        assertTrue(result.truncated)
    }
}
