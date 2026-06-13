plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.station"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        // On-device instrumentation runner for the androidTest source set
        // (SetlistParsingDeviceTest proves the regex patterns load on Android's
        // ICU engine). Literal here on purpose: do NOT touch libs.versions.toml.
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

// CORPUS HARNESS: forward -Dkolai.corpus into the FORKED unit-test JVM. Gradle
// command-line -D sets it on the daemon, not the test worker, so CorpusGenerator
// would otherwise always Assume-skip. Harmless for the normal suite: when the
// property is unset the harness still skips.
tasks.withType<Test>().configureEach {
    System.getProperty("kolai.corpus")?.let { systemProperty("kolai.corpus", it) }
}

dependencies {
    // Task 5.1 (SetlistPlanner half): port of backend/radioai/setlist.py.
    // kotlinx-serialization-json is used at RUNTIME ONLY (tree navigation via
    // Json.parseToJsonElement / jsonArray / jsonObject / jsonPrimitive) -- no
    // @Serializable, no serialization compiler plugin.
    // Literal versions on purpose: do NOT touch the shared gradle/libs.versions.toml
    // (avoids conflicts with parallel module work).
    implementation(project(":core"))
    // Task 5.3 (BlockRenderer): the renderer calls Dsp.* (trimSilence, duck,
    // equalPowerCrossfade, SR) from :mix instead of re-porting the DSP math.
    implementation(project(":mix"))
    // Wave 2 (feature 13): BlockRenderer refines the analyzer's outro hint
    // against the decoded audio via ai.kolai.analyze.refineOutroStart (pure
    // Kotlin, no native load in unit tests).
    implementation(project(":analyze"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // CORPUS HARNESS ONLY (CorpusGenerator, gated by -Dkolai.corpus=1; the
    // normal suite never touches these). Builds the REAL GeminiTextClient
    // (:voice) over a real Ktor OkHttp HttpClient to drive DjBrain +
    // DeezerDiscovery against live Gemini/Deezer. Literal versions match
    // :voice / :app -- do NOT touch gradle/libs.versions.toml.
    testImplementation(project(":voice"))
    testImplementation("io.ktor:ktor-client-core:3.0.3")
    testImplementation("io.ktor:ktor-client-okhttp:3.0.3")

    // On-device (androidTest) deps for SetlistParsingDeviceTest. Literal versions
    // on purpose: do NOT touch the shared gradle/libs.versions.toml.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}