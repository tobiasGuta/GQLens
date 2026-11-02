package dev.gqlens.ui

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gqlens.core.EvidenceEvent
import dev.gqlens.core.ObservationRow
import dev.gqlens.core.ObservationStore
import dev.gqlens.core.SchemaRecon
import dev.gqlens.core.ApiSurfaceStore
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableColumn
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Endpoint-first investigation workspace. The UI and export contain metadata only.
 * Swing state is modified on the EDT; capture handlers use store snapshots.
 */
class GqlensPanel(
    private val store: ObservationStore,
    private val surfaceStore: ApiSurfaceStore = ApiSurfaceStore(),
    private val openEvidence: (EvidenceEvent) -> String = { "Opening captured evidence is unavailable." },
    private val runRecon: (String, SchemaRecon.Mode) -> SchemaRecon.Result =
        { _, mode -> SchemaRecon.Result(mode, null, "RECON_NOT_CONFIGURED") }
) : JPanel(BorderLayout()) {
    private data class OperationNode(val row: ObservationRow) {
        override fun toString(): String =
            row.kind + "  " + row.operation + "  [" + row.fingerprint.take(8) + "]"
    }

    private val prefs = Preferences.userNodeForPackage(GqlensPanel::class.java)
    private val capture = AtomicBoolean(true)
    private val pendingRefresh = AtomicBoolean(false)
    private val search = JTextField(22)
    private val typeFilter = JComboBox<String>()
    private val toolFilter = JComboBox<String>()
    private val resultFilter = JComboBox<String>()
    private var updatingFilters = false

    private val countLabel = JLabel("No observations")
    private val showSelfRecon = JCheckBox("Show GQLens recon", false).apply {
        toolTipText = "Keep extension-generated introspection separate from application traffic."
    }
    private val notice = JLabel("Read-only capture; evidence references are local to this Burp session.")
    private val root = DefaultMutableTreeNode("Endpoints")
    private val operationTree = JTree(DefaultTreeModel(root))
    private val fieldTree = JTree(DefaultTreeModel(DefaultMutableTreeNode("Requested fields")))
    private val summary = JTextArea(6, 50)
    private val summaryScroll = JScrollPane(summary)
    private val compactMode = JCheckBox("Compact view", prefs.getBoolean("compactView", false))
    private val requestsMetric = JLabel("Requests  0")
    private val responsesMetric = JLabel("Responses  0")
    private val statusMetric = JLabel("Last result  -")
    private val overview = JPanel(BorderLayout()).apply {
        val metrics = JPanel(GridLayout(1, 3, 8, 0))
        metrics.add(requestsMetric)
        metrics.add(responsesMetric)
        metrics.add(statusMetric)
        add(metrics, BorderLayout.NORTH)
        add(summaryScroll, BorderLayout.CENTER)
    }
    private val responseDetail = JTextArea(12, 50)
    private val evidenceDetail = JTextArea(3, 50)
    private val evidenceModel = object : DefaultTableModel(
        arrayOf("Captured (UTC)", "Tool", "Message ID", "HTTP", "Result", "Batch"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val evidenceTable = JTable(evidenceModel)
    private lateinit var savedColumns: List<TableColumn>
    private val openButton = JButton("Open selected Proxy capture in Repeater")
    private val copyButton = JButton("Copy evidence reference")
    private val tabbed = JTabbedPane()
    private val surfacePanel = ApiSurfacePanel(
        surfaceStore,
        { store.snapshot() },
        { endpoint, kind, field -> store.evidenceForField(endpoint, kind, field) },
        { event -> jumpToEvidence(event) }
    )
    private val reconPanel = ReconPanel(
        { selectedOperation?.endpoint }, runRecon,
        { endpoint, result ->
            surfaceStore.record(endpoint, result)
            surfacePanel.refresh()
        }
    )
    private val workspace: JSplitPane
    private val detailsSplit: JSplitPane
    private var selectedOperation: ObservationRow? = null
    private var selectedEvidence: EvidenceEvent? = null
    private var visibleEvidence = emptyList<EvidenceEvent>()
    private val refreshTimer = Timer(200) {
        pendingRefresh.set(false)
        refresh()
    }.apply { isRepeats = false }

    val captureEnabled: Boolean get() = capture.get()

    init {
        val top = JPanel(GridLayout(2, 1))
        val filters = JPanel(FlowLayout(FlowLayout.LEFT))
        val captureCheck = JCheckBox("Capture new in-scope requests", true)
        captureCheck.addActionListener { capture.set(captureCheck.isSelected) }
        filters.add(captureCheck)
        filters.add(showSelfRecon)
        showSelfRecon.addActionListener { refresh() }
        filters.add(compactMode)
        compactMode.addActionListener {
            prefs.putBoolean("compactView", compactMode.isSelected)
            summaryScroll.isVisible = !compactMode.isSelected
            overview.revalidate()
            overview.repaint()
            detailsSplit.dividerLocation = if (compactMode.isSelected) 68
                else prefs.getInt("detailDivider", 190)
        }
        summaryScroll.isVisible = !compactMode.isSelected
        filters.add(JLabel("Search:"))
        filters.add(search)
        filters.add(JLabel("Type:"))
        filters.add(typeFilter)
        filters.add(JLabel("Tool:"))
        filters.add(toolFilter)
        filters.add(JLabel("Result:"))
        filters.add(resultFilter)
        top.add(filters)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT))
        actions.add(countLabel)
        val export = JButton("Export metadata JSON")
        export.addActionListener { exportMetadata() }
        val columns = JButton("Evidence columns")
        columns.addActionListener { showColumnMenu(columns) }
        val reset = JButton("Reset layout")
        reset.addActionListener { resetLayout() }
        val clear = JButton("Clear")
        clear.addActionListener {
            if (JOptionPane.showConfirmDialog(
                    this, "Clear all observations and evidence in this GQLens session?",
                    "Clear", JOptionPane.YES_NO_OPTION
                ) == JOptionPane.YES_OPTION) {
                store.clear()
                surfaceStore.clear()
                selectedOperation = null
                selectedEvidence = null
                refresh()
            }
        }
        actions.add(export)
        actions.add(columns)
        actions.add(reset)
        actions.add(clear)
        top.add(actions)
        add(top, BorderLayout.NORTH)

        operationTree.isRootVisible = false
        operationTree.showsRootHandles = true
        operationTree.selectionModel.selectionMode =
            javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        operationTree.addTreeSelectionListener {
            val node = operationTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val row = (node?.userObject as? OperationNode)?.row
            if (row != null) selectOperation(row)
        }
        operationTree.toolTipText = "Select a parsed operation. Entries are observations, not findings."

        fieldTree.isRootVisible = false
        fieldTree.showsRootHandles = true
        summary.isEditable = false
        summary.lineWrap = true
        summary.wrapStyleWord = true
        responseDetail.isEditable = false
        responseDetail.lineWrap = true
        responseDetail.wrapStyleWord = true
        evidenceDetail.isEditable = false
        evidenceDetail.lineWrap = true

        evidenceTable.autoCreateRowSorter = true
        evidenceTable.selectionModel.selectionMode =
            javax.swing.ListSelectionModel.SINGLE_SELECTION
        savedColumns = (0 until evidenceTable.columnModel.columnCount)
            .map { evidenceTable.columnModel.getColumn(it) }
        savedColumns.forEachIndexed { index, column ->
            if (!prefs.getBoolean("showEvidenceColumn" + index, true)) {
                evidenceTable.columnModel.removeColumn(column)
            }
        }
        evidenceTable.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) selectEvidenceFromTable()
        }

        openButton.isEnabled = false
        openButton.toolTipText = "Only original Proxy history can currently be resolved; Repeater captures remain identifiable by ID."
        openButton.addActionListener { openSelectedEvidence() }
        copyButton.isEnabled = false
        copyButton.addActionListener { copyEvidenceReference() }
        val evidenceActions = JPanel(FlowLayout(FlowLayout.LEFT))
        evidenceActions.add(openButton)
        evidenceActions.add(copyButton)
        val evidencePanel = JPanel(BorderLayout())
        evidencePanel.add(evidenceActions, BorderLayout.NORTH)
        val evidenceBottom = JScrollPane(evidenceDetail)
        val evidenceSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT, JScrollPane(evidenceTable), evidenceBottom
        )
        evidenceSplit.resizeWeight = 0.78
        evidenceSplit.dividerLocation = prefs.getInt("evidenceDivider", 240)
        evidenceSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            prefs.putInt("evidenceDivider", evidenceSplit.dividerLocation)
        }
        evidencePanel.add(evidenceSplit, BorderLayout.CENTER)

        tabbed.addTab("API Surface", surfacePanel)
        tabbed.addTab("Fields", JScrollPane(fieldTree))
        tabbed.addTab("Evidence history", evidencePanel)
        tabbed.addTab("Response", JScrollPane(responseDetail))
        tabbed.addTab("Recon (opt-in)", reconPanel)

        detailsSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, overview, tabbed)
        detailsSplit.resizeWeight = 0.26
        detailsSplit.dividerLocation = if (compactMode.isSelected) 68
            else prefs.getInt("detailDivider", 190)
        detailsSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (!compactMode.isSelected) prefs.putInt("detailDivider", detailsSplit.dividerLocation)
        }
        workspace = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, JScrollPane(operationTree), detailsSplit
        )
        workspace.resizeWeight = 0.30
        workspace.dividerLocation = prefs.getInt("workspaceDivider", 330)
        workspace.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            prefs.putInt("workspaceDivider", workspace.dividerLocation)
        }
        add(workspace, BorderLayout.CENTER)

        val footer = JPanel(GridLayout(2, 1))
        footer.add(notice)
        footer.add(JLabel("Passive by default | Recon is opt-in, in-scope and one request per confirmation | No auto-send"))
        add(footer, BorderLayout.SOUTH)

        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        listOf(typeFilter, toolFilter, resultFilter).forEach {
            it.addActionListener { if (!updatingFilters) refresh() }
        }
        refresh()
    }

    /** Coalesces high-frequency events into a bounded Swing refresh rate. */
    fun scheduleRefresh() {
        if (pendingRefresh.compareAndSet(false, true)) {
            SwingUtilities.invokeLater { refreshTimer.restart() }
        }
    }

    private fun filtersFor(snapshot: List<ObservationRow>) {
        updatingFilters = true
        try {
            updateOptions(typeFilter, "All types", snapshot.map { it.kind })
            updateOptions(toolFilter, "All tools", snapshot.map { it.lastTool })
            updateOptions(resultFilter, "All results", snapshot.map { it.lastResponseShape })
        } finally {
            updatingFilters = false
        }
    }

    private fun updateOptions(combo: JComboBox<String>, all: String, options: List<String>) {
        val selected = combo.selectedItem as? String ?: all
        val items = (listOf(all) + options.filter { it.isNotBlank() }.distinct().sorted())
        if ((0 until combo.itemCount).map { combo.getItemAt(it) } != items) {
            combo.removeAllItems()
            items.forEach { combo.addItem(it) }
        }
        combo.selectedItem = if (selected in items) selected else all
    }

    private fun refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { refresh() }
            return
        }
        val snapshot = store.snapshot()
        filtersFor(snapshot)
        val needle = search.text.trim()
        val kind = typeFilter.selectedItem as? String ?: "All types"
        val tool = toolFilter.selectedItem as? String ?: "All tools"
        val result = resultFilter.selectedItem as? String ?: "All results"
        val filtered = snapshot.filter { row ->
            (showSelfRecon.isSelected || row.regularSeen > 0) &&
            (kind == "All types" || row.kind == kind) &&
                (tool == "All tools" || row.lastTool == tool) &&
                (result == "All results" || row.lastResponseShape == result) &&
                (needle.isBlank() || row.endpoint.contains(needle, true) ||
                    row.kind.contains(needle, true) ||
                    row.operation.contains(needle, true) ||
                    row.fieldPaths.any { it.contains(needle, true) })
        }
        countLabel.text = filtered.size.toString() + " visible / " +
            snapshot.size + " total operations / " + store.rejectedCount() + " rejected"
        val selectionKey = selectedOperation?.let {
            listOf(it.endpoint, it.kind, it.operation, it.fingerprint)
        }
        root.removeAllChildren()
        var nextPath: TreePath? = null
        // Host > path > observed operation. Labels are derived from actual endpoints,
        // never from guesses about hostnames or conventional /graphql locations.
        for ((host, hostRows) in filtered.groupBy { row ->
            try { URI(row.endpoint).authority ?: row.endpoint } catch (_: Exception) { row.endpoint }
        }.toSortedMap()) {
            val hostNode = DefaultMutableTreeNode(host)
            root.add(hostNode)
            for ((endpoint, rows) in hostRows.groupBy { it.endpoint }.toSortedMap()) {
                val pathLabel = try { URI(endpoint).rawPath?.ifEmpty { "/" } ?: "/" }
                    catch (_: Exception) { endpoint }
                val endpointNode = DefaultMutableTreeNode(pathLabel)
                hostNode.add(endpointNode)
                for (row in rows.sortedWith(compareBy({ it.kind }, { it.operation }, { it.fingerprint }))) {
                    val node = DefaultMutableTreeNode(OperationNode(row))
                    endpointNode.add(node)
                    if (listOf(row.endpoint, row.kind, row.operation, row.fingerprint) == selectionKey) {
                        nextPath = TreePath(node.path)
                    }
                }
            }
        }
        (operationTree.model as DefaultTreeModel).reload()
        for (i in 0 until minOf(operationTree.rowCount, 80)) operationTree.expandRow(i)
        if (nextPath != null) {
            operationTree.selectionPath = nextPath
        } else if (selectionKey == null && root.childCount > 0) {
            val host = root.getChildAt(0) as DefaultMutableTreeNode
            val endpoint = host.getChildAt(0) as? DefaultMutableTreeNode
            val operation = endpoint?.getChildAt(0) as? DefaultMutableTreeNode
            if (operation != null) operationTree.selectionPath = TreePath(operation.path)
        } else {
            selectedOperation = null
            selectedEvidence = null
            showEmptyDetails()
        }
        if (nextPath != null) {
            val row = (nextPath.lastPathComponent as DefaultMutableTreeNode)
                .userObject as OperationNode
            selectOperation(row.row)
        }
        surfacePanel.refresh()
        notice.text = "Evidence retained: latest 3,000 captures; " +
            store.evictedEvidenceCount() + " older records evicted. Burp handles raw HTTP independently."
    }

    private fun selectOperation(row: ObservationRow) {
        selectedOperation = row
        val latest = store.snapshot().firstOrNull {
            it.endpoint == row.endpoint && it.kind == row.kind &&
                it.operation == row.operation && it.fingerprint == row.fingerprint
        } ?: row
        selectedOperation = latest
        surfacePanel.selectEndpoint(latest.endpoint)
        reconPanel.setEndpoint(latest.endpoint)
        requestsMetric.text = "Requests  " + latest.seen
        responsesMetric.text = "Responses  " + latest.responsesSeen
        statusMetric.text = "Last result  " + (latest.lastHttpStatus ?: "-") + " / " + latest.lastResponseShape
        summary.text = buildString {
            appendLine("Endpoint: " + latest.endpoint)
            appendLine("Requested: " + latest.kind + "  " + latest.operation)
            appendLine("Document SHA-256 prefix: " + latest.fingerprint)
            appendLine("Requests: " + latest.seen + "   Correlated responses: " + latest.responsesSeen)
            appendLine("First: " + formatTime(latest.firstSeenMillis))
            appendLine("Last:  " + formatTime(latest.lastSeenMillis))
            appendLine("Last tool / message: " + latest.lastTool + " / " + (latest.lastMessageId ?: "-"))
            appendLine("Last HTTP / response: " + (latest.lastHttpStatus ?: "-") +
                " / " + latest.lastResponseShape)
        }
        summary.caretPosition = 0
        populateFields(latest.fieldPaths)
        populateEvidence(latest)
    }

    private fun populateFields(paths: List<String>) {
        val rootFields = DefaultMutableTreeNode("Requested fields (not proof of execution)")
        for (path in paths) {
            var parent = rootFields
            for (segment in path.split('.')) {
                val match = (0 until parent.childCount)
                    .map { parent.getChildAt(it) as DefaultMutableTreeNode }
                    .firstOrNull { it.userObject == segment }
                val node = match ?: DefaultMutableTreeNode(segment).also { parent.add(it) }
                parent = node
            }
        }
        fieldTree.model = DefaultTreeModel(rootFields)
        for (i in 0 until minOf(fieldTree.rowCount, 150)) fieldTree.expandRow(i)
    }

    private fun populateEvidence(row: ObservationRow) {
        val previousId = selectedEvidence?.id
        visibleEvidence = store.evidenceFor(row)
        evidenceModel.rowCount = 0
        visibleEvidence.forEach { event ->
            evidenceModel.addRow(arrayOf(
                formatTime(event.observedAtMillis), event.tool, event.messageId,
                event.httpStatus ?: "-", event.responseShape, event.batchIndex
            ))
        }
        val index = visibleEvidence.indexOfFirst { it.id == previousId }
        if (index >= 0) {
            val view = evidenceTable.convertRowIndexToView(index)
            if (view >= 0) evidenceTable.selectionModel.setSelectionInterval(view, view)
        } else if (visibleEvidence.isNotEmpty()) {
            val view = evidenceTable.convertRowIndexToView(0)
            if (view >= 0) evidenceTable.selectionModel.setSelectionInterval(view, view)
        } else {
            selectedEvidence = null
            evidenceDetail.text = "No individual capture metadata available."
            openButton.isEnabled = false
            copyButton.isEnabled = false
            updateResponseDetail()
        }
    }

    /** Jump to one exact capture from API Surface without replaying any HTTP. */
    private fun jumpToEvidence(event: EvidenceEvent) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { jumpToEvidence(event) }
            return
        }
        val row = store.snapshot().firstOrNull {
            it.endpoint == event.endpoint && it.kind == event.kind &&
                it.operation == event.operation && it.fingerprint == event.fingerprint
        } ?: return
        search.text = ""
        typeFilter.selectedItem = "All types"
        toolFilter.selectedItem = "All tools"
        resultFilter.selectedItem = "All results"
        refresh()
        val operationRoot = operationTree.model.root as DefaultMutableTreeNode
        fun locate(node: DefaultMutableTreeNode): TreePath? {
            val model = node.userObject as? OperationNode
            if (model != null && model.row.endpoint == row.endpoint &&
                model.row.kind == row.kind && model.row.operation == row.operation &&
                model.row.fingerprint == row.fingerprint) return TreePath(node.path)
            for (i in 0 until node.childCount) {
                val path = locate(node.getChildAt(i) as DefaultMutableTreeNode)
                if (path != null) return path
            }
            return null
        }
        locate(operationRoot)?.let {
            operationTree.selectionPath = it
            operationTree.scrollPathToVisible(it)
        }
        selectOperation(row)
        tabbed.selectedIndex = 2 // existing Evidence history view
        val modelIndex = visibleEvidence.indexOfFirst { it.id == event.id }
        if (modelIndex >= 0) {
            val view = evidenceTable.convertRowIndexToView(modelIndex)
            evidenceTable.selectionModel.setSelectionInterval(view, view)
            evidenceTable.scrollRectToVisible(evidenceTable.getCellRect(view, 0, true))
        } else {
            notice.text = "Capture is outside the most recent per-operation evidence window."
        }
    }

    private fun selectEvidenceFromTable() {
        val viewIndex = evidenceTable.selectedRow
        val index = if (viewIndex < 0) -1 else evidenceTable.convertRowIndexToModel(viewIndex)
        selectedEvidence = visibleEvidence.getOrNull(index)
        val event = selectedEvidence
        openButton.isEnabled = event?.tool == "PROXY"
        copyButton.isEnabled = event != null
        evidenceDetail.text = if (event == null) "Select a captured exchange." else buildString {
            appendLine("Captured: " + formatTime(event.observedAtMillis))
            appendLine("Burp tool: " + event.tool + "   Message ID: " + event.messageId)
            appendLine("Batch index: " + event.batchIndex)
            appendLine("HTTP status: " + (event.httpStatus ?: "pending or unobserved"))
            appendLine("GraphQL response shape: " + event.responseShape)
            event.diagnostics?.let { diagnostic ->
                appendLine("Error objects (bounded): " + diagnostic.errors +
                    if (diagnostic.truncated) " [truncated]" else "")
                appendLine("Data key present: " + diagnostic.hasData)
                appendLine("Error locations present: " + diagnostic.hasLocations)
                appendLine("Error response paths present: " + diagnostic.hasPaths)
                appendLine("Server-provided error codes: " +
                    if (diagnostic.codes.isEmpty()) "not provided" else diagnostic.codes.joinToString(", "))
                appendLine("No error messages, response data or error paths are retained.")
            }
            if (event.tool != "PROXY") {
                appendLine("Existing Repeater tabs cannot be directly selected through the Montoya API.")
            }
        }
        evidenceDetail.caretPosition = 0
        updateResponseDetail()
    }

    private fun updateResponseDetail() {
        val row = selectedOperation
        val event = selectedEvidence
        responseDetail.text = if (row == null) {
            "Select an operation to inspect its response classification."
        } else buildString {
            appendLine("Aggregate last status: " + (row.lastHttpStatus ?: "not observed"))
            appendLine("Aggregate last result: " + row.lastResponseShape)
            appendLine("Matched responses: " + row.responsesSeen + " of " + row.seen + " requests")
            appendLine()
            appendLine("Recent HTTP statuses:")
            val statusCounts = visibleEvidence.groupingBy { it.httpStatus?.toString() ?: "unobserved" }.eachCount()
            for ((status, count) in statusCounts.toSortedMap()) appendLine("  $status: $count")
            appendLine("Recent GraphQL result categories:")
            val resultCounts = visibleEvidence.groupingBy { it.responseShape }.eachCount()
            for ((category, count) in resultCounts.toSortedMap()) appendLine("  $category: $count")
            if (row.seen > visibleEvidence.size) {
                appendLine("Showing the most recent ${visibleEvidence.size} evidence items; not all historical captures.")
            }
            appendLine()
            if (event != null) {
                appendLine("Selected capture #" + event.id + ": " + event.tool +
                    " message " + event.messageId)
                appendLine("HTTP status: " + (event.httpStatus ?: "not observed"))
                appendLine("Response structure: " + event.responseShape)
                event.diagnostics?.let { diagnostic ->
                    appendLine("Error objects (bounded): " + diagnostic.errors)
                    appendLine("Location metadata present: " + diagnostic.hasLocations)
                    appendLine("Path metadata present: " + diagnostic.hasPaths)
                    appendLine("Server error codes (opaque): " +
                        if (diagnostic.codes.isEmpty()) "none" else diagnostic.codes.joinToString(", "))
                    if (diagnostic.truncated) appendLine("Error metadata truncated by safety limit.")
                }
            }
            appendLine()
            appendLine("DATA and PARTIAL_DATA describe response structure only.")
            appendLine("An HTTP 200 is not proof of GraphQL success or authorization.")
            appendLine("GQLens never stores response values or GraphQL error messages.")
        }
        responseDetail.caretPosition = 0
    }

    private fun openSelectedEvidence() {
        val evidence = selectedEvidence ?: return
        if (evidence.tool != "PROXY") return
        openButton.isEnabled = false
        notice.text = "Resolving original Proxy request; no traffic will be sent..."
        Thread({
            val result = try { openEvidence(evidence) } catch (_: Exception) {
                "Unable to resolve Proxy evidence. No request was sent."
            }
            SwingUtilities.invokeLater {
                notice.text = result
                openButton.isEnabled = selectedEvidence?.tool == "PROXY"
            }
        }, "GQLens-evidence-navigation").apply { isDaemon = true; start() }
    }

    private fun copyEvidenceReference() {
        val event = selectedEvidence ?: return
        val ref = "GQLens evidence: " + event.endpoint + " | " + event.kind + " " +
            event.operation + " | " + event.tool + " message " + event.messageId +
            " | " + formatTime(event.observedAtMillis) + " | batch " + event.batchIndex
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ref), null)
            notice.text = "Copied session-local evidence reference; no raw HTTP copied."
        } catch (_: Exception) {
            notice.text = "Clipboard unavailable. Message ID: " + event.messageId
        }
    }

    private fun showColumnMenu(anchor: JButton) {
        val menu = JPopupMenu()
        savedColumns.forEachIndexed { index, column ->
            val visible = (0 until evidenceTable.columnModel.columnCount)
                .any { evidenceTable.columnModel.getColumn(it) === column }
            val item = javax.swing.JCheckBoxMenuItem(column.headerValue.toString(), visible)
            item.addActionListener {
                if (item.isSelected && !visible) evidenceTable.columnModel.addColumn(column)
                else if (!item.isSelected && visible) evidenceTable.columnModel.removeColumn(column)
                prefs.putBoolean("showEvidenceColumn" + index, item.isSelected)
            }
            menu.add(item)
        }
        menu.show(anchor, 0, anchor.height)
    }

    private fun resetLayout() {
        workspace.dividerLocation = 330
        detailsSplit.dividerLocation = 190
        savedColumns.forEachIndexed { index, column ->
            if ((0 until evidenceTable.columnModel.columnCount).none {
                    evidenceTable.columnModel.getColumn(it) === column
                }) evidenceTable.columnModel.addColumn(column)
            prefs.putBoolean("showEvidenceColumn" + index, true)
        }
        notice.text = "Layout reset. Divider positions and visible columns can be customized."
    }

    private fun showEmptyDetails() {
        summary.text = "Browse an in-scope GraphQL API to collect passive evidence."
        requestsMetric.text = "Requests  0"
        responsesMetric.text = "Responses  0"
        statusMetric.text = "Last result  -"
        reconPanel.setEndpoint(null)
        surfacePanel.selectEndpoint(null)
        fieldTree.model = DefaultTreeModel(DefaultMutableTreeNode("No selected operation"))
        evidenceModel.rowCount = 0
        visibleEvidence = emptyList()
        evidenceDetail.text = "No evidence selected."
        responseDetail.text = "No response selected."
        openButton.isEnabled = false
        copyButton.isEnabled = false
    }

    private fun exportMetadata() {
        val chooser = JFileChooser()
        chooser.selectedFile = java.io.File("gqlens-evidence-metadata.json")
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val destination = chooser.selectedFile
        if (destination.exists() && JOptionPane.showConfirmDialog(
                this, "Overwrite the selected file?", "Confirm overwrite",
                JOptionPane.YES_NO_OPTION
            ) != JOptionPane.YES_OPTION) return
        try {
            val rows = store.snapshot()
            val data = linkedMapOf(
                "formatVersion" to 2,
                "operations" to rows,
                "evidence" to rows.flatMap { store.evidenceFor(it, 1000) },
                "evictedEvidence" to store.evictedEvidenceCount()
            )
            val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data)
            Files.writeString(destination.toPath(), json, StandardCharsets.UTF_8)
            JOptionPane.showMessageDialog(this,
                "Exported metadata only. Endpoint paths and operation names may still be sensitive.")
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this, "Unable to export: " + e.message, "Error", JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun formatTime(epochMillis: Long): String =
        if (epochMillis <= 0) "-" else Instant.ofEpochMilli(epochMillis).toString()
}
