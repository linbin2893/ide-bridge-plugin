# DSH × IntelliJ IDEA 桥接插件技术设计文档

- 版本：0.3（M1 原型已验证）
- 日期：2026-06-16
- 状态：M1 完成 ✅；待进入 M3（IDEA 插件工程）

---

## 1. 背景与目标

开发者在 IntelliJ IDEA 中向 DeepSeek Harness（DSH）提问代码问题时，当前流程是：
手动复制代码 → 粘贴到 DSH 对话框 → 手打文件路径/行号/类名 → 或让 AI 自行检索代码库。

**痛点**：手工操作多、上下文易缺失（复制漏行、路径记错）、AI 检索代码库耗时且可能找错位置。

**目标**：在 IDEA 中选中代码（或把光标放在某个类上），一键将
「代码 + 结构化元数据（项目/路径/语言/行号/类名）+ 可选追问」
发送到 **DSH 当前打开的对话窗口的输入框（草稿）**。

### 核心交互原则（用户明确要求）

> **代码只进入输入框草稿，绝不自动发送给 AI。** 用户审阅、编辑、附加其他信息后，
> **亲自点击发送**，AI 才开始回答。

即：IDEA 的职责是"替用户完成复制粘贴 + 元数据说明"，发送决策权完全保留给用户。

---

## 2. 范围

### v1 包含

| # | 能力 |
|---|------|
| 1 | 编辑器右键菜单 + 可配置快捷键触发 |
| 2 | 有选区 → 发送选区；无选区 → 发送光标所在**整个类**（用户决策：不做方法级） |
| 3 | 自动附带元数据：项目名、相对路径、语言、起止行号、类名/限定名 |
| 4 | 可选追问输入框（发送前弹出，可为空；关闭开关在设置里） |
| 5 | 内容填入 DSH **当前打开会话**的原生输入框（用户决策：当前窗口，不是最近活跃） |
| 6 | 输入框上方一条提示行：「已从 IDEA 收到代码上下文」+ 清空按钮 |
| 7 | 两端完整错误处理与通知（DSH 未启动、无打开会话、超长、格式错误等） |
| 8 | 设置页：服务地址、可选 token、超长阈值、快捷键、「测试连接」按钮 |

### v1 明确不包含（非目标）

- AI 回复回传 IDE（用户决策：v1 不回传，回复在 DSH 窗口看）
- 自动触发 AI 回合 / 打断正在进行的回合（违反核心交互原则）
- 远程 DSH 服务器（仅本机环回）
- 方法级提取、会话选择器、IDEA 内渲染回复、Marketplace 发布

---

## 3. 运行时调研结论（关键事实与出处）

以下结论均来自对当前部署源码与运行时目录的实查，是设计的硬依据：

| # | 事实 | 出处 |
|---|------|------|
| F1 | Web 服务器支持插件注册 HTTP 路由：`webServer.register({ kind: 'exact'\|'prefix', path, handler(req,res) })`，返回 disposer，重复 path 抛错 | `dsh-host-webserver/lib/index.js` |
| F2 | 现有 `/api` 前缀通道为 RPC 信封格式并做 trusted-host 校验；第三方客户端走自有路由更简单 | `dsh-client-connection/lib/index.js` |
| F3 | **输入框草稿可编程设置**：`inputActions.setDraft(text)` 为官方公开的输入区标准 prop，官方自己用它还原草稿（`if (inputState.draft === "" && storedDraft !== "") inputActions.setDraft(storedDraft)`） | `dsh-client-ui-conversation/lib/client.js` L7026；Slot 契约 `conversation.input.dock` 标准 props 含 `inputActions: InputActions`、`useInput`、`sessionId` |
| F4 | `conversation.input.dock` Slot：会话作用域 list 插槽，位于输入框卡片正上方整行（todo 条、目标条、队列行的官方位置）；注册后渲染组件收到 `sessionId` 与 `inputActions` 作为 props | 运行时 `Slots.listSubTree` 精确契约 |
| F5 | 会话是事件溯源模型；Web UI 发消息路径：`sessions.prompt` → `agent.followup(msg)`（queue 模式）；消息形态 `{ id, role:'user', source:{kind:'user',...}, content:[{type:'text',text}] }` | `dsh-host-apiproxy/lib/index.js` L2822；`dsh-llm/lib/types/message.js` |
| F6 | 用户消息的框架文本由调用方烘焙进 content，投影层不做二次包装 | `dsh-session/lib/types/surface.js`（deriveEventMessage 注释） |
| F7 | 动态插件私有 RPC：Host `harness.handle(method, handler)`，Client `host.call(method, args)`，仅无损 JSON | 运行时 `Builtin.listBuiltins`（host/client 均已确认） |
| F8 | 客户端 Builtins：`React.createElement/useState/useEffect`、`host.call`、`ctx.effect`、`styles.insert`；客户端 `timer` 服务提供 `interval` | 运行时 `Builtin.listBuiltins` + `Service.listService`（client） |
| F9 | 宿主 Builtins：`harness.handle`、`TextEncoder/TextDecoder`、`btoa/atob`；无 `crypto`/`Buffer` → id 用时间戳+计数，body 用 req.on('data') 分块 + TextDecoder | 运行时 `Builtin.listBuiltins`（host） |
| F10 | 插件生命周期：`ctx.effect(() => webServer.register(route))` 保证随插件停止/更新而卸载路由 | `cordis-plugin-development` 技能 + F1 |

