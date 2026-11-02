package dev.gqlens.ui

import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Small dependency-free, read-only code viewer for generated GraphQL and JSON.
 * Line numbers share a JScrollPane viewport and the UIManager supplies colors
 * so the display follows Burp's light/dark Look and Feel.
 */
class CodePreviewPanel : JPanel(BorderLayout()) {
    enum class Language { GRAPHQL, JSON }

    private val code = object : JTextPane() {
        override fun getScrollableTracksViewportWidth(): Boolean = false
    }.apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size + 1)
        border = BorderFactory.createEmptyBorder(10, 12, 10, 16)
    }
    private val gutter = JTextArea("1").apply {
        isEditable = false
        isFocusable = false
        font = code.font
        foreground = UIManager.getColor("Label.disabledForeground")
        background = UIManager.getColor("Panel.background")
        border = BorderFactory.createEmptyBorder(10, 8, 10, 8)
    }

    init {
        val scroll = JScrollPane(code)
        scroll.setRowHeaderView(gutter)
        add(scroll, BorderLayout.CENTER)
        showCode("", Language.GRAPHQL)
    }

    fun currentCode(): String = code.text

    fun showCode(text: String, language: Language) {
        val document = code.styledDocument
        document.remove(0, document.length)
        val normal = SimpleAttributeSet()
        StyleConstants.setFontFamily(normal, Font.MONOSPACED)
        document.insertString(0, text, normal)

        val keyword = SimpleAttributeSet()
        StyleConstants.setBold(keyword, true)
        val variable = SimpleAttributeSet()
        StyleConstants.setBold(variable, true)
        StyleConstants.setForeground(variable,
            UIManager.getColor("Component.linkColor")
                ?: UIManager.getColor("TextField.foreground")
                ?: code.foreground)
        val muted = SimpleAttributeSet()
        StyleConstants.setForeground(muted,
            UIManager.getColor("Label.disabledForeground") ?: code.foreground)
        val syntax = if (language == Language.GRAPHQL) {
            Regex("""(?m)#[^\n]*|\$[_A-Za-z][_0-9A-Za-z]*|\b(?:query|fragment|on|mutation|subscription)\b|[{}():!,\[\]]""")
        } else {
            Regex(""""(?:\\.|[^"\\])*"(?=\s*:)|\b(?:true|false|null)\b|[{}:,\[\]]""")
        }
        for (match in syntax.findAll(text)) {
            val token = match.value
            val style = when {
                token.startsWith("#") -> muted
                token.startsWith("$") || (language == Language.JSON && token.startsWith("\"")) -> variable
                else -> keyword
            }
            document.setCharacterAttributes(match.range.first, token.length, style, false)
        }
        val lineCount = text.count { it == '\n' } + 1
        val width = lineCount.toString().length
        gutter.text = (1..lineCount).joinToString("\n") { it.toString().padStart(width, ' ') }
        code.caretPosition = 0
        gutter.caretPosition = 0
    }
}
