cmake_minimum_required(VERSION 3.22.1)

project(kolaidsp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Stub native library: just proves the NDK/CMake toolchain wires up and a JNI
# symbol can be called from Kotlin. Essentia + the real analyze bridge land in
# Task 0.3.
add_library(kolaidsp SHARED
    kolaidsp.cpp
)

find_library(log-lib log)

target_link_libraries(kolaidsp
    ${log-lib}
)

# 16 KB page alignment, required by the OnePlus 15 / Android 15+ devices.
# This is the load-bearing flag the task calls for; NDK r27 supports it.
target_link_options(kolaidsp PRIVATE "-Wl,-z,max-page-size=16384")
