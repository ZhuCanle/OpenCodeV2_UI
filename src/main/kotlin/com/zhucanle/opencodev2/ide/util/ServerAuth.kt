package com.zhucanle.opencodev2.ide.util

/**
 * Credentials for an OpenCode server connection.
 *
 * opencode v2 always uses HTTP Basic auth with the fixed username `opencode`: the local service
 * password is read from `service.json` via [OpenCodeServiceDiscovery], and remote passwords are
 * supplied by the user in the connect dialog.
 *
 * @param username Basic-auth username; opencode v2 always uses `opencode`
 * @param password Basic-auth password, or null for an unauthenticated endpoint
 */
data class ServerAuth(
    val username: String = DEFAULT_USERNAME,
    val password: String? = null
) {
    companion object {
        /**
         * The fixed Basic-auth username required by the opencode v2 server.
         *
         * Shared by [ServerAuth], the REST client and the SSE handshake so they can never disagree
         * about who to authenticate as.
         */
        const val DEFAULT_USERNAME = "opencode"
    }
}
