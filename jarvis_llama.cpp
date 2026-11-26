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
    if (!value) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            } else cp = 0xFFFD;
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) cp = 0xFFFD;
        if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
        else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

static jstring stringToJString(JNIEnv * env, const std::string & value) {
    std::vector<jchar> out;
    out.reserve(value.size());
    const auto * bytes = reinterpret_cast<const unsigned char *>(value.data());
    size_t i = 0;
    while (i < value.size()) {
        uint32_t cp = 0xFFFD;
        const unsigned char c = bytes[i++];
        if (c < 0x80) cp = c;
        else if ((c & 0xE0) == 0xC0 && i < value.size()) {
            const unsigned char c1 = bytes[i];
            if ((c1 & 0xC0) == 0x80) { cp = ((c & 0x1F) << 6) | (c1 & 0x3F); ++i; }
        } else if ((c & 0xF0) == 0xE0 && i + 1 < value.size()) {
            const unsigned char c1 = bytes[i], c2 = bytes[i + 1];
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80) {
                cp = ((c & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F); i += 2;
            }
        } else if ((c & 0xF8) == 0xF0 && i + 2 < value.size()) {
            const unsigned char c1 = bytes[i], c2 = bytes[i + 1], c3 = bytes[i + 2];
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80) {
                cp = ((c & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F); i += 3;
            }
        }
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
        if (cp <= 0xFFFF) out.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
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
    contextParams.n_batch = 8;
    contextParams.n_ubatch = 8;
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
    if (!state || !state->context || !state->vocab) return stringToJString(env, "موتور هوش مصنوعی آماده نیست.");
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
    if (tokenCount <= 0) return stringToJString(env, "متن ورودی قابل پردازش نیست.");
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
        return stringToJString(env, "گفتگو برای ظرفیت فعلی مدل طولانی است؛ تاریخچه گفتگو را کوتاه یا پاک کن.");
    }
    // llama_decode cannot accept more tokens than contextParams.n_batch (128).
    // Split long Persian prompts/history into bounded batches; request logits only
    // for the final prompt token so generation starts from the correct position.
    constexpr int32_t batchCapacity = 8;
    size_t offset = 0;
    while (offset < tokens.size()) {
        const size_t count = std::min<size_t>(batchCapacity, tokens.size() - offset);
        llama_batch promptBatch = llama_batch_init(static_cast<int32_t>(count), 0, 1);
        for (size_t i = 0; i < count; ++i) {
            const size_t tokenIndex = offset + i;
            promptBatch.token[i] = tokens[tokenIndex];
            promptBatch.pos[i] = static_cast<llama_pos>(tokenIndex);
            promptBatch.n_seq_id[i] = 1;
            promptBatch.seq_id[i][0] = 0;
            promptBatch.logits[i] = (tokenIndex + 1 == tokens.size());
        }
        const int decodeResult = llama_decode(state->context, promptBatch);
        llama_batch_free(promptBatch);
        if (decodeResult != 0) {
            LOGE("Prompt decode failed at token %zu/%zu (batch size %zu)",
                 offset, tokens.size(), count);
            LOGE("Prompt decode failed with code %d at token %zu/%zu (batch size %zu)", decodeResult, offset, tokens.size(), count);
            return stringToJString(env, "مدل نتوانست متن را پردازش کند؛ حافظه آزاد گوشی یا سازگاری فایل مدل را بررسی کن.");
        }
        offset += count;
    }

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
    if (output.empty()) output = "مدل پاسخی تولید نکرد؛ پیام را کوتاه‌تر بفرست یا مدل را دوباره بارگذاری کن.";
    return stringToJString(env, output);
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
