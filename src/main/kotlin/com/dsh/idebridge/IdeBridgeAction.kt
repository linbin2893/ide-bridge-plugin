package com.dsh.idebridge

import com.dsh.idebridge.extraction.Extraction
import com.dsh.idebridge.extraction.SelectionExtractor
import com.dsh.idebridge.extraction.previewSummary
import com.dsh.idebridge.settings.BridgeSettings
import com.dsh.idebridge.transport.BridgeClient
import com.dsh.idebridge.transport.CodeContextPayload
import com.dsh.idebridge.transport.FileInfo
import com.dsh.idebridge.transport.ProjectInfo
import com.dsh.idebridge.transport.SelectionInfo
import com.dsh.idebridge.transport.SendOutcome
import com.dsh.idebridge.transport.SymbolInfo
import com.dsh.idebridge.ui.SendDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.Messages

class IdeBridgeAction : AnAction() {

    // update() 只读 DataContext，放后台线程，避免占用 EDT（2022.3+ 平台要求显式声明）
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabled = project != null && editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        // PSI 访问必须在 ReadAction 内
        val extraction: Extraction? = ReadAction.compute<Extraction?, RuntimeException> {
            SelectionExtractor.extract(project, editor, psiFile)
        }
        if (extraction == null) {
            IdeBridgeNotifications.warn(
                project, "DSH IDE Bridge",
                "无法提取：请先选中代码，或把光标放在某个类 / 函数内部。",
            )
            return
        }

        val settings = BridgeSettings.getInstance().state

        // 超长阈值预检（0 = 不限制）
        val maxChars = settings.maxChars
        if (maxChars > 0 && extraction.code.length > maxChars) {
            val answer = Messages.showYesNoDialog(
                project,
                "选中内容共 ${extraction.code.length} 字符，超过设置阈值 $maxChars。继续发送？",
                "内容较大",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }

        // 可选追问（设置里可关）
        var question: String? = null
        if (settings.askQuestion) {
            val dialog = SendDialog(project, extraction.previewSummary(), extraction.code.length)
            if (!dialog.showAndGet()) return
            question = dialog.questionText().ifEmpty { null }
        }

        val payload = CodeContextPayload(
            project = ProjectInfo(name = extraction.projectName, basePath = extraction.basePath),
            file = FileInfo(path = extraction.filePath, language = extraction.language),
            selection = SelectionInfo(
                kind = extraction.kind,
                startLine = extraction.startLine,
                endLine = extraction.endLine,
            ),
            symbol = if (extraction.symbolName != null) {
                SymbolInfo(
                    name = extraction.symbolName,
                    qualifiedName = extraction.symbolQualifiedName,
                    kind = extraction.symbolKind,
                )
            } else {
                null
            },
            code = extraction.code,
            question = question,
        )
        val baseUrl = settings.baseUrl

        // HTTP 在后台线程，通知回 EDT。token 走 PasswordSafe，读取也一并放到后台
        ApplicationManager.getApplication().executeOnPooledThread {
            val token = BridgeSettings.getInstance().token
            val outcome = BridgeClient.send(baseUrl, token, payload)
            ApplicationManager.getApplication().invokeLater {
                // 请求飞行期间工程可能已关闭
                if (project.isDisposed) return@invokeLater
                when (outcome) {
                    is SendOutcome.Accepted -> IdeBridgeNotifications.info(
                        project, "已发送到 DSH",
                        if (outcome.draftId.isBlank()) {
                            "草稿已提交，请在对话窗口审阅后点击发送。"
                        } else {
                            "草稿 ${outcome.draftId} 已提交，请在对话窗口审阅后点击发送。"
                        },
                    )
                    is SendOutcome.Rejected -> IdeBridgeNotifications.error(
                        project, "DSH 未接受请求（HTTP ${outcome.httpStatus}）",
                        outcome.message + (outcome.code?.let { "（$it）" } ?: ""),
                    )
                    is SendOutcome.Failed -> IdeBridgeNotifications.error(
                        project, "发送失败",
                        outcome.reason + "\n可在 Settings → Tools → DSH IDE Bridge 检查服务地址。",
                    )
                }
            }
        }
    }
}
