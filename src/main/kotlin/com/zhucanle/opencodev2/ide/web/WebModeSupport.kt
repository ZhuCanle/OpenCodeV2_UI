package com.zhucanle.opencodev2.ide.web

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.jcef.JBCefApp

object WebModeSupport {

    /** Host used when the server binds all interfaces; `0.0.0.0` is not navigable from a browser. */
    private const val BIND_ALL_HOST = "0.0.0.0"
    private const val LOOPBACK_HOST = "127.0.0.1"

    fun isJcefSupported(): Boolean {
        return try {
            JBCefApp.isSupported()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Builds the v2 web UI URL: `http://<host>:<port>`.
     *
     * The web app is a single-page application served from the root. Its client-side router only
     * accepts `/`, `/settings`, `/connect`, `/new-session` and `/server/<id>/session/<id>`; any
     * other path (such as the old `/doc`) throws "Unrecognised route!" while booting.
     *
     * A bind-all host is normalized to loopback because browsers reject `0.0.0.0` as a target.
     */
    fun buildUrl(host: String, port: Int): String {
        val browserHost = if (host == BIND_ALL_HOST) LOOPBACK_HOST else host
        return "http://$browserHost:$port"
    }

    /**
     * The `scheme://host:port` origin of [url], or null when it cannot be parsed.
     *
     * The web app issues `http://host:port/api/...` XHRs, so the embedded browser must scope its
     * auth-header injection to the whole origin, not the page URL.
     */
    fun originOf(url: String): String? = try {
        val uri = java.net.URI(url)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme == null || host == null) {
            null
        } else {
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$portPart"
        }
    } catch (e: Exception) {
        null
    }

    /**
     * True when [requestUrl] targets [targetOrigin].
     *
     * Compares normalized origins instead of raw string prefixes: a prefix check accepted
     * `http://127.0.0.1:40960/...` for the origin `http://127.0.0.1:4096` and ignored host casing
     * and default ports.
     */
    fun isSameOrigin(requestUrl: String, targetOrigin: String): Boolean {
        val requestOrigin = originOf(requestUrl) ?: return false
        val target = originOf(targetOrigin) ?: targetOrigin
        return requestOrigin.equals(target, ignoreCase = true)
    }

    fun openWebTab(project: Project, host: String, port: Int, password: String? = null, onClose: () -> Unit): OpenCodeWebVirtualFile? {
        if (!isJcefSupported()) {
            Messages.showErrorDialog(project, "JCEF (Embedded Browser) is not supported in this IDE environment.", "Web Interface Error")
            return null
        }

        // The web app is served from the server root. The URL carries no credentials:
        // credentials are injected via JS Pre-Auth in OpenCodeWebFileEditor.
        val url = buildUrl(host, port)
        
        val tabName = "OpenCode Web($port)"
        // Pass password to VirtualFile so Editor can use it
        val virtualFile = OpenCodeWebVirtualFile(tabName, url)
        virtualFile.putUserData(OpenCodeWebVirtualFile.PASSWORD_KEY, password)

        OpenCodeWebFileEditorProvider.registerCallback(virtualFile, onClose)

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                // The tab will never open: drop the callback so it (and the password) does not stay
                // in the provider's static map.
                OpenCodeWebFileEditorProvider.unregisterCallback(virtualFile)
                return@invokeLater
            }
            logger.info("Opening web tab: $url")
            val editors = FileEditorManager.getInstance(project).openFile(virtualFile, true)
            logger.info("FileEditorManager returned ${editors.size} editors")
            if (editors.isEmpty()) {
                OpenCodeWebFileEditorProvider.unregisterCallback(virtualFile)
                Messages.showErrorDialog(project, "Failed to open Web Interface tab. No editor provider found.", "Error")
            }
            pinTab(project, virtualFile)
        }
        
        return virtualFile
    }

    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(WebModeSupport::class.java)
    
    fun unregisterCallback(file: OpenCodeWebVirtualFile) {
        try {
            OpenCodeWebFileEditorProvider.unregisterCallback(file)
        } catch (e: Exception) {
            logger.warn("Failed to unregister the web tab close callback for ${file.name}", e)
        }
    }

    private fun pinTab(project: Project, file: com.intellij.openapi.vfs.VirtualFile) {
         try {
            val managerEx = FileEditorManagerEx.getInstanceEx(project)
            managerEx.currentWindow?.let { window ->
                if (!window.isFilePinned(file)) {
                    window.setFilePinned(file, true)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
