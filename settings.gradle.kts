plugins {
    // The plugin is compiled against IntelliJ IDEA 2024.2, which pins the toolchain to JDK 21.
    // Developers (and CI) are not guaranteed to have a JDK 21 installed, so let Gradle provision
    // one automatically instead of failing with "Cannot find a Java installation".
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "OpenCodeV2_UI"
