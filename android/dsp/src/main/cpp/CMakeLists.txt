cmake_minimum_required(VERSION 3.22.1)

project(kolaidsp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ----------------------------------------------------------------------------
# Essentia (prebuilt static lib for arm64-v8a) + its public headers.
# The lib was cross-compiled with NDK r27 clang, KissFFT (bundled), lightweight
# (no ffmpeg/yaml/samplerate/taglib). See dsp/ESSENTIA_BUILD.md for the recipe.
# ----------------------------------------------------------------------------
set(ESSENTIA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/essentia")
set(ESSENTIA_LIB "${ESSENTIA_DIR}/lib/${ANDROID_ABI}/libessentia.a")

if(NOT EXISTS "${ESSENTIA_LIB}")
    message(FATAL_ERROR "Prebuilt Essentia not found at ${ESSENTIA_LIB}. "
                        "Build it per dsp/ESSENTIA_BUILD.md and drop it in.")
endif()

add_library(essentia STATIC IMPORTED)
set_target_properties(essentia PROPERTIES IMPORTED_LOCATION "${ESSENTIA_LIB}")

add_library(kolaidsp SHARED
    kolaidsp.cpp
)

target_include_directories(kolaidsp PRIVATE
    "${ESSENTIA_DIR}/include"        # exposes <essentia/...>
    "${ESSENTIA_DIR}/include/eigen3" # exposes <Eigen/...>
)

find_library(log-lib log)

target_link_libraries(kolaidsp
    essentia
    ${log-lib}
)

# 16 KB page alignment, required by the OnePlus 15 / Android 15+ devices.
# This is the load-bearing flag the task calls for; NDK r27 supports it.
target_link_options(kolaidsp PRIVATE "-Wl,-z,max-page-size=16384")