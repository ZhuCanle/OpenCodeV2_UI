# opencode v2 wire format — captured evidence (2026-10-15)

Captured from **opencode v2.0.29** (`/opt/homebrew/bin/opencode`, Homebrew `opencode-v2` 2.0.29)
on macOS, against a freshly started `opencode serve` on `http://127.0.0.1:14096`.

This document is **evidence, not design**. Everything in the "Captured" sections is verbatim
bytes from a live server. Everything in the "Declared but NOT captured" sections is read out of
the shipped binary's schema definitions and is explicitly marked as such.

## 0. Fixtures produced by this spike

| File | What it is | How it was produced |
| --- | --- | --- |
| `src/test/resources/v2/event-frame.txt` | 69,065 bytes of raw `/api/event` SSE stream output | `curl -sN --no-buffer -u opencode:<pw> -H 'Accept: text/event-stream' -H 'Cache-Control: no-cache' "http://127.0.0.1:14096/api/event?directory=<scratch>"` while driving sessions |
| `src/test/resources/v2/session-list.json` | real `GET /api/session?directory=<scratch>` body | `curl -s -u opencode:<pw> "http://127.0.0.1:14096/api/session?directory=<scratch>"` |
| `src/test/resources/v2/session-diff.json` | real `GET /api/session/<id>/diff?from=<userMsgId>` body | `curl -s -u opencode:<pw> "http://127.0.0.1:14096/api/session/ses_ec15a2197ffefz47oVYIfAxUTQ/diff?from=msg_13ea5decd001v2cuzqpU1YUDKC"` |

`event-frame.txt` is a **verbatim, unedited, contiguous** capture (LF line endings, no CR, no
added header/comment lines). It starts at the `server.connected` frame the server sends on
connect and ends with the heartbeat comments that were in flight when the capture was stopped.
SHA-256 of the fixture: `711d3b83f887c9ae78b3a346d7d72e8568ec31e4cdf7471a1b6badf40d9fea3d`.

The capture contains three real turns: (1) a turn that failed with a provider quota error,
(2) a successful turn that edited `hello.txt` and created `notes.md` with the `write`/`edit`
tools, (3) a turn driven by the real `opencode run` CLI client (`--server http://127.0.0.1:14096`).

---

## 1. SSE framing — **there is no `event:` line**

This is the single most important finding and it contradicts the assumption in the task brief
and in `2026-10-15-opencode-v2-compat-design.md`.

The raw stream is made of exactly two kinds of frames:

```
data: {"id":"evt_13ea533a70012H48XZE9Ud4y4W","type":"server.connected","data":{}}
<blank line>
```

```
: heartbeat
<blank line>
```

Byte-level facts (measured over the whole capture):

* A data frame is `data:` + one ASCII space + a **raw JSON object** + `\n` + `\n`.
* A heartbeat frame is `: heartbeat` + `\n` + `\n` (an SSE comment line).
* Line endings are `\n` only. There is no `\r` anywhere in the fixture.
* **Zero `event:` lines** and **zero `id:` lines** were produced by the server.
* Frame counts in the fixture: 182 `data:` lines, 33 `: heartbeat` lines, 215 blank lines (430
  newline-terminated lines total).
* The event's *name* is **inside** the JSON, in the top-level `type` field. There is no
  out-of-band event name.
* The stream never closes on its own; absence of data is signalled by `: heartbeat` comments.

The OpenAPI document (`GET /openapi.json`, declared at `/{openapiPath:"/openapi.json"}`) describes
this route as `event.subscribe` / `GET /api/event` with a success schema of
`{id?: string|null, event: string, data: V2EventEncoded}` where `V2EventEncoded` is
`{"type":"string","contentMediaType":"application/json"}` — i.e. "JSON text". The server-written
wire format only ever fills the `data` field, so the `event` field is absent and no `event:` line
is emitted; the `data` field carries the whole event object (including its `type`). Treat the
openapi SSE schema as an inaccurate wrapper description, not as the wire format.

### 1.1 Is `data` a raw JSON object or a JSON-encoded string?

