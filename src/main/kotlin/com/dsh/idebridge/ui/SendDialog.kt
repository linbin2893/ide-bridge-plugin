package com.dsh.idebridge.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/** 发送前的可选追问框：预览一行摘要 + 多行追问输入。 */
class SendDialog(
    project: Project?,
    summary: String,
    chars: Int,
) : DialogWrapper(project, true) {

    private val questionArea = JBTextArea(4, 60)

    // summary 含用户内容，做最小 HTML 转义
    private val infoLabel: JBLabel = JBLabel(
        "<html><b>${escapeHtml(summary)}</b> · $chars 字符</html>",
    )

    init {
        title = "发送到 DeepSeek Harness"
        setOKButtonText("发送")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(infoLabel, BorderLayout.NORTH)
        val center = JPanel(BorderLayout(0, 4))
        center.add(JBLabel("可选追问（可为空）："), BorderLayout.NORTH)
        questionArea.lineWrap = true
        val scroll = JBScrollPane(questionArea)
        scroll.preferredSize = Dimension(480, 96)
        center.add(scroll, BorderLayout.CENTER)
        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    fun questionText(): String = questionArea.text.trim()

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
