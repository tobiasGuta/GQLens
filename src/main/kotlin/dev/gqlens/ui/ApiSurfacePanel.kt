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
        override fun toString(): String = field.name
    }
    private data class TypeNode(val type: SurfaceType) {
        override fun toString(): String = type.name
    }
    private data class TypeFieldNode(
        val type: String, val field: String, val signature: SchemaRecon.FieldSignature?
    ) {
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
    private val detailsPanel = SchemaDetailsPanel()
    private val changes = textArea()
    private val builderPreview = CodePreviewPanel()
    private val builderFormat = JComboBox(arrayOf("GraphQL", "JSON request body"))
    private val builderNotes = JLabel("Local preview only · never sends requests")
    private val copyDraft = JButton("Copy GraphQL").apply { isEnabled = false }
    private val copyJson = JButton("Copy JSON body").apply { isEnabled = false }
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
                toolTipText = when (node) {
                    is FieldNode -> (node.field.signature?.display ?: node.field.name) +
                        " — " + node.field.status.name.replace('_', ' ')
                    is TypeFieldNode -> node.signature?.display ?: node.field
                    is TypeNode -> node.type.kind + " " + node.type.name
                    else -> null
                }
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
        detailsPanel.placeholder(
            "Declared does not mean accessible. Requested does not mean executed.")
        changes.text = "Run a permitted introspection query to record a schema snapshot."
        tabs.addTab("Field details", detailsPanel)
        tabs.addTab("Schema changes", JScrollPane(changes))
        val builderPanel = JPanel(BorderLayout(8, 8))
        builderPanel.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        val builderControls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        builderControls.add(JLabel("Preview"))
        builderControls.add(builderFormat)
        builderControls.add(copyDraft)
        builderControls.add(copyJson)
        builderPanel.add(builderControls, BorderLayout.NORTH)
        builderPanel.add(builderPreview, BorderLayout.CENTER)
        builderPanel.add(builderNotes, BorderLayout.SOUTH)
        builderFormat.addActionListener { renderDraft() }
        copyDraft.addActionListener { copyDraftToClipboard(false) }
        copyJson.addActionListener { copyDraftToClipboard(true) }
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
            ?.userObject?.let(::nodeKey)
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
                    if (nodeKey(node.userObject) == previousSelection) restore = TreePath(node.path)
                }
            }
            if (filter.selectedIndex == 0) {
                val typeBranch = DefaultMutableTreeNode("Schema types   (" + current.types.size + ")")
                for ((kind, typeSet) in current.types.groupBy { it.kind }.toSortedMap()) {
                    val byKind = DefaultMutableTreeNode(typeGroupLabel(kind) + "   (" + typeSet.size + ")")
                    for (type in typeSet.sortedBy { it.name }) {
                        val fields = type.fields.filter { it.contains(needle, true) }
                        if (needle.isNotBlank() &&
                            !type.name.contains(needle, true) && fields.isEmpty()) continue
                        val typeNode = DefaultMutableTreeNode(TypeNode(type))
                        byKind.add(typeNode)
                        for (field in if (needle.isNotBlank() && !type.name.contains(needle, true))
                            fields else type.fields) {
                            val child = DefaultMutableTreeNode(TypeFieldNode(
                                type.name, field, type.signatures.firstOrNull { it.name == field }
                            ))
                            typeNode.add(child)
                            if (nodeKey(child.userObject) == previousSelection) restore = TreePath(child.path)
                        }
                        if (nodeKey(typeNode.userObject) == previousSelection) restore = TreePath(typeNode.path)
                    }
                    if (byKind.childCount > 0) typeBranch.add(byKind)
                }
                if (typeBranch.childCount > 0) root.add(typeBranch)
            }
        }
        (tree.model as DefaultTreeModel).reload()
        // Expand the root operation groups, but leave large type categories
        // collapsed until chosen. Search expands matching types for quick discovery.
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            tree.expandPath(TreePath(child.path))
            if (search.text.isNotBlank() && child.userObject.toString().startsWith("Schema types")) {
                for (kindIndex in 0 until child.childCount) {
                    val group = child.getChildAt(kindIndex) as DefaultMutableTreeNode
                    tree.expandPath(TreePath(group.path))
                    for (typeIndex in 0 until minOf(group.childCount, 30)) {
                        val typeNode = group.getChildAt(typeIndex) as DefaultMutableTreeNode
                        tree.expandPath(TreePath(typeNode.path))
                    }
                }
            }
        }
        if (restore != null) tree.selectionPath = restore
        showChanges(current?.changes)
        if (tree.selectionPath == null) {
            updateDraft(null)
            detailsPanel.placeholder(
                "Requested selections may be skipped or rejected; declarations do not prove authorization.")
        }
    }

    private fun passesFilter(field: SurfaceField): Boolean = when (filter.selectedIndex) {
        1 -> field.status == SurfaceStatus.DECLARED_AND_REQUESTED
        2 -> field.status == SurfaceStatus.DECLARED_ONLY
        3 -> field.status == SurfaceStatus.REQUESTED_ONLY
        4 -> field.status == SurfaceStatus.META_FIELD_REQUESTED
        else -> true
    }

    private fun nodeKey(item: Any?): String = when (item) {
        is FieldNode -> "root:" + item.group + ":" + item.field.name
        is TypeNode -> "type:" + item.type.kind + ":" + item.type.name
        is TypeFieldNode -> "member:" + item.type + ":" + item.field
        else -> item.toString()
    }

    private fun typeGroupLabel(kind: String): String =
        kind.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + " types"

    private fun showSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        when (val value = node.userObject) {
            is FieldNode -> {
                detailsPanel.showRoot(value.group, value.field)
                updateDraft(QueryDraftBuilder.build(value.group, value.field.signature))
            }
            is TypeNode -> {
                detailsPanel.showType(value.type)
                updateDraft(null)
            }
            is TypeFieldNode -> {
                detailsPanel.showMember(value.type, value.field, value.signature)
                updateDraft(null)
            }
            else -> {
                detailsPanel.placeholder("Choose a root field or schema member to inspect its type.")
                updateDraft(null)
            }
        }
    }

    private fun updateDraft(draft: QueryDraftBuilder.Draft?) {
        generatedDraft = draft
        copyDraft.isEnabled = draft != null
        copyJson.isEnabled = draft != null
        renderDraft()
    }

    private fun renderDraft() {
        val draft = generatedDraft
        val json = builderFormat.selectedIndex == 1
        val display = if (draft == null)
            "Select a QUERY root with complete argument and return-type metadata.\n" +
                "No inputs or mutations are automatically generated."
        else if (json) draft.jsonBody() else draft.document
        builderPreview.showCode(display,
            if (json) CodePreviewPanel.Language.JSON else CodePreviewPanel.Language.GRAPHQL)
        builderNotes.text = when {
            draft == null -> "Incomplete schema fields cannot generate requests · no execution"
            draft.requiredVariables.isNotEmpty() ->
                "Required variables: " + draft.requiredVariables.joinToString(", ") {
                    it.name + ": " + it.type
                } + " — values must be supplied manually"
            else -> "Generated locally · no HTTP requests sent"
        }
    }

    private fun copyDraftToClipboard(asJson: Boolean) {
        val draft = generatedDraft ?: return
        val content = if (asJson) draft.jsonBody() else draft.document
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(content), null)
            builderNotes.text = (if (asJson) "JSON body" else "GraphQL document") +
                " copied · nothing was sent"
        } catch (_: Exception) {
            builderNotes.text = "Clipboard unavailable; preview remains readable"
        }
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
