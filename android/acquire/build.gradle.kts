plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.acquire"
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
    implementation(project(":core"))

    // Task 2.1: pure-JVM unit tests for the ported candidate-scoring logic.
    // Literal version on purpose: do NOT touch the shared gradle/libs.versions.toml
    // (avoids conflicts with parallel module work).
    testImplementation("junit:junit:4.13.2")
}
