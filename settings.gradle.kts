pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is published on JitPack only (ADR 1); nothing else comes from there.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.teamnewpipe")
                includeGroup("com.github.TeamNewPipe") // its nanojson fork
            }
        }
    }
}

rootProject.name = "meavox"

include(":app")
include(":core")
include(":data")
include(":engine")
include(":export")
include(":lint-checks")
include(":separation")
include(":lyrics")
include(":coverart")
include(":newpipe")