**可行性结论：输入框草稿注入、当前会话定位、HTTP 接入三条路径全部有官方公开 API，无平台改造需求。**

---

## 4. 总体架构

```
┌─────────────────────────────┐     POST /ide/context       ┌───────────────────────────────────────────┐
│  IntelliJ IDEA               │ ──────────────────────────► │  DSH (127.0.0.1:3080)                      │
│  ┌─────────────────────────┐ │    JSON + 可选 Token         │  ┌─────────────────────────────────────┐  │
│  │ IdeBridgeAction         │ │                             │  │ ide-bridge 插件（Host 半部）           │  │
│  │  ├ SelectionExtractor   │ │ ◄────────────────────────── │  │  ├ POST /ide/context 路由             │  │
│  │  ├ ContextMetadata      │ │  { ok, status }             │  │  ├ 校验（环回/token/尺寸）            │  │
│  │  ├ SendDialog(追问)     │ │                             │  │  ├ 组装草稿文本                       │  │
│  │  └ BridgeClient(HTTP)   │ │                             │  │  ├ 草稿暂存（内存 FIFO + TTL）        │  │
│  │  Settings / 通知        │ │                             │  │  └ harness.handle('peekDraft')       │  │
│  └─────────────────────────┘ │                             │  └───────────────────▲─────────────────┘  │
└─────────────────────────────┘                             │                      │ host.call 轮询 (~1s)  │
                                                             │  ┌───────────────────┴─────────────────┐  │
                                                             │  │ ide-bridge 插件（Client 半部）       │  │
                                                             │  │  ├ conversation.input.dock 条目      │  │
                                                             │  │  │   └ 提示行：「已收到 IDEA 上下文」  │  │
                                                             │  │  ├ 取回草稿 → inputActions.setDraft() │  │
                                                             │  │  └ 清空按钮                          │  │
                                                             │  └─────────────────────────────────────┘  │
                                                             │          ▼                                │
                                                             │  原生输入框出现草稿文本                     │
                                                             │  用户审阅/编辑/补充 → 用户点击发送 → AI 回复 │
                                                             └───────────────────────────────────────────┘
```

**关键点**：Host 只暂存草稿文本；真正写入输入框的是浏览器端的 Client 半部（通过官方 `inputActions.setDraft`）。"当前打开的对话窗口"由 Client 半部在会话作用域内天然获得（dock 条目的 `sessionId` prop），无需 Host 猜测。

---

## 5. 接口协议（Wire Protocol v1）

基础地址：`http://127.0.0.1:3080`（可配置）。仅本机环回。

### 5.1 `POST /ide/context` — 发送代码上下文（进输入框草稿）

请求头：
- `Content-Type: application/json`
- `X-DSH-IDE-Token: <token>`（仅当 DSH 侧配置了 token 时必需）

