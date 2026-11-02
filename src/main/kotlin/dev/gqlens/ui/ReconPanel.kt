package dev.gqlens.ui

import dev.gqlens.core.SchemaRecon
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.BorderFactory
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The only UI that starts an HTTP request. Requires a separate click + confirmation
 * for each single, in-scope introspection request. Disabled by default.
 */
class ReconPanel(
    private val selectedEndpoint: () -> String?,
    private val runSingleRecon: (String, SchemaRecon.Mode) -> SchemaRecon.Result,
    private val onResult: (String, SchemaRecon.Result) -> Unit = { _, _ -> }
) : JPanel(BorderLayout()) {
    private val busy = AtomicBoolean(false)
    private val endpoint = JLabel("Select an observed operation in the endpoint tree.")
    private val mode = JComboBox(SchemaRecon.Mode.entries.toTypedArray())
    private val launch = JButton("Send 1 introspection query...")
    private val previewButton = JButton("Preview query")
    private val resultTree = JTree(DefaultTreeModel(DefaultMutableTreeNode("No schema discovered")))
    private val summary = JTextArea(5, 48)
    private val controls = JPanel(FlowLayout(FlowLayout.LEFT))

    init {
        val header = JPanel(GridLayout(3, 1))
        header.add(endpoint)
        controls.add(JLabel("Discovery depth:"))
        controls.add(mode)
        controls.add(launch)
        controls.add(previewButton)
        previewButton.addActionListener { previewQuery() }
        header.add(controls)
        header.add(JLabel(
            "OFF by default · one request per confirmation · no credentials, redirects, mutation execution or field guessing"
        ))
        add(header, BorderLayout.NORTH)
        summary.isEditable = false
        summary.lineWrap = true
        summary.wrapStyleWord = true
        summary.text = "Discovery uses standard GraphQL introspection fields. " +
            "The server may block introspection. No result is interpreted as a vulnerability."
        resultTree.isRootVisible = true
        val divider = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(summary), JScrollPane(resultTree))
        divider.resizeWeight = 0.28
        add(divider, BorderLayout.CENTER)
        launch.isEnabled = false
        launch.addActionListener { sendOnce() }
    }

    fun setEndpoint(url: String?) {
        endpoint.text = if (url == null) "Select an observed operation in the endpoint tree."
            else "Selected endpoint: $url"
        launch.isEnabled = url != null && !busy.get()
    }

    private fun previewQuery() {
        val selected = mode.selectedItem as? SchemaRecon.Mode ?: SchemaRecon.Mode.ROOT_FIELDS
        val query = JTextArea(SchemaRecon.document(selected), 15, 58).apply {
            isEditable = false
            lineWrap = false
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }
        JOptionPane.showMessageDialog(
            this, JScrollPane(query), "Read-only GraphQL request preview",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun sendOnce() {
        if (!busy.compareAndSet(false, true)) return
        val url = selectedEndpoint()
        if (url.isNullOrBlank()) {
            busy.set(false)
            setEndpoint(null)
            return
        }
        val chosen = mode.selectedItem as? SchemaRecon.Mode ?: SchemaRecon.Mode.ROOT_FIELDS
        val confirmation = JPanel(BorderLayout(8, 8))
        val explanation = JTextArea(
            "Send one unauthenticated POST to: $url\\nMode: ${chosen.title}\\n" +
            "Scope required. No redirects, retries, mutations or field guessing."
        , 3, 54).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        confirmation.add(explanation, BorderLayout.NORTH)
        val queryPreview = JTextArea(SchemaRecon.document(chosen), 12, 54).apply {
            isEditable = false
            lineWrap = false
        }
        confirmation.add(JScrollPane(queryPreview), BorderLayout.CENTER)
        confirmation.add(JLabel("Review the exact request before allowing it."),
            BorderLayout.SOUTH)
        val decision = JOptionPane.showConfirmDialog(
            this, confirmation, "Confirm one GraphQL introspection request",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (decision != JOptionPane.YES_OPTION) {
            busy.set(false)
            setEndpoint(selectedEndpoint())
            return
        }
        launch.isEnabled = false
        summary.text = "Executing one scope-checked introspection request. No other requests scheduled."
        Thread({
            val result = try { runSingleRecon(url, chosen) } catch (_: Exception) {
                SchemaRecon.Result(chosen, null, "REQUEST_FAILED_OR_OUT_OF_SCOPE")
            }
            SwingUtilities.invokeLater {
                onResult(url, result)
                showResult(url, result)
                busy.set(false)
                setEndpoint(selectedEndpoint())
            }
        }, "GQLens-single-introspection").apply { isDaemon = true; start() }
    }

    private fun showResult(url: String, result: SchemaRecon.Result) {
        val roots = result.rootTypes
        val types = result.allTypes
        val count = types.sumOf { it.fields.size }
        summary.text = buildString {
            appendLine("Endpoint: $url")
            appendLine("Discovery: " + result.mode.title)
            appendLine("HTTP: " + (result.httpStatus ?: "no response"))
            appendLine("Result: " + result.outcome)
            appendLine("Types shown: " + types.size + " | Field names shown: " + count)
            appendLine("Root types: " + roots.joinToString(", ") { it.name })
            if (result.truncated) appendLine("TRUNCATED: bounded parser reached its type/field limit.")
            appendLine("Schema declarations are not evidence that fields are accessible to this user.")
            appendLine("No GraphQL response values, auth headers, cookies, or error messages are retained.")
        }
        summary.caretPosition = 0
        val root = DefaultMutableTreeNode(result.outcome)
        for (type in types.sortedWith(compareBy({ it.kind }, { it.name }))) {
            val parent = DefaultMutableTreeNode(type.name + "  [" + type.kind + "]")
            root.add(parent)
            type.fields.forEach { parent.add(DefaultMutableTreeNode(it)) }
        }
        resultTree.model = DefaultTreeModel(root)
        resultTree.expandRow(0)
    }
}
