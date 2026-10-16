# OpenCode JetBrains Plugin — opencode v2 Compatibility Design

Date: 2026-10-15
Branch: `feature/opencode-v2-compat`
Status: Awaiting approval

## 1. Intent / Why

The plugin was written against opencode **v1**. With opencode **v2** installed
(verified locally: `opencode v2.0.29`), the plugin cannot start at all: the CLI
flags it relies on no longer exist, and the HTTP/SSE API it talks to has been
replaced by an entirely different surface.

Goal: make the plugin work again on opencode v2 with **full feature parity**
(terminal launch, session tracking, diff review, completion notifications,
`@`-reference injection, web mode), while keeping the existing UX as close as
possible.

## 2. Verified v1 → v2 Differences (evidence-based)

All facts below were verified against a running `opencode serve` and the
installed v2.0.29 binary on the development machine.

### 2.1 CLI

| | v1 (current code) | v2 (verified) |
|---|---|---|
| Launch server | `opencode --hostname H --port P --continue` | top-level flags removed |
| TUI | same process hosts a server | `opencode` = TUI attached to a **background service** |
| Server-only | — | `opencode serve [--hostname --port]` = v2 API + web server |
| Private server | — | `opencode --standalone` |
| Attach to server | — | `opencode --server <url>` |
| Service mgmt | — | `opencode service start/stop/status/get/set` |
| Continue | `--continue` | `--continue` / `-c` still exists (top-level) |

Current code builds: `"$cmd --hostname $h --port $p [--continue]"`
(`OpenCodeService.kt:646`). v2 rejects `--hostname/--port` at the top level →
process exits → "CLI 启动都无法完成".

### 2.2 Auth

- v1: `OPENCODE_SERVER_PASSWORD` env var (injected by `buildOpenCodeCommand`).
- v2: HTTP **Basic** `opencode:<password>`.
  - Explicit `opencode serve` prints the password to stdout.
  - Background service password lives in **`~/.config/opencode/service.json`**:
    `{"password": "..."}` (file mode 600).

### 2.3 Service discovery

- `opencode service status` prints the running background service URL,
  e.g. `http://127.0.0.1:49374`.
- `opencode service get` returned `{}` on the tested install; the password is in
  the `service.json` file, the URL is obtained from `service status`
  (or by starting the service).

### 2.4 HTTP API (all paths now under `/api`)

| Capability | v1 | v2 |
|---|---|---|
| Health | `GET /global/health` | `GET /api/info` → `{version,pid,urls,paths,capabilities}` |
| List sessions | `GET /session?directory=` → `Session[]` | `GET /api/session?directory=` → `{data:[Session.Info]}` |
| Session status | `GET /session/status` → `{id: status}` | `GET /api/session/active` → `{data:{}}` (active/busy session) |
| Session detail | `GET /session/:id` | `GET /api/session/:id` |
| Diff | `GET /session/:id/diff?messageID=` → `FileDiff[]` | `GET /api/session/:id/diff?from=&to=&context=` → `{data:[FileDiff.Info]}` |
| Messages | `GET /session/:id/message` | `GET /api/session/:id/message?limit=&cursor=` → `{data:[Message]}` |
| Prompt | (n/a) | `POST /api/session/:id/prompt` (`PromptInput.*`) |
| Command | `POST /session/:id/command` | `POST /api/session/:id/command` |
| Append to TUI input | `POST /tui/append-prompt` | **removed** (405) |

Auth is required on every route (401 otherwise). Responses are wrapped in
`{data: ...}` envelopes.

### 2.5 SSE events

- Subscribe: `GET /api/event` (SSE). Each frame: `data:` = a JSON object with
  the event fields plus an envelope (`{..., "durable": {aggregateID, seq, version}}`).
- Event **types** (from the v2 binary's event definitions) and payloads:
  - `session.created|updated|deleted` → `{sessionID, info: Session.Info}`
  - `session.status` → `{sessionID, status: {type: idle|busy|retry, ...}}`
  - `session.idle` → `{sessionID}`
  - `session.diff` → `{sessionID, diff: [FileDiff.Info]}`
  - `session.error` → `{sessionID?, error}`
  - `message.updated` → `{sessionID, info: Message}`
  - `message.removed` → `{sessionID, messageID}`
  - `message.part.updated` → `{sessionID, part, time}`
  - `message.part.removed` → `{sessionID, messageID, partID}`
  - `message.part.delta` → `{sessionID, messageID, partID, field, delta}`
  - `command.executed` → `{name, sessionID, arguments, messageID}`
  - `file.edited` → `{file}`
  - `session.compacted` → `{sessionID}`
  - `vcs.branch.updated`, `worktree.ready|failed`, `mcp.*`, `permission.*`,
    `filesystem.changed`, `server.connected`, `global.disposed`, `tui.*`

### 2.6 Diff data shape (critical)

- v1 `FileDiff` used by the plugin: `{file, before, after, additions, deletions}`
  (full before/after **contents**).
- v2 `FileDiff.Info`: `{file, patch (unified diff string), additions, deletions, status}`
  — **no before/after content**.
- The plugin's Diff viewer (`DiffViewerService`, `SessionManager`,
  `DiffEntry.beforeContent/resolvedBefore`) is built around before/after
  contents and its own local git snapshot logic. This is the largest logic
  change in the port.

