// SPDX-License-Identifier: GPL-3.0-or-later
// Data layer: Room persistence and the app-private file store, implementing the domain core's
// repository and storage interfaces (ADR 8, ADR 10).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/** Where Room exports each database version's schema; the migration tests read them (#26). */
val schemas = "$projectDir/schemas"

android {
    namespace = "eu.schicht8.meavox.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        // Robolectric reads the exported Room schemas as test assets (migration tests, #26).
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("test").assets.directories.add(schemas)
    }
}

room {
    // Exported schemas are committed so later migrations can be tested (spec #1, testing decisions).
    schemaDirectory(schemas)
}

dependencies {
    api(project(":core"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Migration tests (#26): JUnit 4 on Robolectric, run on the JUnit Platform by the Vintage engine.
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}
