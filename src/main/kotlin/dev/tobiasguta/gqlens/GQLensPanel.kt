package dev.tobiasguta.gqlens

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class GQLensPanel(
    private val inventory: OperationInventory,
    private val enabled: AtomicBoolean
) : JPanel(BorderLayout()) {
    private val rows = mutableListOf<InventoryItem>()
    private var lastRevision = -1L
    private val model = object : DefaultTableModel(arrayOf("Endpoint", "Kind", "Operation", "Fields", "Sightings"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 3 || columnIndex == 4) Integer::class.java else String::class.java
    }
    private val table = JTable(model)
    private val sorter = TableRowSorter(model)
    private val details = JTextArea()
    private val summary = JLabel("0 operations observed")
    private val filter = JTextField(22)
    private val timer = Timer(1200) { refresh() }

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEADING))
        val toggle = JCheckBox("Capture in-scope traffic", true)
        toggle.addActionListener { enabled.set(toggle.isSelected) }
        controls.add(toggle)
        controls.add(JLabel("Search:"))
        controls.add(filter)
        val export = JButton("Export CSV")
        export.addActionListener { exportCsv() }
        controls.add(export)
        val clear = JButton("Clear inventory")
        clear.addActionListener {
            if (JOptionPane.showConfirmDialog(this, "Clear the in-memory GQLens inventory?",
                    "Confirm clear", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) inventory.clear()
        }
        controls.add(clear)
        controls.add(summary)
        add(controls, BorderLayout.NORTH)

        table.rowSorter = sorter
        table.fillsViewportHeight = true
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) displaySelection()
        }
        filter.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
        })

        details.isEditable = false
        details.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(details))
        split.resizeWeight = 0.65
        add(split, BorderLayout.CENTER)
        add(JLabel("Passive only · Burp Target scope required · no raw requests/variables retained"),
            BorderLayout.SOUTH)
        timer.start()
    }

    fun shutdown() {
        if (SwingUtilities.isEventDispatchThread()) timer.stop()
        else SwingUtilities.invokeLater { timer.stop() }
    }

    private fun applyFilter() {
        val needle = filter.text.trim()
        // Literal substring (not regex) prevents malformed filters and regex DoS.
        sorter.rowFilter = if (needle.isEmpty()) null else object : RowFilter<DefaultTableModel, Int>() {
            override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                val item = rows.getOrNull(entry.identifier) ?: return false
                return listOf(item.endpoint, item.kind, item.name).any { it.contains(needle, true) } ||
                    item.fields.any { it.contains(needle, true) }
            }
        }
    }

    private fun refresh() {
        val (revision, updated) = inventory.snapshot()
        if (revision == lastRevision) return
        lastRevision = revision
        rows.clear()
        rows.addAll(updated)
        model.setRowCount(0)
        for (item in rows) {
            model.addRow(arrayOf(item.endpoint, item.kind, item.name, item.fields.size, item.sightings))
        }
        summary.text = "${rows.size} operations observed"
        if (table.selectedRow < 0) details.text = ""
    }

    private fun displaySelection() {
        val selected = table.selectedRow
        if (selected < 0) {
            details.text = ""
            return
        }
        val index = table.convertRowIndexToModel(selected)
        val item = rows.getOrNull(index) ?: return
        details.text = buildString {
            appendLine("Endpoint: ${item.endpoint}")
            appendLine("Operation: ${item.kind} ${item.name}")
            appendLine("Evidence: OBSERVED query syntax (not an authorization finding)")
            appendLine("Document fingerprint: ${item.fingerprint}")
            appendLine("Sightings: ${item.sightings}")
            appendLine()
            appendLine("Selected field paths:")
            item.fields.forEach { appendLine("  $it") }
        }
        details.caretPosition = 0
    }

    private fun exportCsv() {
        val chooser = JFileChooser()
        chooser.selectedFile = java.io.File("gqlens-observed.csv")
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path = chooser.selectedFile.toPath()
        try {
            val (_, items) = inventory.snapshot()
            val content = buildString {
                appendLine("endpoint,kind,operation,field_path,fingerprint,sightings")
                items.forEach { item ->
                    val fields = item.fields.ifEmpty { listOf("") }
                    fields.forEach { pathValue ->
                        appendLine(listOf(item.endpoint, item.kind, item.name, pathValue, item.fingerprint,
                            item.sightings.toString()).joinToString(",") { csv(it) })
                    }
                }
            }
            // Never overwrite an existing report without an explicit separate filesystem action.
            Files.writeString(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            JOptionPane.showMessageDialog(this, "Metadata-only CSV saved.")
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(this, "Export failed: ${ex.javaClass.simpleName}",
                "GQLens", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
