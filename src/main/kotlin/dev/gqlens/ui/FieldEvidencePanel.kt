package dev.gqlens.ui

import dev.gqlens.core.EvidenceEvent
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * Field-to-capture provenance browser. Uses only accepted, bounded metadata
 * from the observation store. Opens existing metadata view, never sends HTTP.
 */
class FieldEvidencePanel(
    private val openInHistory: (EvidenceEvent) -> Unit
) : JPanel(BorderLayout(6, 6)) {
    private val heading = JLabel("Select a requested root field to see captured evidence.")
    private val model = object : DefaultTableModel(
        arrayOf("UTC time", "Operation", "Tool", "ID", "HTTP", "Shape", "Errors"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JTable(model).apply {
        autoCreateRowSorter = true
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        rowHeight = (font.size * 2).coerceAtLeast(24)
        fillsViewportHeight = true
    }
    private val open = JButton("Open in evidence history").apply { isEnabled = false }
    private val copy = JButton("Copy evidence reference").apply { isEnabled = false }
    private val note = JLabel("Evidence metadata only; no request execution or authorization claims.")
    private var events = emptyList<EvidenceEvent>()

    init {
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 3))
        actions.add(open)
        actions.add(copy)
        val header = JPanel(BorderLayout())
        header.add(heading, BorderLayout.NORTH)
        header.add(actions, BorderLayout.SOUTH)
        add(header, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(note, BorderLayout.SOUTH)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                open.isEnabled = selected() != null
                copy.isEnabled = selected() != null
            }
        }
        open.addActionListener { selected()?.let(openInHistory) }
        copy.addActionListener {
            val event = selected() ?: return@addActionListener
            val reference = listOf(event.endpoint, event.kind, event.operation,
                event.tool, event.messageId.toString(), event.id.toString())
                .joinToString(" | ")
            try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection(reference), null)
                note.text = "Session-local metadata reference copied; no raw HTTP."
            } catch (_: Exception) { note.text = "Clipboard unavailable." }
        }
    }

    private fun selected(): EvidenceEvent? {
        val row = table.selectedRow
        return if (row < 0) null else events.getOrNull(table.convertRowIndexToModel(row))
    }

    fun showField(kind: String?, field: String?, evidence: List<EvidenceEvent>) {
        events = evidence
        model.rowCount = 0
        heading.text = if (kind == null || field == null)
            "Select a requested root field to see its captures."
        else "$kind / $field — " + evidence.size + " recent evidence records"
        for (event in evidence) {
            model.addRow(arrayOf(
                Instant.ofEpochMilli(event.observedAtMillis).toString(),
                event.operation, event.tool, event.messageId,
                event.httpStatus ?: "—", event.responseShape,
                event.diagnostics?.errors ?: "—"
            ))
        }
        open.isEnabled = false
        copy.isEnabled = false
        note.text = "Exact selected root field only. May exclude older evicted captures."
    }
}
