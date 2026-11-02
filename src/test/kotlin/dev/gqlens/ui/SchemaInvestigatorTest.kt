package dev.gqlens.ui

import dev.gqlens.core.ApiSurfaceStore
import dev.gqlens.core.SchemaRecon
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SchemaInvestigatorTest {
    private fun all(component: Component): List<Component> =
        listOf(component) + if (component is Container) {
            component.components.flatMap { all(it) }
        } else emptyList()

    @Test
    fun organizesTypesAndShowsReadableQueryAndJsonWithLineNumbers() {
        val endpoint = "https://example.org/graphql"
        val schema = SchemaRecon.parse(SchemaRecon.Mode.TYPE_FIELDS, 200, """
            {"data":{"__schema":{
              "queryType":{"name":"ReadRoot"},
              "mutationType":null,
              "subscriptionType":null,
              "types":[
                {"name":"ReadRoot","kind":"OBJECT","fields":[
                  {"name":"organization","type":{"kind":"OBJECT","name":"Organization"},
                   "args":[{"name":"slug","defaultValue":null,
                     "type":{"kind":"NON_NULL","ofType":{"kind":"SCALAR","name":"String"}}}]}
                ]},
                {"name":"Organization","kind":"OBJECT","fields":[
                  {"name":"id","type":{"kind":"SCALAR","name":"ID"},"args":[]}]},
                {"name":"Plan","kind":"ENUM","enumValues":[{"name":"FREE"},{"name":"TEAM"}]}
              ]
            }}}
        """.trimIndent())
        assertEquals("SCHEMA_OBSERVED", schema.outcome)
        val storage = ApiSurfaceStore()
        storage.record(endpoint, schema)
        SwingUtilities.invokeAndWait {
            val panel = ApiSurfacePanel(storage) { emptyList() }
            panel.selectEndpoint(endpoint)
            val tree = all(panel).filterIsInstance<JTree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            val labels = (0 until root.childCount).map { root.getChildAt(it).toString() }
            assertTrue(labels.any { it.startsWith("QUERY") })
            val schemaNode = (0 until root.childCount)
                .map { root.getChildAt(it) as DefaultMutableTreeNode }
                .first { it.toString().startsWith("Schema types") }
            val typeGroups = (0 until schemaNode.childCount)
                .map { schemaNode.getChildAt(it).toString() }
            assertTrue(typeGroups.any { it.startsWith("Object types") })
            assertTrue(typeGroups.any { it.startsWith("Enum types") })

            val queryNode = root.getChildAt(0) as DefaultMutableTreeNode
            val fieldNode = queryNode.getChildAt(0) as DefaultMutableTreeNode
            assertEquals("organization", fieldNode.toString())
            tree.selectionPath = TreePath(fieldNode.path)
            val viewer = all(panel).filterIsInstance<CodePreviewPanel>().single()
            assertTrue(viewer.currentCode().contains("organization(slug: \$slug)"))
            assertTrue(viewer.currentCode().contains("\n    __typename\n"))
            val scroll = all(viewer).filterIsInstance<JScrollPane>().single()
            val gutter = scroll.rowHeader.view as JTextArea
            assertTrue(gutter.text.lines().size >= 4)

            val mode = all(panel).filterIsInstance<JComboBox<*>>()
                .first { it.itemCount == 2 && it.getItemAt(1) == "JSON request body" }
            mode.selectedIndex = 1
            val json = viewer.currentCode()
            assertTrue(json.contains("\"query\""))
            assertTrue(json.contains("\"variables\""))
            val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
            assertEquals(0, parsed.path("variables").size())
        }
    }
}
