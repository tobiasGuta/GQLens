package dev.tobiasguta.gqlens

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser
import java.security.MessageDigest

data class ObservedOperation(
    val kind: String,
    val name: String,
    val fieldPaths: List<String>,
    val fingerprint: String
)

/** Syntax-only analysis. No introspection, execution, or type-accuracy claim. */
object GraphQlAnalyzer {
    private const val MAX_FIELD_PATHS = 2000
    private const val MAX_DEPTH = 32
    private const val MAX_OPERATIONS = 40

    fun analyze(query: String): List<ObservedOperation> {
        if (query.isBlank() || query.length > 256 * 1024) return emptyList()
        val document: Document = try {
            Parser().parseDocument(query)
        } catch (_: Exception) {
            return emptyList()
        }
        val fragments = document.definitions.filterIsInstance<FragmentDefinition>()
            .associateBy { it.name }
        val operations = document.definitions.filterIsInstance<OperationDefinition>()
        if (operations.size > MAX_OPERATIONS) return emptyList()

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(query.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        return operations.map { op ->
            val paths = linkedSetOf<String>()
            visit(op.selectionSet, "", fragments, emptySet(), paths, 0)
            ObservedOperation(
                kind = op.operation.name,
                name = op.name ?: "(anonymous)",
                fieldPaths = paths.sorted(),
                fingerprint = digest + ":" + (op.name ?: "(anonymous)") + ":" + op.operation.name
            )
        }
    }

    private fun visit(
        selectionSet: SelectionSet?,
        prefix: String,
        fragments: Map<String, FragmentDefinition>,
        traversedFragments: Set<String>,
        paths: MutableSet<String>,
        depth: Int
    ) {
        if (selectionSet == null || depth >= MAX_DEPTH || paths.size >= MAX_FIELD_PATHS) return
        for (selection in selectionSet.selections) {
            if (paths.size >= MAX_FIELD_PATHS) return
            when (selection) {
                is Field -> {
                    // Ignore aliases: the underlying schema field name is what matters.
                    val path = if (prefix.isEmpty()) selection.name else "$prefix.${selection.name}"
                    paths.add(path)
                    visit(selection.selectionSet, path, fragments, traversedFragments, paths, depth + 1)
                }
                is InlineFragment ->
                    visit(selection.selectionSet, prefix, fragments, traversedFragments, paths, depth + 1)
                is FragmentSpread -> {
                    val name = selection.name
                    val fragment = fragments[name]
                    if (name !in traversedFragments && fragment != null) {
                        visit(fragment.selectionSet, prefix, fragments, traversedFragments + name, paths, depth + 1)
                    }
                }
            }
        }
    }
}