**Raw JSON object.** It starts with `{`, not with `"`. Nothing is double-encoded. Verified with a
byte dump of the first frame (`od -c` on the capture):

```
d a t a :   { " i d " : " e v t _ ... , " t y p e " : " s e r v e r . c o n n e c t e d " , " d a t a " : { } } \n \n
```

So a client must do `payload = line.substring("data:".length).trim(); JSON.parse(payload)`.
There is **no** need for the "unquote a JSON-string-encoded payload" fallback that
`2026-10-15-opencode-v2-compat-design.md` line 224 anticipated — at least not in v2.0.29.
(Only the `/api/experimental/session/:id/log` route was seen emitting an SSE stream whose `data:`
payload is also a raw JSON object, e.g. `data: {"type":"log.synced",...}`.)

### 1.2 Event envelope shapes

The JSON object on the `data:` line always has `id` (string, `evt_…`), `type` (string) and `data`
(object). Four optional keys appear depending on the event: `created` (unix ms), `metadata`,
`location` (`{"directory": "<abs path>"}`), and `durable`
(`{"aggregateID": "<ses_…>", "seq": <int>, "version": <int>}`).
Six distinct key-sets were observed in the fixture:

```
["data","id","type"]                                       server.connected
["created","data","durable","id","type"]                   session.execution.started/succeeded/failed,
                                                           session.viewed, worktree.resolved
["created","data","durable","id","location","type"]        session.created, session.step.*,
                                                           session.text.*, session.tool.called/success/
                                                           input.*, session.reasoning.started/ended,
                                                           session.renamed, session.model.selected,
                                                           session.inbox.enqueued/delivered
["created","data","id","location","type"]                  project.updated, provider.updated,
                                                           model.updated, agent.updated, skill.updated,
                                                           command.updated, plugin.updated,
                                                           mcp.status.changed, mcp.resources.changed,
                                                           session.text.delta, session.reasoning.delta,
                                                           vcs.branch.updated, ...
["created","data","durable","id","location","metadata","type"]
                                                           session.instructions.updated
["created","data","id","type"]                             session.usage.updated (and one
                                                           project.updated variant)
```

Notes for implementers:

* `data` is the payload; `durable` is bookkeeping for the server's per-session log and can be
  ignored by the plugin (the design doc already says this — confirmed safe).
* `location.directory` is the cwd of the event, present on most but **not all** events.
* `type` is the only trustworthy discriminator. Do not infer the event name from the route or
  from any header.

### 1.3 The `?directory=` query parameter

Undocumented in the OpenAPI (the route declares no parameters) but functional: passing
`?directory=<abs path>` restricts the stream to events for that directory. Omitting it yields the
same event *types* (verified) but events for every location.

---

## 2. Verbatim frames (copied byte-for-byte out of `event-frame.txt`)

### 2.1 `server.connected` (first frame of every connection)

```
data: {"id":"evt_13ea533a70012H48XZE9Ud4y4W","type":"server.connected","data":{}}
```

Field names: top-level `id`, `type`, `data` (empty object). No `created`. This is the "connected"
marker; it is synthesized per subscriber (a `server.connected` frame appears even if no other
event exists).

### 2.2 `session.created`

```
data: {"id":"evt_13ea53bd1001e9nQFhfUcAAPOF","created":1792052378577,"type":"session.created","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15ac47dffey3rdTfZFb6rSX9","slug":"cosmic-forest","version":"2.0.29","projectID":"40a88780f75e877fb956d50f561d2f9c1e7378e2","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"subpath":""},"durable":{"aggregateID":"ses_ec15ac47dffey3rdTfZFb6rSX9","seq":0,"version":1}}
```

`data` fields: `sessionID`, `slug`, `version`, `projectID`, `location.directory`, `subpath`.

### 2.3 `session.execution.started` — the real "busy" signal

```
data: {"id":"evt_13ea543ad001IgwUDsfqRN8kmh","created":1792052380589,"type":"session.execution.started","data":{"sessionID":"ses_ec15ac47dffey3rdTfZFb6rSX9"},"durable":{"aggregateID":"ses_ec15ac47dffey3rdTfZFb6rSX9","seq":2,"version":1}}
```

