package dev.gqlens.ui

import dev.gqlens.core.ApiSurfaceStore
import dev.gqlens.core.EvidenceEvent
import dev.gqlens.core.ObservationRow
import dev.gqlens.core.SchemaRecon
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvestigationPanelTest {
    private fun all(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::all) else emptyList()

    @Test fun followsActualReturnTypeAndLinksRootFieldToExactCapture() {
        val endpoint = "https://api.example.test/graphql"
        val store = ApiSurfaceStore()
        val result = SchemaRecon.parse(SchemaRecon.Mode.TYPE_FIELDS, 200, """
            {"data":{"__schema":{
              "queryType":{"name":"ReadAPI"},"mutationType":null,"subscriptionType":null,
              "types":[
                {"name":"ReadAPI","kind":"OBJECT","fields":[
                  {"name":"organization","type":{"kind":"OBJECT","name":"Org"},"args":[]}]},
                {"name":"Org","kind":"OBJECT","fields":[
                  {"name":"id","type":{"kind":"SCALAR","name":"ID"},"args":[]}]}
              ]
            }}}
        """.trimIndent())
        assertTrue(store.record(endpoint, result))
        val observed = ObservationRow(endpoint, "QUERY", "GetOrg", "abc",
            listOf("organization"), 1, regularSeen = 1)
        val capture = EvidenceEvent(1, endpoint, "QUERY", "GetOrg", "abc", 0,
            "REPEATER", 23, 1000, 200, "DATA")
        var navigated: EvidenceEvent? = null
        SwingUtilities.invokeAndWait {
            val panel = ApiSurfacePanel(store,
                { listOf(observed) },
                { _, kind, field ->
                    if (kind == "QUERY" && field == "organization") listOf(capture)
                    else emptyList()
                },
                { navigated = it })
            panel.selectEndpoint(endpoint)
            val tree = all(panel).filterIsInstance<JTree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            val rootField = (root.getChildAt(0) as DefaultMutableTreeNode)
                .getChildAt(0) as DefaultMutableTreeNode
            tree.selectionPath = TreePath(rootField.path)
            val evidence = all(panel).filterIsInstance<FieldEvidencePanel>().single()
            val table = all(evidence).filterIsInstance<JTable>().single()
            assertEquals(1, table.rowCount)
            table.setRowSelectionInterval(0, 0)
            all(evidence).filterIsInstance<JButton>()
                .single { it.text == "Open in evidence history" }.doClick()
            assertEquals(23, navigated?.messageId)

            all(panel).filterIsInstance<JButton>()
                .single { it.text == "Follow return type" }.doClick()
            assertEquals("Org", (tree.lastSelectedPathComponent as DefaultMutableTreeNode).toString())
            all(panel).filterIsInstance<JButton>()
                .single { it.text == "Back" }.doClick()
            assertEquals("organization", (tree.lastSelectedPathComponent as DefaultMutableTreeNode).toString())
        }
    }
}
