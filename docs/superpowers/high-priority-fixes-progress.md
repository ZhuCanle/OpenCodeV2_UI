# High-priority bug fixes

- [x] Recheck startup ordering, rejection writes, and working-directory consumers.
- [x] Add regression coverage and confirm failures for rejection and directory resolution.
- [x] Initialize local endpoint state before scheduling UI or starting connections (ordering verified by code review).
- [x] Guard rejection against newer disk/document content and stale entries.
- [x] Resolve API and local diff paths against the active working directory.
- [x] Invalidate old scope snapshots and queued actions when switching directories.
- [x] Run final compilation, tests, checks, and review the resulting diff.

Verification notes:
- Regression tests reproduced overwritten saved edits, deletion of an edited new file, and wrong-root resolution.
- Platform tests cover unsaved document edits and unchanged-file rejection.
- Scope tests cover stale responses and identical-content files in another working directory.
- A full rerun was needed after changing Kotlin data class constructors because incremental instrumentation retained old signatures.
- The local startup race is fixed by moving endpoint initialization before both asynchronous consumers; no live IDE scheduling reproduction is claimed.
- Final verification: `./gradlew classes test check --offline` passed, 190 tests / 0 failures / 0 errors; `git diff --check` passed.
- Independent review identified scope-switch hazards; rejection and late responses now validate the scope, and Accept captures its original root before dispatch and conditionally removes only its own pending entry.