`data.sessionID` only. This is emitted once per *turn*, before the first step.

### 2.4 `session.execution.succeeded` — the real "idle" signal

```
data: {"id":"evt_13ea600ef001i87e7hQtZRdFLF","created":1792052429039,"type":"session.execution.succeeded","data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ"},"durable":{"aggregateID":"ses_ec15a2197ffefz47oVYIfAxUTQ","seq":48,"version":1}}
```

`data.sessionID` only. The siblings are `session.execution.failed` and
`session.execution.interrupted` (the latter is declared but was not produced in this capture).

### 2.5 `session.execution.failed`

```
data: {"id":"evt_13ea5473d001bCbA7G5nYAybJK","created":1792052381501,"type":"session.execution.failed","data":{"sessionID":"ses_ec15ac47dffey3rdTfZFb6rSX9","error":{"type":"provider.quota","message":"Insufficient Balance (request_id: a4a66f7f-6a0e-405e-aa30-1263c8b56682)","status":402,"response":{"body":"{\"error\":{\"message\":\"Insufficient Balance (request_id: a4a66f7f-6a0e-405e-aa30-1263c8b56682)\",\"type\":\"unknown_error\",\"param\":\"\",\"code\":\"invalid_request_error\"}}"}}},"durable":{"aggregateID":"ses_ec15ac47dffey3rdTfZFb6rSX9","seq":7,"version":1}}
```

`data.error` fields: `type`, `message`, `status`, `response.body` (stringified provider body).

### 2.6 `session.step.started`

```
data: {"id":"evt_13ea54706001HkkFpVJimYL8fp","created":1792052381446,"type":"session.step.started","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15ac47dffey3rdTfZFb6rSX9","agent":"build","model":{"id":"deepseek-v4-flash","providerID":"newapi"},"assistantMessageID":"msg_13ea543da001zgEXURFnMfgNPq","snapshot":"aaa96ced2d9a1c8e72c56b253a0e2fe78393feb7","started":1792052380646},"durable":{"aggregateID":"ses_ec15ac47dffey3rdTfZFb6rSX9","seq":5,"version":1}}
```

`data` fields: `sessionID`, `agent`, `model{id,providerID,variant?}`, `assistantMessageID`,
`snapshot` (git blob sha of the working tree snapshot *before* the step), `started`.

### 2.7 `session.step.ended` **with changed files** — this replaces `session.diff`

```
data: {"id":"evt_13ea5f2b8001ey51nv020t9FeX","created":1792052425400,"type":"session.step.ended","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ","assistantMessageID":"msg_13ea5e8bd001iHuaJzDvz8LM4H","finish":"tool-calls","rawFinish":"tool_calls","cost":0.000152382,"tokens":{"input":153,"output":121,"reasoning":32,"cache":{"read":12544,"write":0}},"snapshot":"cd8589b3bd9ec83661b557579f56fdd844cb5ba3","files":["hello.txt","notes.md"]},"durable":{"aggregateID":"ses_ec15a2197ffefz47oVYIfAxUTQ","seq":29,"version":1}}
```

`data` fields: `sessionID`, `assistantMessageID`, `finish`, `rawFinish`, `cost`,
`tokens{input,output,reasoning,cache{read,write}}`, `snapshot`, `files` (array of repo-relative
paths changed during the step; `[]` when nothing changed).

`session.step.failed` has the same shape plus `error` (see §2.5 semantics).

### 2.8 `session.tool.called` / `session.tool.success`

```
data: {"id":"evt_13ea5e87d0017Znl1u7A4op7sO","created":1792052422781,"type":"session.tool.called","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ","assistantMessageID":"msg_13ea5deed001ojYZIV7UpWO0pj","id":"call_00_q825qkh5w5rktu5tlwju1koo","input":{"path":"hello.txt"},"executed":false},"durable":{"aggregateID":"ses_ec15a2197ffefz47oVYIfAxUTQ","seq":10,"version":1}}
```

