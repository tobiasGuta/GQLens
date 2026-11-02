package dev.gqlens.ui

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gqlens.core.ObservationRow
import dev.gqlens.core.ObservationStore
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/** UI shows structural metadata only; no raw HTTP requests, cookies, or variable values. */
class GqlensPanel(
    private val store: ObservationStore,
    private val openEvidence: (ObservationRow) -> String = { "Evidence navigation is unavailable." }
) : JPanel(BorderLayout()) {
    private val capture = AtomicBoolean(true)
    private val pendingRefresh = AtomicBoolean(false)
    private val filter = JTextField(28)
    private val countLabel = JLabel("0 unique operations")
    private val evidenceStatus = JLabel("Captured message IDs are session-local.")
    private val toRepeater = JButton("Send selected Proxy request to Repeater")
    private val tableModel = object : DefaultTableModel(
        arrayOf("Endpoint", "Type", "Operation", "Fields", "Seen", "HTTP", "Result", "Tool", "ID"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JTable(tableModel)
    private val details = JTextArea("Browse an in-scope GraphQL API through Burp.\nSelect an observed operation to see its field paths.")
    private var visibleRows = emptyList<ObservationRow>()

    val captureEnabled: Boolean get() = capture.get()

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        val enabled = JCheckBox("Capture new in-scope requests", true)
        enabled.addActionListener { capture.set(enabled.isSelected) }
        toolbar.add(enabled)
        toolbar.add(JLabel("Filter:"))
        toolbar.add(filter)
        toolbar.add(countLabel)
        val clear = JButton("Clear")
        clear.addActionListener {
            if (JOptionPane.showConfirmDialog(this, "Clear all observations in this GQLens session?",
                    "Clear", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                store.clear()
                refresh()
            }
        }
        val export = JButton("Export metadata JSON")
        export.addActionListener { exportMetadata() }
        toolbar.add(export)
        toolbar.add(clear)
        toRepeater.isEnabled = false
        toRepeater.addActionListener { openSelectedInRepeater() }
        toolbar.add(toRepeater)
        add(toolbar, BorderLayout.NORTH)

        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
        table.autoCreateRowSorter = true
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showDetails()
        }
        details.isEditable = false
        details.lineWrap = false
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(details))
        split.resizeWeight = 0.65
        add(split, BorderLayout.CENTER)
        val footer = JPanel(BorderLayout())
        footer.add(evidenceStatus, BorderLayout.NORTH)
        footer.add(JLabel("PASSIVE ONLY  |  Burp target scope enforced  |  Metadata only; Repeater never auto-sends"),
            BorderLayout.SOUTH)
        add(footer, BorderLayout.SOUTH)

        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
    }

    /** Coalesce bursts of HTTP events into at most one queued Swing refresh. */
    fun scheduleRefresh() {
        if (pendingRefresh.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                pendingRefresh.set(false)
                refresh()
            }
        }
    }

    private fun selectedRow(): ObservationRow? {
        val viewIndex = table.selectedRow
        val modelIndex = if (viewIndex < 0) -1 else table.convertRowIndexToModel(viewIndex)
        return visibleRows.getOrNull(modelIndex)
    }

    private fun refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            scheduleRefresh()
            return
        }
        val selected = selectedRow()?.let { listOf(it.endpoint, it.kind, it.operation, it.fingerprint) }
        val term = filter.text.trim()
        val snapshot = store.snapshot()
        visibleRows = snapshot.filter {
            term.isEmpty() ||
                it.endpoint.contains(term, ignoreCase = true) ||
                it.operation.contains(term, ignoreCase = true) ||
                it.kind.contains(term, ignoreCase = true) ||
                it.fieldPaths.any { path -> path.contains(term, ignoreCase = true) } ||
                it.lastResponseShape.contains(term, ignoreCase = true)
        }
        tableModel.rowCount = 0
        visibleRows.forEach {
            tableModel.addRow(arrayOf(
                it.endpoint, it.kind, it.operation, it.fieldPaths.size, it.seen,
                it.lastHttpStatus ?: "-", it.lastResponseShape,
                it.lastTool, it.lastMessageId ?: "-"
            ))
        }
        countLabel.text = snapshot.size.toString() + " unique / " + visibleRows.size +
            " shown / " + store.rejectedCount() + " rejected at capacity"
        val index = visibleRows.indexOfFirst {
            selected == listOf(it.endpoint, it.kind, it.operation, it.fingerprint)
        }
        if (index >= 0) {
            val viewIndex = table.convertRowIndexToView(index)
            if (viewIndex >= 0) table.selectionModel.setSelectionInterval(viewIndex, viewIndex)
        }
        showDetails()
    }

    private fun showDetails() {
        val row = selectedRow()
        toRepeater.isEnabled = row?.lastTool == "PROXY" && row.lastMessageId != null
        if (row == null) {
            details.text = "Select a requested operation to inspect its field paths and session-local evidence reference."
            return
        }
        details.text = buildString {
            appendLine("Endpoint: " + row.endpoint)
            appendLine("Requested operation: " + row.kind + " " + row.operation)
            appendLine("Document SHA-256 prefix: " + row.fingerprint)
            appendLine("Observed requests: " + row.seen)
            appendLine("First seen (UTC): " + Instant.ofEpochMilli(row.firstSeenMillis))
            appendLine("Last seen (UTC): " + Instant.ofEpochMilli(row.lastSeenMillis))
            appendLine("Last Burp tool: " + row.lastTool)
            appendLine("Last message ID (session-local): " + (row.lastMessageId ?: "unavailable"))
            appendLine("Last HTTP status: " + (row.lastHttpStatus ?: "not observed"))
            appendLine("Last response structure: " + row.lastResponseShape)
            appendLine("Correlated responses: " + row.responsesSeen)
            appendLine()
            appendLine("Requested field paths (not proof of execution or authorization):")
            if (row.fieldPaths.isEmpty()) appendLine("(No query document; persisted hash only)")
            else row.fieldPaths.forEach { appendLine("  " + it) }
        }
        details.caretPosition = 0
    }

    private fun openSelectedInRepeater() {
        val row = selectedRow() ?: return
        if (row.lastTool != "PROXY" || row.lastMessageId == null) return
        toRepeater.isEnabled = false
        evidenceStatus.text = "Resolving exact captured message in Burp Proxy history..."
        Thread({
            val message = try { openEvidence(row) }
                catch (_: Exception) { "Unable to resolve evidence. No request was sent." }
            SwingUtilities.invokeLater {
                evidenceStatus.text = message
                showDetails()
            }
        }, "GQLens-evidence-resolution").apply {
            isDaemon = true
            start()
        }
    }

    private fun exportMetadata() {
        val chooser = JFileChooser()
        chooser.selectedFile = java.io.File("gqlens-observations.json")
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val destination = chooser.selectedFile
        if (destination.exists() && JOptionPane.showConfirmDialog(this, "Overwrite the selected file?",
                "Confirm overwrite", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        try {
            val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(store.snapshot())
            Files.writeString(destination.toPath(), json, StandardCharsets.UTF_8)
            JOptionPane.showMessageDialog(this, "Exported structural metadata only.")
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Unable to export: " + e.message, "Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}
