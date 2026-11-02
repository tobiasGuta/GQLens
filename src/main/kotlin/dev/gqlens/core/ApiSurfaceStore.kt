package dev.gqlens.core

import java.security.MessageDigest
import java.time.Instant

/** Schema declarations are not proof of execution, access or authorization. */
enum class SurfaceStatus {
    DECLARED_AND_REQUESTED, DECLARED_ONLY, REQUESTED_ONLY, META_FIELD_REQUESTED
}

data class SurfaceField(
    val name: String,
    val status: SurfaceStatus,
    val requests: Int,
    val signature: SchemaRecon.FieldSignature? = null
)
data class SurfaceRoot(
    val kind: String,
    val typeName: String?,
    val fields: List<SurfaceField>,
    val hasDeclarations: Boolean
)
data class SurfaceType(
    val name: String, val kind: String, val fields: List<String>,
    val signatures: List<SchemaRecon.FieldSignature> = emptyList()
)
data class SchemaDelta(
    val mode: SchemaRecon.Mode,
    val previousAtMillis: Long,
    val currentAtMillis: Long,
    val added: List<String>,
    val removed: List<String>,
    val comparable: Boolean
)
data class SurfaceReport(
    val endpoint: String,
    val roots: List<SurfaceRoot>,
    val types: List<SurfaceType>,
    val schemaSeenAtMillis: Long?,
    val operationsSeen: Int,
    val declaredFields: Int,
    val requestedFields: Int,
    val matchedFields: Int,
    val truncated: Boolean,
    val changes: SchemaDelta?
)

/**
 * Bounded per-endpoint schema snapshots. Stores structural names only.
 * Every successful introspection replaces its mode's latest snapshot; one previous
 * snapshot is retained for change comparison. No raw HTTP content is persisted.
 */
class ApiSurfaceStore(private val maxEndpoints: Int = 64) {
    private data class Version(val result: SchemaRecon.Result, val whenMillis: Long)
    private data class History(
        var roots: Version? = null,
        var previousRoots: Version? = null,
        var types: Version? = null,
        var previousTypes: Version? = null
    )
    private val endpoints = LinkedHashMap<String, History>()

    @Synchronized
    fun record(endpoint: String, result: SchemaRecon.Result, now: Long = System.currentTimeMillis()): Boolean {
        if (result.outcome != "SCHEMA_OBSERVED") return false
        val history = endpoints.remove(endpoint) ?: History()
        if (result.mode == SchemaRecon.Mode.ROOT_FIELDS) {
            history.previousRoots = history.roots
            history.roots = Version(result, now)
        } else {
            history.previousTypes = history.types
            history.types = Version(result, now)
        }
        endpoints[endpoint] = history
        while (endpoints.size > maxEndpoints) endpoints.remove(endpoints.keys.first())
        return true
    }

    @Synchronized fun endpoints(): List<String> = endpoints.keys.toList()

    @Synchronized fun clear() = endpoints.clear()