请求体（JSON）：

```json
{
  "version": 1,
  "client": { "name": "dsh-idea-bridge", "version": "0.1.0" },
  "project": { "name": "my-app", "basePath": "D:\\projects\\my-app" },
  "file": { "path": "src/main/java/com/example/OrderService.java", "language": "java" },
  "selection": { "kind": "selection", "startLine": 42, "endLine": 87 },
  "symbol": { "name": "OrderService", "qualifiedName": "com.example.OrderService", "kind": "class" },
  "code": "public class OrderService {\n  // ...\n}",
  "question": "这个类的并发安全性怎么样？"
}
```

- `selection.kind`：`selection` | `class`（v1 两种；`method`/`file` 预留）
- `symbol`：无符号上下文时为 `null`
- `question`：可空字符串
- `code`：上限 120 KB（约 3000 行），超限 413

成功响应（200）：

```json
{ "ok": true, "status": "accepted", "draftId": "ide-1718…-1" }
```

`status` 语义：
- `accepted`：草稿已入暂存队列，等待 DSH 页面取回（通常在 1 秒内；若页面当前无打开会话，等待至打开会话或 TTL 过期）
- 响应**不代表**已填入输入框，更不代表已发送给 AI——IDE 侧通知文案固定为「已发送到 DSH，请在对话窗口确认后点击发送」

错误响应（对应 HTTP 状态码，body 统一为 `{ "ok": false, "code": "...", "message": "..." }`）：

| HTTP | code | 含义 | IDEA 侧表现 |
|------|------|------|-------------|
| 400 | `invalid-payload` | JSON 不合法/字段缺失/version 不支持 | 错误通知 + 详情日志 |
| 401 | `bad-token` | token 不匹配 | 错误通知，提示检查设置 |
| 404 | `no-page-listener` | 无浏览器页面在取草稿且已等待超时（v1.1 引入；v1 返回 accepted 后静默 TTL 丢弃） | 警告通知 |
| 413 | `payload-too-large` | code 超过 120 KB | 错误通知，提示缩小选区 |
| 429 | `rate-limited` | 触发频率限制（v1.1） | 警告通知 |
| 500 | `internal` | DSH 侧异常 | 错误通知 |

### 5.2 `GET /ide/health` — 健康检查

响应（200）：`{ "ok": true, "plugin": "dsh-ide-bridge", "version": "0.1.0", "pendingDrafts": 1 }`

用途：IDEA 设置页「测试连接」。

### 5.3 包内私有 RPC（Host↔Client，不走 HTTP，不对外）

| method | 方向 | 参数 | 返回 |
|--------|------|------|------|
| `peekDraft` | Client→Host（约 1 s 轮询） | `{ sessionId: string }` | `{ draftId, text }` 或 `null`；取走即从暂存队列移除并绑定该 sessionId |

该通道使用插件包私有的 `harness.handle`/`host.call`，仅插件两半部之间可见。

---

## 6. DSH 侧设计（ide-bridge 插件，Host + Client 两半部）

### 6.1 插件形态与归属

| 阶段 | 形态 | 说明 |
|------|------|------|
| A（原型） | 动态 Cordis 插件（本会话"创造模式"），Host + Client 两半部 | 验证全链路；随进程重启消失 |
| B（沉淀） | 独立包，宿主组合挂 Host 半部（HTTP 路由 + 草稿暂存），**Web 组合**（`dsh.client` 插件）挂 Client 半部（dock 提示行 + setDraft） | Host 半部只消费宿主服务，无 realm 约束；Client 半部属 Web 应用界面，须进 Web 组合；动态插件的 Client 半部仅原型期可用 |

### 6.2 Host 半部

```
POST /ide/context
  → ① 传输校验：method、Content-Type、body ≤ 512 KB 总限
  → ② 环回校验：req.socket.remoteAddress ∈ {127.0.0.1, ::1, ::ffff:127.0.0.1}，否则 403
  → ③ token 校验（若配置）
  → ④ payload 校验：version===1、kind 合法、行号为正整数、code 非空且 ≤ 120 KB
  → ⑤ 组装草稿文本（见 6.4）
  → ⑥ 入暂存队列（FIFO；TTL 10 分钟，过期静默丢弃）
  → ⑦ 返回 200 { ok, status:'accepted', draftId }
```

