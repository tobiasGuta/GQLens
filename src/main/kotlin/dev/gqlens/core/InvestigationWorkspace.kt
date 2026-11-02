package dev.gqlens.core

/** Analyst-owned, session-local labels and evidence only; never raw HTTP or credentials. */
class InvestigationWorkspace(
    private val maxCases: Int = 100,
    private val maxContexts: Int = 32,
    private val maxEvidencePerCase: Int = 200
) {
    data class Context(val id: Long, val label: String)
    data class Case(
        val id: Long, val title: String, val notes: String,
        val contextIds: Set<Long>, val evidenceIds: Set<Long>, val status: Status
    )
    enum class Status { INVESTIGATING, NEEDS_EVIDENCE, VERIFIED_BY_RESEARCHER, DISMISSED }
    enum class CandidateKind { RESPONSE_ERRORS, SCHEMA_MISMATCH }
    data class Candidate(
        val key: String, val kind: CandidateKind, val endpoint: String,
        val operation: String, val field: String?, val evidenceIds: List<Long>,
        val why: String, val missingProof: String
    )
    private val cases = linkedMapOf<Long, Case>()
    private val contexts = linkedMapOf<Long, Context>()
    private var nextId = 1L

    @Synchronized fun contexts(): List<Context> = contexts.values.toList()
    @Synchronized fun cases(): List<Case> = cases.values.toList()

    @Synchronized fun addContext(label: String): Context? {
        val name = label.trim().take(80)
        if (name.isBlank() || contexts.size >= maxContexts) return null
        if (contexts.values.any { it.label.equals(name, ignoreCase = true) }) return null
        return Context(nextId++, name).also { contexts[it.id] = it }
    }

    @Synchronized fun addCase(title: String): Case? {
        val safe = title.trim().take(120)
        if (safe.isBlank() || cases.size >= maxCases) return null
        return Case(nextId++, safe, "", emptySet(), emptySet(),
            Status.INVESTIGATING).also { cases[it.id] = it }
    }

    @Synchronized fun updateCase(
        id: Long, title: String, notes: String, status: Status, contextIds: Set<Long>
    ): Boolean {
        val old = cases[id] ?: return false
        val safeTitle = title.trim().take(120)
        if (safeTitle.isBlank() || notes.length > 3000 ||
            !contextIds.all { it in contexts }) return false
        cases[id] = old.copy(title = safeTitle, notes = notes, status = status,
            contextIds = contextIds)
        return true
    }

    @Synchronized fun attachEvidence(id: Long, event: EvidenceEvent): Boolean {
        val old = cases[id] ?: return false
        if (old.evidenceIds.size >= maxEvidencePerCase) return false
        cases[id] = old.copy(evidenceIds = old.evidenceIds + event.id)
        return true
    }

    @Synchronized fun clear() {
        cases.clear()
        contexts.clear()
        nextId = 1
    }

    /**
     * Data-driven hypotheses about observed facts, NOT vulnerability findings.
     * Only exact parsed root selections and captured response metadata qualify.
     * No application-specific field names or inferred account identities.
     */
    fun candidates(
        rows: List<ObservationRow>, events: List<EvidenceEvent>,
        surfaces: (String) -> SurfaceReport, max: Int = 150
    ): List<Candidate> {
        val output = linkedMapOf<String, Candidate>()
        val byOperation = rows.associateBy {
            listOf(it.endpoint, it.kind, it.operation, it.fingerprint)
        }
        val surfaceCache = mutableMapOf<String, SurfaceReport>()
        for (event in events.asReversed()) {
            if (event.tool == "GQLENS_RECON") continue
            val row = byOperation[listOf(event.endpoint, event.kind,
                event.operation, event.fingerprint)] ?: continue
            val errors = event.diagnostics?.errors ?: 0
            if (errors > 0 || event.responseShape in setOf("ERRORS", "PARTIAL_DATA")) {
                val key = "response|" + event.endpoint + "|" + event.kind + "|" + event.fingerprint
                if (key !in output) {
                    output[key] = Candidate(key, CandidateKind.RESPONSE_ERRORS,
                        event.endpoint, event.operation, null, listOf(event.id),
                        "Captured response includes GraphQL errors (" + event.responseShape + ").",
                        "Inspect the response in Burp and establish the cause, expected " +
                            "behavior and actual impact before any security claim.")
                }
            }
            val surface = surfaceCache.getOrPut(event.endpoint) { surfaces(event.endpoint) }
            for (group in surface.roots.filter { it.kind.equals(event.kind, true) && it.hasDeclarations }) {
                for (field in group.fields.filter {
                    it.status == SurfaceStatus.REQUESTED_ONLY && it.name in row.fieldPaths
                }) {
                    val key = "schema|" + event.endpoint + "|" + event.kind + "|" +
                        event.fingerprint + "|" + field.name
                    if (key !in output) {
                        output[key] = Candidate(key, CandidateKind.SCHEMA_MISMATCH,
                            event.endpoint, event.operation, field.name, listOf(event.id),
                            "Requested root selection differs from available schema declarations.",
                            "Verify schema completeness, freshness and the original response. " +
                                "A mismatch is not an authorization issue by itself.")
                    }
                }
            }
            if (output.size >= max.coerceIn(1, 500)) break
        }
        return output.values.toList()
    }
}