```
data: {"id":"evt_13ea5e886001zEyXDj0c9yhsCI","created":1792052422790,"type":"session.tool.success","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ","assistantMessageID":"msg_13ea5deed001ojYZIV7UpWO0pj","id":"call_00_q825qkh5w5rktu5tlwju1koo","content":[{"type":"text","text":"Read file hello.txt, lines 1-1\n1: hello"}],"metadata":{"truncated":false},"executed":false},"durable":{"aggregateID":"ses_ec15a2197ffefz47oVYIfAxUTQ","seq":13,"version":2}}
```

`session.tool.called.data`: `sessionID`, `assistantMessageID`, `id`, `input`, `executed`.
`session.tool.success.data`: same keys plus `content` (array of parts) and `metadata`.
Note `durable.version` increments (`1` → `2`) for the same seq.

`metadata` has exactly **two shapes across all five `session.tool.success` frames** in
`event-frame.txt`, and neither of them contains `status` or `exit`
(those were an earlier mistake in this doc, mixing declared fields into captured evidence):

```
{"truncated": false}                                   (4 of the 5 frames)
{"files": [ … ], "truncated": false}                   (1 of the 5 frames, line 233)
```

#### 2.8.1 `metadata.files[]` — the real per-file diff (verbatim, `event-frame.txt` line 233)

This is the second, independent source of a per-file diff (the first is the `/diff` route, §3).
The full captured frame, call id `call_00_ygad8nxyh1gacui3wahob7g2`:

```
data: {"id":"evt_13ea5f235001bY3v5kq1zEvh73","created":1792052425270,"type":"session.tool.success","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ","assistantMessageID":"msg_13ea5e8bd001iHuaJzDvz8LM4H","id":"call_00_ygad8nxyh1gacui3wahob7g2","content":[{"type":"text","text":"Edited hello.txt (1 replacement)"}],"metadata":{"files":[{"file":"hello.txt","patch":"Index: hello.txt\n===================================================================\n--- hello.txt\n+++ hello.txt\n@@ -1,1 +1,3 @@\n-hello\n+alpha\n+beta\n+gamma\n","status":"modified","additions":3,"deletions":1}],"truncated":false},"executed":false},"durable":{"aggregateID":"ses_ec15a2197ffefz47oVYIfAxUTQ","seq":27,"version":2}}
```

Fields of each `metadata.files[]` element: `file`, `patch`, `status`, `additions`, `deletions` —
the same five names as `FileDiff.Info` (§3.2), but **the patch header is different**:

| | `/diff` route (§3) / `FileDiff.Info.patch` | `session.tool.success.metadata.files[].patch` |
| --- | --- | --- |
| first line | `diff --git a/hello.txt b/hello.txt` | `Index: hello.txt` |
| separators | `index <old>..<new> <mode>` | a `===================================================================` line |
| hunk header | `@@ -1 +1,3 @@` | `@@ -1,1 +1,3 @@` (unified `-l,s` form) |

So a patch parser must cope with **both** header dialects; it cannot assume `diff --git`.
`metadata.files[]` was present on exactly one of the five `session.tool.success` frames in the
fixture — the `edit` call. The sibling `write` call that created `notes.md` (line 235,
`call_01_va3i62hm2bio4wh2er0uz4wq`) carried `{"truncated": false}` only, so `files[]` must **not** be
treated as a guaranteed per-file report; use the `/diff` route (§3) as the authoritative source.

### 2.9 `session.text.*` / `session.reasoning.*`

```
data: {"id":"evt_13ea5e74c001USsRthWRVH0qZS","created":1792052422476,"type":"session.text.delta","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ","assistantMessageID":"msg_13ea5deed001ojYZIV7UpWO0pj","ordinal":0,"delta":"I'll start"}}
```

```
data: {"id":"evt_13ea5e87e001tCfLHm6CUIefw2","created":1792052422783,"type":"session.text.ended","location":{"directory":"/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike"},"data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ","assistantMessageID":"msg_13ea5deed001ojYZIV7UpWO0pj","ordinal":0,"text":"I'll start by reading the current contents of hello.txt."},"durable":{"aggregateID":"ses_ec15a2197ffefz47oVYIfAxUTQ","seq":11,"version":1}}
```

