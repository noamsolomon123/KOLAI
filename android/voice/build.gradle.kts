plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.voice"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        // On-device androidTest (GeminiTtsDeviceTest): real Gemini TTS call.
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
    // VoiceRenderer returns ai.kolai.core.DJSlot.
    implementation(project(":core"))

    // Task 1.3 (:voice half): raw Gemini REST via Ktor + kotlinx-serialization-json
    // RUNTIME ONLY (tree navigation, no @Serializable / no serialization compiler plugin).
    // Literal versions on purpose: do NOT touch the shared gradle/libs.versions.toml
    // (avoids conflicts with parallel module work). Verified resolvable on Maven Central.
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3") // real engine for production
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("junit:junit:4.13.2")

    // On-device androidTest plumbing for GeminiTtsDeviceTest (real Gemini TTS call).
    // Literal versions on purpose: do NOT touch gradle/libs.versions.toml.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}