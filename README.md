# DSH IDE Bridge（IntelliJ 插件）

把 IDEA 中选中的代码（或光标所在类）一键发送到 **DeepSeek Harness 当前对话窗口的输入框**，
附带项目名 / 相对路径 / 语言 / 行号 / 类名等元数据。代码以**草稿**形式出现在输入框，
由你审阅、编辑、补充后**手动点击发送**——插件绝不自动发送给 AI。

配套设计文档：[`../ide-bridge/DESIGN.md`](../ide-bridge/DESIGN.md)

## 前提

- IntelliJ IDEA 2023.3+（Community 或 Ultimate）
- **Gradle 8.5+（含 9.x）**——已迁移到新一代 `org.jetbrains.intellij.platform` 2.4.0
- DSH 正在运行，且已挂载 ide-bridge 接收插件（DSH 侧，监听 `http://127.0.0.1:3080/ide/context`）
- 本机构建需要：网络（首次构建会下载 JDK 17 工具链与 IntelliJ SDK）

## 构建（IDEA 内）

1. 用 IDEA 打开本目录，Gradle 面板刷新（Reload）
2. 运行任务：
   - `buildPlugin` → 产物 `build/distributions/dsh-ide-bridge-0.1.2.zip`
   - `runIde` → 启动带本插件的沙箱 IDEA 联调
3. 命令行构建：`./gradlew buildPlugin`（wrapper 已锁定 9.1.0，Windows 用 `gradlew.bat`）

> 说明：
> - 构建插件用新一代 2.x DSL（`intellijPlatform {}`），兼容 Gradle 8.5+ 与 9.x
> - JDK 17 工具链由 foojay resolver 自动下载（本机只需有任一 JDK 运行 Gradle）
> - **跑 Gradle 的 JDK 必须是 17～23**：Kotlin 2.0.21 的编译器不认识 JDK 25 的版本号，
>   会以 `IllegalArgumentException: 25.0.2` 形式报 internal compiler error。
>   IDEA 里对应 Settings → Build Tools → Gradle → Gradle JVM
> - 插件不捆绑 kotlin-stdlib / kotlinx-serialization：这两个平台自带，
>   重复捆绑会因类加载 parent-first 导致编译期与运行期版本不一致。JSON 用平台自带的 Gson

## 安装

1. `./gradlew buildPlugin` 得到 zip
2. IDEA → Settings → Plugins → ⚙️ → **Install Plugin from Disk…** → 选 zip → 重启

## 配置

Settings → Tools → **DSH IDE Bridge**

| 项 | 默认 | 说明 |
|----|------|------|
| 服务地址 | `http://127.0.0.1:3080` | DSH 地址，可改端口 |
| Token | 空 | 与 DSH 侧一致；本机场景可不配 |
| 超长阈值 | 30000 字符 | 超过弹确认；0 = 不限制 |
| 发送前追问框 | 开 | 可关（纯发代码） |
| 测试连接 | — | 调 `GET /ide/health` |

## 使用

1. 在编辑器里**选中一段代码**（或把光标放在某个类 / 函数内部）
2. 右键 → **发送到 DeepSeek Harness**（或快捷键 `Ctrl+Alt+Shift+D`，可在 Keymap 中改）
3. 可选输入追问 → 发送
4. 切到 DSH 窗口：代码草稿已在输入框中，审阅/编辑/补充后点**发送**
5. 输入框上方提示行 8 秒后自动消失；「清空」按钮可清空草稿

## 行为规则

| 场景 | 行为 |
|------|------|
| 有选区 | 发送选区文本 + 行号（附代码块） |
| 无选区、光标在声明内 | **只发绝对路径 + 名字（不附代码）**，AI 按路径自行读取文件 |
| 无选区、光标不在任何声明内 | 提示「无法提取」，不发送 |
| DSH 未启动 | 错误通知，含「打开设置」引导 |
| DSH 页面无打开会话 | 草稿暂存（10 分钟），打开会话后 1 秒内出现 |
| 内容超过阈值 | 先弹确认再发；DSH 侧硬上限 120 KB 返回 413 |

## DSH 侧协议速查

```
selection.kind: "selection" | "class"（Java 整类）| "declaration"（其他语言的具名声明，新增值）
symbol.kind:    "class" | "declaration" | null
code:           选区时非空（kind="selection" 强制非空）；无选区引用可传 ""

POST /ide/context   { version, client, project, file, selection, symbol, code, question }
  → 200 { ok, status:"accepted", draftId }
  → 400/401/403/413/500 { ok:false, code, message }
GET  /ide/health    → { ok, plugin, version, pendingDrafts }
```

curl 测试：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:3080/ide/health" -Method Get
$body = @{ version=1; project=@{name="my-app"}; file=@{path="src/A.java"; language="JAVA"}; selection=@{kind="selection"; startLine=1; endLine=3}; symbol=@{name="A"; kind="class"}; code="int x = 1;"; question="" } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "http://127.0.0.1:3080/ide/context" -Method Post -ContentType "application/json" -Body $body
```

## 目录结构

```
src/main/kotlin/com/dsh/idebridge/
├── IdeBridgeAction.kt              # 入口动作（右键菜单 + Ctrl+Alt+Shift+D）
├── IdeBridgeNotifications.kt       # 通知封装
├── extraction/SelectionExtractor.kt # 选区 / 整类 PSI 提取
├── transport/CodeContextPayload.kt  # wire DTO（kotlinx.serialization）
├── transport/BridgeClient.kt        # JDK HttpClient（2s 连接 / 10s 总超时）
├── settings/BridgeSettings.kt       # 持久化设置
├── settings/BridgeSettingsConfigurable.kt
└── ui/SendDialog.kt                 # 可选追问框
```

## 已知限制（v1）

- 回复不回传 IDEA（在 DSH 窗口查看）
- 无方法级提取、无会话选择器（发到当前打开的对话窗口）
- 仅本机环回 DSH