- **草稿暂存**：插件内存数组 + `ctx.interval` 清扫过期项；不落盘、不写会话日志（用户未发送前，会话必须保持干净）
- **`harness.handle('peekDraft')`**：出队最早一项返回 `{ draftId, text }`
- 生命周期：路由与 RPC 全部由 `ctx.effect()` 持有

### 6.3 Client 半部

- 注册 `conversation.input.dock` 条目（id `ide-bridge-dock`，order 30，排在 todo/goal/queue 之后）
- 组件逻辑（React 函数组件）：
  1. props 取 `sessionId` 与 `inputActions`
  2. `useEffect` + `ctx.interval` 每 1 s 调 `host.call('peekDraft', { sessionId })`
  3. 拿到草稿 → `inputActions.setDraft(text)` → 显示提示行：「已从 IDEA 收到代码上下文（<文件> <行范围>），已填入输入框，请审阅后发送」+「清空」按钮（`inputActions.setDraft('')`）
  4. 提示行 8 秒后自动消失；期间收到新草稿则更新
- 用户随后在**原生输入框**中审阅、编辑、补充，点击原生发送按钮 → 走 DSH 原生 `prompt` 路径（F5），AI 开始回答
- 无打开会话时 dock 不渲染（session 作用域），草稿留在 Host 暂存，直到用户打开会话或 TTL 过期——与"当前打开的对话窗口"语义一致

### 6.4 草稿文本格式（单段纯文本，用户可在输入框内任意编辑）

````markdown
[来自 IntelliJ IDEA] my-app · src/main/java/com/example/OrderService.java · java · 第 42–87 行 · 类 OrderService

```java
public class OrderService {
  // 代码内容…
}
```

<若附带追问>
问题：这个类的并发安全性怎么样？
</若附带追问>
````

- 截断：`code` 超 120 KB 直接 413，不静默截断（避免误导）；IDEA 侧另有低阈值预检（见 7.5）
- 元数据行与代码块均为草稿一部分，用户可在发送前任意删改

---

## 7. IntelliJ 侧设计

### 7.1 工程骨架

- Kotlin + Gradle，`gradle-intellij-plugin`；基线 `sinceBuild = 233`（IDEA 2023.3+）
- JVM target 17；序列化 kotlinx.serialization
- 包名 `com.dsh.idebridge`，插件名 "DSH IDE Bridge"（显示名「DSH 代码桥」）

```
ide-bridge-plugin/
├── build.gradle.kts
├── gradle.properties
└── src/main/
    ├── kotlin/com/dsh/idebridge/
    │   ├── IdeBridgeAction.kt          # AnAction 入口
    │   ├── extraction/SelectionExtractor.kt   # 选区/PSI
    │   ├── extraction/ContextMetadata.kt      # 项目/文件/符号元数据
    │   ├── transport/BridgeClient.kt          # JDK HttpClient
    │   ├── transport/CodeContextPayload.kt    # wire DTO
    │   ├── settings/BridgeSettings.kt         # PersistentStateComponent
    │   └── ui/SendDialog.kt                   # 可选追问对话框
    └── resources/META-INF/plugin.xml
```

### 7.2 动作与入口

- `AnAction`「发送到 DeepSeek Harness」注册于 `EditorPopupMenu`；默认快捷键 `Ctrl+Alt+Shift+D`（可在 Keymap 改）
- enabled 条件：存在项目且编辑器打开

### 7.3 内容提取（用户决策：无选区时只取整个类）

```
有选区 → SelectionModel 文本 → kind=selection，行号取选区首尾行
无选区 → caret 处 PsiElement 向上找 PsiClass → kind=class，取其 textRange
       → 找不到类则动作提示「光标不在类内」，不发送
符号名：PsiClass.qualifiedName；语言：file.language.displayName；相对路径：相对 project.baseDir
```

### 7.4 发送流程（后台线程，不阻塞 EDT）