    /**
     * Only the first selection segment of a captured request is mapped to a root.
     * Nested selections cannot be matched to a declared type without an actual
     * schema traversal and variable/type-condition analysis.
     */
    @Synchronized
    fun report(endpoint: String, observed: List<ObservationRow>): SurfaceReport {
        val history = endpoints[endpoint]
        val rootVersion = history?.roots
        val typesVersion = history?.types
        val roots = rootVersion?.result
        val types = typesVersion?.result
        val roles = linkedMapOf<String, String>()
        roots?.rootTypeNames?.let { roles.putAll(it) }
        types?.rootTypeNames?.let { roles.putAll(it) }

        val ordinary = observed.filter { it.endpoint == endpoint && it.regularSeen > 0 }
        val requestedByKind = ordinary.groupBy { it.kind.uppercase() }
            .mapValues { (_, rows) ->
                val countByField = linkedMapOf<String, Int>()
                for (row in rows) {
                    for (path in row.fieldPaths) {
                        if ('.' in path || path.isBlank()) continue
                        countByField[path] = (countByField[path] ?: 0) + row.regularSeen
                    }
                }
                countByField
            }
        val typeByName = types?.types?.associateBy { it.name }.orEmpty()
        val rootByName = roots?.rootTypes?.associateBy { it.name }.orEmpty()
        val allRoles = (roles.keys + requestedByKind.keys).distinct()
            .sortedWith(compareBy { listOf("QUERY", "MUTATION", "SUBSCRIPTION").indexOf(it).let { index ->
                if (index == -1) Int.MAX_VALUE else index
            } })
        val rootGroups = allRoles.map { kind ->
            val name = roles[kind]
            val known = name != null &&
                (typeByName[name]?.fields != null || rootByName[name]?.fields != null)
            // TYPE_FIELDS includes the complete type's named fields; ROOT_FIELDS
            // has only root field names. Do not combine unnamed/cross-endpoint data.
            val declared = if (name != null) {
                typeByName[name]?.fields ?: rootByName[name]?.fields.orEmpty()
            } else emptyList()
            val declaredSet = declared.toSet()
            val signatures = if (name != null) {
                (typeByName[name]?.details ?: rootByName[name]?.details).orEmpty().associateBy { it.name }
            } else emptyMap()
            val requested = requestedByKind[kind].orEmpty()
            val fields = (declared + requested.keys).distinct().sorted().map { field ->
                val count = requested[field] ?: 0
                val status = when {
                    field.startsWith("__") && count > 0 && field !in declaredSet ->
                        SurfaceStatus.META_FIELD_REQUESTED
                    field in declaredSet && count > 0 -> SurfaceStatus.DECLARED_AND_REQUESTED
                    field in declaredSet -> SurfaceStatus.DECLARED_ONLY
                    else -> SurfaceStatus.REQUESTED_ONLY
                }
                SurfaceField(field, status, count, signatures[field])
            }
            SurfaceRoot(kind, name, fields, known)
        }
        val declaredTypes = types?.types.orEmpty()
            .filterNot { it.name.startsWith("__") }
            .map { SurfaceType(it.name, it.kind, it.fields, it.details) }
            .sortedWith(compareBy({ it.kind }, { it.name }))
        val version = listOfNotNull(rootVersion, typesVersion).maxByOrNull { it.whenMillis }
        val latestDiff = if (typesVersion != null && history.previousTypes != null) {
            diff(typesVersion, history.previousTypes!!)
        } else if (rootVersion != null && history.previousRoots != null) {
            diff(rootVersion, history.previousRoots!!)
        } else null
        return SurfaceReport(
            endpoint, rootGroups, declaredTypes, version?.whenMillis, ordinary.size,
            rootGroups.sumOf { group -> group.fields.count {
                it.status == SurfaceStatus.DECLARED_ONLY || it.status == SurfaceStatus.DECLARED_AND_REQUESTED
            } },
            rootGroups.sumOf { group -> group.fields.count { it.requests > 0 &&
                it.status != SurfaceStatus.META_FIELD_REQUESTED } },
            rootGroups.sumOf { group -> group.fields.count { it.status == SurfaceStatus.DECLARED_AND_REQUESTED } },
            roots?.truncated == true || types?.truncated == true,
            latestDiff
        )
    }

    private fun diff(current: Version, previous: Version): SchemaDelta {
        fun names(result: SchemaRecon.Result): Set<String> {
            val selected = if (result.mode == SchemaRecon.Mode.TYPE_FIELDS) result.types
                else result.rootTypes
            return selected.flatMap { type ->
                listOf(type.name + " [type]") + type.fields.map { type.name + "." + it }
            }.toSet()
        }
        val currentNames = names(current.result)
        val previousNames = names(previous.result)
        val reliable = !current.result.truncated && !previous.result.truncated
        return SchemaDelta(current.result.mode, previous.whenMillis, current.whenMillis,
            (currentNames - previousNames).sorted().take(200),
            if (reliable) (previousNames - currentNames).sorted().take(200) else emptyList(),
            reliable)
    }
}
