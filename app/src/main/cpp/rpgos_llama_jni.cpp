#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char * TAG = "RPGOS-LLAMA";

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    float temperature = 0.1f;
    int top_k = 40;
    float top_p = 0.95f;
    float repeat_penalty = 1.1f;
    int batch = 64;
    std::mutex mutex;

    ~LlamaHandle() {
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
    }
};

std::once_flag backend_once;
std::mutex cancellation_mutex;
std::unordered_map<std::string, std::shared_ptr<std::atomic_bool>> cancellations;

void android_log(ggml_log_level level, const char * text, void *) {
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
        : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
        : level == GGML_LOG_LEVEL_DEBUG ? ANDROID_LOG_DEBUG
        : ANDROID_LOG_INFO;
    __android_log_write(priority, TAG, text);
}

void throw_runtime(JNIEnv * env, const std::string & reason) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, reason.c_str());
}

std::string utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

ggml_type kv_type(const std::string & name) {
    if (name == "F32") return GGML_TYPE_F32;
    if (name == "Q8_0") return GGML_TYPE_Q8_0;
    if (name == "Q4_0") return GGML_TYPE_Q4_0;
    return GGML_TYPE_F16;
}

std::string apply_chat_template(llama_model * model, const std::string & payload) {
    const std::string system =
        "You are the AI provider inside RPG OS. Follow the supplied contract exactly. "
        "Return only the requested JSON object, without markdown or commentary. "
        "You propose; RPG OS Core validates and commits all canonical state.";
    llama_chat_message messages[2] = {
        {"system", system.c_str()},
        {"user", payload.c_str()},
    };
    const char * model_template = llama_model_chat_template(model, nullptr);
    int32_t required = llama_chat_apply_template(model_template, messages, 2, true, nullptr, 0);
    if (required <= 0) {
        return system + "\n\n" + payload + "\n\nAssistant JSON:\n";
    }
    std::vector<char> buffer(static_cast<size_t>(required) + 1);
    int32_t written = llama_chat_apply_template(model_template, messages, 2, true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (written <= 0) return system + "\n\n" + payload + "\n\nAssistant JSON:\n";
    return std::string(buffer.data(), static_cast<size_t>(written));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int32_t count = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    if (count == 0) return {};
    if (count > 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(-count));
    count = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (count < 0) return {};
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    return count > 0 ? std::string(buffer.data(), static_cast<size_t>(count)) : std::string();
}

std::shared_ptr<std::atomic_bool> begin_request(const std::string & uid) {
    auto flag = std::make_shared<std::atomic_bool>(false);
    std::lock_guard<std::mutex> lock(cancellation_mutex);
    if (cancellations.count(uid) != 0) return nullptr;
    cancellations.emplace(uid, flag);
    return flag;
}

void end_request(const std::string & uid) {
    std::lock_guard<std::mutex> lock(cancellation_mutex);
    cancellations.erase(uid);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rpgos_app_NativeLocalInferenceBridge_open(
    JNIEnv * env, jobject, jstring artifact_path, jstring, jint context_units, jlong,
    jstring backend, jint threads, jint prefill_batch, jint micro_batch, jint gpu_layers,
    jstring kv_key_type, jstring kv_value_type, jfloat temperature, jint top_k,
    jfloat top_p, jfloat repeat_penalty, jboolean flash_attention, jboolean memory_map) {
    std::call_once(backend_once, [] {
        // Xclipse 940 was validated with cooperative FP4 disabled. Do not
        // overwrite an explicit developer/device setting.
        setenv("GGML_VK_DISABLE_OCP_FP4", "1", 0);
        llama_log_set(android_log, nullptr);
        llama_backend_init();
        ggml_backend_load_all();
    });

    const std::string path = utf8(env, artifact_path);
    const std::string backend_name = utf8(env, backend);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = backend_name == "CPU"
        ? 0
        : (gpu_layers < 0 ? std::numeric_limits<int32_t>::max() : gpu_layers);
    model_params.load_mode = memory_map ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        throw_runtime(env, "LLAMA_MODEL_LOAD_FAILED");
        return 0;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_units);
    context_params.n_batch = static_cast<uint32_t>(prefill_batch > 0 ? prefill_batch : 64);
    context_params.n_ubatch = static_cast<uint32_t>(micro_batch > 0 ? micro_batch : context_params.n_batch);
    context_params.n_threads = threads > 0 ? threads : 4;
    context_params.n_threads_batch = context_params.n_threads;
    context_params.type_k = kv_type(utf8(env, kv_key_type));
    context_params.type_v = kv_type(utf8(env, kv_value_type));
    context_params.flash_attn_type = flash_attention ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    context_params.no_perf = false;
    llama_context * context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        llama_model_free(model);
        throw_runtime(env, "LLAMA_CONTEXT_CREATE_FAILED");
        return 0;
    }

    auto handle = std::make_unique<LlamaHandle>();
    handle->model = model;
    handle->context = context;
    handle->temperature = temperature;
    handle->top_k = top_k;
    handle->top_p = top_p;
    handle->repeat_penalty = repeat_penalty;
    handle->batch = static_cast<int>(context_params.n_batch);
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rpgos_app_NativeLocalInferenceBridge_generate(
    JNIEnv * env, jobject, jlong raw_handle, jstring request_uid, jstring prompt, jint maximum_output_units) {
    auto * handle = reinterpret_cast<LlamaHandle *>(raw_handle);
    if (handle == nullptr) {
        throw_runtime(env, "LLAMA_INVALID_HANDLE");
        return nullptr;
    }
    const std::string uid = utf8(env, request_uid);
    auto cancelled = begin_request(uid);
    if (cancelled == nullptr) {
        throw_runtime(env, "LLAMA_DUPLICATE_REQUEST");
        return nullptr;
    }
    struct RequestGuard { std::string uid; ~RequestGuard() { end_request(uid); } } guard{uid};
    std::lock_guard<std::mutex> lock(handle->mutex);

    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    const std::string formatted = apply_chat_template(handle->model, utf8(env, prompt));
    std::vector<llama_token> tokens = tokenize(vocab, formatted);
    if (tokens.empty()) {
        throw_runtime(env, "LLAMA_TOKENIZATION_FAILED");
        return nullptr;
    }
    const uint32_t context_size = llama_n_ctx(handle->context);
    if (tokens.size() + static_cast<size_t>(maximum_output_units) > context_size) {
        throw_runtime(env, "LLAMA_CONTEXT_OVERFLOW");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(handle->context), false);
    size_t offset = 0;
    while (offset < tokens.size()) {
        if (cancelled->load()) {
            throw_runtime(env, "LOCAL_CANCELLED");
            return nullptr;
        }
        const int32_t count = static_cast<int32_t>(std::min(tokens.size() - offset, static_cast<size_t>(handle->batch)));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(handle->context, batch) != 0) {
            throw_runtime(env, "LLAMA_PROMPT_DECODE_FAILED");
            return nullptr;
        }
        offset += static_cast<size_t>(count);
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(handle->top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(handle->top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, handle->repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(handle->temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    for (int generated = 0; generated < maximum_output_units; ++generated) {
        if (cancelled->load()) {
            llama_sampler_free(sampler);
            throw_runtime(env, "LOCAL_CANCELLED");
            return nullptr;
        }
        llama_token token = llama_sampler_sample(sampler, handle->context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        output += token_piece(vocab, token);
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(handle->context, batch) != 0) {
            llama_sampler_free(sampler);
            throw_runtime(env, "LLAMA_GENERATION_DECODE_FAILED");
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rpgos_app_NativeLocalInferenceBridge_cancel(JNIEnv * env, jobject, jstring request_uid) {
    const std::string uid = utf8(env, request_uid);
    std::lock_guard<std::mutex> lock(cancellation_mutex);
    auto found = cancellations.find(uid);
    if (found != cancellations.end()) found->second->store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rpgos_app_NativeLocalInferenceBridge_close(JNIEnv *, jobject, jlong raw_handle) {
    delete reinterpret_cast<LlamaHandle *>(raw_handle);
}
