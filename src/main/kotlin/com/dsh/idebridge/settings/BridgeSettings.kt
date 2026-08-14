package com.dsh.idebridge.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "DshIdeBridgeSettings", storages = [Storage("dsh-ide-bridge.xml")])
class BridgeSettings : PersistentStateComponent<BridgeSettings.SettingsState> {

    data class SettingsState(
        var baseUrl: String = "http://127.0.0.1:3080",
        // 仅用于从旧版本迁移（字段名必须与旧 XML 一致）；运行期一律走 PasswordSafe，迁移后置空
        var token: String = "",
        var maxChars: Int = 30000,
        var askQuestion: Boolean = true,
    )

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(value: SettingsState) {
        state = value
        // 旧版本把 token 明文写在 dsh-ide-bridge.xml 里，加载时迁进 PasswordSafe 并清空
        if (value.token.isNotBlank()) {
            token = value.token
            value.token = ""
        }
    }

    /**
     * 凭据存 PasswordSafe，不进 XML（XML 会被设置同步与备份带走）。
     * 读写可能触碰系统钥匙串，不要在 EDT 上调用。
     */
    var token: String
        get() = PasswordSafe.instance.getPassword(credentialAttributes()) ?: ""
        set(value) {
            PasswordSafe.instance.setPassword(credentialAttributes(), value.ifBlank { null })
        }

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName("DSH IDE Bridge", "token"))

    companion object {
        fun getInstance(): BridgeSettings =
            ApplicationManager.getApplication().getService(BridgeSettings::class.java)
    }
}
