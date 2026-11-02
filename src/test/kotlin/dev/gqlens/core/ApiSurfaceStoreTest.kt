package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiSurfaceStoreTest {
    private val endpoint = "https://api.example.test/graphql"
    private fun parsed(mode: SchemaRecon.Mode, body: String): SchemaRecon.Result =
        SchemaRecon.parse(mode, 200, body)

    private fun roots(fields: List<String>, rootType: String = "RootQuery"): SchemaRecon.Result {
        val jsonFields = fields.joinToString(",") { """{"name":"$it"}""" }
        return parsed(SchemaRecon.Mode.ROOT_FIELDS,
            """{"data":{"__schema":{"queryType":{"name":"$rootType","fields":[$jsonFields]},"mutationType":null,"subscriptionType":null}}}""")
    }

    @Test fun separatesDeclaredRequestedAndImplicitMetaFields() {
        val store = ApiSurfaceStore()
        assertTrue(store.record(endpoint, roots(listOf("me", "organization")), 100))
        val rows = listOf(
            ObservationRow(endpoint, "QUERY", "Current", "aaa", listOf("me", "me.id", "__typename"),
                2, regularSeen = 2),
            ObservationRow(endpoint, "QUERY", "Other", "bbb", listOf("missing", "missing.id"),
                1, regularSeen = 1)
        )
        val report = store.report(endpoint, rows)
        assertEquals(2, report.declaredFields)
        assertEquals(3, report.requestedFields)
        assertEquals(1, report.matchedFields)
        val fields = report.roots.single().fields.associateBy { it.name }
        assertEquals(SurfaceStatus.DECLARED_AND_REQUESTED, fields.getValue("me").status)
        assertEquals(2, fields.getValue("me").requests)
        assertEquals(SurfaceStatus.DECLARED_ONLY, fields.getValue("organization").status)
        assertEquals(SurfaceStatus.REQUESTED_ONLY, fields.getValue("missing").status)
        assertEquals(SurfaceStatus.META_FIELD_REQUESTED, fields.getValue("__typename").status)
        assertTrue(report.roots.single().hasDeclarations)
    }

    @Test fun excludesExtensionReconAndOtherEndpointsFromObservedMapping() {
        val store = ApiSurfaceStore()
        store.record(endpoint, roots(listOf("node")), 100)
        val rows = listOf(
            ObservationRow(endpoint, "QUERY", "GQLensSchemaRoots", "h1",
                listOf("__schema", "__schema.queryType"), 1, reconSeen = 1),
            ObservationRow("https://other.test/graphql", "QUERY", "Q", "h2",
                listOf("node"), 1, regularSeen = 1)
        )
        val report = store.report(endpoint, rows)
        assertEquals(0, report.requestedFields)
        assertEquals(0, report.operationsSeen)
        assertEquals(SurfaceStatus.DECLARED_ONLY, report.roots.single().fields.single().status)
    }

    @Test fun roleNamesComeFromSchemaNotConventionalQueryOrMutationNames() {
        val store = ApiSurfaceStore()
        val schema = parsed(SchemaRecon.Mode.TYPE_FIELDS, """
            {"data":{"__schema":{
              "queryType":{"name":"ReadAPI"},
              "mutationType":{"name":"WriteAPI"},
              "subscriptionType":null,
              "types":[
                {"name":"ReadAPI","kind":"OBJECT","fields":[{"name":"get"}]},
                {"name":"WriteAPI","kind":"OBJECT","fields":[{"name":"change"}]}
              ]
            }}}
        """.trimIndent())
        store.record(endpoint, schema)
        val observed = listOf(ObservationRow(endpoint, "MUTATION", "Change", "h",
            listOf("change"), 1, regularSeen = 1))
        val report = store.report(endpoint, observed)
        assertEquals("WriteAPI", report.roots.first { it.kind == "MUTATION" }.typeName)
        assertEquals(SurfaceStatus.DECLARED_AND_REQUESTED,
            report.roots.first { it.kind == "MUTATION" }.fields.single().status)
    }

    @Test fun comparesSchemaSnapshotsAndDoesNotTrustTruncatedRemovals() {
        val store = ApiSurfaceStore()
        store.record(endpoint, roots(listOf("a", "b")), 1000)
        store.record(endpoint, roots(listOf("a", "c")), 2000)
        val diff = store.report(endpoint, emptyList()).changes!!
        assertEquals(listOf("RootQuery.c"), diff.added)
        assertEquals(listOf("RootQuery.b"), diff.removed)
        assertTrue(diff.comparable)
        assertEquals(1000L, diff.previousAtMillis)
        val bigFields = (1..200).map { "f$it" }
        store.record(endpoint, roots(bigFields), 3000)
        val unsafe = store.report(endpoint, emptyList()).changes!!
        assertFalse(unsafe.comparable)
        assertTrue(unsafe.removed.isEmpty())
    }

    @Test fun boundsEndpointsAndIgnoresRejectedSchemaResults() {
        val store = ApiSurfaceStore(maxEndpoints = 2)
        assertFalse(store.record(endpoint,
            SchemaRecon.Result(SchemaRecon.Mode.ROOT_FIELDS, 403, "INTROSPECTION_UNAVAILABLE_OR_REJECTED")))
        store.record(endpoint, roots(listOf("a")))
        store.record("https://second.test/graphql", roots(listOf("b")))
        store.record("https://third.test/graphql", roots(listOf("c")))
        assertEquals(2, store.endpoints().size)
        assertFalse(endpoint in store.endpoints())
        store.clear()
        assertTrue(store.endpoints().isEmpty())
    }
}
