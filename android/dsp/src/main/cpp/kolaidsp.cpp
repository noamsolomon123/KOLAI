#include <jni.h>
#include <string>

// Stub JNI entry point. Proves the NDK toolchain compiles a .so for arm64-v8a
// and that Kotlin can call into native code. The real Essentia analyze bridge
// (analyzePcm -> {bpm, beats, key, ...}) replaces this in Task 0.3.
extern "C" JNIEXPORT jstring JNICALL
Java_ai_kolai_dsp_KolaiDsp_nativeHello(JNIEnv* env, jobject /* this */) {
    std::string msg = "KOLAI DSP native online";
    return env->NewStringUTF(msg.c_str());
}
