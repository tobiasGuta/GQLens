package dev.gqlens.ui

import dev.gqlens.core.ApiSurfaceStore
import dev.gqlens.core.EvidenceEvent
import dev.gqlens.core.HistoryImportReport
import dev.gqlens.core.InvestigationWorkspace
import dev.gqlens.core.ObservationStore
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.table.DefaultTableModel

/** No active tests: user-owned research cases, human-labeled contexts and evidence. */
class BountyWorkspacePanel(
    private val research: InvestigationWorkspace,
    private val observations: ObservationStore,
    private val schemas: ApiSurfaceStore,
    private val importHistory: (Int) -> HistoryImportReport,
    private val openEvidence: (EvidenceEvent) -> Unit
) : JPanel(BorderLayout()) {
    private val tabs = JTabbedPane()
    private val candidatesModel = object : DefaultTableModel(
        arrayOf("Signal", "Operation", "Field", "Evidence"), 0
    ) { override fun isCellEditable(row: Int, col: Int) = false }
    private val candidateTable = JTable(candidatesModel).apply {
        autoCreateRowSorter = true
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rowHeight = (font.size * 2).coerceAtLeast(24)
    }
    private val candidateDetail = JTextArea(6, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
    }
    private val caseModel = javax.swing.DefaultListModel<InvestigationWorkspace.Case>()
    private val casesList = JList(caseModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val caseTitle = JTextField(20)
    private val caseNotes = JTextArea(6, 25).apply { lineWrap = true; wrapStyleWord = true }
    private val caseStatus = JComboBox(InvestigationWorkspace.Status.entries.toTypedArray())
    private val caseContext = JComboBox<InvestigationWorkspace.Context>()
    private val evidenceInCase = JTextArea(3, 25).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val contextForCapture = JComboBox<InvestigationWorkspace.Context>()
    private val candidateStatus = JLabel("Candidates are observations, not vulnerabilities.")
    private val historyStatus = JTextArea(5, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val importButton = JButton("Import Proxy history (read-only)")
    private val cancelImport = JButton("Cancel").apply { isEnabled = false }
    private val importLimit = JComboBox(arrayOf(100, 250, 500, 1000))
    private var activeWorker: SwingWorker<HistoryImportReport, Unit>? = null
    private val importRunning = AtomicBoolean(false)
    private var shownCandidates = emptyList<InvestigationWorkspace.Candidate>()

    init {
        casesList.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                val item = value as? InvestigationWorkspace.Case
                return super.getListCellRendererComponent(
                    list, if (item != null) item.title + "  [" + item.status + "]" else value,
                    index, isSelected, cellHasFocus
                )
            }
        }
        val contextRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component = super.getListCellRendererComponent(
                list, (value as? InvestigationWorkspace.Context)?.label ?: "No label",
                index, isSelected, cellHasFocus
            )
        }
        contextForCapture.renderer = contextRenderer
        caseContext.renderer = contextRenderer
        tabs.addTab("Investigation queue", createQueue())
        tabs.addTab("Cases & contexts", createCases())
        tabs.addTab("History import", createHistory())
        add(tabs, BorderLayout.CENTER)
        refreshContexts()
        refreshCases()
        refresh()
    }

    private fun createQueue(): JPanel {
        val panel = JPanel(BorderLayout(6, 6))
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val refreshButton = JButton("Refresh candidates")
        refreshButton.addActionListener { refresh() }
        val open = JButton("Show source evidence")
        open.addActionListener {
            selectedCandidate()?.evidenceIds?.firstOrNull()
                ?.let(observations::evidenceById)?.let(openEvidence)
        }
        val attach = JButton("Attach evidence to selected case")
        attach.addActionListener {
            val candidate = selectedCandidate() ?: return@addActionListener
            val selectedCase = casesList.selectedValue
            val event = candidate.evidenceIds.firstNotNullOfOrNull(observations::evidenceById)
            if (selectedCase == null || event == null) {
                candidateStatus.text = "Select a case on Cases & contexts and an available candidate."
                return@addActionListener
            }
            if (research.attachEvidence(selectedCase.id, event)) {
                candidateStatus.text = "Attached metadata reference to case (no raw HTTP stored)."
                updateCaseView()
            } else candidateStatus.text = "Case limit reached or case is unavailable."
        }
        val label = JButton("Label selected evidence context")
        label.addActionListener {
            val candidate = selectedCandidate() ?: return@addActionListener
            val event = candidate.evidenceIds.firstNotNullOfOrNull(observations::evidenceById)
            val context = contextForCapture.selectedItem as? InvestigationWorkspace.Context
            if (event != null && context != null && research.labelEvidence(event.id, context.id)) {
                candidateStatus.text = "Manually assigned context: " + context.label
            } else candidateStatus.text = "Create/select a context and a candidate with retained evidence."
        }
        controls.add(refreshButton)
        controls.add(open)
        controls.add(attach)
        controls.add(contextForCapture)
        controls.add(label)
        panel.add(controls, BorderLayout.NORTH)
        candidateTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) showCandidate()
        }
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT,
            JScrollPane(candidateTable), JScrollPane(candidateDetail))
        split.resizeWeight = 0.67
        panel.add(split, BorderLayout.CENTER)
        panel.add(candidateStatus, BorderLayout.SOUTH)
        return panel
    }

    private fun createCases(): JPanel {
        val panel = JPanel(BorderLayout(8, 8))
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val newCase = JButton("New case")
        newCase.addActionListener {
            val title = JOptionPane.showInputDialog(this, "Investigation case title:") ?: return@addActionListener
            val created = research.addCase(title)
            if (created == null) {
                JOptionPane.showMessageDialog(this, "Case name blank or capacity reached.")
            }
            refreshCases(created?.id)
        }
        val newContext = JButton("New test context")
        newContext.addActionListener {
            val label = JOptionPane.showInputDialog(this,
                "Label for an authorized, researcher-controlled test context:") ?: return@addActionListener
            if (research.addContext(label) == null) {
                JOptionPane.showMessageDialog(this, "Duplicate/blank label or context limit reached.")
            }
            refreshContexts()
        }
        val save = JButton("Save case details")
        save.addActionListener {
            val item = casesList.selectedValue ?: return@addActionListener
            val contexts = (caseContext.selectedItem as? InvestigationWorkspace.Context)
                ?.let { setOf(it.id) } ?: emptySet()
            if (!research.updateCase(item.id, caseTitle.text, caseNotes.text,
                    caseStatus.selectedItem as InvestigationWorkspace.Status, contexts)) {
                JOptionPane.showMessageDialog(this, "Title, notes or context is invalid. Notes limit: 3,000 characters.")
            }
            refreshCases(item.id)
        }
        controls.add(newCase)
        controls.add(newContext)
        controls.add(save)
        panel.add(controls, BorderLayout.NORTH)

        val fields = JPanel(BorderLayout(6, 6))
        val metadata = JPanel(GridLayout(3, 2, 8, 6))
        metadata.add(JLabel("Case title"))
        metadata.add(caseTitle)
        metadata.add(JLabel("Verification state"))
        metadata.add(caseStatus)
        metadata.add(JLabel("Manually labeled context"))
        metadata.add(caseContext)
        fields.add(metadata, BorderLayout.NORTH)
        val notesPanel = JPanel(BorderLayout())
        notesPanel.add(JLabel("Research notes (session-local; avoid secrets)"), BorderLayout.NORTH)
        notesPanel.add(JScrollPane(caseNotes), BorderLayout.CENTER)
        notesPanel.add(JScrollPane(evidenceInCase), BorderLayout.SOUTH)
        fields.add(notesPanel, BorderLayout.CENTER)
        casesList.addListSelectionListener { if (!it.valueIsAdjusting) updateCaseView() }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(casesList), fields)
        split.resizeWeight = 0.32
        panel.add(split, BorderLayout.CENTER)
        panel.add(JLabel("Statuses are researcher assertions, never auto-generated findings."),
            BorderLayout.SOUTH)
        return panel
    }

    private fun createHistory(): JPanel {
        val panel = JPanel(BorderLayout(8, 8))
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        controls.add(JLabel("Max in-scope history entries"))
        controls.add(importLimit)
        controls.add(importButton)
        controls.add(cancelImport)
        importButton.addActionListener { startImport() }
        cancelImport.addActionListener {
            activeWorker?.cancel(true)
            historyStatus.text = "Cancellation requested; import may have partially added metadata."
        }
        panel.add(controls, BorderLayout.NORTH)
        historyStatus.text = "Manual import reads existing Burp Proxy history.\n" +
            "Only in-scope, bounded GraphQL metadata enters GQLens.\n" +
            "No requests are sent; request/response bodies are not retained.\n" +
            "Previously imported message IDs are skipped.\n" +
            "Only currently loaded Burp Proxy history can be imported."
        panel.add(JScrollPane(historyStatus), BorderLayout.CENTER)
        return panel
    }

    private fun startImport() {
        if (!importRunning.compareAndSet(false, true)) return
        val limit = importLimit.selectedItem as? Int ?: 250
        importButton.isEnabled = false
        cancelImport.isEnabled = true
        historyStatus.text = "Reading a maximum of " + limit + " in-scope entries from existing Proxy history."
        val worker = object : SwingWorker<HistoryImportReport, Unit>() {
            override fun doInBackground(): HistoryImportReport = try {
                importHistory(limit)
            } finally {
                importRunning.set(false)
                SwingUtilities.invokeLater {
                    if (activeWorker == null) {
                        importButton.isEnabled = true
                        cancelImport.isEnabled = false
                    }
                }
            }
            override fun done() {
                activeWorker = null
                importButton.isEnabled = !importRunning.get()
                cancelImport.isEnabled = importRunning.get()
                historyStatus.text = if (isCancelled) {
                    "Import cancelled; already accepted metadata may remain in this session."
                } else try {
                    val result = get()
                    "History import complete. No HTTP requests sent.\n" +
                        "Inspected: " + result.inspected +
                        " · Exchanges imported: " + result.importedExchanges +
                        " · Operations: " + result.importedOperations + "\n" +
                        "Duplicate: " + result.skippedDuplicates +
                        " · Out of scope: " + result.skippedOutOfScope +
                        " · Not GraphQL: " + result.skippedUnsupported +
                        " · Too large: " + result.skippedOversized +
                        " · Inventory rejected: " + result.rejectedByStore
                } catch (_: Exception) { "History import failed; no requests were transmitted." }
                refresh()
            }
        }
        activeWorker = worker
        worker.execute()
    }

    private fun selectedCandidate(): InvestigationWorkspace.Candidate? {
        val view = candidateTable.selectedRow
        return if (view < 0) null else shownCandidates.getOrNull(candidateTable.convertRowIndexToModel(view))
    }

    private fun showCandidate() {
        val item = selectedCandidate()
        candidateDetail.text = if (item == null) "Select a candidate to inspect its evidence and verification gaps."
        else "Operation: " + item.operation +
            "\nEndpoint: " + item.endpoint +
            "\nField: " + (item.field ?: "(operation response)") +
            "\nObserved: " + item.why +
            "\nMissing evidence: " + item.missingProof +
            "\nRetained evidence IDs: " + item.evidenceIds.joinToString(", ") +
            "\nContext (manual): " + (item.evidenceIds.firstOrNull()
                ?.let(research::contextForEvidence)?.label ?: "unlabeled") +
            "\n\nThis is NOT a vulnerability finding."
        candidateDetail.caretPosition = 0
    }

    private fun refreshContexts() {
        val selectedId = (contextForCapture.selectedItem as? InvestigationWorkspace.Context)?.id
        val caseId = (caseContext.selectedItem as? InvestigationWorkspace.Context)?.id
        contextForCapture.removeAllItems()
        caseContext.removeAllItems()
        for (item in research.contexts()) {
            contextForCapture.addItem(item)
            caseContext.addItem(item)
        }
        (0 until contextForCapture.itemCount).firstOrNull {
            contextForCapture.getItemAt(it).id == selectedId
        }?.let { contextForCapture.selectedIndex = it }
        (0 until caseContext.itemCount).firstOrNull {
            caseContext.getItemAt(it).id == caseId
        }?.let { caseContext.selectedIndex = it }
    }

    private fun refreshCases(target: Long? = casesList.selectedValue?.id) {
        caseModel.clear()
        research.cases().forEach { caseModel.addElement(it) }
        val index = (0 until caseModel.size()).firstOrNull { caseModel.get(it).id == target }
        if (index != null) casesList.selectedIndex = index
    }

    private fun updateCaseView() {
        val item = casesList.selectedValue
        caseTitle.text = item?.title.orEmpty()
        caseNotes.text = item?.notes.orEmpty()
        caseStatus.selectedItem = item?.status ?: InvestigationWorkspace.Status.INVESTIGATING
        val targetContext = item?.contextIds?.firstOrNull()
        (0 until caseContext.itemCount).firstOrNull {
            caseContext.getItemAt(it).id == targetContext
        }?.let { caseContext.selectedIndex = it }
        evidenceInCase.text = if (item == null) "" else
            "Evidence references: " + item.evidenceIds.joinToString(", ") +
                "\nOnly session-local capture IDs are retained."
    }

    fun isImporting(): Boolean = importRunning.get()

    /** Called only after an explicit session Clear. Never reset the user's notes on traffic refresh. */
    fun resetSession() {
        refreshContexts()
        refreshCases(null)
        caseTitle.text = ""
        caseNotes.text = ""
        evidenceInCase.text = ""
        refresh()
    }

    fun refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { refresh() }
            return
        }
        val rows = observations.snapshot()
        val evidence = observations.evidenceSnapshot()
        val candidates = research.candidates(rows, evidence,
            { endpoint -> schemas.report(endpoint, rows) })
        val selectedKey = selectedCandidate()?.key
        shownCandidates = candidates
        candidatesModel.rowCount = 0
        for (item in candidates) {
            candidatesModel.addRow(arrayOf(item.kind.name.replace('_', ' '),
                item.operation, item.field ?: "—", item.evidenceIds.size))
        }
        val index = candidates.indexOfFirst { it.key == selectedKey }
        if (index >= 0) {
            val view = candidateTable.convertRowIndexToView(index)
            if (view >= 0) candidateTable.setRowSelectionInterval(view, view)
        }
        candidateStatus.text = candidates.size.toString() +
            " investigation candidates based on retained evidence — no vulnerability claims."
        showCandidate()
    }
}
