package ai.kolai.dsp

/**
 * Thin Kotlin handle over the native `libkolaidsp.so`.
 *
 * For Task 0.1 this only exposes [nativeHello] to prove the JNI/NDK toolchain is
 * wired up. The real analyze bridge (Essentia) is added in Task 0.3.
 */
object KolaiDsp {
    init {
        System.loadLibrary("kolaidsp")
    }

    external fun nativeHello(): String
}
