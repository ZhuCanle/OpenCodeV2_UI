plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "ai.opencode"
version = "1.2.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        // NOTE: IntelliJ IDEA Community is no longer published as a separate artifact since 2025.3;
        // the single "IntelliJ IDEA" distribution is the only option and it bundles the ultimate plugin.
        intellijIdea("2026.2.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Terminal API
        bundledPlugin("org.jetbrains.plugins.terminal")

        // JCEF: bundled in the product up to 2025.x, but split into its own bundled plugin on 2026.2
        bundledPlugin("com.intellij.modules.jcef")

        // The single 2026.2 "IntelliJ IDEA" distribution bundles the ultimate plugin, but its module
        // does not reach the test runtime classpath by default. Without it, opening the fixture
        // project trips over an ultimate extension (B.B.B.B.s) that cannot be instantiated.
        testBundledModule("com.intellij.modules.ultimate")
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
            sinceBuild = "262"
            // No untilBuild - compatible with all future versions
        }

        changeNotes = """
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
    // Plugin Verifier for binary compatibility. `recommended()` resolves the IDE builds that the
    // Marketplace considers relevant, so run `./gradlew verifyPlugin` before publishing.
    pluginVerification {
        ides {
            recommended()
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
