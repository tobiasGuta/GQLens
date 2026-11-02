package dev.gqlens.ui

import dev.gqlens.core.SchemaRecon
import dev.gqlens.core.SurfaceField
import dev.gqlens.core.SurfaceStatus
import dev.gqlens.core.SurfaceType
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.DefaultTableModel

/**
 * A native Swing property view. Full GraphQL type signatures belong here,
 * not in the navigation tree where long arguments are truncated.
 */
class SchemaDetailsPanel : JPanel(BorderLayout(8, 8)) {
    private val title = JLabel("Select a schema field").apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 3f)
    }
    private val classification = JLabel("No selection")
    private val declaredType = JLabel("—")
    private val count = JLabel("—")
    private val origin = JLabel("—")
    private val tableModel = object : DefaultTableModel(
        arrayOf("Argument", "GraphQL type", "Requirement"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val arguments = JTable(tableModel).apply {
        rowHeight = (font.size * 2).coerceAtLeast(24)
        fillsViewportHeight = true
    }
    private val note = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }

    init {
        border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
        val info = JPanel(GridLayout(5, 1, 0, 6)).apply {
            add(title)
            add(classification)
            add(declaredType)
            add(count)
            add(origin)
        }
        add(info, BorderLayout.NORTH)
        val center = JPanel(BorderLayout(4, 6))
        center.add(JLabel("DECLARED ARGUMENTS"), BorderLayout.NORTH)
        center.add(JScrollPane(arguments), BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)
        add(note, BorderLayout.SOUTH)
        placeholder()
    }

    fun placeholder(message: String = "Select a field or type to inspect its schema declaration.") {
        title.text = "Schema field details"
        classification.text = "No selection"
        declaredType.text = "Return type: —"
        count.text = "Captured requests: —"
        origin.text = "Schema source: —"
        tableModel.rowCount = 0
        note.text = message
    }

    fun showRoot(kind: String, field: SurfaceField) {
        title.text = field.name
        classification.text = "Status: " + when (field.status) {
            SurfaceStatus.DECLARED_AND_REQUESTED -> "Declared + requested"
            SurfaceStatus.DECLARED_ONLY -> "Declared only"
            SurfaceStatus.REQUESTED_ONLY -> "Requested only"
            SurfaceStatus.META_FIELD_REQUESTED -> "Implicit GraphQL meta-field"
        }
        declaredType.text = "Return type: " + (field.signature?.type?.display ?: "Not available")
        count.text = "Captured request observations: " + field.requests
        origin.text = "Root operation: " + kind
        setArguments(field.signature)
        note.text = when (field.status) {
            SurfaceStatus.DECLARED_AND_REQUESTED ->
                "This root field is declared by introspection and requested in captured traffic."
            SurfaceStatus.DECLARED_ONLY ->
                "Declared by the server; not requested in the current observation window."
            SurfaceStatus.REQUESTED_ONLY ->
                "Requested but not matched to the available, potentially partial schema."
            SurfaceStatus.META_FIELD_REQUESTED ->
                "GraphQL meta-fields are implicit and need not appear in the schema field list."
        } + " No status implies successful execution, access, or authorization."
    }

    fun showType(type: SurfaceType) {
        title.text = type.name
        classification.text = "GraphQL kind: " + type.kind
        declaredType.text = "Declared members: " + type.fields.size
        count.text = "Captured requests: not inferred from type member names"
        origin.text = "Source: introspection"
        tableModel.rowCount = 0
        note.text = "Types are declarations. Nested traffic cannot be attributed by name alone."
    }

    fun showMember(type: String, name: String, signature: SchemaRecon.FieldSignature?) {
        title.text = "$type.$name"
        classification.text = "Status: declared schema member"
        declaredType.text = "Member type: " + (signature?.type?.display ?: "Not available")
        count.text = "Captured requests: not inferred"
        origin.text = "Source: introspection"
        setArguments(signature)
        note.text = "This does not prove the member is accessible or has executed."
    }

    private fun setArguments(signature: SchemaRecon.FieldSignature?) {
        tableModel.rowCount = 0
        signature?.args?.forEach { arg ->
            tableModel.addRow(arrayOf(arg.name, arg.type.display,
                if (arg.required) "Required" else "Optional / default"))
        }
        if (signature != null && !signature.complete) {
            note.text += " Signature metadata is incomplete; generation is disabled."
        }
    }
}
