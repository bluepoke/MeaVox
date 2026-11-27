// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The license texts one app carries (#21): every text in `docs/licenses/` and the app's own license
 * (`LICENSE`, as `GPL-3.0.txt`), plus those in `docs/licenses/max/` for MeaVox Max only. They land in
 * the app's assets under `licenses/`, where the third-party licenses screen reads them.
 */
abstract class LicenseTexts : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenses: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val appLicense: RegularFileProperty

    @get:Input
    abstract val withMax: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val from = licenses.get().asFile
        val into = outputDirectory.get().asFile.resolve("licenses")
        into.deleteRecursively()
        from.walkTopDown().filter { it.isFile && it.extension == "txt" }.forEach { text ->
            val path = text.relativeTo(from).invariantSeparatorsPath
            if (withMax.get() || !path.startsWith("max/")) text.copyTo(into.resolve(path))
        }
        appLicense.get().asFile.copyTo(into.resolve("GPL-3.0.txt"))
    }
}

/**
 * The release signing of [flavor] (#21), from the environment only, never from a file in the
 * repository: the Gradle properties `meavox.signing.<flavor>.storeFile`, `.storePassword`,
 * `.keyAlias` and `.keyPassword`, or the variables `MEAVOX_SIGNING_<FLAVOR>_STORE_FILE`, `…_STORE_PASSWORD`,
 * `…_KEY_ALIAS` and `…_KEY_PASSWORD`. Without all four, the release build stays unsigned.
 */
fun releaseSigning(flavor: String): com.android.build.api.dsl.ApkSigningConfig? {
    fun value(name: String): String? {
        val variable = "MEAVOX_SIGNING_${flavor.uppercase()}_" + name.replace(Regex("([A-Z])"), "_$1").uppercase()
        return providers.gradleProperty("meavox.signing.$flavor.$name").orNull?.ifBlank { null }
            ?: providers.environmentVariable(variable).orNull?.ifBlank { null }
    }
    val storeFile = value("storeFile") ?: return null
    val storePassword = value("storePassword") ?: return null
    val keyAlias = value("keyAlias") ?: return null
    val keyPassword = value("keyPassword") ?: return null
    return android.signingConfigs.create("${flavor}Release") {
        this.storeFile = file(storeFile)
        this.storePassword = storePassword
        this.keyAlias = keyAlias
        this.keyPassword = keyPassword
    }
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

    // Two apps from one code base (ADR 24): MeaVox, the local flavor, only ever has the Local File
    // Source and goes to Google Play; MeaVox Max, the network flavor, adds the NewPipe Sources and goes
    // to F-Droid and GitHub Releases. Both are GPL-3.0-or-later. The IDs can't change after the first
    // release.
    flavorDimensions += "distribution"
    productFlavors {
        create("local") {
            dimension = "distribution"
            manifestPlaceholders["appLabel"] = "MeaVox"
            signingConfig = releaseSigning("local")
        }
        create("network") {
            dimension = "distribution"
            applicationIdSuffix = ".max"
            manifestPlaceholders["appLabel"] = "MeaVox Max"
            signingConfig = releaseSigning("network")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // Debug builds keep the debug key, whatever the flavor says for release.
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        // The licenses screen links LAME's source at the version's tag (#21).
        buildConfig = true
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

androidComponents {
    onVariants { variant ->
        val texts = tasks.register<LicenseTexts>("${variant.name}LicenseTexts") {
            licenses.set(rootProject.layout.projectDirectory.dir("docs/licenses"))
            appLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
            withMax.set(variant.flavorName == "network")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(texts, LicenseTexts::outputDirectory)
    }
}

// The unit tests read the license texts the variant ships, as the assets hold them.
tasks.withType<Test>().configureEach {
    val variant = name.removePrefix("test").removeSuffix("UnitTest").replaceFirstChar { it.lowercase() }
    val texts = tasks.named<LicenseTexts>("${variant}LicenseTexts")
    dependsOn(texts)
    systemProperty("meavox.licenseTexts", texts.get().outputDirectory.get().asFile.resolve("licenses").path)
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
