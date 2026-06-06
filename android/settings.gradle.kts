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
        // NewPipeExtractor (Task 0.4 / 2.2 download spike) is published via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "kolai"

include(":app")
include(":core")
include(":acquire")
include(":analyze")
include(":mix")
include(":voice")
include(":station")
include(":dsp")