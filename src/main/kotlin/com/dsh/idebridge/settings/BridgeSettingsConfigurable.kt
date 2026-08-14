package com.dsh.idebridge.settings

import com.dsh.idebridge.transport.BridgeClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.net.URI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class BridgeSettingsConfigurable : Configurable {

    private val settings = BridgeSettings.getInstance()
    private val baseUrlField = JBTextField()
    private val tokenField = JBPasswordField()
    private val maxCharsField = JBTextField()
    private val askQuestionBox = JBCheckBox("发送前弹出追问框")
    private var panel: JPanel? = null

    // token 落在 PasswordSafe，读一次缓存下来，避免 isModified 每次刷新都去读钥匙串
    private var loadedToken: String = ""

    override fun getDisplayName(): String = "DSH IDE Bridge"

    override fun createComponent(): JComponent {
        val p = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.insets = JBUI.insets(4)
        c.fill = GridBagConstraints.HORIZONTAL

        c.gridx = 0; c.gridy = 0; c.weightx = 0.0
        p.add(JLabel("服务地址："), c)
        c.gridx = 1; c.weightx = 1.0
        p.add(baseUrlField, c)

        c.gridx = 0; c.gridy = 1; c.weightx = 0.0
        p.add(JLabel("Token（可选）："), c)
        c.gridx = 1; c.weightx = 1.0
        p.add(tokenField, c)

        c.gridx = 0; c.gridy = 2; c.weightx = 0.0
        p.add(JLabel("超长阈值（字符，0 = 不限制）："), c)
        c.gridx = 1; c.weightx = 1.0
        p.add(maxCharsField, c)

        c.gridx = 0; c.gridy = 3; c.gridwidth = 2
        p.add(askQuestionBox, c)

        val testButton = JButton("测试连接")
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2
        c.weightx = 0.0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.WEST
        p.add(testButton, c)
        testButton.addActionListener {
            val url = baseUrlField.text.trim()
            testButton.isEnabled = false
            testButton.text = "测试中…"
            // HTTP 不能在 EDT 上跑（最长阻塞 5 秒会冻住设置面板）
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = BridgeClient.health(url)
                // 设置对话框是模态的，不指定 modality 的回调会被推迟到对话框关闭之后
                ApplicationManager.getApplication().invokeLater(
                    {
                        testButton.isEnabled = true
                        testButton.text = "测试连接"
                        if (result.ok) Messages.showInfoMessage(p, result.message, "DSH IDE Bridge")
                        else Messages.showErrorDialog(p, result.message, "DSH IDE Bridge")
                    },
                    ModalityState.stateForComponent(p),
                )
            }
        }

        panel = p
        return p
    }

    override fun isModified(): Boolean {
        val s = settings.state
        // maxChars 按文本比较：输入非法时也要判定为「已修改」，
        // 这样点 Apply 会走到 apply() 拿到明确报错，而不是永远亮着按钮却静默丢弃
        return baseUrlField.text.trim() != s.baseUrl ||
            currentToken() != loadedToken ||
            maxCharsField.text.trim() != s.maxChars.toString() ||
            askQuestionBox.isSelected != s.askQuestion
    }

    override fun apply() {
        val url = baseUrlField.text.trim()
        validateUrl(url)
        val maxChars = maxCharsField.text.trim().toIntOrNull()
            ?: throw ConfigurationException("超长阈值必须是整数（0 表示不限制）")
        if (maxChars < 0) throw ConfigurationException("超长阈值不能为负数")

        val s = settings.state
        s.baseUrl = url
        s.maxChars = maxChars
        s.askQuestion = askQuestionBox.isSelected

        val token = currentToken()
        if (token != loadedToken) {
            settings.token = token
            loadedToken = token
        }
    }

    override fun reset() {
        val s = settings.state
        baseUrlField.text = s.baseUrl
        loadedToken = settings.token
        tokenField.text = loadedToken
        maxCharsField.text = s.maxChars.toString()
        askQuestionBox.isSelected = s.askQuestion
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun currentToken(): String = String(tokenField.password)

    private fun validateUrl(url: String) {
        if (url.isEmpty()) throw ConfigurationException("服务地址不能为空")
        val uri = try {
            URI.create(url)
        } catch (_: IllegalArgumentException) {
            throw ConfigurationException("服务地址格式不正确：$url")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw ConfigurationException("服务地址必须形如 http://127.0.0.1:3080")
        }
    }
}
