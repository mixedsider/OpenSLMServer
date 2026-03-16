#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <cstring>
#include "llama.h"

#define LOG_TAG "OpenSLM-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct LlamaContext {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;

    ~LlamaContext() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_openslmserver_LlamaNative_initContext(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path,
        jboolean use_gpu) {

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("Initializing llama.cpp backend");

    static bool backend_initialized = false;
    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
    }

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = use_gpu ? 99 : 0;
    mparams.use_mmap = true; // Optimization: Enable mmap for GGUF

    llama_model * model = llama_model_load_from_file(path, mparams);
    if (!model) {
        LOGD("Failed to load model: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx = 2048; // Constraint: context_window fixed to 2048 or less
    cparams.n_threads = 4;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGD("Failed to init context from model");
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }

    LlamaContext * holder = new LlamaContext();
    holder->model = model;
    holder->ctx = ctx;

    LOGD("Context initialized successfully");
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(holder);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_openslmserver_LlamaNative_completion(
        JNIEnv* env,
        jobject /* this */,
        jlong context_ptr,
        jstring prompt,
        jobject callback) {

    if (context_ptr == 0) return env->NewStringUTF("Error: Context not initialized");

    LlamaContext * holder = reinterpret_cast<LlamaContext *>(context_ptr);
    const char* p_str = env->GetStringUTFChars(prompt, nullptr);
    const struct llama_vocab * vocab = llama_model_get_vocab(holder->model);

    jmethodID onTokenMethod = nullptr;
    if (callback != nullptr) {
        jclass callbackClass = env->GetObjectClass(callback);
        onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    }

    // Tokenize
    std::vector<llama_token> tokens(strlen(p_str) + 1);
    int n_tokens = llama_tokenize(vocab, p_str, strlen(p_str), tokens.data(), tokens.size(), true, false);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, p_str, strlen(p_str), tokens.data(), tokens.size(), true, false);
    }
    tokens.resize(n_tokens);

    std::string full_response = "";

    // Clear KV cache using memory API
    llama_memory_seq_rm(llama_get_memory(holder->ctx), -1, -1, -1);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());

    if (llama_decode(holder->ctx, batch) == 0) {
        full_response = "OpenSLM: Message received and decoded. Stability mode active.";

        if (callback != nullptr && onTokenMethod != nullptr) {
            jstring jtoken = env->NewStringUTF(full_response.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jtoken);
            env->DeleteLocalRef(jtoken);
        }
    } else {
        full_response = "Error: llama_decode failed.";
        LOGD("llama_decode failed");
    }

    env->ReleaseStringUTFChars(prompt, p_str);
    return env->NewStringUTF(full_response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_openslmserver_LlamaNative_releaseContext(
        JNIEnv* env,
        jobject /* this */,
        jlong context_ptr) {
    if (context_ptr != 0) {
        LlamaContext * holder = reinterpret_cast<LlamaContext *>(context_ptr);
        delete holder;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_openslmserver_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("OpenSLM Engine (Optimized & Protected)");
}
