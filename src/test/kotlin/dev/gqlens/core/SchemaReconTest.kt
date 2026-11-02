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
            assertTrue(body.contains("query"))
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

    @Test
    fun preservesNestedListNonNullTypesAndRequiredArgumentSemantics() {
        val body = """
            {"data":{"__schema":{"queryType":{"name":"ReadRoot","fields":[
              {"name":"nodes","type":{"kind":"NON_NULL","ofType":{"kind":"LIST",
                "ofType":{"kind":"NON_NULL","ofType":{"kind":"OBJECT","name":"Node"}}}},
                "args":[
                  {"name":"ids","defaultValue":null,"type":{"kind":"NON_NULL",
                    "ofType":{"kind":"LIST","ofType":{"kind":"NON_NULL",
                      "ofType":{"kind":"SCALAR","name":"ID"}}}}},
                  {"name":"limit","defaultValue":"10","type":{"kind":"NON_NULL",
                    "ofType":{"kind":"SCALAR","name":"Int"}}}
                ]}
              ]}}}}
        """.trimIndent()
        val result = SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200, body)
        val field = result.rootTypes.single().details.single()
        assertEquals("[Node!]!", field.type?.display)
        assertEquals("Node", field.type?.namedType)
        assertEquals("OBJECT", field.type?.namedKind)
        assertEquals("[ID!]!", field.args[0].type.display)
        assertTrue(field.args[0].required)
        assertFalse(field.args[1].required)
        assertTrue(field.display.contains("ids: [ID!]!"))
        assertTrue(SchemaRecon.document(SchemaRecon.Mode.ROOT_FIELDS).contains("ofType"))
    }

    @Test
    fun legacyShapeWithoutSignatureDataRemainsMetadataOnly() {
        val result = SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200,
            """{"data":{"__schema":{"queryType":{"name":"Query","fields":[{"name":"me"}]}}}}""")
        assertEquals(listOf("me"), result.rootTypes.single().fields)
        assertNull(result.rootTypes.single().details.single().type)
        assertNull(QueryDraftBuilder.build("QUERY", result.rootTypes.single().details.single()))
    }
    @Test
    fun missingArgumentListOrDefaultMetadataCannotAuthorizeQueryDraft() {
        val result = SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200, """
            {"data":{"__schema":{"queryType":{"name":"Query","fields":[
              {"name":"partial","type":{"kind":"OBJECT","name":"User"}},
              {"name":"argPartial","type":{"kind":"OBJECT","name":"User"},
                "args":[{"name":"id","type":{"kind":"NON_NULL",
                   "ofType":{"kind":"SCALAR","name":"ID"}}}]},
              {"name":"complete","type":{"kind":"OBJECT","name":"User"},"args":[]}
            ]}}}}
        """.trimIndent())
        val fields = result.rootTypes.single().details.associateBy { it.name }
        assertFalse(fields.getValue("partial").complete)
        assertFalse(fields.getValue("argPartial").complete)
        assertTrue(fields.getValue("complete").complete)
        assertNull(QueryDraftBuilder.build("QUERY", fields.getValue("partial")))
        assertNull(QueryDraftBuilder.build("QUERY", fields.getValue("argPartial")))
        assertNotNull(QueryDraftBuilder.build("QUERY", fields.getValue("complete")))
    }

    @Test
    fun argumentLimitFlagsTruncationAndDisablesGeneratedDrafts() {
        val args = (1..40).joinToString(",") {
            """{"name":"a$it","defaultValue":null,
                "type":{"kind":"SCALAR","name":"String"}}"""
        }
        val body = """{"data":{"__schema":{"queryType":{"name":"Query","fields":[
            {"name":"large","type":{"kind":"SCALAR","name":"String"},"args":[$args]}
        ]}}}}"""
        val result = SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200, body)
        assertTrue(result.truncated)
        val field = result.rootTypes.single().details.single()
        assertFalse(field.complete)
        assertNull(QueryDraftBuilder.build("QUERY", field))
    }

}
