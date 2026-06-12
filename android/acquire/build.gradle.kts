plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.acquire"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        // Task 2.2: on-device NewPipe search+download spike runs via androidTest.
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
    implementation(project(":core"))

    // Task 2.2: on-device YouTube search + audio-stream resolution. Published via
    // JitPack (see settings.gradle.kts). Literal version on purpose: do NOT touch
    // the shared gradle/libs.versions.toml (parallel-module-safe).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.2")

    // OkHttp backs the NewPipe Downloader and the stream download to cache.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // runBlocking bridges the suspend PoTokenSource onto NewPipe's synchronous
    // PoTokenProvider interface (NpePoToken.kt). Literal version on purpose: do
    // NOT touch the shared gradle/libs.versions.toml (parallel-module-safe).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Task 2.1: pure-JVM unit tests for the ported candidate-scoring logic.
    // Literal version on purpose: do NOT touch the shared gradle/libs.versions.toml
    // (avoids conflicts with parallel module work).
    testImplementation("junit:junit:4.13.2")

    // Task 2.2: instrumented (androidTest) device test exercises real
    // search -> download, then decodes the result to PROVE it is real audio.
    // Literal versions on purpose: do NOT touch gradle/libs.versions.toml.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // :analyze provides AudioDecoder.decodeToPcm to validate the download.
    androidTestImplementation(project(":analyze"))
}