`session.text.started.data`: `{sessionID, assistantMessageID, ordinal}`.
`session.text.delta.data`: `{sessionID, assistantMessageID, ordinal, delta}` (also carries
`location`, no `durable`).
`session.text.ended.data`: `{sessionID, assistantMessageID, ordinal, text}`.
`session.reasoning.started/delta/ended` are identical in shape.

### 2.10 `session.usage.updated`

```
data: {"id":"evt_13ea5e8b60012tD2yVDjqzU8G1","created":1792052422838,"type":"session.usage.updated","data":{"sessionID":"ses_ec15a2197ffefz47oVYIfAxUTQ","cost":0.0010195319999999999,"tokens":{"input":6478,"output":49,"reasoning":0,"cache":{"read":6144,"write":0}}}}
```

No `location`, no `durable` on this one.

---

## 3. `GET /api/session/{sessionID}/diff` — real captured response

Full body, verbatim, of `session-diff.json` (the fixture):

```json
{"data":[{"file":"hello.txt","patch":"diff --git a/hello.txt b/hello.txt\nindex ce01362..85c3040 100644\n--- a/hello.txt\n+++ b/hello.txt\n@@ -1 +1,3 @@\n-hello\n+alpha\n+beta\n+gamma\n","additions":3,"deletions":1,"status":"modified"},{"file":"notes.md","patch":"diff --git a/notes.md b/notes.md\nnew file mode 100644\nindex 0000000..ac3fd69\n--- /dev/null\n+++ b/notes.md\n@@ -0,0 +1 @@\n+summary\n","additions":1,"deletions":0,"status":"added"}]}
```

(The `\n` above are real two-character escapes inside the JSON string, not literal newlines; the
fixture file is one line.)

### 3.1 Response envelope

Top level is `{"data": [ ... ]}`. `data` is an **array**, possibly empty (`{"data":[]}` was observed
for a turn that changed nothing, and for a session with no user message yet). There is no `error`
envelope on success.

### 3.2 `FileDiff.Info` — exact field names

Each element of `data` is a `FileDiff.Info`
(`#/components/schemas/FileDiff.Info`, all five fields required, `additionalProperties:false`):

| field | type | notes |
| --- | --- | --- |
| `file` | string | repo-relative path, e.g. `hello.txt` |
| `patch` | string | **unified diff text** (`diff --git a/… b/…` … `@@` hunks). Newlines are `\n` escaped inside the JSON string. For a new file it starts with `diff --git …`, `new file mode 100644`, `--- /dev/null`. |
| `additions` | int ≥ 0 | 3 in the captured `hello.txt` entry |
| `deletions` | int ≥ 0 | 1 in the captured `hello.txt` entry |
| `status` | string enum | `added` \| `deleted` \| `modified` — `notes.md` captured as `added`, `hello.txt` as `modified` |

There is **no** `before`/`after` content pair in v2 (v1 had `{file, before, after, additions,
deletions}`). The plugin must parse `patch` or fall back to local git.

### 3.3 Query parameters (from `/openapi.json`)

* `from` — `Session.Message.ID` (`^msg_`). **Must be a *user* message.** Passing an assistant or
  idle message id returns HTTP 400 `InvalidRequestError: Message <id> is not a user message`
  (`{"field":"from"}`). Omitting it defaults to the turn of the newest user message.
* `to` — later user message whose turn ends the range; defaults to the `from` turn alone.
* `context` — number of unchanged lines around each hunk; omitting it yields full-file patches
  (which is what the fixture above was captured with).

The response was non-empty only when the `from` message was a user message of a turn that actually
changed files. A turn that ran but touched no files returned `{"data":[]}`.

---

## 4. What was NOT observed (read this before relying on the design doc)

**`session.status`, `session.idle`, `message.updated`, `session.diff` and `file.edited` were never
emitted on `/api/event` in any flow I could drive.** This is the most important caveat in this
document.

Observed `/api/event` types (union across all captures, **45 distinct names**). Names marked `†`
were seen only in captures other than the committed fixture (they came from the user's background
service on port 49374, driving shell tools); the committed `event-frame.txt` contains the other
**40**:

