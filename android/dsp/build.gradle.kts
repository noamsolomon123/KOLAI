plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.kolai.dsp"
    compileSdk = 36

    // NDK selection: the task asked for 27.1.12297006, but that directory is an
    // empty/broken stub on this machine (no source.properties / toolchain). The
    // fully-installed r27 NDK is 27.0.12077973, which also supports 16 KB page
    // alignment via the linker flag in CMakeLists.txt. r28 is not installed.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 31

        ndk {
            // arm64-v8a: real device (OnePlus 15). x86_64: standard Android
            // emulator for unattended soak tests. Each ABI links its own
            // prebuilt essentia per-ABI libessentia.a (see CMakeLists.txt).
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // 16 KB page alignment, required by the OnePlus 15 (Android 15+).
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
}
