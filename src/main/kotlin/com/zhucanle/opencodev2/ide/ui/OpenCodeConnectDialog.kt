package com.zhucanle.opencodev2.ide.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.util.Base64
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for configuring OpenCode server connection.
 * Allows user to specify host:port before starting the terminal.
 */
class OpenCodeConnectDialog(
    private val project: Project,
    private val defaultPort: Int
) : DialogWrapper(project, true) {

    data class ConnectionInfo(
        val hostname: String,
        val port: Int,
        val password: String?,
        val useWebInterface: Boolean,
        val customBasePath: String? = null
    )

    private val addressField = JBTextField("127.0.0.1:$defaultPort")
    private val passwordField = JBPasswordField()
    private val basePathField = ComboBox<String>().apply {
        isEditable = true
    }
    private val webInterfaceCheckBox = JCheckBox("Use web interface")
    
    var hostname: String = "127.0.0.1"
        private set
    var port: Int = defaultPort
        private set
    var password: String? = null
        private set
    var customBasePath: String? = null
        private set
    var useWebInterface: Boolean = false
        private set

    init {
        title = "Connect to OpenCode"
        setOKButtonText("Connect")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(350), JBUI.scale(210))

        val formPanel = JPanel(GridLayout(7, 1, 0, JBUI.scale(4)))
        val addressLabel = JBLabel("Server address:")
        val passwordLabel = JBLabel("Server password (remote only):")
        val basePathLabel = JBLabel("Custom base path (optional):")

        // Load saved values
        val props = PropertiesComponent.getInstance()
        // Always suggest a new available port by default
        addressField.text = "127.0.0.1:$defaultPort"
        
        // Load saved password (Base64 encoded for basic obfuscation)
        try {
            val encodedPassword = props.getValue(PROP_LAST_PASSWORD, "")
            if (encodedPassword.isNotBlank()) {
                val decodedPassword = String(Base64.getDecoder().decode(encodedPassword))
                passwordField.text = decodedPassword
            }
        } catch (e: Exception) {
            // Ignore if password retrieval fails
        }

        // Load saved custom base path
        val savedPath = props.getValue(PROP_CUSTOM_BASE_PATH, "")

        // Load saved web-interface preference (defaults to off: the terminal is the primary UX).
        webInterfaceCheckBox.isSelected = props.getBoolean(PROP_USE_WEB, false)

        // Populate module paths
        val moduleManager = ModuleManager.getInstance(project)
        val modules = moduleManager.modules
        val paths = modules.mapNotNull { it.guessModuleDir()?.path }.sorted()
        paths.forEach { basePathField.addItem(it) }

        if (savedPath.isNotBlank()) {
            basePathField.item = savedPath
        } else {
            basePathField.selectedIndex = -1
        }

        addressField.toolTipText = "Format: hostname:port (e.g., 127.0.0.1:4096)"
        passwordField.toolTipText = "Password printed by `opencode serve`. Required for remote hosts; " +
            "local servers discover their password automatically."
        passwordField.emptyText.text = "Required for remote hosts (local servers are automatic)"
        basePathField.toolTipText = "Override project base path for opencode.exe working directory"
        webInterfaceCheckBox.toolTipText = "Open the opencode web UI (/doc) instead of the terminal"

        formPanel.add(addressLabel)
        formPanel.add(addressField)
        formPanel.add(passwordLabel)
        formPanel.add(passwordField)
        formPanel.add(basePathLabel)
        formPanel.add(basePathField)
        formPanel.add(webInterfaceCheckBox)

        panel.add(formPanel, BorderLayout.CENTER)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = addressField

    override fun doValidate(): ValidationInfo? {
        val info = validateConnection(addressField.text, passwordField.password.concatToString())
            ?: return null
        // The address is checked first, so an address-only re-check identifies the offending field.
        val component = if (validateAddress(addressField.text) == null) passwordField else addressField
        return ValidationInfo(info.message, component)
    }

    override fun doOKAction() {
        val input = addressField.text.trim()
        val parts = input.split(":")
        hostname = parts[0].trim()
        port = parts[1].trim().toInt()

        val passwordValue = passwordField.password.concatToString().trim()
        val newPassword = passwordValue.ifBlank { null }
        password = newPassword

        val basePathValue = (basePathField.editor.item as? String)?.trim() ?: ""
        val newBasePath = basePathValue.ifBlank { null }
        customBasePath = newBasePath

        useWebInterface = webInterfaceCheckBox.isSelected

        // Save values for next time
        val props = PropertiesComponent.getInstance()
        props.setValue(PROP_LAST_ADDRESS, "$hostname:$port")

        // Save password (Base64 encoded for basic obfuscation)
        try {
            if (newPassword.isNullOrBlank()) {
                // Clear saved password if empty
                props.unsetValue(PROP_LAST_PASSWORD)
            } else {
                val encodedPassword = Base64.getEncoder().encodeToString(newPassword.toByteArray())
                props.setValue(PROP_LAST_PASSWORD, encodedPassword)
            }
        } catch (e: Exception) {
            // Ignore if password save fails
        }

        // Save custom base path
        if (newBasePath.isNullOrBlank()) {
            props.unsetValue(PROP_CUSTOM_BASE_PATH)
        } else {
            props.setValue(PROP_CUSTOM_BASE_PATH, newBasePath)
        }

        props.setValue(PROP_USE_WEB, useWebInterface)

        super.doOKAction()
    }

    companion object {
        private const val PROP_LAST_ADDRESS = "opencode.lastAddress"
        private const val PROP_LAST_PASSWORD = "opencode.lastPassword"
        private const val PROP_CUSTOM_BASE_PATH = "opencode.customBasePath"
        private const val PROP_USE_WEB = "opencode.useWebInterface"

        /**
         * Hosts treated as "local". The v2 background service is service-managed: its endpoint and
         * password are auto-discovered from `service.json`, so the dialog password is not required.
         */
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "0.0.0.0")

        /**
         * Pure validation shared by [doValidate]; kept free of Swing/EDT state so it is unit-testable.
         *
         * The password is only required for **remote** hosts: a local host resolves its credentials
         * through service discovery (`opencode service status` + `service.json`), so a blank password
         * must not block connecting to it. Remote hosts authenticate with the password printed by
         * `opencode serve` and therefore require a non-blank value.
         *
         * @param host the "hostname:port" text from the address field.
         * @param password the raw server password; ignored for local hosts.
         * @return a [ValidationInfo] describing the first problem, or null when the input is usable.
         */
        fun validateConnection(host: String, password: String): ValidationInfo? {
            validateAddress(host)?.let { return it }
            if (isLocalHost(host)) return null
            return validatePassword(password)
        }

        /** Validates the "hostname:port" address text. */
        private fun validateAddress(host: String): ValidationInfo? {
            val input = host.trim()

            if (input.isBlank()) {
                return ValidationInfo("Server address cannot be empty")
            }

            val parts = input.split(":")
            if (parts.size != 2) {
                return ValidationInfo("Invalid format. Expected: hostname:port")
            }

            val hostname = parts[0].trim()
            val portStr = parts[1].trim()

            if (hostname.isBlank()) {
                return ValidationInfo("Hostname cannot be empty")
            }

            val portNum = portStr.toIntOrNull()
            if (portNum == null || portNum < 1 || portNum > 65535) {
                return ValidationInfo("Port must be a number between 1 and 65535")
            }

            return null
        }

        /**
         * True when the "hostname:port" text points at a local host (the service-managed endpoint).
         * Only meaningful after [validateAddress] accepted the text.
         */
        private fun isLocalHost(host: String): Boolean {
            val hostname = host.trim().substringBefore(':').trim().lowercase()
            return hostname in LOCAL_HOSTS
        }

        /** Validates the server password, required only for remote hosts. */
        private fun validatePassword(password: String): ValidationInfo? =
            if (password.isBlank()) {
                ValidationInfo("Server password is required for remote hosts. It is printed by `opencode serve`.")
            } else {
                null
            }

        /**
         * Shows the dialog and returns the result.
         * @return ConnectionInfo if user clicked Connect, null if cancelled
         */
        fun show(project: Project, defaultPort: Int): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project, defaultPort)
            return if (dialog.showAndGet()) {
                ConnectionInfo(
                    dialog.hostname, dialog.port, dialog.password,
                    dialog.useWebInterface, dialog.customBasePath
                )
            } else {
                null
            }
        }
    }
}
