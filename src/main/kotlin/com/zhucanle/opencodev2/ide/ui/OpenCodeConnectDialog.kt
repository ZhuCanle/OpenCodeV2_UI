package com.zhucanle.opencodev2.ide.ui

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.util.Base64
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

/**
 * Dialog for starting an OpenCode session.
 *
 * A local session needs no input: opencode v2 runs as a service-managed background daemon whose
 * endpoint and password are discovered from `opencode service status` and `service.json`. Only a
 * remote server needs a typed address and password, so those fields stay hidden until "Remote
 * server" is selected. The working directory and the web UI apply to both modes and stay visible.
 */
class OpenCodeConnectDialog(
    private val project: Project
) : DialogWrapper(project, true) {

    data class ConnectionInfo(
        /** True when the endpoint and credentials come from service discovery, not from the dialog. */
        val local: Boolean,
        val hostname: String,
        val port: Int,
        val password: String?,
        val useWebInterface: Boolean,
        val customBasePath: String? = null
    )

    private val props = PropertiesComponent.getInstance()

    private val localRadio = JRadioButton("Local service")
    private val remoteRadio = JRadioButton("Remote server")

    private val addressField = JBTextField()
    private val passwordField = JBPasswordField()
    private val basePathField = ComboBox<String>().apply {
        isEditable = true
    }
    private val webInterfaceCheckBox = JCheckBox("Use web interface")

    private lateinit var remotePanel: JPanel

    var local: Boolean = true
        private set
    var hostname: String = DEFAULT_HOSTNAME
        private set
    var port: Int = DEFAULT_PORT
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
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        ButtonGroup().apply {
            add(localRadio)
            add(remoteRadio)
        }
        val savedRemote = props.getValue(PROP_CONNECTION_MODE, MODE_LOCAL) == MODE_REMOTE
        remoteRadio.isSelected = savedRemote
        localRadio.isSelected = !savedRemote

        // The address is restored so a remote host does not have to be typed again. The password
        // lives in the IDE credential store, never in options/*.xml, and the field masks it.
        addressField.text = props.getValue(PROP_LAST_ADDRESS, "").ifBlank { "$DEFAULT_HOSTNAME:$DEFAULT_PORT" }
        addressField.emptyText.text = "hostname:port (e.g. 10.0.0.5:49374)"
        addressField.toolTipText = "Format: hostname:port (e.g., 127.0.0.1:4096)"

        val savedPassword = loadSavedPassword() ?: migrateLegacyPassword(props)
        if (!savedPassword.isNullOrBlank()) {
            passwordField.text = savedPassword
        }
        passwordField.emptyText.text = "Password printed by `opencode serve`"
        passwordField.toolTipText = "Password printed by `opencode serve`. Ignored for local sessions, " +
            "which discover their credentials automatically."

        ModuleManager.getInstance(project).modules
            .mapNotNull { it.guessModuleDir()?.path }
            .sorted()
            .forEach { basePathField.addItem(it) }
        val savedPath = props.getValue(PROP_CUSTOM_BASE_PATH, "")
        if (savedPath.isNotBlank()) {
            basePathField.item = savedPath
        } else {
            basePathField.selectedIndex = -1
        }
        basePathField.toolTipText = "Working directory for the opencode CLI (defaults to the project root)"

        webInterfaceCheckBox.isSelected = props.getBoolean(PROP_USE_WEB, false)
        webInterfaceCheckBox.toolTipText = "Open the opencode web UI (/doc) instead of the terminal"

        remotePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Server address:", addressField)
            .addLabeledComponent("Server password:", passwordField)
            .panel

        remotePanel.isVisible = savedRemote

        localRadio.addActionListener { onModeChanged() }
        remoteRadio.addActionListener { onModeChanged() }

        // Everything but the remote address/password is always visible: the working directory and
        // the web UI apply to a local session too, so folding them away only hid the settings.
        return FormBuilder.createFormBuilder()
            .addComponent(localRadio)
            .addComponent(remoteRadio)
            .addComponent(remotePanel, 1)
            .addSeparator(10)
            .addLabeledComponent("Custom base path:", basePathField)
            .addComponent(webInterfaceCheckBox)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun onModeChanged() {
        val remote = remoteRadio.isSelected
        remotePanel.isVisible = remote
        resizeToContent()
        if (remote) {
            addressField.requestFocusInWindow()
        } else {
            localRadio.requestFocusInWindow()
        }
    }

    /**
     * Resizes the dialog to the rows that are visible now.
     *
     * `pack()` on its own only ever grows the window: the platform derives a window minimum size
     * from the content, and a window cannot be resized below that minimum, so the height of the
     * taller remote layout stayed in place after switching back to a local session. Dropping the
     * minimum and re-applying the recomputed size lets the dialog shrink again.
     */
    private fun resizeToContent() {
        pack()
        window?.minimumSize = null
        preferredSize?.let { setSize(it.width, it.height) }
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (remoteRadio.isSelected) addressField else localRadio

    override fun doValidate(): ValidationInfo? {
        if (!remoteRadio.isSelected) return null
        val problem = validateRemoteConnection(addressField.text, passwordField.password.concatToString())
            ?: return null
        // The address is checked first, so an address-only re-check identifies the offending field.
        val component = if (validateAddress(addressField.text) == null) passwordField else addressField
        return ValidationInfo(problem.message, component)
    }

    override fun doOKAction() {
        val remote = remoteRadio.isSelected
        local = !remote
        useWebInterface = webInterfaceCheckBox.isSelected

        val newBasePath = (basePathField.editor.item as? String)?.trim().orEmpty().ifBlank { null }
        customBasePath = newBasePath
        if (newBasePath.isNullOrBlank()) {
            props.unsetValue(PROP_CUSTOM_BASE_PATH)
        } else {
            props.setValue(PROP_CUSTOM_BASE_PATH, newBasePath)
        }
        props.setValue(PROP_USE_WEB, useWebInterface)
        props.setValue(PROP_CONNECTION_MODE, if (remote) MODE_REMOTE else MODE_LOCAL)

        if (remote) {
            val input = addressField.text.trim()
            val parts = input.split(":")
            hostname = parts[0].trim()
            port = parts[1].trim().toInt()
            password = passwordField.password.concatToString().trim().ifBlank { null }
            props.setValue(PROP_LAST_ADDRESS, "$hostname:$port")
            savePassword(password)
        }
        // A local session keeps the disabled defaults: discovery supplies endpoint and password.

        super.doOKAction()
    }

    companion object {
        private val logger = Logger.getInstance(OpenCodeConnectDialog::class.java)

        private const val DEFAULT_HOSTNAME = "127.0.0.1"
        private const val DEFAULT_PORT = 4096

        /** Legacy location of the password: Base64 in the IDE options file. Migrated on first read. */
        private const val PROP_LEGACY_PASSWORD = "opencode.lastPassword"

        private const val PROP_LAST_ADDRESS = "opencode.lastAddress"
        private const val PROP_CUSTOM_BASE_PATH = "opencode.customBasePath"
        private const val PROP_USE_WEB = "opencode.useWebInterface"
        private const val PROP_CONNECTION_MODE = "opencode.connectionMode"

        private const val MODE_LOCAL = "local"
        private const val MODE_REMOTE = "remote"

        /** Credential-store entry holding the last remote server password. */
        private val passwordAttributes = CredentialAttributes("OpenCodeV2/serverPassword")

        private fun loadSavedPassword(): String? = try {
            PasswordSafe.instance.getPassword(passwordAttributes)
        } catch (e: Exception) {
            logger.warn("Could not read the saved OpenCode password", e)
            null
        }

        private fun savePassword(password: String?) {
            try {
                PasswordSafe.instance.setPassword(passwordAttributes, password)
            } catch (e: Exception) {
                logger.warn("Could not store the OpenCode password", e)
            }
        }

        /**
         * Moves a password saved by an older plugin version out of the options file.
         *
         * @return the migrated password, or null when there is nothing to migrate
         */
        private fun migrateLegacyPassword(props: PropertiesComponent): String? {
            val legacy = props.getValue(PROP_LEGACY_PASSWORD, "")
            if (legacy.isBlank()) return null
            props.unsetValue(PROP_LEGACY_PASSWORD)
            val decoded = try {
                String(Base64.getDecoder().decode(legacy))
            } catch (e: Exception) {
                logger.warn("Could not decode the legacy OpenCode password", e)
                return null
            }
            savePassword(decoded)
            return decoded
        }

        /**
         * Pure validation shared by [doValidate]; kept free of Swing/EDT state so it is unit-testable.
         *
         * A local session needs neither address nor password: discovery resolves both, so nothing
         * typed here could be authoritative. A remote session authenticates with the password
         * printed by `opencode serve` and therefore requires a valid address and a non-blank value.
         *
         * @param local whether the dialog's "Local service" mode is selected
         * @param host the "hostname:port" text from the address field
         * @param password the raw server password
         * @return a [ValidationInfo] describing the first problem, or null when the input is usable
         */
        fun validateConnection(local: Boolean, host: String, password: String): ValidationInfo? =
            if (local) null else validateRemoteConnection(host, password)

        /** Validates a remote connection: address first, then the password. */
        private fun validateRemoteConnection(host: String, password: String): ValidationInfo? {
            validateAddress(host)?.let { return it }
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

        /** Validates the server password, required only for remote hosts. */
        private fun validatePassword(password: String): ValidationInfo? =
            if (password.isBlank()) {
                ValidationInfo("Server password is required for remote hosts. It is printed by `opencode serve`.")
            } else {
                null
            }

        /**
         * Shows the dialog and returns the result.
         * @return ConnectionInfo if the user clicked Connect, null if cancelled
         */
        fun show(project: Project): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project)
            return if (dialog.showAndGet()) {
                ConnectionInfo(
                    dialog.local, dialog.hostname, dialog.port, dialog.password,
                    dialog.useWebInterface, dialog.customBasePath
                )
            } else {
                null
            }
        }
    }
}