1. 提取上下文 → 超过阈值（默认 30000 字符）弹确认（继续/取消）
2. 弹 SendDialog（追问 + 大小预览）→ OK/Cancel（设置里可关闭追问框）
3. `BridgeClient.send()`：JDK HttpClient，连接超时 2 s、总超时 10 s
4. 结果映射通知：200 → info「已发送到 DSH，请在对话窗口确认后点击发送」；失败按 5.1 错误表
5. 连接失败（DSH 未启动）→ 专属错误通知，含「打开设置」动作

### 7.5 设置页

| 项 | 默认 | 说明 |
|----|------|------|
| 服务地址 | `http://127.0.0.1:3080` | 可改端口 |
| Token | 空 | 与 DSH 侧一致；本机场景可不配 |
| 超长阈值 | 30000 字符 | 超过弹确认；0 = 不限制 |
| 发送前追问框 | 开 | 可关（纯发代码） |
| 测试连接 | — | 调 `GET /ide/health` |

---

## 8. 关键流程时序

```
IDEA                DSH Host(3080)        Host 半部              Client 半部(页面)        原生输入框      用户
 │ 1.右键/快捷键         │                     │                       │                    │        │
 │ 2.PSI提取+追问框      │                     │                       │                    │        │
 │ 3.POST /ide/context──►│──────路由───────────►│                       │                    │        │
 │                      │                     │ 4.校验+组装            │                    │        │
 │                      │                     │ 5.草稿入FIFO(TTL10m)   │                    │        │
 │ 6.◄──200 accepted────│◄────────────────────│                       │                    │        │
 │ 7.info通知           │                     │  ◄──peekDraft(1s轮询)──│                    │        │
 │                      │                     │──{draftId,text}──────►│ 8.setDraft(text)──►│ 草稿出现 │        │
 │                      │                     │                       │ 9.提示行显示        │                    │        │
 │                      │                     │                       │                    │ 10.审阅/编辑/补充 │
 │                      │                     │                       │                    │ 11.点击原生发送 ──► 原生prompt路径 → AI回答
```

- 第 6 步立即返回，不代表已送达页面（通常 1 s 内送达；无打开会话则等待）
- 第 8–11 步完全由用户掌控节奏；插件不触碰发送

---

## 9. 边界情况与决策汇总

| 场景 | 决策 |
|------|------|
| DSH 未启动 | IDEA 连接失败 → 专属错误通知（含打开设置动作） |
| DSH 页面无打开会话 | 草稿暂存至打开会话或 TTL 10 分钟过期；IDE 通知文案已暗示需打开对话窗口 |
| 发送时正在另一个会话 | 草稿由**取回时**打开的会话接收（即"当前打开的对话窗口"） |
| AI 正在回合中 | 无影响：草稿在输入框里，用户想什么时候发就什么时候发 |
| 选区超大 | IDEA 阈值预检弹确认；DSH 侧 120 KB 硬上限 → 413 |
| 空选区且无类 | 动作提示「光标不在类内」，不发送 |
| 文件不在项目内（scratch） | 路径降级为文件名，kind=selection 仍可发送 |
| 连续快速发送多条 | 依次入 FIFO，页面按顺序逐条填入（后到的覆盖输入框，提示行计数） |
| 动态插件原型期重启 | 路由/RPC/dock 全部消失，草稿随内存丢弃 |

---

## 10. 安全模型

- 仅监听环回（`remoteAddress` 白名单，403 拒绝其他来源）
- 可选共享 token（DSH settings 配置，IDEA 填写）；本机单用户场景可空
- 请求体总限 512 KB；`code` 限 120 KB
- 不执行代码、不调用工具、不自动写会话；草稿只是文本，且必须用户点击发送才进入模型
- 草稿暂存内存态，TTL 10 分钟，进程重启即清空
- IDEA 侧 token 用 `PasswordSafe` 存储（若实现），不落明文

---

## 11. 测试策略

### DSH 侧（原型期即可验证）