## 3. Goals

1. Plugin launches the opencode v2 TUI and connects to the same backend it uses.
2. Session discovery/status, diff review, completion notifications, `@`-reference
   injection, and web mode all work on v2.
3. Keep user-facing UX and behavior as close to today as possible.
4. Keep the build green (`./gradlew build`, `./gradlew test`).

## 4. Non-Goals

- Supporting opencode v1 and v2 simultaneously. **Decision (my call): v2-only.**
  v1's top-level flags are gone, so dual support would be dead weight. Revisit if
  users report being pinned to v1.
- Adding brand-new features beyond parity.
- Rewriting the diff *viewer UI*; only the data pipeline feeding it changes.
- Changing the plugin ID / marketplace identity.

## 5. Constraints

- IntelliJ Platform 2025.2.4, JDK 17, Kotlin 2.1.20; use `./gradlew` wrapper.
- Follow repo conventions in `AGENTS.md` (4-space indent, no `!!`, EDT for UI,
  background threads for IO, `PathUtil` for paths, English comments).
- Reuse existing abstractions (`OpenCodeApiClient`, `SseEventListener`,
  `SessionManager`, `DiffViewerService`, terminal classes) rather than replacing
  the app architecture.

## 6. Design

### 6.1 Connection model (Decision: use the background service)

- For **local** use, the plugin does not own a port anymore. It:
  1. Ensures the background service is running (`opencode service start` if
     `service status` shows nothing).
  2. Reads the service URL from `opencode service status`.
  3. Reads the password from `~/.config/opencode/service.json`.
  4. Builds the API client against `http://<host>:<port>` with Basic auth.
- The terminal tab runs the **TUI**: `opencode [--continue] [--session <id>]`
  in the chosen working directory. The TUI auto-connects to the same background
  service; the plugin's API client talks to that same service.
- **Remote / explicit host:port** connect flow is kept: user supplies host:port
  (and password), plugin connects directly. This covers `opencode serve` on
  another machine.
- **Web mode**: open `http://<host>:<port>/doc` in the existing JCEF web tab.

Consequence: the connection dialog's "port" field is no longer meaningful for
the local "create new" case (port is service-managed). Keep the field for
remote/existing-server connect, and make local-create ignore it. The tab title
should show the discovered service URL instead of a hardcoded port
(e.g. `OpenCode(<port>)` where port is discovered).

### 6.2 CLI launch (fixes the immediate blocker)

- Replace `buildOpenCodeCommand` with a v2 command builder:
  - Local TUI: `opencode [--continue]` (no `--hostname/--port`).
  - Optionally `--session <id>` to resume a specific session.
  - Password is no longer passed via env for the local case (background service
    handles auth); only pass `OPENCODE_SERVER_PASSWORD` for an explicitly started
    `opencode serve` if we still support that path.
- Keep CLI binary auto-detection (`getOpenCodeBinary`/`detectOpenCodeBinary`),
  extend candidate paths to include the Homebrew `opencode-v2` location if needed.

### 6.3 API client (`OpenCodeApiClient`)

- Base URL provided by the discovery step; add Basic auth always when a password
  is known.
- Endpoints:
  - `checkHealth` → `GET /api/info`.
  - `getSessions(directory)` → `GET /api/session?directory=` → map `{data}`.
  - `findBusySession/active` → `GET /api/session/active` → `{data}`.
  - `getSessionDiff(sessionId, directory, fromMessageId?)` →
    `GET /api/session/:id/diff?from=` → `{data:[FileDiff.Info]}`.
  - `getMessages(sessionId, directory, limit)` → `GET /api/session/:id/message`.
- Response envelope unwrapping (`{data}`) added centrally.

### 6.4 SSE listener (`SseEventListener`)

- Subscribe to `/api/event` (with Basic auth). Parse the SSE `data:` JSON.
- Re-parse the event payload into the v2 event model, dispatch by `type`.
- Reconnect logic unchanged.

### 6.5 Event model (`api/models/*`)

- Rewrite `Events.kt` for v2 types/envelopes; drop `FileDiffDeserializer`'s
  Go-quote workaround if v2 returns clean JSON (verify during implementation).
- `FileDiff` model becomes `{file, patch, additions, deletions, status}`.
- Keep a mapping layer (`SessionManager`) that derives before/after content for
  the viewer from the `patch` + local file/git state.

### 6.6 Diff pipeline (biggest change)

- `SessionManager` currently trusts server `before/after` + local snapshots.
  Port to:
  - Use v2 `patch` to know *which* files/hunks changed and additions/deletions.
  - Derive `before` from the local git snapshot captured at turn start (the
    existing snapshot mechanism), and `after` from the current working tree.
  - Keep the "server-authoritative file list + local before" reconciliation.
- This is where most testing effort goes; may need the diff endpoint's
  `context` parameter to reconstruct full content, or a fallback to
  `GET /api/session/:id/message` patch parts.

### 6.7 `@`-reference injection

