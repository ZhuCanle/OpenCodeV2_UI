# OpenCode Plugin opencode-v2 Compatibility — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the OpenCode JetBrains plugin from the opencode v1 CLI/HTTP/SSE surface to opencode v2 so it launches, connects, tracks turns, reviews diffs, notifies, injects `@`-references, and serves web mode again.

**Architecture:** The plugin talks to the opencode v2 **background service** (one shared daemon) discovered via `opencode service status` + `~/.config/opencode/service.json`, authenticating with HTTP Basic `opencode:<password>` against `/api/*`. The terminal tab runs the v2 TUI (`opencode [--continue]`). The internal turn/barrier/diff architecture (SessionManager snapshots, DiffViewerService) is preserved; only its data source (v2 `FileDiff.Info.patch` + local git snapshots) changes.

**Tech Stack:** Kotlin 2.1.20, JDK 17, IntelliJ Platform 2025.2.4, OkHttp 4.12.0, Gson 2.11.0, JUnit 4.13.2, Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-10-15-opencode-v2-compat-design.md`

## Global Constraints

- Build/test with `./gradlew` wrapper only. Verify each task with `./gradlew test` (targeted class) then `./gradlew classes`.
- Kotlin coding conventions; 4-space indent; no `!!`; UI on EDT; IO on `AppExecutorUtil` background threads; paths via `PathUtil`.
- All comments/strings in English. Only user-facing behavior may remain localized as-is.
- v2 only: remove v1 code paths (do not keep dual support).
- Auth: HTTP Basic `opencode:<password>` on every `/api/*` request; password is mandatory.
- Never stop the shared background service; only start it if absent.
- API responses are `{data: ...}` envelopes; unwrap centrally.
- Do not change the plugin ID or marketplace identity.

## Amendments (from Task 1 wire capture — binding)

Task 1's real capture (`docs/superpowers/specs/2026-10-15-opencode-v2-wire-format.md`,
`src/test/resources/v2/`) shows v2.0.29 `/api/event` does **not** emit `session.status`,
`session.idle`, `message.updated`, `session.diff`, or `file.edited`. The real signals are:

- busy → `session.execution.started` `{sessionID}`
- idle → `session.execution.succeeded` `{sessionID}` / `session.execution.failed`
  `{sessionID,error{type,message,status,response.body}}` / `session.execution.interrupted`
- per-turn changed files → `session.step.ended` `{sessionID, assistantMessageID, finish,
  snapshot, files: [repoRelativePath], cost, tokens{...}}` (`files: []` when nothing changed)
- assistant message id → `assistantMessageID` on `session.step.started/ended`, `session.text.*`,
  `session.reasoning.*`, `session.tool.*`
- diff → no event; call `GET /api/session/:id/diff` **without `from`** (defaults to the newest
  user message's turn) after the turn ends
- SSE framing: frames are `data: <raw JSON object>\n\n` (name is the JSON `type` field);
  heartbeats are `: heartbeat\n\n`; there is **no** `event:` line and `data` is **not**
  double-encoded.

Keep the v1 event names as tolerant fallbacks (a live TUI might still use them), but the barrier
must be driven by `session.execution.*` and `session.step.ended`. Tasks 4, 6, and 7 below are
written to this amendment.

### Amendment 2 (final-review ruling — supersedes the "tolerant fallbacks" above)

The user ruled **strict v2-only**: the v1 event subtypes, their `properties`-based deserializer
routes, and the v1 `handleEvent` branches (`session.status`/`session.idle`/`session.diff`,
`message.updated`, `message.part.*`, `command.executed`, `file.edited`, `session.updated`) are
**deleted**, not kept as fallbacks. `Events.kt` now routes only `session.execution.started`,
`session.execution.{succeeded,failed,interrupted}`, and `session.step.ended`; everything else
deserializes to `UnknownEvent`. The fixture tests in `V2WireFixtureTest` assert both that the real
v2 frames parse and that the v1 names fall through to `UnknownEvent`. Update the amendment above as
historical context only.

The v1 `--session <id>` TUI resume flag was likewise dropped: only `opencode [--continue]` is
supported.

## Review Focus

Inputs/conditions the spec implies but no task naturally tests — each gets a test in the owning task:

1. `/api/event` frame where the JSON `data` is an encoded string (double-encoded) vs a raw object — must parse both (Task 4).
2. A session whose `FileDiff.Info.status == "added"` (new file) — `before` must resolve to empty, not the on-disk content (Task 7).
3. Empty `/api/session/active` result (`{"data":{}}`) — must not throw (Task 3).
4. Password file missing / unreadable — connect flow must fail with a clear error, not NPE (Task 1).
5. `opencode service status` output with extra log lines around the URL — parser must still extract the URL (Task 1).

---

### Task 1: v2 wire-format fixtures (spike)

**Files:**
- Create: `src/test/resources/v2/event-frame.txt`
- Create: `src/test/resources/v2/session-list.json`
- Create: `src/test/resources/v2/session-diff.json`
- Create: `docs/superpowers/specs/2026-10-15-opencode-v2-wire-format.md`

**Interfaces:**
- Produces: real captured fixtures used by Tasks 3, 4, 7.

- [ ] **Step 1: Start a v2 server and capture frames**

Run (from a scratch dir): `opencode serve --hostname 127.0.0.1 --port 14096` (note the printed password). Then capture the raw SSE and a diff:
```bash
curl -sN -u "opencode:<pw>" "http://127.0.0.1:14096/api/event?directory=<abs dir>" | head -5
curl -s -u "opencode:<pw>" "http://127.0.0.1:14096/api/session?directory=<abs dir>" -o session-list.json
curl -s -u "opencode:<pw>" "http://127.0.0.1:14096/api/session/<id>/diff?from=<msgId>" -o session-diff.json
```
Trigger activity (send a prompt in the TUI) so at least `session.status`, `session.idle`, `message.updated`, and `session.diff` frames appear.

- [ ] **Step 2: Save fixtures and document the exact frame encoding**

Copy the captured files into the paths above. In `...-wire-format.md`, record verbatim: the SSE line structure (`event:`/`data:` lines), whether `data` is a raw JSON object or a JSON-encoded string, and the exact field names of one `session.status`, one `message.updated`, one `session.diff`, and one `FileDiff.Info`.

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/v2 docs/superpowers/specs/2026-10-15-opencode-v2-wire-format.md
git commit -m "test: capture opencode v2 wire-format fixtures"
```

---

### Task 2: Service discovery utility

**Files:**
- Create: `src/main/kotlin/ai/opencode/ide/jetbrains/util/OpenCodeServiceDiscovery.kt`
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/util/OpenCodeServiceDiscoveryTest.kt`

**Interfaces:**
- Produces:
  - `data class ServiceEndpoint(val host: String, val port: Int, val password: String?)`
  - `object OpenCodeServiceDiscovery` with:
    - `fun parseServiceUrl(output: String): Pair<String, Int>?`
    - `fun readPassword(configDir: File = defaultConfigDir()): String?`
    - `fun defaultConfigDir(): File`
    - `fun discover(binary: String): ServiceEndpoint?`
    - `fun ensureRunning(binary: String): Boolean` (runs `opencode service start` if status empty)
- Consumes: `GeneralCommandLine`/`CapturingProcessHandler` (pattern already in `ProcessAuthDetector`).

- [ ] **Step 1: Write the failing test**

```kotlin
class OpenCodeServiceDiscoveryTest {
    @Test fun parseServiceUrlExtractsHostAndPort() {
        val out = "noise line\nhttp://127.0.0.1:49374\nmore\n"
        assertEquals("127.0.0.1" to 49374, OpenCodeServiceDiscovery.parseServiceUrl(out))
    }
    @Test fun parseServiceUrlReturnsNullWhenAbsent() {
        assertNull(OpenCodeServiceDiscovery.parseServiceUrl("no server running\n"))
    }
    @Test fun readPasswordParsesServiceJson() {
        val dir = createTempDir(); File(dir, "service.json").writeText("""{"password":"abc123"}""")
        assertEquals("abc123", OpenCodeServiceDiscovery.readPassword(dir))
    }
    @Test fun readPasswordReturnsNullWhenMissing() {
        assertNull(OpenCodeServiceDiscovery.readPassword(createTempDir()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "ai.opencode.ide.jetbrains.util.OpenCodeServiceDiscoveryTest"`
Expected: FAIL (unresolved reference `OpenCodeServiceDiscovery`).

- [ ] **Step 3: Implement**

`parseServiceUrl`: regex `https?://([^:\s/]+):(\d+)` over the whole output, first match, return host to port; null if none. `defaultConfigDir`: use `XDG_CONFIG_HOME` if set else `~/.config`, plus `opencode`; on Windows `%APPDATA%\opencode`. `readPassword`: read `service.json`, parse `password` via Gson, null on any failure. `discover`: run `<binary> service status`; if unparseable, `ensureRunning` then retry; read password. `ensureRunning`: run `<binary> service start` (ignore "already running").

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "ai.opencode.ide.jetbrains.util.OpenCodeServiceDiscoveryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ai/opencode/ide/jetbrains/util/OpenCodeServiceDiscovery.kt src/test/kotlin/ai/opencode/ide/jetbrains/util/OpenCodeServiceDiscoveryTest.kt
git commit -m "feat: add opencode v2 service discovery"
```

---

### Task 3: v2 HTTP client and models

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/api/models/FileDiff.kt`
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/api/models/Session.kt`
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/api/OpenCodeApiClient.kt`
- Modify: `src/test/kotlin/ai/opencode/ide/jetbrains/integration/FakeOpenCodeServer.kt`
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/api/OpenCodeApiClientV2Test.kt`

**Interfaces:**
- Produces:
  - `data class FileDiff(val file: String, val patch: String, val additions: Int, val deletions: Int, val status: String)` (status: `added|deleted|modified`).
  - `OpenCodeApiClient(hostname, port, username, password)` — unchanged constructor; now sends Basic auth always when password != null.
  - `fun checkHealth(directory: String): Boolean` → `GET /api/info` 200.
  - `fun getSessions(directory: String): List<Session>` → `GET /api/session?directory=`, unwrap `{data}`.
  - `fun getActiveSession(directory: String): Session?` → `GET /api/session/active`, unwrap `{data}` (object or `{}`).
  - `fun getSessionDiff(sessionId, directory, fromMessageId): List<FileDiff>` → `GET /api/session/{id}/diff?from=`, unwrap `{data}`.
- Consumes: `ServiceEndpoint` (Task 2) — not directly; the client is still constructed with host/port/password.

- [ ] **Step 1: Update the fake server to v2 routes + write failing tests**

In `FakeOpenCodeServer`: replace the `/tui/append-prompt`, `/global/health`, `/session` contexts with `/api/info`, `/api/session`, `/api/session/active`, `/api/session/<id>/diff` (return `{"data": [...]}`), and `/api/event`. Require `Authorization: Basic ...` (return 401 otherwise). Keep `setDiffResponse`/`broadcast` helpers.

```kotlin
@Test fun getSessionsUnwrapsDataEnvelope() {
    fake.setSessions("""{"data":[{"id":"ses_1","projectID":"p","location":{"directory":"/x"},"title":"t","time":{"created":1.0,"updated":2.0}}]}""")
    val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "pw")
    assertEquals(1, client.getSessions("/x").size)
}
@Test fun getActiveSessionHandlesEmptyObject() {
    val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "pw")
    assertNull(client.getActiveSession("/x"))   // server returns {"data":{}}
}
@Test fun getSessionDiffUnwrapsData() {
    fake.setDiffResponse("msg_1", """{"data":[{"file":"a.kt","patch":"@@","additions":1,"deletions":0,"status":"modified"}]}""")
    val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "pw")
    assertEquals("a.kt", client.getSessionDiff("ses_1", "/x", "msg_1").first().file)
}
@Test fun requestsCarryBasicAuth() {
    fake.requireAuth("opencode", "pw")
    val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "wrong")
    assertTrue(client.getSessions("/x").isEmpty())  // 401 -> empty
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ai.opencode.ide.jetbrains.api.OpenCodeApiClientV2Test"`
Expected: FAIL (endpoints/fields unresolved).

- [ ] **Step 3: Implement models + client**

`FileDiff` fields per above; delete `FileDiffDeserializer`'s Go-quote logic (keep `decodeGoQuotedString` only if Task 1 fixtures show it is still needed). `Session` keeps `id, projectID, title, parentID?, time`, and gets `directory` sourced from `location.directory` (custom deserializer or a `location` field). Client: change paths to `/api/*`, add `Authorization: Basic` for all requests when password != null, add `unwrapData` helper that returns the `data` element. `getActiveSession` returns null when `data` is an empty object.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "ai.opencode.ide.jetbrains.api.OpenCodeApiClientV2Test"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: port OpenCodeApiClient to opencode v2 /api surface"
```

---

### Task 4: v2 SSE listener and event model

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/api/models/Events.kt`
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/api/SseEventListener.kt`
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/api/OpenCodeApiClient.kt` (`createEventListener` path)
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/api/SseEventParsingTest.kt`

**Interfaces:**
- Produces:
  - New sealed subtypes: `SessionExecutionStartedEvent(sessionID: String)`,
    `SessionExecutionEndedEvent(sessionID: String, outcome: String)` (outcome = `succeeded|failed|interrupted`),
    `SessionStepEndedEvent(sessionID: String, assistantMessageID: String?, files: List<String>, snapshot: String?)`.
  - `OpenCodeEventDeserializer` routes real v2 names (`session.execution.started`,
    `session.execution.succeeded|failed|interrupted`, `session.step.ended`) to the new subtypes,
    and keeps the v1 names (`session.status`, `session.idle`, `session.diff`, `message.updated`,
    `file.edited`, `command.executed`) as tolerant fallbacks to the existing subtypes. Unknown → `UnknownEvent`.
  - `SseEventListener.decodeFrame(payload: String): OpenCodeEvent?` — `payload` is the text after
    `data:`; parse as a **raw JSON object** (per wire doc §1.1).
  - `MessageInfo` gains `type: String?` as well as `role: String?`.
- Consumes: Task 3's `OpenCodeApiClient.createEventListener`.

- [ ] **Step 1: Write failing tests from the Task 1 fixtures**

```kotlin
@Test fun parsesExecutionStartedAsTurnStart() {
    val e = parse("""{"id":"evt_1","type":"session.execution.started","data":{"sessionID":"s1"}}""")
    assertTrue(e is SessionExecutionStartedEvent && e.sessionID == "s1")
}
@Test fun parsesExecutionSucceededAsTurnEnd() {
    val e = parse("""{"type":"session.execution.succeeded","data":{"sessionID":"s1"}}""")
    assertTrue(e is SessionExecutionEndedEvent && e.outcome == "succeeded")
}
@Test fun parsesStepEndedChangedFiles() {
    val e = parse("""{"type":"session.step.ended","data":{"sessionID":"s1","assistantMessageID":"m1","finish":"stop","files":["a.kt","b.kt"]}}""")
    assertEquals(listOf("a.kt","b.kt"), (e as SessionStepEndedEvent).files)
}
@Test fun parsesV1NamesAsFallback() {
    assertTrue(parse("""{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""") is SessionStatusEvent)
}
@Test fun parsesV2MessageUpdatedUsingTypeField() {
    val e = parse("""{"type":"message.updated","properties":{"info":{"id":"m1","sessionID":"s1","type":"assistant"}}}""")
    assertEquals("m1", (e as MessageUpdatedEvent).properties.info.id)
}
@Test fun decodeFrameReadsRawJsonObject() {
    val ev = SseEventListener.decodeFrame("""{"type":"session.execution.started","data":{"sessionID":"s1"}}""")
    assertTrue(ev is SessionExecutionStartedEvent)
}
@Test fun unknownEventIsIgnoredNotThrown() {
    assertTrue(parse("""{"type":"mcp.tools.changed","data":{}}""") is UnknownEvent)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ai.opencode.ide.jetbrains.api.SseEventParsingTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Update `Events.kt`: add the three new sealed subtypes; add `type` to `MessageInfo`. Update `OpenCodeEventDeserializer` to route the real v2 `type` values (execution/step) and keep the v1 names as fallbacks; ignore the `durable`/`created`/`location`/`metadata` envelope fields. `SseEventListener.decodeFrame` parses `data.value` as a raw JSON object (no unquoting). Frames to ignore: lines starting `:` (heartbeat). Point `createEventListener` at `/api/event?directory=`. Send Basic auth on the SSE request.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "ai.opencode.ide.jetbrains.api.SseEventParsingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: parse opencode v2 SSE events from /api/event"
```

---

### Task 5: v2 CLI command builder

**Files:**
- Create: `src/main/kotlin/ai/opencode/ide/jetbrains/util/OpenCodeCommand.kt`
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/OpenCodeService.kt` (`buildOpenCodeCommand`)
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/util/OpenCodeCommandTest.kt`

**Interfaces:**
- Produces: `object OpenCodeCommand { fun tui(binary: String, cont: Boolean, sessionId: String? = null): String }`
- Consumes: none.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun plainTuiCommandHasNoHostOrPortFlags() {
    assertEquals("opencode", OpenCodeCommand.tui("opencode", cont = false))
}
@Test fun continueFlagAddsContinue() {
    assertEquals("opencode --continue", OpenCodeCommand.tui("opencode", cont = true))
}
@Test fun sessionIdAddsSessionFlagAndWinsOverContinue() {
    assertEquals("opencode --session ses_1", OpenCodeCommand.tui("opencode", cont = true, sessionId = "ses_1"))
}
@Test fun pathWithSpacesIsQuoted() {
    assertEquals("\"/opt/my tools/opencode\" --continue", OpenCodeCommand.tui("/opt/my tools/opencode", cont = true))
}
```

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew test --tests "ai.opencode.ide.jetbrains.util.OpenCodeCommandTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** `tui`: quote binary if it contains a space; append `--session <id>` else `--continue` when `cont`. No `--hostname/--port`.

- [ ] **Step 4: Run to verify pass** — same command, PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: build opencode v2 TUI launch command"
```

---

### Task 6: Connect flow via service discovery + version gate

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/OpenCodeService.kt`
  (`processConnectionChoice`, `createLocalTerminal`, `createTerminalUIInternal`, `tryConnect`, `initializeApiClient`, `restartServer`)
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/util/PortFinder.kt` (health endpoint → `/api/info`)
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/OpenCodeServiceConnectTest.kt` (optional, can defer to Task 13)

**Interfaces:**
- Consumes: `OpenCodeServiceDiscovery.discover/ensureRunning` (Task 2), `OpenCodeCommand.tui` (Task 5), `OpenCodeApiClient` (Task 3).
- Produces: `OpenCodeService` connects to the discovered endpoint; local terminal launched with `OpenCodeCommand.tui`; API client built from `ServiceEndpoint`.

- [ ] **Step 1: Write failing test** (unit-level, mocking discovery)

```kotlin
@Test fun localConnectUsesDiscoveredEndpointAndLaunchesTui() {
    // inject a fake discovery returning host=127.0.0.1 port=49374 password="pw"
    // assert the built command has no --port and contains "--continue"
}
```

- [ ] **Step 2: Run to verify fail** — `./gradlew test --tests "*OpenCodeServiceConnectTest"`, FAIL.

- [ ] **Step 3: Implement**
  - `createLocalTerminal`: resolve the binary, call `OpenCodeServiceDiscovery.discover(binary)`; on null show "OpenCode service unavailable" error; else set `hostname/port/password` from the endpoint and create the terminal with `OpenCodeCommand.tui(binary, cont = true)`.
  - Remove the `--hostname/--port` command path and the local `waitForPort` gate; keep a short readiness poll against `/api/info`.
  - `PortFinder.isOpenCodeRunningOnPort`: hit `/api/info` (accept 200; keep 401/403 as "auth required but present").
  - `tryConnect`: after health, call `GET /api/info` and read `version`; if major != 2 show a non-blocking warning ("opencode vX detected; plugin targets v2").
  - `ConnectionMode`: keep TERMINAL/WEB; local create uses TERMINAL.
  - Tab title: `OpenCode(<discoveredPort>)`.
  - `handleEvent` mapping per the Amendment: `SessionExecutionStartedEvent` → `sessionManager.onTurnStart()`; `SessionExecutionEndedEvent` (any outcome) → `sessionManager.onTurnEnd()` + barrier; `SessionStepEndedEvent` → add its `files[]` to the turn's server-edited set (equivalent of `onFileEdited`) and remember `assistantMessageID`. Keep the old `SessionStatusEvent`/`SessionIdleEvent`/`FileEditedEvent`/`SessionDiffEvent` branches as tolerant fallbacks. Diff fetch calls `client.getSessionDiff(sessionId, path)` with **no** `from`.

- [ ] **Step 4: Run to verify pass** — PASS (or document deferral to Task 13 if IDE fixtures are heavy).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: connect plugin to opencode v2 background service"
```

---

### Task 7: Diff pipeline on v2 (patch + local snapshots)

> **Ordering ruling:** this task MUST be executed immediately after Task 3 (before Tasks 4–6),
> because the Task 3 `FileDiff` change breaks compilation of the main source set until this task
> lands. Its scope is expanded to restore a compiling main source set.

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/api/models/DiffEntry.kt`
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/diff/DiffViewerService.kt` (use `entry.resolvedAfter` instead of `entry.diff.after`)
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/OpenCodeService.kt` (`forceVfsRefresh` dummy `FileDiff(...)` construction only — the connect-flow work is Task 6)
- Modify: `src/test/kotlin/ai/opencode/ide/jetbrains/integration/RealProcessIntegrationTest.kt` (fix `FileDiff(...)` named-arg constructions so the TEST source set compiles — behavior update deferred to Task 12)
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/session/SessionManager.kt`
  (`resolveBeforeContent`, `createSyntheticDiff`, `getProcessedDiffs`, `onTurnEnd`, `acceptDiff`/`rejectDiff` guards)
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/session/SessionManagerV2DiffTest.kt`

**Interfaces:**
- Consumes: `FileDiff{file,patch,additions,deletions,status}` (Task 3); the server file list arrives from `getSessionDiff` (called without `from`, so it is the newest user turn) and from `SessionStepEndedEvent.files[]` recorded as server-edited in Task 6.
- Produces:
  - `FileDiff.newFile: Boolean get() = status == "added"`
  - `FileDiff.deleted: Boolean get() = status == "deleted"`
  - `DiffEntry.isNewFile` uses `diff.newFile`.
  - `DiffEntry.beforeContent` = `resolvedBefore ?: ""` (no server before anymore).
  - `DiffEntry.resolvedAfter: String` — the file's current on-disk content (`""` when deleted); `DiffViewerService` renders `entry.resolvedAfter` instead of `entry.diff.after`.
  - `SessionManager.getProcessedDiffs` no longer reads `diff.before/after`; derives `after` from disk and `before` from snapshot sources; uses `status` to classify.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun newFileStatusForcesEmptyBefore() {
    val d = FileDiff("new.kt", "@@ -0,0 +1 @@", 1, 0, status = "added")
    val entry = sm.getProcessedDiffs(listOf(d), snapshotWith(capturedBefore = mapOf("new.kt" to "SHOULD_NOT_BE_USED")))
        .first { it.file == "new.kt" }
    assertEquals("", entry.beforeContent)
}
@Test fun modifiedFileUsesCapturedBeforeAndDiskAfter() {
    // disk new.kt contains "new"; captured before "old"
    val d = FileDiff("a.kt", "@@", 1, 1, status = "modified")
    val entry = sm.getProcessedDiffs(listOf(d), snapshotWith(capturedBefore = mapOf("a.kt" to "old"))).first()
    assertEquals("old", entry.beforeContent)
}
@Test fun deletedFileUsesCapturedBeforeAndEmptyAfter() {
    val d = FileDiff("gone.kt", "@@", 0, 3, status = "deleted")
    // disk: file removed; captured before "old"
    val entry = sm.getProcessedDiffs(listOf(d), snapshotWith(capturedBefore = mapOf("gone.kt" to "old"))).first()
    assertEquals("old", entry.beforeContent)
}
```

- [ ] **Step 2: Run to verify fail** — FAIL.

- [ ] **Step 3: Implement**
  - `onTurnEnd`: for each server/known file, read current disk content into `lastKnownFileStates` (already does) — keep.
  - `resolveBeforeContent`: drop all `diff.before` branches. Order: (1) `diff.newFile` → `""`; (2) snapshot captured/LocalHistory/known-state; (3) if `diff.deleted` and nothing captured and absPath exists → disk content; (4) else `""`.
  - `createSyntheticDiff`: use `diff.status` and `File.exists()` for before/after; before from snapshot sources; after from disk (`""` if deleted).
  - `getProcessedDiffs` pre-filter/filter: compare `before` vs computed `after` (read disk once) instead of `diff.after`.
  - `DiffEntry`: `isNewFile` = `isCreatedExplicitly && diff.newFile`; `beforeContent = resolvedBefore ?: ""`; add `resolvedAfter: String` (populate in `getProcessedDiffs` from disk).
  - `DiffViewerService.showMultiFileDiff`: render `entry.resolvedAfter` instead of `entry.diff.after`.
  - `OpenCodeService.forceVfsRefresh`: fix the dummy `FileDiff(...)` construction to the new 5-field shape (keep behavior; only compilation).
  - Add a `deriveAfter(path): String` helper (disk read; `""` when missing).
- [ ] **Step 4: Run `./gradlew classes` to verify the main source set compiles again** — Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Run the new SessionManager test** `./gradlew test --tests "*SessionManagerV2DiffTest"` — Expected PASS. (The test source set must compile: `RealProcessIntegrationTest` was fixed for compilation in this task; `OpenCodeLogicTest` and `SendSelectionToTerminalActionTest` behavior are updated in Tasks 4/8/12 and must not be edited here.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: drive diff pipeline from v2 patch + local snapshots"
```

---

### Task 8: `@`-reference injection via terminal

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/OpenCodeService.kt` (`pasteToTerminal`, `schedulePasteAttempt`)
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/terminal/OpenCodeTerminalFileEditor.kt` (expose a write-to-tty helper)
- Delete: `src/main/kotlin/ai/opencode/ide/jetbrains/api/models/Tui.kt` (and `tuiAppendPrompt` from the client)
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/TerminalPasteTest.kt`

**Interfaces:**
- Produces: `OpenCodeService.pasteToTerminal(text: String): Boolean` writes `text` to the terminal's `TtyConnector`.
- Consumes: existing terminal widget.

- [ ] **Step 1: Write failing test** — fake `TtyConnector` captures written bytes; assert `pasteToTerminal("@a.kt ")` writes exactly that string.

- [ ] **Step 2: Run to verify fail** — FAIL.

- [ ] **Step 3: Implement** `pasteToTerminal` to write to `terminalEditor?.terminalWidget?.ttyConnector` (create a small `sendText(text)` on the editor). Remove the `apiClient.tuiAppendPrompt` path. Delete `Tui.kt` and `tuiAppendPrompt`.

- [ ] **Step 4: Run to verify pass** — PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: inject @-references into the v2 TUI via terminal input"
```

---

### Task 9: Web mode targets `/doc`

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/web/WebModeSupport.kt`
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/web/WebModeUrlTest.kt`

**Interfaces:**
- Produces: `WebModeSupport.buildUrl(host: String, port: Int): String` → `http://<host>:<port>/doc` (host `0.0.0.0` → `127.0.0.1`).

- [ ] **Step 1: Write failing test** — `assertEquals("http://127.0.0.1:49374/doc", WebModeSupport.buildUrl("0.0.0.0", 49374))`.

- [ ] **Step 2: Run to verify fail** — FAIL.

- [ ] **Step 3: Implement** `buildUrl` and use it in `openWebTab`; keep password/header injection for the web editor.

- [ ] **Step 4: Run to verify pass** — PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: point web mode at v2 /doc"
```

---

### Task 10: Connect dialog (password required, port semantics)

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/ui/OpenCodeConnectDialog.kt`
- Test: `src/test/kotlin/ai/opencode/ide/jetbrains/ui/ConnectDialogValidationTest.kt`

**Interfaces:**
- Produces: dialog validation that rejects blank password; `ConnectionInfo` unchanged; local-create no longer relies on the port field (still shown for remote).

- [ ] **Step 1: Write failing test** — assert `validateConnection(host="127.0.0.1:49374", password="")` returns a `ValidationInfo`, and with a password returns null.

- [ ] **Step 2: Run to verify fail** — FAIL.

- [ ] **Step 3: Implement** extract a `validateConnection(host, password): ValidationInfo?` used by `doValidate`; update the label to "Server password (required)"; keep the address field for remote connect.

- [ ] **Step 4: Run to verify pass** — PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: require server password in connect dialog"
```

---

### Task 11: Remove dead v1 code and update docs

**Files:**
- Modify: `src/main/kotlin/ai/opencode/ide/jetbrains/util/ProcessAuthDetector.kt` (delete file or strip env scraping)
- Modify: `build.gradle.kts` (version → `1.2.0`, changeNotes)
- Create: `releaseNotes/120.md`
- Modify: `README.md` (Requirements: opencode v2; connect instructions)
- Modify: `designDocs/opencode_api_notes.md`

**Interfaces:** none (cleanup + docs).

- [ ] **Step 1: Remove dead code** — delete `ProcessAuthDetector` env-scraping usage from `OpenCodeService`; if nothing else uses it, delete the file. Remove `Tui.kt` references (done in Task 8). Remove unused `waitForPort` callers.

- [ ] **Step 2: Bump version + notes** — `build.gradle.kts` version `1.2.0`; changeNotes describe v2 support; add `releaseNotes/120.md`.

- [ ] **Step 3: Update docs** — README requirements to opencode v2 and the service-based connect flow; refresh `opencode_api_notes.md` to the v2 `/api` surface.

- [ ] **Step 4: Verify build** — `./gradlew build` (compiles; then) `./gradlew test`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: drop v1 code paths, bump to 1.2.0, update docs"
```

---

### Task 12: End-to-end integration on the v2 fake server

**Files:**
- Modify: `src/test/kotlin/ai/opencode/ide/jetbrains/integration/FakeOpenCodeServer.kt` (finalize `/api` routes + SSE)
- Modify: `src/test/kotlin/ai/opencode/ide/jetbrains/integration/OpenCodeLogicTest.kt`
- Modify: `src/test/kotlin/ai/opencode/ide/jetbrains/integration/RealProcessIntegrationTest.kt`

**Interfaces:** Consumes everything above.

- [ ] **Step 1: Rewrite the fake server** to serve `/api/info`, `/api/session`, `/api/session/active`, `/api/session/{id}/diff`, `/api/event`, all behind Basic auth; `broadcast` emits v2 frames.

- [ ] **Step 2: Update `OpenCodeLogicTest`** to v2 event payloads (`message.updated.info.type`, `session.diff`), and diff responses as `{"data":[{file,patch,status,additions,deletions}]}`.

- [ ] **Step 3: Run** `./gradlew test --tests "*OpenCodeLogicTest"` — Expected PASS. Fix until green.

- [ ] **Step 4: Update `RealProcessIntegrationTest`** to spawn a real `opencode serve` if the CLI is present (skip otherwise) and assert `/api/info` version is 2.x.

- [ ] **Step 5: Run full suite** `./gradlew test` — Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: end-to-end v2 integration coverage"
```

---

### Task 13: Manual verification + PR

- [ ] **Step 1: Run IDE sandbox** — `./gradlew runIde`; press `Cmd+Esc`; create a local terminal; confirm the TUI starts and the plugin connects (status/log shows `/api/info` OK).

- [ ] **Step 2: Verify features** — send a prompt that edits a file; confirm the diff viewer appears, Accept (git add) and Reject (restore) work; select code and press `Opt+Cmd+K`, confirm the `@path#Lx-y` text lands in the TUI input; trigger completion and confirm the notification; open web mode and confirm `/doc` loads.

- [ ] **Step 3: Push branch and open PR** — `git push -u origin feature/opencode-v2-compat`; open a PR to `main` (confirm target with maintainer: fork `main` vs upstream). Conventional-commit history.

---

## Self-Review

- **Spec coverage:** §2/§2.1 CLI → Task 5,6; §2.2 auth → Tasks 2,3,10; §2.3 discovery → Task 2; §2.4 API → Task 3; §2.5 events → Task 4; §2.6 diff → Task 7; §6.7 paste → Task 8; §6.9 web → Task 9; §10.4 lifecycle → Task 6 (never stop); §10.7 version gate → Task 6; §11 delivery → Task 13.
- **Type consistency:** `FileDiff{file,patch,additions,deletions,status}`, `ServiceEndpoint{host,port,password}`, `OpenCodeCommand.tui(binary,cont,sessionId)`, `WebModeSupport.buildUrl(host,port)`, `SessionManager.getProcessedDiffs(serverDiffs,snapshot,extraVfsEvents,extraAiCreatedFiles)` are used consistently across tasks.
- **Review Focus:** each of the 5 lines has a test in its owning task (1→Task 4, 2→Task 7, 3→Task 3, 4→Task 1/2, 5→Task 1/2).
- **Proportion:** plan is longer than the spec because the port touches many files; code blocks are signatures/fixtures only, bodies left to the builder.