- curl 验收：正常发送 / 非法 JSON 400 / 超限 413 / 非环回 403 / token 错 401 / 草稿出现在当前会话输入框 / 点击发送后 AI 正常回答
- 多会话切换：草稿落到取回时打开的会话
- 无打开会话：草稿暂存，打开会话后 1 s 内出现
- 插件 stop/update：路由与 dock 立即消失（`ctx.effect` 可逆性）

### IntelliJ 侧

- `SelectionExtractor`：平台测试夹具（`BasePlatformTestCase` + 内存 PSI）覆盖 selection/class/无类三种
- `BridgeClient`：MockWebServer 覆盖 200/4xx/5xx/连接失败
- 端到端手工清单：装插件 → 选区发送 → DSH 输入框出现 → 编辑补充 → 发送 → 回复

---

## 12. 实施里程碑

| 里程碑 | 内容 | 验收 | 状态 |
|--------|------|------|------|
| M1 | DSH 动态插件原型（Host 路由+暂存+peekDraft；Client dock+setDraft+提示行） | 本会话内 curl → 输入框出现草稿 → 手动发送 → AI 回复 | ✅ 已完成（2026-06-16，见 §15） |
| M2 | 消息格式、错误码、TTL 定稿（更新本文档） | 评审通过 | ✅ 已完成（M1 实测覆盖错误码与格式） |
| M3 | IDEA 工程骨架 + 选区/类提取 + 发送 + 通知 + 设置页 | 选区 E2E 打通 | ✅ 工程已生成（`ide-bridge-plugin/`），待用户在 IDEA 构建安装验证 |
| M4 | 追问对话框、快捷键、错误映射、测试连接 | 全场景手工通过 | 未开始 |
| M5 | 联调回归 + 使用文档（安装/配置/快捷键） | 交付安装包 | 未开始 |
| M6（可选） | DSH 侧沉淀：Host 半部进宿主组合、Client 半部进 Web 组合 | 重启后能力常驻 | ✅ 已完成（2026-06-16，见 §16；待用户重启 DSH 生效） |

---

## 13. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| `inputActions` 除 `setDraft` 外的方法名未知（如清空/焦点） | 提示行清空按钮实现受限 | M1 先只依赖已证实的 `setDraft`；清空= `setDraft('')`；其余能力后续查证 |
| 动态插件 Client 半部需本页批准运行 | 原型期页面需激活插件 | 原型仅本会话内验证，符合动态插件机制 |
| IDEA SDK 版本 API 漂移（2023.3+ 跨度大） | 部分版本编译/运行问题 | 基线 233，避免 deprecated API，发布前在 2023.3 与最新版各验证一次 |
| 端口/主机配置漂移 | 发送失败 | health 检查 + 测试连接 + 清晰错误码 |
| 沉淀期 Web 组合改造需重新构建 Web 产物 | M6 成本 | M6 为可选，原型与打包版 IDEA 插件（M5）不受影响 |

---

## 14. 已确认决策（2026-06-16 用户答复）

| # | 决策 | 影响 |
|---|------|------|
| D1 | 无选区时只取**整个类**，不做方法级 | 提取逻辑更简单；method 留扩展位 |
| D1' | **无选区时只发类路径 + 名字（不附代码）**；仅选区附带代码 | 2026-06-16 追加决策：草稿已含绝对路径，AI 自行 `read` 文件即可，草稿更干净 |
| D2 | **代码进输入框草稿，绝不自动发送**；用户审阅/补充后手动点发送 | 架构核心：草稿暂存 + `inputActions.setDraft`；不触碰 `agent.followup` |
| D3 | 目标是**当前打开的对话窗口** | 由 Client 半部在会话作用域取 `sessionId`，Host 不猜测 |
| D4 | AI 回复**不回传 IDEA** | 无回传链路；范围最小 |

---

## 15. M1 实测记录（2026-06-16，动态插件 `idebrg-1/pkg-1`）

