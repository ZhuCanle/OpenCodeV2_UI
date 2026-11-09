package com.zhucanle.opencodev2.ide.diff

import com.zhucanle.opencodev2.ide.api.models.DiffEntry
import com.zhucanle.opencodev2.ide.session.SessionManager

/** The snapshots shown together, not the global history of unhandled files. */
internal class DiffReviewBatch(entries: List<DiffEntry>) {
    val entries = entries.toList()

    fun remaining(manager: SessionManager): List<DiffEntry> =
        entries.filter { manager.getDiffForFile(it.file) === it && manager.isCurrentScope(it.scopeVersion) }
}
