#include <jni.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct LlamaContext {
    llama_model *model = nullptr;
    llama_context *context = nullptr;

    ~LlamaContext() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

std::string readBytes(JNIEnv *env, jbyteArray bytes) {
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}

void fail(JNIEnv *env, const char *message, const char *type = "java/lang/IllegalStateException") {
    env->ThrowNew(env->FindClass(type), message);
}

// A token can end in the middle of a UTF-8 character. Keep that suffix for the next token.
size_t completeUtf8Prefix(const std::string &bytes) {
    if (bytes.empty()) return 0;
    size_t start = bytes.size() - 1;
    while (start > 0 && (static_cast<unsigned char>(bytes[start]) & 0xc0) == 0x80) --start;
    const auto lead = static_cast<unsigned char>(bytes[start]);
    size_t length = 1;
    if (lead >= 0xc2 && lead <= 0xdf) length = 2;
    if (lead >= 0xe0 && lead <= 0xef) length = 3;
    if (lead >= 0xf0 && lead <= 0xf4) length = 4;
    return bytes.size() - start < length ? start : bytes.size();
}

bool emit(JNIEnv *env, jobject output, jmethodID onBytes, const std::string &bytes, size_t size) {
    if (size == 0) return true;
    auto chunk = env->NewByteArray(size);
    if (!chunk) return false;
    env->SetByteArrayRegion(chunk, 0, size, reinterpret_cast<const jbyte *>(bytes.data()));
    const bool keepGoing = !env->ExceptionCheck() && env->CallBooleanMethod(output, onBytes, chunk);
    env->DeleteLocalRef(chunk);
    return keepGoing && !env->ExceptionCheck();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_openslmserver_LlamaNative_initContext(
        JNIEnv *env, jobject, jbyteArray modelPath, jint contextTokens) {
    static std::once_flag backend;
    std::call_once(backend, llama_backend_init);

    auto holder = std::make_unique<LlamaContext>();
    auto modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.use_mmap = true;
    const auto path = readBytes(env, modelPath);
    holder->model = llama_model_load_from_file(path.c_str(), modelParams);
    if (!holder->model) return 0;

    auto contextParams = llama_context_default_params();
    contextParams.n_ctx = contextTokens;
    contextParams.n_batch = 256;
    contextParams.n_threads = 4;
    contextParams.n_threads_batch = 4;
    holder->context = llama_init_from_model(holder->model, contextParams);
    if (!holder->context) return 0;
    return reinterpret_cast<jlong>(holder.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_openslmserver_LlamaNative_streamCompletion(
        JNIEnv *env, jobject, jlong contextPtr, jobjectArray roles, jobjectArray messages,
        jint maxTokens, jobject output) {
    const auto *holder = reinterpret_cast<LlamaContext *>(contextPtr);
    if (!holder) return fail(env, "Model is not initialized");
    auto *context = holder->context;
    const auto *vocab = llama_model_get_vocab(holder->model);
    const auto count = env->GetArrayLength(messages);
    std::vector<std::string> roleText(count), messageText(count);
    std::vector<llama_chat_message> chat;
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto message = static_cast<jbyteArray>(env->GetObjectArrayElement(messages, i));
        const char *roleChars = env->GetStringUTFChars(role, nullptr);
        if (!roleChars) return;
        roleText[i] = roleChars;
        env->ReleaseStringUTFChars(role, roleChars);
        messageText[i] = readBytes(env, message);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(message);
        chat.push_back({roleText[i].c_str(), messageText[i].c_str()});
    }

    const char *chatTemplate = llama_model_chat_template(holder->model, nullptr);
    if (!chatTemplate && count != 1) return fail(env, "GGUF has no chat template");
    std::string prompt = messageText.back();
    if (chatTemplate) {
        const auto size = llama_chat_apply_template(chatTemplate, chat.data(), chat.size(), true, nullptr, 0);
        if (size < 0) return fail(env, "GGUF chat template is unsupported by this llama.cpp build");
        std::vector<char> formatted(size + 1);
        if (llama_chat_apply_template(chatTemplate, chat.data(), chat.size(), true, formatted.data(), formatted.size()) != size) {
            return fail(env, "Could not apply GGUF chat template");
        }
        prompt.assign(formatted.data(), size);
    }

    const int needed = llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, chatTemplate != nullptr);
    const int inputTokens = -needed;
    if (inputTokens <= 0 || inputTokens >= static_cast<int>(llama_n_ctx(context))) {
        return fail(env, "Prompt does not fit in the context window", "java/lang/IllegalArgumentException");
    }
    std::vector<llama_token> tokens(inputTokens);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, chatTemplate != nullptr) != inputTokens) {
        return fail(env, "Could not tokenize prompt");
    }

    llama_memory_clear(llama_get_memory(context), false);
    const int batchSize = llama_n_batch(context);
    for (int offset = 0; offset < inputTokens; offset += batchSize) {
        auto batch = llama_batch_get_one(tokens.data() + offset, std::min(batchSize, inputTokens - offset));
        if (llama_decode(context, batch) != 0) return fail(env, "Could not evaluate prompt");
    }

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>
            sampler(llama_sampler_init_greedy(), llama_sampler_free);
    if (!sampler) return fail(env, "Could not create sampler");

    const auto onBytes = env->GetMethodID(env->GetObjectClass(output), "onBytes", "([B)Z");
    if (!onBytes) return;
    std::string pending;
    std::vector<char> piece(256);
    const int limit = std::min(maxTokens, static_cast<int>(llama_n_ctx(context)) - inputTokens);
    for (int i = 0; i < limit; ++i) {
        auto token = llama_sampler_sample(sampler.get(), context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (size < 0) {
            piece.resize(-size);
            size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        }
        if (size < 0) return fail(env, "Could not decode generated token");
        pending.append(piece.data(), size);
        const size_t complete = completeUtf8Prefix(pending);
        if (!emit(env, output, onBytes, pending, complete)) return;
        pending.erase(0, complete);
        if (i + 1 < limit && llama_decode(context, llama_batch_get_one(&token, 1)) != 0) {
            return fail(env, "Could not evaluate generated token");
        }
    }
    emit(env, output, onBytes, pending, pending.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_openslmserver_LlamaNative_releaseContext(JNIEnv *, jobject, jlong contextPtr) {
    delete reinterpret_cast<LlamaContext *>(contextPtr);
}
