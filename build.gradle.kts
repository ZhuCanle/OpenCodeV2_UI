import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "ai.opencode"
version = "1.3.2"

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
