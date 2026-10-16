package com.zhucanle.opencodev2.ide.api.models

/**
 * Represents a pending diff for a single file.
 *
 * Lifecycle:
 * 1. Created when Server produces a diff (via SSE or API)
 * 2. Displayed in DiffViewer
 * 3. Removed when user Accepts or Rejects
 *
 * @property file Relative path from project root (always normalized with forward slashes)
 * @property diff The actual file diff content
 * @property hasUserEdits True if user also edited this file during the AI turn
 * @property resolvedBefore The resolved "before" content (from LocalHistory or local snapshots),
 *                          or null when nothing could be resolved ("unknown", distinct from a
 *                          legitimately empty file). Must be set on background thread before
 *                          showing diff.
 * @property resolvedAfter A snapshot of the file's content on disk taken when the diff was
 *                         processed (`""` when the file was deleted). It is not live: it is not
 *                         re-read after `SessionManager.getProcessedDiffs` runs. The v2 wire has
 *                         no `after` content to fall back on.
 */
data class DiffEntry(
    val file: String,
    val diff: FileDiff,
    val hasUserEdits: Boolean = false,
    val resolvedBefore: String? = null,
    val isCreatedExplicitly: Boolean = false,
    val resolvedAfter: String = ""
) {
    /** 
     * True if this is a newly created file. 
     * Must satisfy BOTH:
     * 1. VFS detected a creation event (physical creation).
     * 2. Server Diff reports `status == "added"` (logical creation).
     * This prevents "Replace" operations (Delete+Create) from being treated as New Files,
     * ensuring Reject restores the original content instead of deleting the file.
     */
    val isNewFile: Boolean get() = isCreatedExplicitly && diff.newFile
    
    /** Get the before content: the local snapshot resolution, empty when nothing was captured. */
    val beforeContent: String get() = resolvedBefore ?: ""
}
