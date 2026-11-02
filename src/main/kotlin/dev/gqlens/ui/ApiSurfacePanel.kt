package dev.gqlens.ui

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gqlens.core.ApiSurfaceStore
import dev.gqlens.core.QueryDraftBuilder
import dev.gqlens.core.SchemaRecon
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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
import javax.swing.UIManager
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
        override fun toString(): String = (field.signature?.display ?: field.name) + "   ·   " + when (field.status) {
            SurfaceStatus.DECLARED_AND_REQUESTED -> "Declared + requested"
            SurfaceStatus.DECLARED_ONLY -> "Declared"
            SurfaceStatus.REQUESTED_ONLY -> "Requested only"
            SurfaceStatus.META_FIELD_REQUESTED -> "GraphQL meta-field"
        }
    }
    private data class TypeNode(val type: SurfaceType) {
        override fun toString(): String = type.name + "   ·   " + type.kind
    }
    private data class TypeFieldNode(
        val type: String, val field: String, val signature: SchemaRecon.FieldSignature?
    ) {
        override fun toString(): String = signature?.display ?: field
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
    private val builderText = textArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size + 1)
        text = "Select an introspected QUERY root field to generate a read-only document."
    }
    private val builderNotes = JLabel("Generation only — never sends a request")
    private val copyDraft = JButton("Copy query to clipboard").apply { isEnabled = false }
    private var generatedDraft: QueryDraftBuilder.Draft? = null
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
        tree.font = tree.font.deriveFont(tree.font.size2D + 1f)
        tree.rowHeight = (tree.font.size * 2).coerceAtLeast(24)
        tree.visibleRowCount = 15
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, hasFocus
                )
                val node = (value as? DefaultMutableTreeNode)?.userObject
                if (!selected && node is FieldNode) {
                    if (node.field.status == SurfaceStatus.DECLARED_ONLY)
                        foreground = UIManager.getColor("Label.disabledForeground")
                            ?: UIManager.getColor("Label.foreground")
                    if (node.field.status == SurfaceStatus.DECLARED_AND_REQUESTED)
                        font = font.deriveFont(Font.BOLD)
                }
                border = BorderFactory.createEmptyBorder(4, 8, 4, 6)
                return component
            }
        }
        tree.addTreeSelectionListener { showSelection() }
        detail.text = "Schema declarations and observed requests will appear here.\n\n" +
            "Declared does not mean accessible. Requested does not mean executed."
        changes.text = "Run a permitted introspection query to record a schema snapshot."
        tabs.addTab("Field details", JScrollPane(detail))
        tabs.addTab("Schema changes", JScrollPane(changes))
        val builderPanel = JPanel(BorderLayout(8, 8))
        builderPanel.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        val builderControls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        builderControls.add(copyDraft)
        builderControls.add(builderNotes)
        builderPanel.add(builderControls, BorderLayout.NORTH)
        builderPanel.add(JScrollPane(builderText), BorderLayout.CENTER)
        copyDraft.addActionListener {
            generatedDraft?.let { draft ->
                try {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection(draft.document), null
                    )
                    builderNotes.text = "Query copied; no HTTP request sent."
                } catch (_: Exception) {
                    builderNotes.text = "Clipboard unavailable."
                }
            }
        }
        tabs.addTab("Query builder", builderPanel)
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
                        val child = DefaultMutableTreeNode(TypeFieldNode(
                            type.name, field, type.signatures.firstOrNull { it.name == field }
                        ))
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
            updateDraft(null)
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
                updateDraft(QueryDraftBuilder.build(value.group, field.signature))
                detail.text = buildString {
                    appendLine(value.group + "  /  " + field.name)
                    appendLine()
                    appendLine("Evidence: " + value.toString().substringAfter("·").trim())
                    appendLine("Captured requests: " + field.requests)
                    appendLine()
                    field.signature?.let { signature ->
                        appendLine("DECLARED GRAPHQL SIGNATURE")
                        appendLine(signature.display)
                        appendLine("Return type: " + (signature.type?.display ?: "not reported"))
                        if (signature.args.isNotEmpty()) {
                            appendLine()
                            appendLine("ARGUMENTS")
                            signature.args.forEach { arg ->
                                appendLine(arg.name + " : " + arg.type.display +
                                    if (arg.required) "     Required" else "     Optional / default")
                            }
                        }
                        appendLine()
                    }
                    appendLine(when (field.status) {
                        SurfaceStatus.DECLARED_AND_REQUESTED ->
                            "Declared by server and requested in captured traffic."
                        SurfaceStatus.DECLARED_ONLY ->
                            "Declared by server, not requested in this capture window."
                        SurfaceStatus.REQUESTED_ONLY ->
                            "Requested in traffic, not matched to this schema snapshot."
                        SurfaceStatus.META_FIELD_REQUESTED ->
                            "Implicit GraphQL meta-field, not an ordinary schema member."
                    })
                    appendLine()
                    appendLine("No execution, successful access or authorization is implied.")
                }
            }
            is TypeNode -> {
                updateDraft(null)
                detail.text = buildString {
                    appendLine("SCHEMA TYPE")
                    appendLine(value.type.name + "  [" + value.type.kind + "]")
                    appendLine("Declared members: " + value.type.fields.size)
                    appendLine()
                    appendLine("Members are declarations, not observed access claims.")
                }
            }
            is TypeFieldNode -> {
                updateDraft(null)
                detail.text = buildString {
                    appendLine("SCHEMA MEMBER")
                    appendLine(value.type + "." + value.field)
                    value.signature?.let { signature ->
                        appendLine()
                        appendLine(signature.display)
                        appendLine("Return type: " + (signature.type?.display ?: "unavailable"))
                        if (signature.args.isNotEmpty()) {
                            appendLine()
                            appendLine("ARGUMENTS")
                            signature.args.forEach { arg ->
                                appendLine(arg.name + ": " + arg.type.display +
                                    if (arg.required) "     Required" else "     Optional / default")
                            }
                        }
                    }
                    appendLine()
                    appendLine("Declared only. Nested execution is not inferred by name.")
                }
            }
            else -> {
                updateDraft(null)
                detail.text = value?.toString() ?: ""
            }
        }
        detail.caretPosition = 0
    }

    private fun updateDraft(draft: QueryDraftBuilder.Draft?) {
        generatedDraft = draft
        copyDraft.isEnabled = draft != null
        builderNotes.text = if (draft == null)
            "QUERY roots with complete signatures only — no execution"
        else "Generated locally · no request sent"
        builderText.text = if (draft == null) {
            "Select a QUERY root with a known return type.\n" +
                "Mutations are intentionally excluded from generation."
        } else buildString {
            appendLine(draft.document)
            appendLine()
            appendLine("# " + draft.note)
            for (variable in draft.requiredVariables) {
                appendLine("# Required variable: " + variable.name + ": " + variable.type +
                    " (value not generated)")
            }
        }
        builderText.caretPosition = 0
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
