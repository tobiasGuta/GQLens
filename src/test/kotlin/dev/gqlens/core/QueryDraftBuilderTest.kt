package dev.gqlens.core

import graphql.parser.Parser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QueryDraftBuilderTest {
    private fun type(name: String, kind: String) =
        SchemaRecon.TypeSignature(name, name.removeSuffix("!"), kind)

    @Test
    fun producesSyntacticallyValidReadOnlyObjectQueryWithTypedVariables() {
        val field = SchemaRecon.FieldSignature("node", type("Node", "INTERFACE"),
            listOf(SchemaRecon.ArgumentSignature("id",
                SchemaRecon.TypeSignature("ID!", "ID", "SCALAR"), false)))
        val draft = QueryDraftBuilder.build("QUERY", field)!!
        assertEquals("query GQLensPreview(\$id: ID!) {\\n  node(id: \$id) {\\n    __typename\\n  }\\n}".replace("\\n", "\n"), draft.document)
        assertEquals("id", draft.requiredVariables.single().name)
        Parser().parseDocument(draft.document)
        assertFalse(draft.document.contains("mutation"))
    }

    @Test
    fun scalarAndEnumFieldsHaveNoIllegalSubselection() {
        for (kind in listOf("SCALAR", "ENUM")) {
            val draft = QueryDraftBuilder.build("QUERY",
                SchemaRecon.FieldSignature("status", type("String", kind)))!!
            assertEquals("query GQLensPreview {\\n  status\\n}".replace("\\n", "\n"), draft.document)
            Parser().parseDocument(draft.document)
        }
    }

    @Test
    fun excludesOptionalAndDefaultedArgumentsWithoutGuessingInputs() {
        val field = SchemaRecon.FieldSignature("search", type("Result", "OBJECT"), listOf(
            SchemaRecon.ArgumentSignature("limit",
                SchemaRecon.TypeSignature("Int", "Int", "SCALAR"), false),
            SchemaRecon.ArgumentSignature("sort",
                SchemaRecon.TypeSignature("Sort!", "Sort", "ENUM"), true)
        ))
        val draft = QueryDraftBuilder.build("QUERY", field)!!
        assertTrue(draft.requiredVariables.isEmpty())
        assertEquals("query GQLensPreview {\\n  search {\\n    __typename\\n  }\\n}".replace("\\n", "\n"), draft.document)
    }

    @Test
    fun rejectsMutationAndMissingOrMalformedSignatures() {
        assertNull(QueryDraftBuilder.build("MUTATION",
            SchemaRecon.FieldSignature("delete", type("Boolean", "SCALAR"))))
        assertNull(QueryDraftBuilder.build("QUERY", SchemaRecon.FieldSignature("unknown", null)))
        assertNull(QueryDraftBuilder.build("QUERY",
            SchemaRecon.FieldSignature("bad name", type("String", "SCALAR"))))
    }
    @Test
    fun rejectsIncompleteArgumentMetadataEvenWhenReturnTypeExists() {
        val incomplete = SchemaRecon.FieldSignature(
            "organization", type("Organization", "OBJECT"),
            emptyList(), complete = false
        )
        assertNull(QueryDraftBuilder.build("QUERY", incomplete))
        assertNull(QueryDraftBuilder.build("QUERY", incomplete.copy(
            args = listOf(SchemaRecon.ArgumentSignature("slug",
                SchemaRecon.TypeSignature("String!", "String", "SCALAR"), false))
        )))
    }

    @Test
    fun jsonRequestBodyLeavesVariableValuesEmptyAndEscapesGraphqlDocument() {
        val draft = QueryDraftBuilder.build("QUERY",
            SchemaRecon.FieldSignature("node", type("Node", "OBJECT"),
                listOf(SchemaRecon.ArgumentSignature("id",
                    SchemaRecon.TypeSignature("ID!", "ID", "SCALAR"), false))))!!
        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(draft.jsonBody())
        assertEquals(draft.document, parsed.path("query").asText())
        assertTrue(parsed.path("variables").isObject)
        assertEquals(0, parsed.path("variables").size())
        assertFalse(draft.jsonBody().contains("token"))
    }

}
