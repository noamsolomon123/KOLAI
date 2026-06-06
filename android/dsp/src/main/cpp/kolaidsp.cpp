#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <mutex>
#include <cmath>

#include <android/log.h>

#include <essentia/algorithmfactory.h>
#include <essentia/essentiamath.h>
#include <essentia/pool.h>

#define LOG_TAG "KolaiDsp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using essentia::Real;
using essentia::standard::Algorithm;
using essentia::standard::AlgorithmFactory;

namespace {

std::once_flag g_essentiaInit;

void ensureEssentiaInit() {
    std::call_once(g_essentiaInit, []() {
        essentia::init();
        LOGI("essentia::init() done");
    });
}

// Append a JSON number, emitting null for non-finite values so the Kotlin side
// never has to parse NaN/Infinity.
void appendNumber(std::ostringstream& os, double v) {
    if (std::isfinite(v)) {
        os << v;
    } else {
        os << "null";
    }
}

std::string jsonError(const std::string& message) {
    std::ostringstream os;
    os << "{\"error\":\"";
    for (char c : message) {
        if (c == '"' || c == '\\') os << '\\';
        if (c == '\n' || c == '\r') { os << ' '; continue; }
        os << c;
    }
    os << "\"}";
    return os.str();
}

std::string analyze(const std::vector<Real>& signal, int sr) {
    ensureEssentiaInit();

    AlgorithmFactory& factory = AlgorithmFactory::instance();

    // ---- Rhythm: BPM, beat ticks (seconds), confidence ----
    Real bpm = 0.f;
    std::vector<Real> ticks;
    Real beatConfidence = 0.f;
    std::vector<Real> estimates;
    std::vector<Real> bpmIntervals;
    {
        Algorithm* rhythm = factory.create("RhythmExtractor2013", "method", "multifeature");
        rhythm->input("signal").set(signal);
        rhythm->output("bpm").set(bpm);
        rhythm->output("ticks").set(ticks);
        rhythm->output("confidence").set(beatConfidence);
        rhythm->output("estimates").set(estimates);
        rhythm->output("bpmIntervals").set(bpmIntervals);
        rhythm->compute();
        delete rhythm;
    }

    // ---- Key: tonic + scale (Krumhansl/Temperley profile is the default) ----
    std::string keyTonic = "";
    std::string keyScale = "";
    Real keyStrength = 0.f;
    {
        Algorithm* key = factory.create("KeyExtractor");
        key->input("audio").set(signal);
        key->output("key").set(keyTonic);
        key->output("scale").set(keyScale);
        key->output("strength").set(keyStrength);
        key->compute();
        delete key;
    }

    // ---- Energy: RMS over the whole signal ----
    Real energy = 0.f;
    {
        Algorithm* rms = factory.create("RMS");
        rms->input("array").set(signal);
        rms->output("rms").set(energy);
        rms->compute();
        delete rms;
    }

    std::ostringstream os;
    os << "{";
    os << "\"bpm\":"; appendNumber(os, bpm); os << ",";
    os << "\"beatConfidence\":"; appendNumber(os, beatConfidence); os << ",";
    os << "\"energy\":"; appendNumber(os, energy); os << ",";
    os << "\"keyTonic\":\"" << keyTonic << "\",";
    os << "\"keyScale\":\"" << keyScale << "\",";
    os << "\"keyStrength\":"; appendNumber(os, keyStrength); os << ",";
    os << "\"sampleRate\":" << sr << ",";
    os << "\"beatTimes\":[";
    for (size_t i = 0; i < ticks.size(); ++i) {
        if (i) os << ",";
        appendNumber(os, ticks[i]);
    }
    os << "]";
    os << "}";
    return os.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ai_kolai_dsp_KolaiDsp_nativeHello(JNIEnv* env, jobject /* this */) {
    std::string msg = "KOLAI DSP native online (Essentia)";
    return env->NewStringUTF(msg.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_kolai_dsp_KolaiDsp_analyzePcmJson(JNIEnv* env, jobject /* this */,
                                          jfloatArray pcm, jint sr) {
    std::string result;
    try {
        const jsize n = env->GetArrayLength(pcm);
        std::vector<Real> signal(static_cast<size_t>(n));
        if (n > 0) {
            env->GetFloatArrayRegion(pcm, 0, n, signal.data());
        }
        result = analyze(signal, static_cast<int>(sr));
    } catch (const std::exception& e) {
        LOGE("analyzePcmJson failed: %s", e.what());
        result = jsonError(e.what());
    } catch (...) {
        LOGE("analyzePcmJson failed: unknown error");
        result = jsonError("unknown native error");
    }
    return env->NewStringUTF(result.c_str());
}