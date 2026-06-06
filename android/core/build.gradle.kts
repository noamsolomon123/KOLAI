plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Task 1.4: kotlinx-serialization-json RUNTIME ONLY (no @Serializable /
    // no compiler plugin) -- used by ai.kolai.core.taste to parse the Spotify
    // response shape and (de)serialize the snake_case taste.json contract.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Task 1.1: pure-JVM unit tests for the ported core models + Camelot logic.
    // Literal version on purpose: do NOT touch the shared gradle/libs.versions.toml
    // (avoids conflicts with parallel module work).
    testImplementation("junit:junit:4.13.2")
}
