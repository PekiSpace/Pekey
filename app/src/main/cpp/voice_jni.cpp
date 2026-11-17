// Hlasové zadávání Pekey: tenká vrstva JNI nad whisper.cpp. Vše běží v telefonu, nic se neposílá.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "PekeyVoice"

extern "C" JNIEXPORT jlong JNICALL
Java_cz_pekey_Voice_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) __android_log_print(ANDROID_LOG_WARN, TAG, "model se nenačetl");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_cz_pekey_Voice_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray samples,
                                     jstring lang, jint threads, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");
    const char *l = env->GetStringUTFChars(lang, nullptr);
    const char *pr = env->GetStringUTFChars(prompt, nullptr);
    std::string language(l), initial(pr);
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseStringUTFChars(prompt, pr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = language.c_str();
    wp.translate = false;
    wp.no_timestamps = true;
    wp.single_segment = false;
    wp.print_special = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.suppress_blank = true;
    if (!initial.empty()) wp.initial_prompt = initial.c_str();

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) return env->NewStringUTF("");

    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(ctx, i);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_cz_pekey_Voice_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}
