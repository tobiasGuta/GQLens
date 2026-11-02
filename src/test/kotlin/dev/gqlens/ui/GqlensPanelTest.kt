package dev.gqlens.ui

import dev.gqlens.core.Observation
import dev.gqlens.core.ObservationContext
import dev.gqlens.core.ObservationStore
import dev.gqlens.core.ResponseAnalyzer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTree
import javax.swing.SwingUtilities

class GqlensPanelTest {
    @Test
    fun rendersEndpointExplorerAndEvidenceTabsInHeadlessSwing() {
        val store = ObservationStore()
        val context = ObservationContext("REPEATER", 75, 123456)
        store.record(
            Observation("https://example.org/v1/graphql", "QUERY", "Test", "testhash",
                listOf("__typename")), context
        )
        store.recordResponse(context, 200, listOf(ResponseAnalyzer.Shape.DATA))
        SwingUtilities.invokeAndWait {
            val panel = GqlensPanel(store)
            val tabs = allComponents(panel).filterIsInstance<JTabbedPane>()
                .first { it.tabCount == 5 && it.getTitleAt(0) == "API Surface" }
            assertEquals(listOf("API Surface", "Fields", "Evidence history", "Response", "Recon (opt-in)"),
                (0 until tabs.tabCount).map { tabs.getTitleAt(it) })
            assertTrue(allComponents(panel).filterIsInstance<JSplitPane>().size >= 4)
            val nested = allComponents(panel).filterIsInstance<JTabbedPane>()
                .first { tabs -> (0 until tabs.tabCount).any {
                    tabs.getTitleAt(it) == "Query builder"
                } }
            assertEquals(listOf("Field details", "Schema changes", "Query builder"),
                (0 until nested.tabCount).map { nested.getTitleAt(it) })
            assertTrue(allComponents(panel).filterIsInstance<JTree>().size >= 2)
        }
    }

    private fun allComponents(component: Component): List<Component> =
        listOf(component) + if (component is Container) {
            component.components.flatMap { allComponents(it) }
        } else emptyList()
}
