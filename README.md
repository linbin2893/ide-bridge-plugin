# IDE Bridge

把 IntelliJ IDEA 里选中的代码一键送进 **DeepSeek Harness** 当前会话的输入框。
代码以**草稿**形式落在输入框里，由你审阅、补充后**手动点发送**——桥接层绝不代你发给 AI。

本仓库同时存放桥接的两端。它们由同一份 wire 协议耦合，改协议要两边一起动，
所以放在一个仓库里，一次提交表达一次完整的协议变更。

## 目录

| 路径 | 是什么 | 技术栈 |
|---|---|---|
| [`idea-plugin/`](idea-plugin/) | IDEA 端：提取选区 / 光标所在声明，POST 给 DSH | Kotlin + Gradle（IntelliJ Platform 2.x） |
| [`dsh-plugin/`](dsh-plugin/) | DSH 端：收草稿，注入当前会话输入框 | 纯 ESM JavaScript，无构建步骤 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 详细设计与逐版演进记录 | — |

## 数据流

```
IDEA 编辑器
  │  选中代码 / 光标停在某个声明内
  │  右键「发送到 DeepSeek Harness」或 Ctrl+Alt+Shift+D
  ▼
idea-plugin  ──POST /ide/context──►  dsh-plugin (host 半部)
                                        │  草稿入队，10 分钟有效
                                        ▼
                                     dsh-plugin (client 半部)
                                        │  轮询 GET /ide/peek
                                        ▼
                                     DSH 输入框：草稿就位
                                        │
                                        ▼
                                     你审阅后手动发送
```

默认端口 `127.0.0.1:3080`，仅本机环回。

## 两端如何各自就位

**IDEA 端**——构建出 zip 后从磁盘装：

```powershell
cd idea-plugin
./gradlew buildPlugin        # 产物 build/distributions/dsh-ide-bridge-*.zip
```

跑 Gradle 的 JDK 必须是 17～23（Kotlin 2.0.21 的编译器不认 JDK 25 的版本号）。
详见 [`idea-plugin/README.md`](idea-plugin/README.md)。

**DSH 端**——源码真身在本仓库，DSH 那边挂一个 junction 指过来：

```powershell
mklink /J "$env:USERPROFILE\.dsh\profiles\web\dsh-ide-bridge" "<repo>\dsh-plugin"
```

junction 名必须是 `dsh-ide-bridge`（profile 的 pnpm workspace 按精确名匹配成员），
指向的仓库内目录叫什么都行。这样 DSH 升级不会覆盖插件，改动也直接进版本控制。
另需 profile 层三处配置，见 [`dsh-plugin/README.md`](dsh-plugin/README.md)。

> 两端改动都需要重启对应宿主才生效：IDEA 端重装插件重启 IDE，DSH 端重启 DSH
> （client bundle 的 rev 在进程启动时计算）。

## wire 协议速查

```
POST /ide/context   { version, client, project, file, selection, symbol, code, question }
  → 200 { ok, status:"accepted", draftId }
  → 400/401/403/413/500 { ok:false, code, message }

GET  /ide/peek      浏览器侧轮询取草稿
GET  /ide/health    → { ok, plugin, version, pendingDrafts }
```

| 字段 | 取值 |
|---|---|
| `selection.kind` | `selection` \| `class`（Java 整类）\| `declaration`（其他语言的具名声明） |
| `symbol.kind` | `class` \| `declaration` \| `null` |
| `code` | 选区时非空（`kind="selection"` 强制非空）；无选区的纯引用可传 `""` |

单条上限 120 KB，超出返回 413。

联通性自检：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:3080/ide/health" -Method Get
```

## 已知限制

- 回复不回传 IDEA，在 DSH 窗口里看
- 无方法级提取，无会话选择器（固定发往当前打开的会话）
- 仅支持本机环回，不做远程