| # | 验证项 | 结果 |
|---|--------|------|
| V1 | `GET /ide/health` | ✅ 200 `{ok:true, pendingDrafts:0}` |
| V2 | `POST /ide/context`（正常 payload，kind=class） | ✅ 200 `{ok:true, status:'accepted', draftId:'ide-…'}` |
| V3 | 客户端轮询取回 | ✅ 2 秒内 `pendingDrafts` 归零（peekDraft RPC 打通） |
| V4 | 草稿出现在当前会话输入框 | ✅ 用户亲眼确认（`inputActions.setDraft` 生效） |
| V5 | 非法 JSON | ✅ 400 `body is not JSON` |
| V6 | 缺 `code` 字段 | ✅ 400 `code must be a non-empty string` |
| V7 | 手动发送 → AI 回复 | 待日常使用验证（发送走 DSH 原生路径，插件零介入，风险极低） |

**实测修正/确认**：
- `conversation.input.dock` 注册的第二参数（组件工厂）确实收到 props，`props.sessionId`、`props.inputActions.setDraft` 均可用（F3/F4 落地验证）
- 草稿暂存 → 1s 轮询 → 注入的时延实测 ≤ 2 s，符合设计
- 提示行「清空」按钮与 8 秒自动消失为附带功能，日常使用中持续观察

**遗留提醒**：当前形态是**动态插件**，随 DSH 进程重启消失；如需常驻，按 M6 将 Host 半部沉淀入宿主组合、Client 半部沉淀入 Web 组合。

---

## 15.1 用户侧改进记录（2026-06-16 下午，IDEA 插件 0.1.1）

用户在构建联调中自行完成了以下改动（已代码评审确认），设计文档相应更新：

| # | 改动 | 对设计的影响 |
|---|------|-------------|
| U1 | `BridgeClient` 钉死 HTTP/1.1 | 根因更正：JDK HttpClient 默认发 `Upgrade: h2c`，Node 侧转 'upgrade' 事件后销毁 socket → "header parser received no bytes"。**替代 §13 中"连接池死连接"的判断** |
| U2 | 序列化改平台自带 Gson，移除 kotlinx-serialization 依赖 | §7.1 工程骨架更新；避免 parent-first 遮蔽冲突 |
| U3 | token 迁 PasswordSafe（含旧 XML 明文迁移逻辑） | §10 安全模型落实 |
| U4 | 提取器扩展：非 Java 语言（Kotlin 等）回退 `PsiNameIdentifierOwner`，`kind="declaration"`；Java 仍 `class` | wire 协议 `selection.kind` 取值扩为 `selection/class/declaration`（DSH 侧白名单已同步扩展，另预留 `method/file`） |
| U5 | `bundledPlugin("com.intellij.java")` + plugin.xml `<depends>com.intellij.java</depends>` | 依赖 Java 插件（PsiClass 路径所需） |
| U6 | `untilBuild` 不设上限（`provider { null }`） | 避免未来版本被硬禁用 |
| U7 | 设置页：URL 校验、`ConfigurationException`、模态回调 `ModalityState`、`ActionUpdateThread.BGT`、`project.isDisposed` 检查 | 平台合规细节 |

DSH 侧同步：`idebrg-1/pkg-2` 扩展 `selection.kind` 白名单（`selection/class/method/file/declaration`），health 版本号 0.1.1。

---

## 16. M6 常驻化落地记录（2026-06-16）

**独立源码目录**（跨升级保留，升级后重新引入/适配的入口）：

```
~/.dsh/profiles/web/dsh-ide-bridge/   ← 用户自有，不属于 npx 部署，升级不覆盖
├── package.json        dsh.client.platform=web + exports["./client"]
├── host/index.js       Host 半部（/ide/context + /ide/peek + /ide/health，纯 ESM，无构建）
├── client/index.js     Client 半部（__ModuleLoader__ bundle：dock 提示行 + setDraft + fetch 轮询）
└── README.md           挂载方式 + 升级适配清单（每项 API 依赖点逐一列出）
```

**挂载机制**（全部在 profile 层，不碰部署）：
- `cordis.patch.yml`：`- insert: [{ id: ide-bridge, name: dsh-ide-bridge }]`（loader 补丁的 insert 语义，`dsh-app-boot` applyEntryPatches）
- `pnpm-workspace.yaml`：packages 增加 `dsh-ide-bridge`
- `package.json`：依赖 `"dsh-ide-bridge": "workspace:^"`
- `node_modules/dsh-ide-bridge` → 指向源码目录的 junction（免全量 pnpm install 即可被 loader 解析）

