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
    // Engine modules (wired now so the dependency graph is exercised end-to-end;
    // they are empty skeletons for Task 0.1).
    implementation(project(":core"))
    implementation(project(":acquire"))
    implementation(project(":analyze"))
    implementation(project(":mix"))
    implementation(project(":voice"))
    implementation(project(":station"))
    implementation(project(":dsp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
