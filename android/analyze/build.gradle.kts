plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.analyze"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        // Instrumented (androidTest) tests run on the connected device and load the
        // real native :dsp lib (libkolaidsp.so, Essentia statically linked).
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
    // Task 3.1: the Analyzer turns the native :dsp JSON into a :core TrackAnalysis.
    implementation(project(":core"))
    // :dsp provides KolaiDsp.analyzePcmJson, the DEFAULT JSON producer. Referenced
    // only (compile-time); unit tests inject a fake seam and never load the .so.
    implementation(project(":dsp"))

    // kotlinx-serialization-json RUNTIME ONLY (no @Serializable / no compiler
    // plugin) -- mirrors the :core taste layer convention. Literal version on
    // purpose: do NOT touch gradle/libs.versions.toml (parallel-module-safe).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Pure-JVM unit tests; inject a fake analyzeJson seam (never the native lib).
    testImplementation("junit:junit:4.13.2")

    // Instrumented (androidTest) tests: run on the device and exercise the REAL
    // native lib via the default Analyzer seam. Literal versions on purpose: do
    // NOT touch gradle/libs.versions.toml (parallel-module-safe).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    // Task 2.3: the on-device AudioDecoder round-trip test synths PCM, encodes it
    // with ai.kolai.mix.AacEncoder, then decodes+analyzes it. Pulls :mix onto the
    // androidTest classpath only (no main-code dependency on :mix).
    androidTestImplementation(project(":mix"))
}