```
agent.updated                     session.execution.failed          session.tool.called
command.updated                   session.execution.started         session.tool.input.ended
integration.updated               session.execution.succeeded       session.tool.input.started
mcp.resources.changed             session.inbox.delivered           session.tool.progress †
mcp.status.changed                session.inbox.enqueued            session.tool.success
model.updated                     session.instructions.updated      session.usage.updated
plugin.updated                    session.model.selected            session.viewed
project.updated                   session.reasoning.delta           shell.created †
provider.updated                  session.reasoning.ended           shell.deleted †
reference.updated                 session.reasoning.started         shell.exited †
server.connected                  session.renamed                   skill.updated
session.created                   session.step.ended                vcs.branch.updated
session.deleted †                 session.step.failed               websearch.updated
session.step.started              session.step.streamed             worktree.resolved
session.text.delta                session.text.ended
session.text.started
```

Reproduction attempts that all failed to produce the four missing event names:

1. Session driven purely through the HTTP API (`POST /api/session`,
   `POST /api/session/:id/prompt`) including a run that edited files with the `edit`/`write`
   tools — 182 frames captured, no `session.status`/`message.updated`/`session.diff`.
2. A real `opencode run --server http://127.0.0.1:14096` CLI client (i.e. the actual product UI
   path), while subscribed to `/api/event`.
3. `POST /api/session/:id/view {"idle":0}` before prompting (emits `session.viewed`, still no
   `session.status`).
4. Subscribing without the `?directory=` filter (same type set).
5. `POST /api/session/:id/interrupt` while idle (returns `{"interrupted":false}`).
6. Touching a file on disk in the watched directory while subscribed (no `file.edited`).

### 4.1 The four requested objects, as *declared* by the shipped v2.0.29 binary — **NOT captured**

Everything below is extracted from the schema definitions embedded in the shipped binary
(`/opt/homebrew/Cellar/opencode-v2/2.0.29/bin/opencode`, "Schema.Encoded" definitions built with
`Xt({type:"…"})`). It is **not** wire evidence; treat it as "what the server would send if it ever
sends these". Downstream tasks must not assume these frames arrive.

`session.status` — declared as:

```js
Xt({type:"session.status", identifier:"SessionStatusUpdated", schema:{sessionID:ga, status:Ro}})
// Ro = union of:
//   {type:"idle"}
//   {type:"retry", attempt:<int>, message:<str>, action:{reason,provider,title,message,label,link}, next:<int>}
//   {type:"busy"}
```

So the **field names** would be top-level `sessionID` + `status`, with
`status.type ∈ {"idle","retry","busy"}` (`retry` additionally carrying
`attempt`, `message`, `action{reason,provider,title,message,label,link}`, `next`).
`session.idle` is declared as `Xt({type:"session.idle", schema:{sessionID:ga}})` — i.e.
`{sessionID}` only.

`message.updated` — declared inside the v1-compat group:

```js
Fi({type:"message.updated", durable:{aggregate:"sessionID",version:1}, schema:{sessionID:ga, info:Vpe}})
// Vpe = union of SessionV1.UserMessage | SessionV1.AssistantMessage,
//       annotated with discriminator "role"
```

Field names: `sessionID` + `info`, where `info` is a message object discriminated by `role`.
(Note: the *durable* v1 group is the one that also carries `session.diff`,
`message.part.updated`, `message.part.removed`, `message.part.delta`, `session.error`, and
`session.created/updated/deleted` with an `info` payload. The **`session.created` frames that the
server actually sends do not match that shape** — the captured `session.created.data` is
`{sessionID, slug, version, projectID, location, subpath}`, not `{sessionID, info}`. So
`session.created` exists in two shapes and only the non-v1 one is emitted.)

`session.diff` — declared as:

```js
Xt({type:"session.diff", schema:{sessionID:ga, diff:N(MX)}})
// MX = FileDiff.LegacyInfo = {file, patch, additions, deletions, status}  (same shape as FileDiff.Info)
```

Field names: `sessionID` + `diff` (array of the same 5-field objects as `FileDiff.Info`).

