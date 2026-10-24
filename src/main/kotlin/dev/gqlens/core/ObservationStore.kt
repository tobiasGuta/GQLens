package dev.gqlens.core

data class ObservationRow(
    val endpoint: String,
    val kind: String,
    val operation: String,
    val fingerprint: String,
    val fieldPaths: List<String>,
    val seen: Int
)

/** Bounded, in-memory metadata only. Duplicate traffic increments a counter. */
class ObservationStore(private val limit: Int = 2000) {
    private val rows = LinkedHashMap<String, ObservationRow>()

    @Synchronized
    fun record(observation: Observation): Boolean {
        val key = listOf(observation.endpoint, observation.kind, observation.operation, observation.fingerprint)
            .joinToString("\u0000")
        val old = rows[key]
        if (old == null && rows.size >= limit) return false
        rows[key] = if (old == null) {
            ObservationRow(
                observation.endpoint, observation.kind, observation.operation,
                observation.fingerprint, observation.fieldPaths, 1
            )
        } else old.copy(
            fieldPaths = (old.fieldPaths + observation.fieldPaths).distinct().take(500),
            seen = if (old.seen == Int.MAX_VALUE) old.seen else old.seen + 1
        )
        return true
    }

    @Synchronized
    fun snapshot(): List<ObservationRow> = rows.values.toList()

    @Synchronized
    fun clear() = rows.clear()
}
