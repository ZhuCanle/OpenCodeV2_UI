# Checkout diff attribution

- [x] Inspect VFS attribution and existing Git filtering.
- [x] Reproduce branch-switch false diffs in a real temporary Git repository.
- [x] Identify checkout records independently of commit/index activity and VFS event order.
- [x] Rebase affected clean-file snapshots onto the checkout target, excluding checkout-only changes.
- [x] Verify immediate post-checkout edits, committed edits, worktrees, and carried local changes.
- [x] Run compilation, full tests, checks, and independent review.

The existing suppression window intentionally excludes HEAD reflog writes because commits write
them too. Extending that window to every HEAD update would suppress legitimate subsequent edits.
The fix will use checkout reflog records and the checked-out tree, rather than a new blind timeout.

Verification:
- Four original real-Git regression tests failed before the implementation.
- Fourteen checkout tests now cover checkout-only modification/addition/deletion, immediate edits,
  commits, linked worktrees, retained user baselines, CRLF, round trips and branch-specific filters.
- Independent review found two issues, both fixed and covered: recreated tracked files were still
  marked new after a round trip, and historical content was filtered using the wrong attributes.
- Raw stdout capture preserves CRLF. Modern Git reads attributes from the matching commit; older
  Git falls back only when applicable attributes match, otherwise the uncertain baseline is omitted.
- `./gradlew classes test check --offline --rerun-tasks`: 220 tests, 0 failures, 0 errors.
- `git diff --check` passed. Follow-up review found no additional confirmed Critical/Important issue.
- IDE installation/UI reproduction has not been performed; version and distribution are unchanged.
