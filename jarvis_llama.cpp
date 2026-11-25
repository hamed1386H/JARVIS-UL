#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "JARVIS-LLM", __VA_ARGS__)

struct JarvisModel {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    std::mutex mutex;
};

static std::once_flag backend_once;
static std::string jstringToString(JNIEnv * env, jstring value) {
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_chtai_assistant_LocalAiEngine_nativeLoad(JNIEnv * env, jobject,
                                                   jstring jpath, jint threads, jint contextSize) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    const std::string path = jstringToString(env, jpath);
    auto * state = new JarvisModel();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0; // portable CPU path; avoids unsupported GPU backends
    state->model = llama_model_load_from_file(path.c_str(), modelParams);
    if (!state->model) {
        LOGE("Could not load GGUF: %s", path.c_str());
        delete state;
        return 0;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(contextSize);
    contextParams.n_batch = 512;
    contextParams.n_ubatch = 128;
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;
    state->context = llama_init_from_model(state->model, contextParams);
    if (!state->context) {
        llama_model_free(state->model);
        delete state;
        return 0;
    }
    state->vocab = llama_model_get_vocab(state->model);
    auto samplerParams = llama_sampler_chain_default_params();
    state->sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(state->sampler, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(state->sampler, llama_sampler_init_top_p(0.80f, 1));
    llama_sampler_chain_add(state->sampler, llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(state->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_chtai_assistant_LocalAiEngine_nativeGenerate(JNIEnv * env, jobject,
                                                       jlong handle, jstring jprompt, jint maxTokens) {
    auto * state = reinterpret_cast<JarvisModel *>(handle);
    if (!state || !state->context || !state->vocab) return env->NewStringUTF("موتور هوش مصنوعی آماده نیست.");
    std::lock_guard<std::mutex> lock(state->mutex);
    const std::string prompt = jstringToString(env, jprompt);
    llama_memory_clear(llama_get_memory(state->context), true);
    llama_sampler_reset(state->sampler);

    std::vector<llama_token> tokens(std::max<size_t>(prompt.size() + 16, 256));
    int32_t tokenCount = llama_tokenize(state->vocab, prompt.c_str(),
        static_cast<int32_t>(prompt.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (tokenCount < 0) {
        tokens.resize(static_cast<size_t>(-tokenCount) + 16);
        tokenCount = llama_tokenize(state->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (tokenCount <= 0) return env->NewStringUTF("متن ورودی قابل پردازش نیست.");
    tokens.resize(static_cast<size_t>(tokenCount));
    // Reserve room for generated tokens. Keep the beginning (system instructions)
    // and the most recent context if the conversation exceeds the context window.
    const size_t inputLimit = llama_n_ctx(state->context) > static_cast<uint32_t>(maxTokens + 16)
        ? static_cast<size_t>(llama_n_ctx(state->context) - maxTokens - 16) : 512;
    if (tokens.size() > inputLimit) {
        const size_t head = std::min<size_t>(192, inputLimit / 4);
        const size_t tail = inputLimit - head;
        std::vector<llama_token> clipped;
        clipped.reserve(inputLimit);
        clipped.insert(clipped.end(), tokens.begin(), tokens.begin() + head);
        clipped.insert(clipped.end(), tokens.end() - tail, tokens.end());
        tokens.swap(clipped);
    }

    const uint32_t contextLimit = llama_n_ctx(state->context);
    if (tokens.size() + static_cast<size_t>(maxTokens) >= contextLimit) {
        return env->NewStringUTF("گفتگو برای ظرفیت فعلی مدل طولانی است؛ تاریخچه گفتگو را کوتاه یا پاک کن.");
    }
    llama_batch promptBatch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        promptBatch.token[i] = tokens[i];
        promptBatch.pos[i] = static_cast<llama_pos>(i);
        promptBatch.n_seq_id[i] = 1;
        promptBatch.seq_id[i][0] = 0;
        promptBatch.logits[i] = (i + 1 == tokens.size());
    }
    const int decodeResult = llama_decode(state->context, promptBatch);
    llama_batch_free(promptBatch);
    if (decodeResult != 0) return env->NewStringUTF("پردازش ورودی مدل با خطا مواجه شد.");

    std::string output;
    output.reserve(2048);
    for (int i = 0; i < maxTokens; ++i) {
        const llama_token token = llama_sampler_sample(state->sampler, state->context, -1);
        if (llama_vocab_is_eog(state->vocab, token)) break;
        char piece[512];
        const int32_t n = llama_token_to_piece(state->vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, static_cast<size_t>(n));
        else if (n < 0) {
            std::vector<char> large(static_cast<size_t>(-n) + 8);
            const int32_t m = llama_token_to_piece(state->vocab, token, large.data(), static_cast<int32_t>(large.size()), 0, true);
            if (m > 0) output.append(large.data(), static_cast<size_t>(m));
        }
        if (output.find("<|im_end|>") != std::string::npos || output.find("<|fim_suffix|>") != std::string::npos) break;
        llama_token next = token;
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(state->context, batch) != 0) break;
    }
    if (output.empty()) output = "مدل پاسخی تولید نکرد. این نسخه Qwen از نوع Base است و برای مکالمه ممکن است کیفیت محدودی داشته باشد.";
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_chtai_assistant_LocalAiEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * state = reinterpret_cast<JarvisModel *>(handle);
    if (!state) return;
    if (state->sampler) llama_sampler_free(state->sampler);
    if (state->context) llama_free(state->context);
    if (state->model) llama_model_free(state->model);
    delete state;
}