- `/tui/append-prompt` is gone. **Decision (my call): write the reference text
  to the terminal (TTY) of the OpenCode tab**, which the v2 TUI treats as typed
  input. This preserves the "fill the input box" UX.
- Alternative considered: `POST /api/session/:id/prompt` — rejected because it
  *submits* a turn instead of pre-filling input, changing UX.
- The `@path#Lstart-end` reference **syntax** itself is opencode-agnostic
  (opencode renders `@path` mentions); keep it, but verify against v2 TUI
  mention parsing.

### 6.8 Notifications / turn barrier

- `handleEvent` re-mapped to v2 event types. `session.status {busy|idle}` and
  `session.idle` drive turn start/end and the diff barrier, exactly as today.
- `message.updated` still used to capture the turn's message id (now for the
  `from` parameter of the diff query).

### 6.9 Web mode

- Point the web tab at `http://host:port/doc` (v2 serves the web UI there).

## 7. Decisions Made (my calls — check these)

1. **v2-only**; drop v1 code paths.
2. Local mode uses the **background service** (user-selected option).
3. `@`-injection via **terminal TTY input**, not the prompt API.
4. Keep `@path#Lx-y` syntax unchanged unless v2 TUI parsing differs.
5. Reuse the existing diff viewer UI; only re-pipe its data.

## 8. Left to the Builder

- Exact shape/parse of the `/api/event` frame (envelope vs raw) — confirm with a
  live capture during implementation.
- How to reconstruct full before/after for the diff viewer from `patch`
  (git snapshot vs `message` parts vs `context` param). Pick the most reliable.
- Whether to auto-run `opencode service start`, or fail with a clear message.
- Exact resume semantics: `--continue` vs `--session <id>` for "restore last
  session".
- Test approach: extend `FakeOpenCodeServer` to serve v2 routes; port the
  integration tests.

## 9. Risks

- Diff reconstruction may be lossy vs v1 (content vs patch). Mitigation: rely on
  local git snapshots that already exist.
- Background service is global/shared; multiple IDE projects share one service.
  Session scoping is by `directory` query param — verify isolation.
- v2 API is labeled "Experimental" (`opencode HttpApi 0.0.1`) — pin behavior to
  the installed version and document the supported range.

## 10. Builder-Check Resolutions

A fresh builder review raised 8 questions. Resolutions (verified where noted):

1. **Session/event scoping on the shared service** — `/api/event` accepts a
   `directory` query param (verified 200) and scopes events to that location;
   the plugin subscribes with the project base path. Sessions are discovered via
   `GET /api/session?directory=` (newest by `time.updated`) and
   `GET /api/session/active`. Turn state stays keyed per `sessionID` (it already
   is). The "which session is this TUI on" question is answered by the newest /
   active session in the directory, same heuristic as v1. Concurrent child
   sessions (`parentID`) are out of scope for parity.
2. **Auth mandatory; remote credential story** — verified: every `/api/*` route
   returns 401 without Basic auth. `opencode serve` has **no** password flag, so
   the password is always server-generated and printed to stdout at startup. The
   dialog's password field becomes **required**. For remote, the user pastes the
   password printed by their `opencode serve`. `ProcessAuthDetector`'s
   `OPENCODE_SERVER_PASSWORD` env scraping is **removed** (dead for v2).
3. **No plugin-owned `serve`** — keep only: (a) local = background service,
   (b) connect-to-existing = user-provided host:port + password. Drop the
   "spawn an isolated `opencode serve --port P`" path, hence `PortFinder` /
   `waitForPort` usage shrinks to the background-service readiness wait.
4. **Service lifecycle** — the background service is global and shared; the
   plugin **never stops it**. It may start it if absent (`opencode service
   start`). On disconnect/project close it only drops its own API/SSE clients.
5. **Cross-platform discovery** — resolve the config dir via
   `XDG_CONFIG_HOME`/`os` conventions: `~/.config/opencode` on macOS/Linux,
   `%APPDATA%\opencode` on Windows; read `service.json`. Get the URL from
   `opencode service status` (parse the `http://host:port` token); add a
   `PathUtil`-style helper. If parsing fails, surface a clear error.
6. **Web mode auth** — `/doc` loads anonymously (verified 200 without auth); its
   API calls need the Basic header, which the existing web editor already
   injects. Treat web mode as a URL change to `/doc`, with header injection
   retained and verified during implementation.
7. **Version gate** — `GET /api/info` returns `version`; on connect, if the
   major version is not `2`, warn and offer to continue (don't hard-block).
8. **Diff `from`/`to`** — they are **message IDs** (`Session.Message.ID`).
   Omitting them defaults to the newest user message's turn. The barrier passes
   the captured turn user-message id as `from`; if absent, omit and accept the
   default.

## 11. Branching / Delivery (GitHub conventions)

- Work on `feature/opencode-v2-compat` off `main`. No direct commits to `main`.
- Conventional-commit messages (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`).
- Open a PR when green; verify with `./gradlew build test` before pushing.
- Since this is a fork, PR target (fork `main` vs upstream) to be confirmed with
  the maintainer before opening.
