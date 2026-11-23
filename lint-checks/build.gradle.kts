// SPDX-License-Identifier: GPL-3.0-or-later
// The project's own Lint checks (#43): text the UI shows comes from string resources.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Lint supplies its API when it loads the checks.
    compileOnly(libs.lint.api)
}
