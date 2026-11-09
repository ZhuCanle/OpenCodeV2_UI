# Remaining correctness fixes and regression review

- [x] Review commit 725c010 independently for regressions.
- [x] Reproduce and fix abandoned turns on disconnect/reconnect.
- [x] Distinguish a failed session-list request from a valid empty list; retry before subscribing.
- [x] Close SSE responses on failed handshakes and stream exit.
- [x] Keep Accept/Reject navigation within the displayed batch.
- [x] Restore deleted empty files without fabricating unknown contents.
- [x] Complete independent follow-up review and final checks.

Confirmed prior-fix issues addressed:
- Accept now binds to the displayed snapshot and rejects replaced entries before dispatch and before Git execution.
- Reject compares cached documents with normalized line endings, preserving raw disk comparisons for CRLF files.

Regression evidence:
- Red tests reproduced abandoned manual/transient connections, malformed/unauthorized ownership lists, response leaks, empty-file deletion loss, CRLF rejection, and stale Accept staging.
- Navigation tests cover historical pending entries and replaced same-path snapshots.
- Empty-file tests cover both server-reported and locally rescued deletions.
- Follow-up review caught pending-review loss on transient disconnect; separate turn and scope generations now preserve completed reviews while invalidating in-flight responses.
- Accept also checks current disk content/existence before `git add`; its existing working-tree staging semantics are retained, without claiming atomic isolation from external file writers.
- Final `./gradlew classes test check --offline`: 206 tests, 0 failures, 0 errors. `git diff --check` passed.
- Final independent follow-up review found no additional confirmed Critical/Important regressions in the revised scope/turn lifecycle and action guards.
