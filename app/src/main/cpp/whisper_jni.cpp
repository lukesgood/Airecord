// JNI bridge between Kotlin (com.airecord.app.stt.WhisperLib) and whisper.cpp.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>

#include "whisper.h"

#define LOG_TAG "AirecordWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// The abort callback may be invoked from ggml worker threads, which are not
// attached to the JVM, so cancellation is signalled through a plain atomic.
std::atomic<bool> g_abort{false};

struct ProgressCtx {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

// Called by whisper_full on the calling (JVM-attached) thread.
void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *p = static_cast<ProgressCtx *>(user);
    if (p != nullptr && p->listener != nullptr && p->method != nullptr) {
        p->env->CallVoidMethod(p->listener, p->method, progress);
        if (p->env->ExceptionCheck()) {
            p->env->ExceptionClear();
        }
    }
}

bool abort_cb(void *) {
    return g_abort.load();
}

std::string to_std_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

whisper_context *ctx_from(jlong ptr) {
    return reinterpret_cast<whisper_context *>(ptr);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_airecord_app_stt_WhisperLib_initContext(JNIEnv *env, jobject, jstring model_path, jboolean use_gpu) {
    std::string path = to_std_string(env, model_path);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = use_gpu == JNI_TRUE;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_airecord_app_stt_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(ctx_from(ptr));
}

JNIEXPORT void JNICALL
Java_com_airecord_app_stt_WhisperLib_abort(JNIEnv *, jobject) {
    g_abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_airecord_app_stt_WhisperLib_resetAbort(JNIEnv *, jobject) {
    g_abort.store(false);
}

JNIEXPORT jint JNICALL
Java_com_airecord_app_stt_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject, jlong ptr, jint threads, jfloatArray audio,
        jstring language, jstring initial_prompt, jobject listener) {
    whisper_context *ctx = ctx_from(ptr);
    if (ctx == nullptr) return -100;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.single_segment = false;
    params.suppress_blank = true;

    std::string lang = to_std_string(env, language);
    params.language = lang.empty() ? "auto" : lang.c_str();

    std::string prompt = to_std_string(env, initial_prompt);
    if (!prompt.empty()) params.initial_prompt = prompt.c_str();

    ProgressCtx pctx{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        pctx.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        params.progress_callback = progress_cb;
        params.progress_callback_user_data = &pctx;
    }
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = nullptr;

    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    int ret = whisper_full(ctx, params, data, n);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    if (g_abort.load()) return -200;
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_airecord_app_stt_WhisperLib_segmentCount(JNIEnv *, jobject, jlong ptr) {
    return whisper_full_n_segments(ctx_from(ptr));
}

// Returned as raw UTF-8 bytes: NewStringUTF expects *modified* UTF-8 and can
// crash on 4-byte sequences, so decoding happens on the Kotlin side.
JNIEXPORT jbyteArray JNICALL
Java_com_airecord_app_stt_WhisperLib_segmentText(JNIEnv *env, jobject, jlong ptr, jint i) {
    const char *text = whisper_full_get_segment_text(ctx_from(ptr), i);
    jsize len = text == nullptr ? 0 : static_cast<jsize>(strlen(text));
    jbyteArray out = env->NewByteArray(len);
    if (len > 0) env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(text));
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_airecord_app_stt_WhisperLib_segmentT0(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t0(ctx_from(ptr), i);
}

JNIEXPORT jlong JNICALL
Java_com_airecord_app_stt_WhisperLib_segmentT1(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t1(ctx_from(ptr), i);
}

JNIEXPORT jstring JNICALL
Java_com_airecord_app_stt_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    std::string info = whisper_print_system_info();
#ifdef AIRECORD_VULKAN
    info += " | VULKAN = 1";
#endif
    return env->NewStringUTF(info.c_str());
}

}  // extern "C"