**与动态原型的关键差异**：
| 点 | 动态原型（pkg-4） | 常驻包（0.2.0） |
|----|------------------|-----------------|
| Host↔Client 通信 | `harness.handle`/`host.call`（仅动态插件可用） | **`GET /ide/peek` + fetch 轮询**（常驻包可用的通用机制） |
| Client bundle | 动态 runner 注入 | `window.__ModuleLoader__.load({id,factory})` 自注册格式 |
| 定时器 | client `timer` 服务 | 浏览器原生 `setInterval`（bundle 内安全） |
| 样式 | `styles.insert` builtin | 内联 style 属性 |
| 生命周期 | 随会话/进程 | 随 DSH 进程，重启即常驻 |

**已验证**（重启前静态项）：loader 解析路径可找到包；host ESM 导入导出 `apply/inject/name`；client bundle 语法合法。
**待验证**（用户重启 DSH 后）：`/ide/health` 返回 0.2.0；`/plugins/dsh-ide-bridge/client.js` 可访问；端到端草稿注入。

**警告**：常驻后不要再同时挂载动态原型（同路由会 duplicate route 冲突）；改任意半部后需重启 DSH（bundle rev 启动时计算）。

---

## 17. 两端合入单一仓库（2026-08-14）

**动因**：此前三块内容散落在互不相干的位置——IDEA 插件在 `ide-bridge-plugin/`（唯一有版本控制的）、
本设计文档在 `ide-bridge/`、DSH 侧常驻包在 `~/.dsh/profiles/web/dsh-ide-bridge/`。后两者裸奔无备份，
而两端由同一份 wire 协议强耦合（`selection.kind` 白名单、`code` 可空规则改动必须同步落到两边），
分处三地时无法用一次提交表达一次协议变更。

**布局**：

```
ide-bridge/
├── docs/DESIGN.md      本文件
├── idea-plugin/        IDEA 端（Kotlin/Gradle，原仓库根整体下移）
└── dsh-plugin/         DSH 端（纯 ESM，原 ~/.dsh 目录迁入）
```

**DSH 端为何不能直接搬走**：`~/.dsh/profiles/web/` 是一个 pnpm workspace，
`dsh-ide-bridge` 是其成员，§16 列的三处配置（`cordis.patch.yml` / `pnpm-workspace.yaml` /
profile `package.json`）全部按**精确名**引用它。目录一旦移走，loader 就解析不到包。

**评估过但未采用的方案**：把 §16 内层已在用的 junction 手法往上提一层——
真身进仓库，`~/.dsh/profiles/web/dsh-ide-bridge` 留一个指向 `<repo>/dsh-plugin` 的 junction。
实测两层 junction 链可完整穿透（`node_modules/dsh-ide-bridge` → profiles 目录 → 仓库），
Node 也能穿过它 `--check` 通过两个 ESM 文件；但**未经真实 DSH 启动验证**即按决定回退。
profiles 侧现已恢复为独立真实目录。若日后重新考虑，已知的前置结论是：
junction 对 pnpm 与 Node 解析透明，profile 那三处配置无需改动。

**当前采用的方案**：`dsh-plugin/` 是开发源码（权威版本），
`~/.dsh/profiles/web/dsh-ide-bridge/` 是 DSH 实际加载的独立副本，两者**手动同步**。

> 已知代价：改完仓库不同步就重启 DSH，表现是「代码改了却毫无变化」，
> 且无任何报错。排查这类现象时第一步应是比对两边文件是否一致。

**连带影响**：
- 构建路径变为 `idea-plugin/gradlew buildPlugin`，产物落在 `idea-plugin/build/distributions/`
- `.gitignore` 中 wrapper jar 的否定规则改用 `**/` 前缀，否则下移后锚定路径失配
- `.gitignore` 保留 `node_modules/` 一条：当前方案下仓库内不会生成它，
  但该目录任何情况下都不应入库，留着无害
- 两端 README 的交叉引用改为仓库内相对路径，不再依赖任何绝对路径
