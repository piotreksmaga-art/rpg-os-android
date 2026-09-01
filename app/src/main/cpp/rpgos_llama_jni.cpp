#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml.h>
#include <ggml-backend.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

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
    bool embedding = false;
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

bool restrict_to_cpu(JNIEnv * env, const std::string & backend_name,
                     llama_model_params & params, ggml_backend_dev_t (&devices)[2]) {
    if (backend_name != "CPU") return true;
    devices[0] = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    devices[1] = nullptr;
    if (devices[0] == nullptr) {
        throw_runtime(env, "LLAMA_CPU_BACKEND_UNAVAILABLE");
        return false;
    }
    // A null device list means "all available devices" in llama.cpp. n_gpu_layers=0
    // only keeps weights on the CPU; it does not prevent the context scheduler from
    // selecting Vulkan for supported operations. An explicit CPU-only list is required.
    params.devices = devices;
    return true;
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

struct ChatSections {
    std::string system;
    std::string user;
    std::string assistant_prefix;
};

ChatSections chat_sections(const std::string & payload) {
    const std::string system_open = "<|im_start|>system\n";
    const std::string user_open = "<|im_start|>user\n";
    const std::string assistant_open = "<|im_start|>assistant\n";
    const std::string end = "<|im_end|>";
    if (payload.rfind(system_open, 0) == 0) {
        const size_t system_end = payload.find(end, system_open.size());
        const size_t user_start = system_end == std::string::npos
            ? std::string::npos : payload.find(user_open, system_end + end.size());
        const size_t user_end = user_start == std::string::npos
            ? std::string::npos : payload.find(end, user_start + user_open.size());
        const size_t assistant_start = user_end == std::string::npos
            ? std::string::npos : payload.find(assistant_open, user_end + end.size());
        if (system_end != std::string::npos && user_start != std::string::npos &&
            user_end != std::string::npos && assistant_start != std::string::npos) {
            return {
                payload.substr(system_open.size(), system_end - system_open.size()),
                payload.substr(user_start + user_open.size(), user_end - user_start - user_open.size()),
                payload.substr(assistant_start + assistant_open.size())
            };
        }
    }
    return {
        "You are the AI provider inside RPG OS. Follow the supplied contract exactly. "
        "Return only the requested JSON object, without markdown or commentary. "
        "You propose; RPG OS Core validates and commits all canonical state.",
        payload,
        ""
    };
}

std::string apply_chat_template(llama_model * model, const std::string & payload) {
    const ChatSections sections = chat_sections(payload);
    llama_chat_message messages[2] = {
        {"system", sections.system.c_str()},
        {"user", sections.user.c_str()},
    };
    const char * model_template = llama_model_chat_template(model, nullptr);
    int32_t required = llama_chat_apply_template(model_template, messages, 2, true, nullptr, 0);
    if (required <= 0) {
        return sections.system + "\n\n" + sections.user + "\n\nAssistant:\n" + sections.assistant_prefix;
    }
    std::vector<char> buffer(static_cast<size_t>(required) + 1);
    int32_t written = llama_chat_apply_template(model_template, messages, 2, true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (written <= 0) return sections.system + "\n\n" + sections.user + "\n\nAssistant:\n" + sections.assistant_prefix;
    return std::string(buffer.data(), static_cast<size_t>(written)) + sections.assistant_prefix;
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
Java_com_rpgos_app_NativeLocalInferenceBridge_openEmbedding(
    JNIEnv * env, jobject, jstring artifact_path, jint context_units, jstring backend,
    jint threads, jint batch, jint gpu_layers, jboolean memory_map) {
    std::call_once(backend_once, [] {
        setenv("GGML_VK_DISABLE_OCP_FP4", "1", 0);
        llama_log_set(android_log, nullptr);
        llama_backend_init();
        ggml_backend_load_all();
    });
    if (context_units <= 0 || context_units > 8192) {
        throw_runtime(env, "LLAMA_EMBEDDING_CONTEXT_INVALID");
        return 0;
    }
    const std::string backend_name = utf8(env, backend);
    llama_model_params model_params = llama_model_default_params();
    ggml_backend_dev_t model_devices[2] = {nullptr, nullptr};
    if (!restrict_to_cpu(env, backend_name, model_params, model_devices)) return 0;
    model_params.n_gpu_layers = backend_name == "CPU"
        ? 0
        : (gpu_layers < 0 ? std::numeric_limits<int32_t>::max() : gpu_layers);
    model_params.load_mode = memory_map ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    llama_model * model = llama_model_load_from_file(utf8(env, artifact_path).c_str(), model_params);
    if (model == nullptr) {
        throw_runtime(env, "LLAMA_EMBEDDING_MODEL_LOAD_FAILED");
        return 0;
    }
    // llama.cpp represents BERT-family embedding architectures (including ModernBERT)
    // on the decode graph even though they are conceptually encoders. Reject only a true
    // encoder-decoder model, exactly like the upstream embedding example does.
    if (llama_model_has_encoder(model) && llama_model_has_decoder(model)) {
        llama_model_free(model);
        throw_runtime(env, "LLAMA_EMBEDDING_ENCODER_DECODER_UNSUPPORTED");
        return 0;
    }
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(context_units);
    // Non-causal encoders require batch == ubatch. The API accepts smaller logical
    // batches of documents, but one individual document may use the full context.
    params.n_batch = static_cast<uint32_t>(context_units);
    params.n_ubatch = params.n_batch;
    params.n_seq_max = 1;
    params.n_threads = threads > 0 ? threads : 4;
    params.n_threads_batch = params.n_threads;
    params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    params.attention_type = LLAMA_ATTENTION_TYPE_NON_CAUSAL;
    params.embeddings = true;
    params.no_perf = false;
    llama_context * context = llama_init_from_model(model, params);
    if (context == nullptr) {
        llama_model_free(model);
        throw_runtime(env, "LLAMA_EMBEDDING_CONTEXT_CREATE_FAILED");
        return 0;
    }
    auto handle = std::make_unique<LlamaHandle>();
    handle->model = model;
    handle->context = context;
    handle->batch = batch > 0 ? batch : 32;
    handle->embedding = true;
    return reinterpret_cast<jlong>(handle.release());
}

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
    ggml_backend_dev_t model_devices[2] = {nullptr, nullptr};
    if (!restrict_to_cpu(env, backend_name, model_params, model_devices)) return 0;
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
    if (handle->embedding) {
        throw_runtime(env, "LLAMA_GENERATION_HANDLE_REQUIRED");
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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rpgos_app_NativeLocalInferenceBridge_embed(
    JNIEnv * env, jobject, jlong raw_handle, jstring request_uid, jobjectArray texts,
    jint maximum_input_units) {
    auto * handle = reinterpret_cast<LlamaHandle *>(raw_handle);
    if (handle == nullptr || !handle->embedding) {
        throw_runtime(env, "LLAMA_EMBEDDING_HANDLE_REQUIRED");
        return nullptr;
    }
    const jsize count = texts == nullptr ? 0 : env->GetArrayLength(texts);
    if (count <= 0 || count > 64) {
        throw_runtime(env, "LLAMA_EMBEDDING_BATCH_INVALID");
        return nullptr;
    }
    if (maximum_input_units <= 0 || maximum_input_units > llama_n_ctx(handle->context)) {
        throw_runtime(env, "LLAMA_EMBEDDING_INPUT_LIMIT_INVALID");
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
    const int dimensions = llama_model_n_embd_out(handle->model);
    if (dimensions <= 0) {
        throw_runtime(env, "LLAMA_EMBEDDING_DIMENSIONS_INVALID");
        return nullptr;
    }
    std::vector<float> output(static_cast<size_t>(count) * static_cast<size_t>(dimensions));
    for (jsize index = 0; index < count; ++index) {
        if (cancelled->load()) {
            throw_runtime(env, "LOCAL_CANCELLED");
            return nullptr;
        }
        auto value = static_cast<jstring>(env->GetObjectArrayElement(texts, index));
        const std::string text = utf8(env, value);
        env->DeleteLocalRef(value);
        if (text.empty()) {
            throw_runtime(env, "LLAMA_EMBEDDING_TEXT_REQUIRED");
            return nullptr;
        }
        std::vector<llama_token> tokens = tokenize(vocab, text);
        if (tokens.empty()) {
            throw_runtime(env, "LLAMA_EMBEDDING_TOKENIZATION_FAILED");
            return nullptr;
        }
        if (tokens.size() > static_cast<size_t>(maximum_input_units)) {
            throw_runtime(env, "LLAMA_EMBEDDING_CONTEXT_OVERFLOW");
            return nullptr;
        }
        llama_memory_clear(llama_get_memory(handle->context), true);
        llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
        batch.n_tokens = static_cast<int32_t>(tokens.size());
        for (int32_t token_index = 0; token_index < batch.n_tokens; ++token_index) {
            batch.token[token_index] = tokens[static_cast<size_t>(token_index)];
            batch.pos[token_index] = token_index;
            batch.n_seq_id[token_index] = 1;
            batch.seq_id[token_index][0] = 0;
            batch.logits[token_index] = 1;
        }
        llama_set_abort_callback(handle->context, [](void * data) {
            return static_cast<std::atomic_bool *>(data)->load();
        }, cancelled.get());
        const int encoded = llama_decode(handle->context, batch);
        llama_set_abort_callback(handle->context, nullptr, nullptr);
        llama_batch_free(batch);
        if (cancelled->load()) {
            throw_runtime(env, "LOCAL_CANCELLED");
            return nullptr;
        }
        if (encoded < 0) {
            throw_runtime(env, "LLAMA_EMBEDDING_ENCODE_FAILED");
            return nullptr;
        }
        const float * embedding = llama_get_embeddings_seq(handle->context, 0);
        if (embedding == nullptr) {
            throw_runtime(env, "LLAMA_EMBEDDING_POOLING_FAILED");
            return nullptr;
        }
        double squared = 0.0;
        for (int d = 0; d < dimensions; ++d) squared += static_cast<double>(embedding[d]) * embedding[d];
        const float norm = squared > 0.0 ? static_cast<float>(std::sqrt(squared)) : 0.0f;
        if (!(norm > 0.0f) || !std::isfinite(norm)) {
            throw_runtime(env, "LLAMA_EMBEDDING_NORMALIZATION_FAILED");
            return nullptr;
        }
        float * destination = output.data() + static_cast<size_t>(index) * static_cast<size_t>(dimensions);
        for (int d = 0; d < dimensions; ++d) destination[d] = embedding[d] / norm;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rpgos_app_NativeLocalInferenceBridge_scoreFp16(
    JNIEnv * env, jobject, jobject vectors, jlongArray offsets, jfloatArray query) {
    auto * bytes = static_cast<const uint8_t *>(env->GetDirectBufferAddress(vectors));
    const jlong capacity = env->GetDirectBufferCapacity(vectors);
    const jsize count = offsets == nullptr ? 0 : env->GetArrayLength(offsets);
    const jsize dimensions = query == nullptr ? 0 : env->GetArrayLength(query);
    if (bytes == nullptr || capacity <= 0 || count < 0 || dimensions <= 0) {
        throw_runtime(env, "LLAMA_FP16_SCAN_INPUT_INVALID");
        return nullptr;
    }
    jboolean query_copy = JNI_FALSE;
    jboolean offsets_copy = JNI_FALSE;
    const jfloat * query_values = env->GetFloatArrayElements(query, &query_copy);
    const jlong * offset_values = env->GetLongArrayElements(offsets, &offsets_copy);
    if (query_values == nullptr || offset_values == nullptr) {
        if (query_values != nullptr) env->ReleaseFloatArrayElements(query, const_cast<jfloat *>(query_values), JNI_ABORT);
        if (offset_values != nullptr) env->ReleaseLongArrayElements(offsets, const_cast<jlong *>(offset_values), JNI_ABORT);
        return nullptr;
    }
    std::vector<float> scores(static_cast<size_t>(count));
    for (jsize row = 0; row < count; ++row) {
        const jlong offset = offset_values[row];
        if (offset < 0 || offset + static_cast<jlong>(dimensions) * 2 > capacity) {
            env->ReleaseFloatArrayElements(query, const_cast<jfloat *>(query_values), JNI_ABORT);
            env->ReleaseLongArrayElements(offsets, const_cast<jlong *>(offset_values), JNI_ABORT);
            throw_runtime(env, "LLAMA_FP16_SCAN_OFFSET_INVALID");
            return nullptr;
        }
        const uint16_t * half_values = reinterpret_cast<const uint16_t *>(bytes + offset);
        float score = 0.0f;
#if defined(__aarch64__)
        jsize d = 0;
        float32x4_t total = vdupq_n_f32(0.0f);
        for (; d + 8 <= dimensions; d += 8) {
            const float16x8_t halfs = vreinterpretq_f16_u16(vld1q_u16(half_values + d));
            total = vfmaq_f32(total, vcvt_f32_f16(vget_low_f16(halfs)), vld1q_f32(query_values + d));
            total = vfmaq_f32(total, vcvt_f32_f16(vget_high_f16(halfs)), vld1q_f32(query_values + d + 4));
        }
        score = vaddvq_f32(total);
        for (; d < dimensions; ++d) {
            __fp16 value;
            const uint16_t raw = half_values[d];
            std::memcpy(&value, &raw, sizeof(raw));
            score += static_cast<float>(value) * query_values[d];
        }
#else
        for (jsize d = 0; d < dimensions; ++d) {
            const uint16_t raw = half_values[d];
            const uint32_t sign = static_cast<uint32_t>(raw & 0x8000u) << 16;
            uint32_t exponent = (raw >> 10) & 0x1fu;
            uint32_t mantissa = raw & 0x3ffu;
            uint32_t bits;
            if (exponent == 0) {
                if (mantissa == 0) bits = sign;
                else {
                    exponent = 1;
                    while ((mantissa & 0x400u) == 0) { mantissa <<= 1; --exponent; }
                    mantissa &= 0x3ffu;
                    bits = sign | ((exponent + 127 - 15) << 23) | (mantissa << 13);
                }
            } else if (exponent == 31) bits = sign | 0x7f800000u | (mantissa << 13);
            else bits = sign | ((exponent + 127 - 15) << 23) | (mantissa << 13);
            float value;
            std::memcpy(&value, &bits, sizeof(value));
            score += value * query_values[d];
        }
#endif
        scores[static_cast<size_t>(row)] = std::max(-1.0f, std::min(1.0f, score));
    }
    env->ReleaseFloatArrayElements(query, const_cast<jfloat *>(query_values), JNI_ABORT);
    env->ReleaseLongArrayElements(offsets, const_cast<jlong *>(offset_values), JNI_ABORT);
    jfloatArray result = env->NewFloatArray(count);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, count, scores.data());
    return result;
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
    auto * handle = reinterpret_cast<LlamaHandle *>(raw_handle);
    if (handle == nullptr) return;
    std::unique_lock<std::mutex> lock(handle->mutex);
    lock.unlock();
    delete handle;
}
