# dsh-ide-bridge（DSH 侧常驻包）

从 IntelliJ IDEA 接收代码上下文，填入 DSH 当前会话输入框草稿。对端是本仓库的
[`../idea-plugin/`](../idea-plugin/)。

**本目录是开发源码，不是 DSH 实际加载的那份。** DSH 加载的是
`~/.dsh/profiles/web/dsh-ide-bridge/`，那里保持独立的真实目录（不是链接）。

> ⚠️ **改完这里不会自动生效。** 两处是彼此独立的副本，必须手动同步过去再重启 DSH：
>
> ```powershell
> Copy-Item -Recurse -Force "<repo>\dsh-plugin\*" "$env:USERPROFILE\.dsh\profiles\web\dsh-ide-bridge\"
> ```
>
> 忘了这一步的症状是「改了代码却毫无变化」，且没有任何报错——排查时先核对两边文件是否一致。

## 结构

- `host/index.js`   — Host 半部：`POST /ide/context`（收草稿）、`GET /ide/peek`（浏览器轮询）、`GET /ide/health`
- `client/index.js` — Client 半部：`conversation.input.dock` 提示行 + `inputActions.setDraft` 注入草稿
- `package.json`    — `dsh.client.platform=web` 声明 + `exports["./client"]`
- 纯 ESM JavaScript，**无构建步骤**（改动后重启 DSH 生效）

## 挂载方式（在 profile 层，不碰部署）

以下全部发生在 `~/.dsh/profiles/web/` 那份副本上，与本仓库无关：

1. `cordis.patch.yml` 里有一行 insert：`- insert: [{ id: ide-bridge, name: dsh-ide-bridge }]`
2. `pnpm-workspace.yaml` 的 packages 含 `dsh-ide-bridge`
3. profile `package.json` 依赖 `"dsh-ide-bridge": "workspace:^"`
4. `node_modules/dsh-ide-bridge` 是指向该目录的 junction（由安装脚本创建）
5. 重启 DSH 生效

## DSH 升级后如何重新引入/适配

升级后按顺序检查（每项对应源码中的依赖点）：

1. `pnpm install`（在 `~/.dsh/profiles/web/` 下），重建 junction 与依赖
2. 检查 Host 半部依赖的 API：
   - `ctx.webServer.register({kind:'exact',path,handler})` 是否仍在（`dsh-host-webserver`）
   - `timer` 服务与 `ctx.interval`（`cordis-plugin-timer`）
   - `ctx.effect` 生命周期
3. 检查 Client 半部依赖的 API：
   - `window.__ModuleLoader__.load({id,factory})` bundle 注册格式
   - 插槽 `conversation.input.dock` 是否存在、标准 props 是否仍有 `sessionId` 与 `inputActions.setDraft`
   - `require("react")` 在模块注册表中可用
4. 检查组合机制：`cordis.patch.yml` 的 `insert` 语义（`dsh-app-boot` 的 applyEntryPatches）
5. 检查 wire 协议：`selection.kind` 白名单、`code` 可空规则（host/index.js 的 validate）

详细设计与演进记录：[`../docs/DESIGN.md`](../docs/DESIGN.md)

## 注意

- 与动态插件原型（`idebrg-1`）**不要同时挂载**：两者注册相同路由，第二个会抛 duplicate route
- Host 与 Client 任一半部修改后都需要**重启 DSH**（bundle rev 在进程启动时计算）