`file.edited` — declared as `Xt({type:"file.edited", schema:{file:t}})` → field name `file`.

### 4.2 Practical substitution for the missing events

If Tasks 3/4/7 depend on the four missing names, these captured events are the real signal:

* `session.status {status:{type:"busy"}}` → **`session.execution.started`** (once per turn).
* `session.status {status:{type:"idle"}}` / `session.idle` → **`session.execution.succeeded`**
  (success) / **`session.execution.failed`** (`data.error`) / `session.execution.interrupted`.
  (`session.step.ended` with `finish:"stop"` also marks the last step of a successful turn.)
* `session.diff` → there is no event; use `GET /api/session/:id/diff?from=<userMsgId>` (§3) and/or
  the `files[]` + `snapshot` sha carried by `session.step.ended`.
* `message.updated` (used to capture the turn's message id) → the `assistantMessageID` field is
  present on `session.step.started/ended`, `session.text.*`, `session.reasoning.*`,
  `session.tool.*`.

---

## 5. `GET /api/session?directory=…` — real captured response shape

`session-list.json` is the verbatim body. Top-level shape: `{"data":[ Session.Info, ... ], "cursor":
{"previous": <opaque string>, "next": <opaque string>}}`. `data` is an array and may be empty;
`cursor` is a pagination token pair (base64 of a JSON `{directory, anchor:{id,time,direction}}`),
present on this response.

**Keys actually present in the fixture.** All 4 elements carry
`id`, `projectID`, `cost`, `tokens`, `outcome`, `time`, `location`; 3 of them additionally carry
`model` and `title` (the 4th — the turn that failed with the quota error — has none of those two):

```
id, projectID, [model{id,providerID,variant}], cost,
tokens{input,output,reasoning,cache{read,write}},
outcome ("succeeded" | "failed" — absent until a turn finishes),
time{created, updated, idle?, viewed?},
[title], location{directory}
```

**Declared but absent from this fixture** (`Session.Info` also allows these; neither appears in any
captured element, so treat them as optional-and-unproven):
`agent` (agent id) and `parentID` (only on child/subagent sessions).

The `location.directory` field is how the plugin should source a session's working directory
(the design doc's plan to deserialize `location` is correct — confirmed). There is **no** top-level
`directory` field. `time` keys are optional: `idle` appears after the first turn completes,
`viewed` only after `POST /api/session/:id/view`.

---

## 6. Other captured facts worth knowing

* `GET /api/info` (open route) returns
  `{"version":"2.0.29","pid":…,"urls":["http://127.0.0.1:14096"],"paths":{"tmp":"…"},"capabilities":{"persistentPty":true}}`.
* `GET /openapi.json` **requires** Basic auth and returns the full 276 KB OpenAPI 3.1 document.
  `GET /doc` is open but is the SPA shell (with the whole non-minified JS bundle inlined).
* All `/api/*` routes require Basic auth as user `opencode` with the server password; a missing
  auth header yields `{"_tag":"UnauthorizedError","message":"Authentication required"}` with HTTP 401.
* `POST /api/session` accepts `{"location":{"directory":"<abs path>"}}` and creates the session in
  that directory (the `directory` query parameter alone does **not** relocate the session — it
  silently falls back to the server's cwd, which is a trap).
* `POST /api/session/:id/prompt` body is `{"text": "…"}`; it returns the user message
  (`{"data":{id, sessionID, time, type:"user", payload:{text}, delivery:"steer"}}`) and the turn
  runs asynchronously — you must follow the event stream to know when it finishes.
* `POST /api/session` returns a session with `outcome` **absent**; `outcome:"succeeded"` appears in
  the session list only after a successful turn.
* Terminal/shell activity is streamed as `shell.created` / `shell.exited` / `shell.deleted`
  (`data:{id, exit?, status?}`). These three came from the background-service capture only — they are
  **not** in the committed `event-frame.txt` (see the `†` markers in §4).
* `GET /api/experimental/session/:id/log` returns an SSE stream whose first frame is
  `data: {"type":"log.synced","aggregateID":"<ses_…>","seq":<int>}`, then live frames. It does
  **not** replay history.
