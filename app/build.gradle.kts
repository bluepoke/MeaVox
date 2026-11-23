// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "eu.schicht8.meavox"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "eu.schicht8.meavox"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // ADR 1: the network flavor will include network Sources (NewPipe); the local flavor only
    // ever has the Local File Source and stays Play-Store-safe. Both are GPL-3.0-or-later.
    flavorDimensions += "distribution"
    productFlavors {
        create("network") {
            dimension = "distribution"
            manifestPlaceholders["appLabel"] = "MeaVox"
        }
        create("local") {
            dimension = "distribution"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            manifestPlaceholders["appLabel"] = "MeaVox Local"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    // English and German (#43). The generated locale config lets Android 13+ offer the app's
    // languages in its per-app language setting; values/ is English (res/resources.properties).
    androidResources {
        generateLocaleConfig = true
    }

    lint {
        error += listOf("MissingTranslation", "HardcodedUiText")
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor (network flavor) uses java.nio APIs Android has only from API 33. The
        // option applies to every flavor; the local one just doesn't use what it adds.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":engine"))
    implementation(project(":export"))
    lintChecks(project(":lint-checks"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    implementation(project(":separation"))
    implementation(project(":lyrics"))
    // ADR 1: the NewPipe Sources ship in the network flavor only.
    "networkImplementation"(project(":newpipe"))
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.onnxruntime.android)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}
