// SPDX-License-Identifier: GPL-3.0-or-later
// Export (ADR 8): the encoders for WAV, MP3 (LAME through the NDK, linked dynamically for the LGPL) and
// M4A/AAC (MediaCodec), their tags (RIFF INFO, ID3, MP4), and MediaStore output to Music/MeaVox/.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "eu.schicht8.meavox.export"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // The round trip of #15: each format written through MediaStore and read back with the platform's decoders.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
