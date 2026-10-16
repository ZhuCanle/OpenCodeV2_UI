package com.zhucanle.opencodev2.ide.api.models

/**
 * A single file change reported by the opencode v2 `/api/session/{id}/diff` route
 * (`FileDiff.Info` in the server schema).
 *
 * opencode v2 replaced the v1 `{file, before, after, additions, deletions}` content pair with a
 * unified-diff [patch]: there is no before/after content on the wire anymore. Consumers must
 * parse [patch] or reconstruct the content from local state (git snapshot / working tree).
 *
 * @property file repo-relative path, e.g. `hello.txt`
 * @property patch unified diff text (`diff --git a/... b/...` followed by `@@` hunks). Newlines
 *   are `\n` escapes inside the JSON string. A new file starts with `new file mode` and
 *   `--- /dev/null`.
 * @property additions number of added lines
 * @property deletions number of removed lines
 * @property status one of `added`, `deleted`, `modified`
 */
data class FileDiff(
    val file: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val status: String
) {
    /** True when the server reports the file as newly created (`status == "added"`). */
    val newFile: Boolean get() = status == "added"

    /** True when the server reports the file as removed (`status == "deleted"`). */
    val deleted: Boolean get() = status == "deleted"
}
