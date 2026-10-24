import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "ai.opencode"
version = "1.3.9"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        // Compile against the OLDEST IDE we support (2024.3), so the bytecode targets the JVM that
        // build ships with (JBR 21) and no API newer than 2024.3 slips into the plugin. Running on
        // newer IDEs is then checked by the Plugin Verifier instead (see pluginVerification below).
        // NOTE: from 2025.3 on, Community/Ultimate are published as a single "IntelliJ IDEA"
        // artifact; older releases still use the separate ideaIC/ideaIU coordinates.
        intellijIdeaCommunity("2024.2.6")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Terminal API
        bundledPlugin("org.jetbrains.plugins.terminal")

        // Plugin Verifier CLI used by `./gradlew verifyPlugin`.
        pluginVerifier()

        // JCEF is part of the product itself up to 2025.x (it only became a separate bundled
        // plugin on 2026.2), so there is nothing extra to add for 2024.2.
    }

    // HTTP client and JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            // No untilBuild - compatible with all future versions
        }

        changeNotes = """
            <h2>1.3.9</h2>
            <ul>
                <li>Fix: switching the connect dialog back to "Local service" now shrinks it again. The window kept the height of the remote address and password rows, because the platform pins a window minimum size and re-packing cannot resize below it.</li>
            </ul>
            <h2>1.3.8</h2>
            <ul>
                <li>New: the connect dialog is local-first. It opens in "Local service" mode and asks for nothing - the address and password of the background service are discovered automatically - while the address and password fields only appear when "Remote server" is selected. The last choice is remembered.</li>
                <li>New: the working directory and the web interface options are always visible - they apply to a local session too, so there is no Advanced section to expand.</li>
                <li>Fix: the dialog no longer scans for a free port before it opens, so it appears immediately instead of after a probe of up to 100 ports.</li>
                <li>Fix: local web mode opened the web tab on the port typed into the dialog instead of the port the service actually listens on. The tab now follows the discovered endpoint.</li>
                <li>Fix: a turn that ran in one IDE window opened its diff tab in another window. The turn events (session.execution.started/succeeded/failed) carry no directory on the v2 wire, so every window treated them as its own; a turn is now attributed to the project that owns the session, which also covers sessions that existed before the plugin connected.</li>
                <li>Note: the plugin no longer uses platform APIs that are deprecated in 2024.2 and 2026.2 where a supported replacement exists.</li>
            </ul>
            <h2>1.3.7</h2>
            <ul>
                <li>Fix: the first OpenCode launch in an IDE session logged "This method is forbidden on EDT" (issue #3). On IntelliJ 2026.2 the terminal shell path (TerminalProjectOptionsProvider.getShellPath) blocks and may not be read on the Event Dispatch Thread; the plugin now resolves it on a background thread before typing the command into the terminal.</li>
            </ul>
            <h2>1.3.6</h2>
            <ul>
                <li>Fix: with several IDE windows open, the diff tab of a file change could appear in the wrong window. The opencode event stream is not filtered by its directory query parameter - every subscriber receives every directory's events - and the plugin dispatched all of them, so a window reacted to another project's session and opened its diff tab there (usually the focused window). Events are now filtered by their "location.directory" before they are dispatched.</li>
            </ul>
            <h2>1.3.5</h2>
            <ul>
                <li>Fix: closing one IDE window no longer kills another window's terminal. Terminal widgets are registered in a process-wide registry, but project disposal cleared every project's widget, so closing one project window disposed the other window's terminal shell (the other window printed "zsh: warning: 1 jobs SIGHUPed" and its connection state pointed at a dead widget). Widgets are now tracked per owning project and disposal only tears down its own.</li>
                <li>Fix: after the local background service restarted - usually on a different port - the connection manager kept retrying the stale endpoint and never recovered. The plugin now rediscovers the service with "opencode service status" (starting it when needed), and when the endpoint changed it rebuilds the API client, the SSE stream and the terminal against the new endpoint. Rediscovery is throttled, so a transient failure does not restart a working terminal.</li>
                <li>Fix: the "Connection lost" dialog's Reconnect button did nothing; only the New branch had code. Reconnect now restarts the current mode, New opens the connect dialog, and the dialog is rearmed after a successful reconnect instead of appearing at most once per IDE session.</li>
                <li>Fix: the working directory chosen in the connect dialog was lost when the terminal was restarted; it is now kept.</li>
            </ul>
            <h2>1.3.4</h2>
            <ul>
                <li>Fix: on Windows the opencode CLI is now resolved to a real executable (opencode.cmd or opencode.exe) instead of the bare name opencode. Java starts processes through CreateProcess, which only appends .exe to a bare name, so an install from Scoop or npm - where PATH leads with the extensionless POSIX launcher and the Windows entry point is opencode.cmd - was detected but could never be executed, and the local service stayed unavailable (issue #2). The CLI is now read from the "where opencode" output and the first .exe/.com/.cmd/.bat entry is used.</li>
            </ul>
            <h2>1.3.3</h2>
            <ul>
                <li>Fix: on Windows the local service password is now read from the config directory opencode v2 actually uses (~/.config/opencode, i.e. %USERPROFILE%\.config\opencode, or ${'$'}XDG_CONFIG_HOME/opencode). The plugin looked only in %APPDATA%\opencode, so local sessions could fail to authenticate; %APPDATA%\opencode is still checked as a legacy fallback.</li>
            </ul>
            <h2>1.3.2</h2>
            <ul>
                <li>New: the plugin now supports IntelliJ Platform 2024.2 and newer (since-build 242). It is compiled against IntelliJ IDEA 2024.2, so the bytecode runs on the JBR 21 shipped with 2024.2 - 2025.x and still loads on the current 2026.x releases.</li>
                <li>Fix: quick launch is now Cmd+\ on macOS (Ctrl+\ on Windows/Linux). The old Cmd+Esc binding collided with the IDE's own Vcs.CombinedDiff.ToggleCollapseBlock action, so pressing it often did nothing; Shift+Cmd+Esc is registered as a second macOS shortcut.</li>
                <li>Fix: the opencode TUI now starts in Git Bash, Cygwin, PowerShell and WSL terminals on Windows (issue #1). The plugin typed the resolved CLI path into the terminal as C:\Users\...\opencode.exe, and POSIX shells treat backslashes as escape characters, so the command collapsed to C:Users...opencode.exe and was not found. The command now follows the configured shell: C:/Users/.../opencode.exe for cmd, PowerShell and POSIX shells, /mnt/c/Users/.../opencode.exe for WSL, and the &amp; call operator for PowerShell paths that contain a space.</li>
                <li>Fix: the icons match the upstream opencode logo again - the mark's inner block keeps its original 60% opacity, so the mark reads as a rectangular frame with a lighter block instead of a solid shape.</li>
                <li>Note: the optional JCEF web tab is registered through the com.intellij.modules.jcef module, which only exists from 2026.2 on. On 2024.2 - 2025.x the plugin runs in terminal mode only.</li>
            </ul>
            <h2>1.2.2</h2>
            <ul>
                <li>New: the plugin logo and the tool window / action icons carry a "v2" mark, so the plugin is easy to tell apart from the upstream OpenCode UI plugin.</li>
                <li>New: the context menu entries now read "Open OpenCode V2" and "Add to OpenCode V2 Terminal".</li>
                <li>Change: the default keyboard shortcuts are back to the ones the original plugin shipped with - Cmd+Esc / Ctrl+\ for quick launch and Opt+Cmd+K / Ctrl+Alt+K for sending context.</li>
                <li>Fix: the tool window / action icons were invisible in light themes; they now follow the IDE theme (dark ink on light themes, light ink on dark themes).</li>
                <li>Fix: the tool window icon uses the full 16x16 box, so the mark and the "v2" stay readable on HiDPI screens.</li>
            </ul>
            <h2>1.2.0</h2>
            <ul>
                <li>New: opencode v2 support. The plugin now drives the v2 background service: local sessions attach to the service started by the CLI (discovered via `opencode service status` and authenticated with the password from `service.json`), and remote sessions connect with host:port plus the Basic auth password printed by `opencode serve`.</li>
                <li>Change: the password is required for remote servers, while local connections discover it automatically from the running service; the local port field is ignored because the service owns its own port.</li>
                <li>Fix: session discovery, status, diff review, completion notifications and @-reference injection now use the v2 `/api` HTTP and SSE surface.</li>
            </ul>
        """.trimIndent()
    }

    // The JetBrains Marketplace approval guidelines require every upload to be checked with the
    // Plugin Verifier for binary compatibility, so run `./gradlew verifyPlugin` before publishing.
    pluginVerification {
        // Kotlin emits bridge overrides for ToolWindowFactory's default methods (getIcon, getAnchor,
        // manage), which the verifier reports as INTERNAL_API_USAGES on 2024.2 even though the plugin
        // never calls them itself. Only real breakage should fail the build.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
        )

        ides {
            // Check the two ends of the supported range: the minimum IDE (what we compile against)
            // and the current release. That catches both "uses an API the minimum IDE lacks" and
            // "uses an API a newer IDE removed", without downloading every recommended build
            // (~12 GB of IDEs). Switch to recommended() for a full sweep before a major upload.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// Kotlin 2.x emits bridge methods for every default method of an implemented Kotlin interface
// (e.g. ToolWindowFactory). Those bridges reference platform API that is deprecated or experimental
// in newer IDEs, which the Plugin Verifier reports as *our* usages. `no-compatibility` (formerly
// -Xjvm-default=all) lets the platform interfaces keep their own default implementations, so no
// bridges are generated.
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

tasks {
    // NOTE: Java/Kotlin JVM targets are intentionally NOT set here. The IntelliJ Platform Gradle
    // plugin pins the toolchain to the JVM required by the target IDE (JBR 25 for 2026.2), and
    // overrides any hand-written target, which used to leave compileJava (17) and compileKotlin
    // (25) inconsistent. Letting the plugin own it keeps both tasks aligned.

    // Ship the license text inside the plugin artifact. The MIT License requires the copyright
    // notice and the license text to accompany every distributed copy of the software.
    named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            into("META-INF")
        }
    }

    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        systemProperty("ide.no.platform.update", "true")
    }

    // Disable buildSearchableOptions as it is flaky and often fails
    named("buildSearchableOptions") {
        enabled = false
    }

    // RealServerIntegrationTest needs the opencode CLI and a live server (env-dependent), so it is
    // excluded unless explicitly opted in with `-PwithRealServer`.
    withType<org.gradle.api.tasks.testing.Test> {
        // Be explicit about what counts as a test class: the test source set also holds helpers and
        // fixtures whose names trip Gradle's default detection (TestRunner, TestSessionManager,
        // FakeOpenCodeServer, the V2WireFixtureTest file facade, ...), and running them as JUnit
        // classes fails with InvalidTestClassError.
        include("**/*Test.class", "**/*Test")

        if (!project.hasProperty("withRealServer")) {
            exclude("**/RealServerIntegrationTest.class")
        }
    }
}

// The JVM target is owned by the IntelliJ Platform Gradle plugin (see the note in `tasks {}`).
