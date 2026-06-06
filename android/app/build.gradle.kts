plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.kolai.app"
    // SDK note: android-37 is not installed on this machine; the spec asks for 37
    // aspirationally. We build against 36 (android-36.1 is the max installed platform).
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.kolai.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Single ABI for the OnePlus 15.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // On-device integration test (EndToEndBlockTest): real LLM + YouTube + TTS.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Engine modules (the :app wiring layer is the single coupling point that
    // adapts these otherwise-decoupled modules into the :station seams).
    implementation(project(":core"))
    implementation(project(":acquire"))
    implementation(project(":analyze"))
    implementation(project(":mix"))
    implementation(project(":voice"))
    implementation(project(":station"))
    implementation(project(":dsp"))

    // Wiring needs to construct the production Ktor OkHttp HttpClient for the
    // Gemini text/TTS clients, and touch kotlinx JSON / coroutines directly.
    // The engine modules declare these as `implementation` (not `api`), so they
    // are NOT transitive onto :app -- declare them here too. Literal versions to
    // match the engine modules; do NOT touch gradle/libs.versions.toml.
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Playback layer: AndroidX Media3 ExoPlayer + MediaSession (foreground media
    // service, lock-screen controls, gapless local-file playlist). Literal
    // versions on purpose -- do NOT touch gradle/libs.versions.toml.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // MediaController.buildAsync returns a Guava ListenableFuture (pulled in
    // transitively by media3-session; declared explicitly for the direct import).
    implementation("com.google.guava:guava:33.3.1-android")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // On-device end-to-end integration test plumbing (EndToEndBlockTest).
    // Literal versions on purpose: do NOT touch gradle/libs.versions.toml.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}