package dev.gqlens.ui

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gqlens.core.ObservationRow
import dev.gqlens.core.ObservationStore
import java.awt.BorderLayout
import java.awt.FlowLayout
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
class GqlensPanel(private val store: ObservationStore) : JPanel(BorderLayout()) {
    private val capture = AtomicBoolean(true)
    private val pendingRefresh = AtomicBoolean(false)
    private val filter = JTextField(28)
    private val countLabel = JLabel("0 unique operations")
    private val tableModel = object : DefaultTableModel(
        arrayOf("Endpoint", "Type", "Operation", "Fields", "Seen"), 0
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
        add(JLabel("PASSIVE ONLY  |  Burp target scope enforced  |  Metadata stored in memory; no active requests"),
            BorderLayout.SOUTH)

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

    private fun refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            scheduleRefresh()
            return
        }
        val term = filter.text.trim()
        val snapshot = store.snapshot()
        visibleRows = snapshot.filter {
            term.isEmpty() ||
                it.endpoint.contains(term, ignoreCase = true) ||
                it.operation.contains(term, ignoreCase = true) ||
                it.kind.contains(term, ignoreCase = true) ||
                it.fieldPaths.any { path -> path.contains(term, ignoreCase = true) }
        }
        tableModel.rowCount = 0
        visibleRows.forEach {
            tableModel.addRow(arrayOf(it.endpoint, it.kind, it.operation, it.fieldPaths.size, it.seen))
        }
        countLabel.text = snapshot.size.toString() + " unique / " + visibleRows.size + " shown"
        if (table.selectedRow == -1) {
            details.text = "Select an observed operation to inspect its confirmed field paths."
        }
    }

    private fun showDetails() {
        val selected = table.selectedRow
        val index = if (selected < 0) -1 else table.convertRowIndexToModel(selected)
        val row = visibleRows.getOrNull(index) ?: return
        details.text = buildString {
            appendLine("Endpoint: " + row.endpoint)
            appendLine("Operation: " + row.kind + " " + row.operation)
            appendLine("Query SHA-256 prefix: " + row.fingerprint)
            appendLine("Observed count: " + row.seen)
            appendLine()
            appendLine("Observed field paths (not an inferred full schema):")
            if (row.fieldPaths.isEmpty()) appendLine("(No query document; persisted hash only)")
            else row.fieldPaths.forEach { appendLine("  " + it) }
        }
        details.caretPosition = 0
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
