#include <jni.h>
#include <string>
#include <algorithm>
#include <vector>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_chtai_assistant_WhisperEngine_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, params);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_chtai_assistant_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");
    const jsize count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // The bundled speech model is intended for Persian; forcing fa avoids slow language auto-detection.
    params.language = "fa";
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.n_threads = threads > 0 ? std::min(threads, 2) : 2;
    const int result = whisper_full(ctx, params, audio, count);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    if (result != 0) return env->NewStringUTF("");
    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *part = whisper_full_get_segment_text(ctx, i);
        if (part) text += part;
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_chtai_assistant_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}
