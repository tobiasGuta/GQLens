package dev.gqlens.ui

import dev.gqlens.core.ApiSurfaceStore
import dev.gqlens.core.HistoryImportReport
import dev.gqlens.core.InvestigationWorkspace
import dev.gqlens.core.Observation
import dev.gqlens.core.ObservationContext
import dev.gqlens.core.ObservationStore
import dev.gqlens.core.ResponseAnalyzer
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BountyWorkspacePanelTest {
    private fun all(component: Component): List<Component> =
        listOf(component) + if (component is Container)
            component.components.flatMap(::all) else emptyList()

    @Test fun rendersNativeInvestigationTabsAndEvidenceCandidatesWithoutSendingRequests() {
        val store = ObservationStore()
        val label = InvestigationWorkspace()
        label.addContext("Controlled account A")
        label.addCase("Investigate GraphQL error")
        val context = ObservationContext("REPEATER", 77, 1100)
        store.record(Observation("https://example.test/graphql",
            "QUERY", "Compare", "hash", listOf("me")), context)
        store.recordResponse(context, 200, listOf(ResponseAnalyzer.Shape.ERRORS))
        var importCount = 0
        SwingUtilities.invokeAndWait {
            val panel = BountyWorkspacePanel(label, store, ApiSurfaceStore(),
                { importCount++; HistoryImportReport() }, {})
            val tabs = all(panel).filterIsInstance<JTabbedPane>().single()
            assertEquals(listOf("Investigation queue", "Cases & contexts", "History import"),
                (0 until tabs.tabCount).map { tabs.getTitleAt(it) })
            val table = all(panel).filterIsInstance<JTable>().first {
                it.columnCount == 4 && it.columnName(0) == "Signal"
            }
            assertEquals(1, table.rowCount)
            assertEquals("RESPONSE ERRORS", table.getValueAt(0, 0))
            assertNotNull(all(panel).filterIsInstance<JButton>()
                .firstOrNull { it.text == "Import Proxy history (read-only)" })
            assertEquals(0, importCount)
        }
    }
}
