# Diff attribution fix

- [x] Reproduce unclaimed deletion and stale server diff with VFS activity.
- [x] Reproduce foreign Git metadata suppressing local edits.
- [x] Require current-turn server attribution and scope Git markers to project roots.
- [x] Verify multiple claimed files, including rescued edits, remain pending.
- [x] Preserve file claims from step events arriving after the execution snapshot.
- [x] Run compilation and the full test suite.

Verification: `./gradlew classes test check --offline` passed (183 tests, no failures).
`git diff --check` passed. IDE popup behavior still needs an installed-plugin smoke test.
