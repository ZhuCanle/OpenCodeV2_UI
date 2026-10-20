## OpenCode Server API 学习笔记（v2）

> 本插件已 **v2-only**。本文档记录 opencode v2 的 `/api` 接口面、认证方式与服务发现流程。
>
> 参考来源：
> - 官方文档：https://opencode.ai/docs/server/
> - 本机验证：opencode v2.0.29（`opencode serve` / `opencode service status`）

---

## 总览

- opencode v2 的 HTTP API 全部位于 **`/api`** 前缀下（v1 的 `/session`、`/global/*` 顶层路径已废弃）。
- **每个 `/api/*` 路由都需要 HTTP Basic 认证**（用户名固定 `opencode`，密码为服务生成）。缺失认证返回 `401`。
- 除 `/api/info` 外，响应统一包裹在 `{data: ...}` 信封中；`/api/info` 直接返回裸对象。
- SSE 事件通过 `GET /api/event?directory=` 订阅；v1 的 `/global/event` 已不再使用。

---

## 认证与凭据

| 场景 | 端口来源 | 密码来源 |
|---|---|---|
| 本地（后台服务） | `opencode service status` 打印的 `http://host:port` | 配置目录下 `service.json` 的 `password` 字段 |
| 远程（`opencode serve`） | 用户在连接对话框填写 `host:port` | 用户在对话框填写，取自 `opencode serve` 启动时 stdout 打印 |

- 插件**不再**从进程环境变量（`OPENCODE_SERVER_PASSWORD`）抓取凭据；v2 已移除该机制。
- Basic auth 头形如 `Authorization: Basic b3BlbmNvZGU6PHBhc3N3b3JkPg==`（`opencode:<password>`）。

## 服务发现（本地）

- `opencode service status`：服务已运行时打印其 URL，例如 `http://127.0.0.1:49374`。
- `opencode service start`：服务未运行时启动它；重复调用返回 "already running"。
- 密码文件位置（`OpenCodeServiceDiscovery.defaultConfigDir()`）：
  - 所有平台（含 Windows）：`$XDG_CONFIG_HOME/opencode`，默认 `~/.config/opencode`
  - Windows 兼容回退：`%APPDATA%\opencode`
  - 文件名为 `service.json`，内容形如 `{"password": "..."}`（文件权限 600）。
- 插件从不停止全局共享的后台服务；断开连接时只释放自己的 API/SSE 客户端。

---

## 核心端点

### 健康 / 版本

- `GET /api/info`
  - 裸对象（无 `{data}` 信封）：`{version, pid, urls, paths, capabilities}`。
  - `version` 用于连接时的版本门禁（非 v2 主版本时告警但仍可继续）。

### 会话

- `GET /api/session?directory=` → `{data:[Session.Info]}`，按 `time.updated` 找最近会话。
- `GET /api/session/active?directory=` → `{data:{}}`（无活动会话）或 `{data:Session.Info}`。
- `GET /api/session/{id}?directory=` → 单个会话详情。
- `POST /api/session`、`PATCH /api/session/{id}`、`DELETE /api/session/{id}`、`POST /api/session/{id}/abort` 等会话管理接口。

### 消息 / 命令 / 提示

- `GET /api/session/{id}/message?limit=&cursor=` → `{data:[Message]}`（Message 含 `id`、`sessionID`、`role`）。
- `POST /api/session/{id}/prompt`（`PromptInput.*`）：**会提交一轮对话**，因此不用于「预填输入框」。
- `POST /api/session/{id}/command`：执行 `/mystatus` 等命令并返回 Message。

### Diff（v2 关键变化）

- `GET /api/session/{id}/diff?directory=&from=&to=&context=` → `{data:[FileDiff.Info]}`。
  - `from` / `to` 是 **message id**（`Session.Message.ID`），必须传 **user** message id；传错类型服务端返回 `400`。
  - 省略 `from` 时默认取「最新 user 消息所在的一轮」。
- `FileDiff.Info`：`{file, patch, additions, deletions, status}`，其中 `patch` 是统一 diff 文本。
  - v1 的 `{file, before, after, ...}`（整份前后内容）已被移除；插件改为用 `patch` + 本地 git 快照重建 diff 展示。

### TUI

- `POST /tui/append-prompt` 在 v2 **已移除**（返回 `405`）。
  - 替代方案：把 `@path#Lx-y` 文本直接写入 OpenCode 终端（TTY），由 TUI 当作键入的输入处理。

---

## SSE 事件（`GET /api/event?directory=`）

- 帧格式：`data: <裸 JSON 对象>`（没有 `event:` 行，载荷不再二次编码）；`: heartbeat` 等注释行忽略。
- 事件信封除业务字段外，还可能带 `durable: {aggregateID, seq, version}` 等簿记字段（可忽略）。

### 本插件关注的 v2 事件

插件 v2-only，仅解码以下事件，其余一律归为 `UnknownEvent` 忽略：

- `session.execution.started` → `{sessionID}`：一轮对话开始（真正的 v2 "busy" 信号）。
- `session.execution.succeeded | failed | interrupted` → `{sessionID}`：一轮对话结束；`failed` 另带 `error.message`。
- `session.step.ended` → `{sessionID, assistantMessageID?, files:[], snapshot?}`：单步结束，携带该步内变更的文件列表（取代 v1 的 `session.diff`）。

> v1 事件名（`session.status`、`session.idle`、`session.diff`、`session.updated`、`message.updated`、
> `message.part.*`、`command.executed`、`file.edited`）的载荷位于 `properties` 字段下，v2 线路上从未观测到；
> 插件是 v2-only，不再解码这些事件。

### 其他 v2 事件（当前忽略）

- `session.created | updated | deleted`、`session.error`、`session.compacted`。
- `message.updated`、`message.part.updated | removed | delta`、`command.executed`。
- `vcs.branch.updated`、`worktree.ready | failed`、`mcp.*`、`permission.*`、`filesystem.changed`、
  `server.connected`、`global.disposed`、`tui.*`。

---

## 本插件当前 API/事件使用点

- `GET /api/info`：健康检查与版本门禁
- `GET /api/session?directory=`：连接时的凭据探测（`checkAuth`）；`/api/info` 是匿名的，无法验证密码
- `GET /api/session/{id}/diff?directory=`：获取一轮 diff（**省略 `from`**，默认为最新一轮 user 消息）
- `GET /api/event?directory=`：SSE 事件订阅
- 订阅事件（仅此三类，其余忽略）：`session.execution.*`、`session.step.ended`
- `@`-引用写入终端 TTY（不再使用 `/tui/append-prompt`）

> 说明：`OpenCodeApiClient` 还定义了 `getSessions` / `getActiveSession` / `getSession`，但插件主流程不使用
> `/api/session/active` 与 `/api/session/{id}`；会话列表经 `GET /api/session` 仅用于凭据探测。
