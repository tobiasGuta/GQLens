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
        assertEquals("query GQLensPreview(\$id: ID!) { node(id: \$id) { __typename } }", draft.document)
        assertEquals("id", draft.requiredVariables.single().name)
        Parser().parseDocument(draft.document)
        assertFalse(draft.document.contains("mutation"))
    }

    @Test
    fun scalarAndEnumFieldsHaveNoIllegalSubselection() {
        for (kind in listOf("SCALAR", "ENUM")) {
            val draft = QueryDraftBuilder.build("QUERY",
                SchemaRecon.FieldSignature("status", type("String", kind)))!!
            assertEquals("query GQLensPreview { status }", draft.document)
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
        assertEquals("query GQLensPreview { search { __typename } }", draft.document)
    }

    @Test
    fun rejectsMutationAndMissingOrMalformedSignatures() {
        assertNull(QueryDraftBuilder.build("MUTATION",
            SchemaRecon.FieldSignature("delete", type("Boolean", "SCALAR"))))
        assertNull(QueryDraftBuilder.build("QUERY", SchemaRecon.FieldSignature("unknown", null)))
        assertNull(QueryDraftBuilder.build("QUERY",
            SchemaRecon.FieldSignature("bad name", type("String", "SCALAR"))))
    }
}
