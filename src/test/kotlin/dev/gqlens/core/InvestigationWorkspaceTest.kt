package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvestigationWorkspaceTest {
    private val endpoint = "https://research.test/graphql"

    @Test fun caseContextsAreManualBoundedAndNeverInferred() {
        val workspace = InvestigationWorkspace(maxCases = 1, maxContexts = 2, maxEvidencePerCase = 1)
        val a = workspace.addContext("Account A")!!
        val b = workspace.addContext("Account B")!!
        assertNull(workspace.addContext("account a"))
        assertNull(workspace.addContext("Account C"))
        val case = workspace.addCase("Authorization comparison")!!
        assertNull(workspace.addCase("second"))
        assertTrue(workspace.updateCase(case.id, "Compare behavior", "Own test account evidence",
            InvestigationWorkspace.Status.NEEDS_EVIDENCE, setOf(a.id, b.id)))
        assertEquals(2, workspace.cases().single().contextIds.size)
        assertFalse(workspace.updateCase(case.id, "", "notes",
            InvestigationWorkspace.Status.INVESTIGATING, setOf(a.id)))
        assertFalse(workspace.updateCase(case.id, "Title", "ok",
            InvestigationWorkspace.Status.INVESTIGATING, setOf(999L)))
        val event = EvidenceEvent(11, endpoint, "QUERY", "Read", "abc", 0,
            "PROXY", 52, 1000)
        assertTrue(workspace.attachEvidence(case.id, event))
        assertTrue(workspace.labelEvidence(event.id, a.id))
        assertEquals("Account A", workspace.contextForEvidence(event.id)?.label)
        assertFalse(workspace.attachEvidence(case.id,
            event.copy(id = 12)))
        workspace.clear()
        assertTrue(workspace.cases().isEmpty())
        assertNull(workspace.contextForEvidence(event.id))
    }

    @Test fun candidateQueueUsesObservedFactsAndHasNoTargetSpecificKeywords() {
        val workspace = InvestigationWorkspace()
        val observations = listOf(
            ObservationRow(endpoint, "QUERY", "Read", "f1", listOf("customField"),
                1, regularSeen = 1)
        )
        val events = listOf(EvidenceEvent(1, endpoint, "QUERY", "Read", "f1",
            0, "REPEATER", 51, 1000, 200, "ERRORS",
            ResponseDiagnostics.Summary(errors = 1, codes = listOf("CUSTOM_CODE"))))
        val schema = ApiSurfaceStore()
        schema.record(endpoint, SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200,
            """{"data":{"__schema":{"queryType":{"name":"Root",
                "fields":[{"name":"otherField"}]}}}}"""))
        val found = workspace.candidates(observations, events,
            { schema.report(it, observations) })
        assertTrue(found.any { it.kind == InvestigationWorkspace.CandidateKind.RESPONSE_ERRORS })
        assertTrue(found.any { it.kind == InvestigationWorkspace.CandidateKind.SCHEMA_MISMATCH })
        assertTrue(found.all { it.evidenceIds == listOf(1L) })
        assertTrue(found.all { it.missingProof.isNotBlank() })
        assertTrue(found.none { it.why.contains("vulnerability", true) })
        assertTrue(workspace.candidates(observations,
            events.map { it.copy(tool = "GQLENS_RECON") },
            { schema.report(it, observations) }).isEmpty())
    }

    @Test fun schemaMatchedOrUnobservedFieldsDoNotCreateFalseCandidates() {
        val workspace = InvestigationWorkspace()
        val row = ObservationRow(endpoint, "QUERY", "Read", "abc", listOf("me"),
            1, regularSeen = 1)
        val event = EvidenceEvent(1, endpoint, "QUERY", "Read", "abc", 0,
            "PROXY", 3, 1000, 200, "DATA")
        val schema = ApiSurfaceStore()
        schema.record(endpoint, SchemaRecon.parse(SchemaRecon.Mode.ROOT_FIELDS, 200,
            """{"data":{"__schema":{"queryType":{"name":"Query",
                "fields":[{"name":"me"}]}}}}"""))
        assertTrue(workspace.candidates(listOf(row), listOf(event),
            { schema.report(it, listOf(row)) }).isEmpty())
    }
}
