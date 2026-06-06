plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.mix"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        // Task 4.2: on-device instrumented test for the MediaCodec AAC encoder.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
    // Task 4.1: pure-JVM unit tests for the ported FloatArray DSP math.
    // Literal version on purpose: do NOT touch the shared gradle/libs.versions.toml
    // (avoids conflicts with parallel module work).
    testImplementation("junit:junit:4.13.2")

    // Task 4.2: on-device test for AacEncoder (needs real android.media.*).
    // Literal versions on purpose: do NOT touch the shared gradle/libs.versions.toml.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}