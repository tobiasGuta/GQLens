package dev.gqlens.ui

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gqlens.core.ApiSurfaceStore
import dev.gqlens.core.ObservationRow
import dev.gqlens.core.SchemaDelta
import dev.gqlens.core.SurfaceField
import dev.gqlens.core.SurfaceReport
import dev.gqlens.core.SurfaceRoot
import dev.gqlens.core.SurfaceStatus
import dev.gqlens.core.SurfaceType
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Burp-theme-friendly, read-only correlation of declared schema versus requested
 * fields in captured queries. Never assumes that declaration means access.
 */
class ApiSurfacePanel(
    private val schemas: ApiSurfaceStore,
    private val observations: () -> List<ObservationRow>
) : JPanel(BorderLayout()) {
    private data class FieldNode(
        val group: String,
        val field: SurfaceField
    ) {
        override fun toString(): String = field.name + "   ·   " + when (field.status) {
            SurfaceStatus.DECLARED_AND_REQUESTED -> "Declared + requested"
            SurfaceStatus.DECLARED_ONLY -> "Declared"
            SurfaceStatus.REQUESTED_ONLY -> "Requested only"
            SurfaceStatus.META_FIELD_REQUESTED -> "GraphQL meta-field"
        }
    }
    private data class TypeNode(val type: SurfaceType) {
        override fun toString(): String = type.name + "   ·   " + type.kind
    }
    private data class TypeFieldNode(val type: String, val field: String) {
        override fun toString(): String = field
    }

    private val preferences = Preferences.userNodeForPackage(ApiSurfacePanel::class.java)
    private val endpointLabel = JLabel("Select a captured GraphQL operation")
    private val schemaStatus = JLabel("No schema recorded")
    private val declaredMetric = metric()
    private val requestedMetric = metric()
    private val matchedMetric = metric()
    private val typesMetric = metric()
    private val search = JTextField(20)
    private val filter = JComboBox(arrayOf("All fields", "Declared + requested",
        "Declared only", "Requested only", "Meta-fields"))
    private val root = DefaultMutableTreeNode("API Surface")
    private val tree = JTree(DefaultTreeModel(root))
    private val detail = textArea()
    private val changes = textArea()
    private val tabs = JTabbedPane()
    private val split: JSplitPane
    private var endpoint: String? = null
    private var report: SurfaceReport? = null

    init {
        val heading = JPanel(BorderLayout(8, 6))
        heading.border = BorderFactory.createEmptyBorder(10, 12, 8, 12)
        val info = JPanel(GridLayout(2, 1, 0, 3))
        endpointLabel.font = endpointLabel.font.deriveFont(Font.BOLD)
        info.add(endpointLabel)
        info.add(schemaStatus)
        heading.add(info, BorderLayout.NORTH)

        val metrics = JPanel(GridLayout(1, 4, 10, 0))
        metrics.add(metricCard("Declared roots", declaredMetric))
        metrics.add(metricCard("Requested roots", requestedMetric))
        metrics.add(metricCard("Matched roots", matchedMetric))
        metrics.add(metricCard("Schema types", typesMetric))
        heading.add(metrics, BorderLayout.CENTER)

        val tools = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        tools.add(JLabel("Search"))
        tools.add(search)
        tools.add(JLabel("Show"))
        tools.add(filter)
        val export = JButton("Export surface JSON")
        export.addActionListener { exportSurface() }
        tools.add(export)
        heading.add(tools, BorderLayout.SOUTH)
        add(heading, BorderLayout.NORTH)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, hasFocus
                )
                border = BorderFactory.createEmptyBorder(3, 5, 3, 4)
                return component
            }
        }
        tree.addTreeSelectionListener { showSelection() }
        detail.text = "Schema declarations and observed requests will appear here.\n\n" +
            "Declared does not mean accessible. Requested does not mean executed."
        changes.text = "Run a permitted introspection query to record a schema snapshot."
        tabs.addTab("Field details", JScrollPane(detail))
        tabs.addTab("Schema changes", JScrollPane(changes))
        split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(tree), tabs)
        split.resizeWeight = 0.55
        split.dividerLocation = preferences.getInt("surfaceDivider", 410)
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (split.dividerLocation > 0) preferences.putInt("surfaceDivider", split.dividerLocation)
        }
        add(split, BorderLayout.CENTER)

        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = redraw()
            override fun removeUpdate(e: DocumentEvent) = redraw()
            override fun changedUpdate(e: DocumentEvent) = redraw()
        })
        filter.addActionListener { redraw() }
        redraw()
    }

    fun selectEndpoint(next: String?) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { selectEndpoint(next) }
            return
        }
        if (endpoint == next && report != null) return
        endpoint = next
        refresh()
    }

    fun refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { refresh() }
            return
        }
        report = endpoint?.let { schemas.report(it, observations()) }
        redraw()
    }

    private fun redraw() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { redraw() }
            return
        }
        val current = report
        endpointLabel.text = current?.endpoint ?: "Select an observed GraphQL endpoint"
        schemaStatus.text = if (current?.schemaSeenAtMillis != null)
            "Schema observed at " + Instant.ofEpochMilli(current.schemaSeenAtMillis) +
                (if (current.truncated) "   ·   partial/truncated" else "   ·   structural metadata only")
        else "No schema for this endpoint · use Recon (opt-in) if authorized"
        declaredMetric.text = (current?.declaredFields ?: 0).toString()
        requestedMetric.text = (current?.requestedFields ?: 0).toString()
        matchedMetric.text = (current?.matchedFields ?: 0).toString()
        typesMetric.text = (current?.types?.size ?: 0).toString()

        val previousSelection = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            ?.userObject?.toString()
        root.removeAllChildren()
        var restore: TreePath? = null
        if (current != null) {
            val needle = search.text.trim()
            for (group in current.roots) {
                val matching = group.fields.filter { field ->
                    (needle.isBlank() || field.name.contains(needle, true) ||
                        group.kind.contains(needle, true) ||
                        group.typeName.orEmpty().contains(needle, true)) &&
                        passesFilter(field)
                }
                if (matching.isEmpty() && needle.isNotBlank()) continue
                val caption = group.kind + "   ·   " + (group.typeName ?: "type unknown") +
                    "   (" + matching.size + " fields)"
                val branch = DefaultMutableTreeNode(caption)
                root.add(branch)
                for (field in matching) {
                    val node = DefaultMutableTreeNode(FieldNode(group.kind, field))
                    branch.add(node)
                    if (node.userObject.toString() == previousSelection) restore = TreePath(node.path)
                }
            }
            if (filter.selectedIndex == 0 || filter.selectedIndex == 1) {
                val typeBranch = DefaultMutableTreeNode("Schema types   (" + current.types.size + ")")
                for (type in current.types) {
                    val fields = type.fields.filter { it.contains(needle, true) }
                    if (needle.isNotBlank() && !type.name.contains(needle, true) && fields.isEmpty()) continue
                    val item = DefaultMutableTreeNode(TypeNode(type))
                    typeBranch.add(item)
                    for (field in if (needle.isNotBlank() && !type.name.contains(needle, true))
                        fields else type.fields) {
                        val child = DefaultMutableTreeNode(TypeFieldNode(type.name, field))
                        item.add(child)
                        if (child.userObject.toString() == previousSelection) restore = TreePath(child.path)
                    }
                }
                if (typeBranch.childCount > 0) root.add(typeBranch)
            }
        }
        (tree.model as DefaultTreeModel).reload()
        for (i in 0 until minOf(tree.rowCount, 60)) tree.expandRow(i)
        if (restore != null) tree.selectionPath = restore
        showChanges(current?.changes)
        if (tree.selectionPath == null) {
            detail.text = "Select a field to see its provenance.\n\n" +
                "Requested fields are parsed from captured GraphQL documents; " +
                "they may be skipped by directives or rejected by the server.\n\n" +
                "Schema declarations do not establish authorization or successful execution."
        }
    }

    private fun passesFilter(field: SurfaceField): Boolean = when (filter.selectedIndex) {
        1 -> field.status == SurfaceStatus.DECLARED_AND_REQUESTED
        2 -> field.status == SurfaceStatus.DECLARED_ONLY
        3 -> field.status == SurfaceStatus.REQUESTED_ONLY
        4 -> field.status == SurfaceStatus.META_FIELD_REQUESTED
        else -> true
    }

    private fun showSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        when (val value = node.userObject) {
            is FieldNode -> {
                val field = value.field
                detail.text = buildString {
                    appendLine(value.group + " → " + field.name)
                    appendLine()
                    appendLine("Classification: " + value.toString().substringAfter("·").trim())
                    appendLine("Captured request observations: " + field.requests)
                    appendLine()
                    appendLine(when (field.status) {
                        SurfaceStatus.DECLARED_AND_REQUESTED ->
                            "Field exists in the introspected root type and was requested in captured GraphQL traffic."
                        SurfaceStatus.DECLARED_ONLY ->
                            "Field is declared by the introspected root type. No matching request was observed in this capture window."
                        SurfaceStatus.REQUESTED_ONLY ->
                            "A document requested this root field, but no matching declaration was found in the available schema snapshot. This can be due to stale/partial schema or rejection."
                        SurfaceStatus.META_FIELD_REQUESTED ->
                            "A reserved GraphQL meta-field was requested. Meta-fields such as __typename are implicit and are not listed as normal schema fields."
                    })
                    appendLine()
                    appendLine("This is not proof of execution, successful access, or an authorization defect.")
                }
            }
            is TypeNode -> detail.text = buildString {
                appendLine("Schema type: " + value.type.name)
                appendLine("Type kind: " + value.type.kind)
                appendLine("Declared member names: " + value.type.fields.size)
                appendLine()
                appendLine("These type members are declarations only. GQLens does not infer " +
                    "which nested selections correspond to this type.")
            }
            is TypeFieldNode -> detail.text = buildString {
                appendLine(value.type + "." + value.field)
                appendLine()
                appendLine("Declared schema member, not an observed access claim.")
                appendLine("Nested selection matching requires schema-aware traversal " +
                    "and is intentionally not inferred by matching name alone.")
            }
            else -> detail.text = value?.toString() ?: ""
        }
        detail.caretPosition = 0
    }

    private fun showChanges(delta: SchemaDelta?) {
        changes.text = if (delta == null) {
            "No comparable previous schema snapshot for this endpoint.\n\n" +
                "Re-run the same introspection mode after an authorized schema change " +
                "to compare two discoveries."
        } else buildString {
            appendLine("Mode: " + delta.mode.title)
            appendLine("Previous: " + Instant.ofEpochMilli(delta.previousAtMillis))
            appendLine("Current:  " + Instant.ofEpochMilli(delta.currentAtMillis))
            appendLine("Comparison: " + if (delta.comparable) "Complete within parser bounds" else
                "Partial: at least one capture was truncated. Removals suppressed.")
            appendLine()
            appendLine("ADDED (" + delta.added.size + " listed)")
            delta.added.forEach { appendLine("  + " + it) }
            appendLine()
            appendLine("REMOVED (" + delta.removed.size + " listed)")
            delta.removed.forEach { appendLine("  - " + it) }
            appendLine()
            appendLine("Only declared schema metadata is compared. No network activity occurs here.")
        }
        changes.caretPosition = 0
    }

    private fun exportSurface() {
        val current = report ?: return
        val chooser = JFileChooser()
        chooser.selectedFile = java.io.File("gqlens-api-surface.json")
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        if (file.exists() && JOptionPane.showConfirmDialog(
                this, "Overwrite selected metadata export?", "Confirm", JOptionPane.YES_NO_OPTION
            ) != JOptionPane.YES_OPTION) return
        try {
            val doc = mapOf("formatVersion" to 1, "surface" to current)
            val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc)
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8)
            JOptionPane.showMessageDialog(this,
                "Exported structural schema/request metadata only. Review endpoint paths before sharing.")
        } catch (error: Exception) {
            JOptionPane.showMessageDialog(this, "Export failed: " + error.message,
                "Export error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun metric(): JLabel = JLabel("0").apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 7f)
    }

    private fun metricCard(label: String, value: JLabel): JPanel = JPanel(BorderLayout(2, 2)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEtchedBorder(),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        )
        add(JLabel(label), BorderLayout.NORTH)
        add(value, BorderLayout.CENTER)
        minimumSize = Dimension(110, 58)
    }

    private fun textArea(): JTextArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
    }
}
