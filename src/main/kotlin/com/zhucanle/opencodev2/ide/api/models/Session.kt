package com.zhucanle.opencodev2.ide.api.models

/**
 * An opencode v2 session (`Session.Info`).
 *
 * v2 has no top-level `directory` field: the session's working directory lives inside
 * [location]. The [directory] convenience accessor preserves the property the plugin used to
 * read from the response.
 *
 * @property id session id (`ses_...`)
 * @property projectID project the session belongs to
 * @property title session title; absent until the first turn produces one
 * @property parentID set only on child/subagent sessions
 * @property time session timestamps
 * @property location working directory of the session
 */
data class Session(
    val id: String,
    val projectID: String,
    val title: String? = null,
    val parentID: String? = null,
    val time: SessionTime? = null,
    val location: SessionLocation? = null
) {
    /** Working directory of the session, sourced from `location.directory` in v2. */
    val directory: String get() = location?.directory ?: ""
}

/**
 * The `location` object of a v2 session. Only `directory` is consumed by the plugin.
 */
data class SessionLocation(
    val directory: String? = null
)

/**
 * Session time information (`time` object). All fields are optional in v2: `idle` appears after
 * the first turn completes and `viewed` only after `POST /api/session/:id/view`.
 */
data class SessionTime(
    val created: Double? = null,
    val updated: Double? = null,
    val idle: Double? = null,
    val viewed: Double? = null
)
