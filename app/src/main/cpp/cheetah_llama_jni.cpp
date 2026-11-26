#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <exception>
#include <mutex>
#include <new>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"

#define LOG_TAG "CheetahLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct EngineState {
    std::mutex mutex;
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int32_t context_len = 1024;
    int32_t threads = 4;
    bool backend_initialized = false;
    std::atomic<bool> cancel_requested{false};
    std::vector<llama_token> cached_tokens;
};

EngineState g_engine;

void ensure_backend_init() {
    if (!g_engine.backend_initialized) {
        llama_backend_init();
        g_engine.backend_initialized = true;
    }
}

void unload_locked() {
    g_engine.cached_tokens.clear();
    if (g_engine.ctx != nullptr) {
        llama_free(g_engine.ctx);
        g_engine.ctx = nullptr;
    }
    if (g_engine.model != nullptr) {
        llama_model_free(g_engine.model);
        g_engine.model = nullptr;
    }
}

size_t valid_utf8_prefix_len(const std::string & s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t char_len = 0;
        if (c <= 0x7F) {
            char_len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            char_len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            char_len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            char_len = 4;
        } else {
            i += 1;
            continue;
        }
        if (i + char_len > n) {
            break;
        }
        bool valid_cont = true;
        for (size_t k = 1; k < char_len; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) {
                valid_cont = false;
                break;
            }
        }
        if (!valid_cont) {
            i += 1;
            continue;
        }
        i += char_len;
    }
    return i;
}

bool batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n_tokens, llama_pos pos_0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n_tokens; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        if (idx < 0) return false;
        const llama_pos pos = pos_0 + i;
        if (!llama_batch_ext_set_pos(batch, idx, &pos)) return false;
    }
    if (n_tokens > 0) {
        llama_batch_ext_set_output_logits(batch, n_tokens - 1, true);
    }
    return true;
}

std::string build_prompt(llama_model * model, const char * system_cstr, const char * user_cstr) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    llama_chat_message msgs[2];
    msgs[0].role = "system";
    msgs[0].content = system_cstr;
    msgs[1].role = "user";
    msgs[1].content = user_cstr;

    const char * active_tmpl = (tmpl != nullptr && tmpl[0] != '\0') ? tmpl : "chatml";
    int32_t needed = llama_chat_apply_template(active_tmpl, msgs, 2, true, nullptr, 0);
    if (needed > 0 && needed < 131072) {
        std::vector<char> buf(static_cast<size_t>(needed) + 16);
        int32_t written = llama_chat_apply_template(active_tmpl, msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
        if (written > 0) {
            return std::string(buf.data(), static_cast<size_t>(written));
        }
    }
    return std::string("<|im_start|>system\n") + system_cstr +
           "<|im_end|>\n<|im_start|>user\n" + user_cstr +
           "<|im_end|>\n<|im_start|>assistant\n";
}

std::string run_generation_locked(
        JNIEnv * env,
        const std::string & formatted,
        int32_t max_tokens,
        float temperature,
        const std::string & gbnf_grammar,
        bool reuse_kv_cache,
        jobject tokenCallback) {
    if (g_engine.model == nullptr || g_engine.ctx == nullptr || formatted.empty()) {
        return "";
    }

    jmethodID on_token_method = nullptr;
    if (tokenCallback != nullptr) {
        jclass cb_cls = env->GetObjectClass(tokenCallback);
        if (cb_cls != nullptr) {
            on_token_method = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cb_cls);
        }
    }

    g_engine.cancel_requested.store(false);

    const llama_vocab * vocab = llama_model_get_vocab(g_engine.model);
    int32_t n_prompt = -llama_tokenize(
            vocab,
            formatted.c_str(),
            static_cast<int32_t>(formatted.size()),
            nullptr,
            0,
            true,
            true);
    if (n_prompt <= 0) {
        return "";
    }

    const int32_t max_prompt_budget = g_engine.context_len - 32;
    if (max_prompt_budget <= 16) {
        return "";
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(
            vocab,
            formatted.c_str(),
            static_cast<int32_t>(formatted.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true) < 0) {
        return "";
    }

    if (static_cast<int32_t>(prompt_tokens.size()) > max_prompt_budget) {
        // Preserve start (BOS/system) and tail (latest user turn) if prompt exceeds context budget
        const size_t head_keep = std::min<size_t>(64, static_cast<size_t>(max_prompt_budget) / 4);
        const size_t tail_keep = static_cast<size_t>(max_prompt_budget) - head_keep;
        std::vector<llama_token> trimmed;
        trimmed.reserve(static_cast<size_t>(max_prompt_budget));
        trimmed.insert(trimmed.end(), prompt_tokens.begin(), prompt_tokens.begin() + head_keep);
        trimmed.insert(trimmed.end(), prompt_tokens.end() - tail_keep, prompt_tokens.end());
        prompt_tokens.swap(trimmed);
    }

    llama_memory_t mem = llama_get_memory(g_engine.ctx);
    int32_t common_prefix = 0;
    if (reuse_kv_cache && mem != nullptr && !g_engine.cached_tokens.empty()) {
        const size_t limit = std::min(g_engine.cached_tokens.size(), prompt_tokens.size());
        size_t i = 0;
        while (i < limit && g_engine.cached_tokens[i] == prompt_tokens[i]) {
            ++i;
        }
        // Leave at least 1 token in the suffix so llama_process produces output logits at -1
        if (i >= prompt_tokens.size() && i > 0) {
            i = prompt_tokens.size() - 1;
        }
        if (i > 0) {
            if (llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(i), -1)) {
                common_prefix = static_cast<int32_t>(i);
            } else {
                llama_memory_clear(mem, true);
                g_engine.cached_tokens.clear();
                common_prefix = 0;
            }
        } else {
            llama_memory_clear(mem, true);
            g_engine.cached_tokens.clear();
        }
    } else if (mem != nullptr) {
        llama_memory_clear(mem, true);
        g_engine.cached_tokens.clear();
    }

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (!gbnf_grammar.empty()) {
        llama_sampler * grammar_smpl = llama_sampler_init_grammar(vocab, gbnf_grammar.c_str(), "root");
        if (grammar_smpl != nullptr) {
            llama_sampler_chain_add(smpl, grammar_smpl);
        } else {
            LOGW("Failed to parse GBNF grammar; falling back to unconstrained sampling");
        }
    }
    if (temperature <= 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.90f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_batch_ext * batch = llama_batch_ext_init(g_engine.ctx);
    if (batch == nullptr) {
        llama_sampler_free(smpl);
        g_engine.cached_tokens.clear();
        return "";
    }

    const int32_t total_prompt = static_cast<int32_t>(prompt_tokens.size());
    const int32_t chunk_cap = 256;
    int32_t n_pos = common_prefix;
    bool eval_ok = true;
    while (n_pos < total_prompt && !g_engine.cancel_requested.load()) {
        const int32_t chunk_len = std::min(chunk_cap, total_prompt - n_pos);
        if (!batch_set_tokens(batch, prompt_tokens.data() + n_pos, chunk_len, n_pos)) {
            eval_ok = false;
            break;
        }
        if (llama_process(g_engine.ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            eval_ok = false;
            break;
        }
        n_pos += chunk_len;
    }

    std::string full_output;
    std::string utf8_pending;
    std::vector<llama_token> generated_tokens;
    const int32_t gen_limit = std::min<int32_t>(
            max_tokens > 0 ? max_tokens : 192,
            std::max<int32_t>(16, g_engine.context_len - n_pos));

    for (int32_t step = 0; step < gen_limit && eval_ok && !g_engine.cancel_requested.load(); ++step) {
        llama_token new_tok = llama_sampler_sample(smpl, g_engine.ctx, -1);
        llama_sampler_accept(smpl, new_tok);
        if (llama_vocab_is_eog(vocab, new_tok)) {
            break;
        }
        generated_tokens.push_back(new_tok);

        char piece[256];
        int32_t piece_len = llama_token_to_piece(vocab, new_tok, piece, sizeof(piece), 0, false);
        if (piece_len > 0) {
            utf8_pending.append(piece, static_cast<size_t>(piece_len));
            const size_t ready_len = valid_utf8_prefix_len(utf8_pending);
            if (ready_len > 0) {
                std::string chunk = utf8_pending.substr(0, ready_len);
                utf8_pending.erase(0, ready_len);
                full_output += chunk;

                if (tokenCallback != nullptr && on_token_method != nullptr) {
                    jstring jchunk = env->NewStringUTF(chunk.c_str());
                    if (jchunk != nullptr) {
                        env->CallVoidMethod(tokenCallback, on_token_method, jchunk);
                        env->DeleteLocalRef(jchunk);
                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                            break;
                        }
                    }
                }
            }
        }

        if (!batch_set_tokens(batch, &new_tok, 1, n_pos)) {
            eval_ok = false;
            break;
        }
        if (llama_process(g_engine.ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            eval_ok = false;
            break;
        }
        n_pos += 1;
    }

    if (!utf8_pending.empty()) {
        const size_t ready_len = valid_utf8_prefix_len(utf8_pending);
        if (ready_len > 0) {
            std::string tail = utf8_pending.substr(0, ready_len);
            full_output += tail;
            if (tokenCallback != nullptr && on_token_method != nullptr) {
                jstring jtail = env->NewStringUTF(tail.c_str());
                if (jtail != nullptr) {
                    env->CallVoidMethod(tokenCallback, on_token_method, jtail);
                    env->DeleteLocalRef(jtail);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            }
        }
    }

    if (eval_ok && !g_engine.cancel_requested.load() && gbnf_grammar.empty()) {
        g_engine.cached_tokens = prompt_tokens;
        g_engine.cached_tokens.insert(
                g_engine.cached_tokens.end(),
                generated_tokens.begin(),
                generated_tokens.end());
    } else {
        g_engine.cached_tokens.clear();
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);
    return full_output;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeLoadModelFromPath(
        JNIEnv * env,
        jobject /* thiz */,
        jstring path,
        jint contextLen,
        jint threads) {
    if (path == nullptr) return JNI_FALSE;
    const char * cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return JNI_FALSE;
    std::string model_path(cpath);
    env->ReleaseStringUTFChars(path, cpath);

    std::lock_guard<std::mutex> lock(g_engine.mutex);
    try {
        ensure_backend_init();
        unload_locked();

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0;
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

        llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
        if (model == nullptr) {
            LOGE("Failed to load GGUF model from path");
            return JNI_FALSE;
        }

        const int32_t n_ctx = contextLen > 128 ? contextLen : 1024;
        const int32_t n_threads = threads > 0 ? threads : 4;

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = static_cast<uint32_t>(n_ctx);
        cparams.n_batch = static_cast<uint32_t>(n_ctx < 512 ? n_ctx : 512);
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;
        cparams.no_perf = true;

        llama_context * ctx = llama_init_from_model(model, cparams);
        if (ctx == nullptr) {
            LOGE("Failed to create llama_context");
            llama_model_free(model);
            return JNI_FALSE;
        }

        g_engine.model = model;
        g_engine.ctx = ctx;
        g_engine.context_len = n_ctx;
        g_engine.threads = n_threads;
        g_engine.cached_tokens.clear();
        LOGI("Loaded GGUF model successfully (ctx=%d, threads=%d)", n_ctx, n_threads);
        return JNI_TRUE;
    } catch (const std::bad_alloc &) {
        LOGE("OOM loading GGUF model from path");
        unload_locked();
        return JNI_FALSE;
    } catch (...) {
        LOGE("Native exception loading GGUF model from path");
        unload_locked();
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeLoadModelFromFd(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jint fd,
        jint contextLen,
        jint threads) {
    if (fd < 0) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_engine.mutex);
    try {
        ensure_backend_init();
        unload_locked();

        char fd_path[64];
        std::snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", static_cast<int>(fd));

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0;
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

        llama_model * model = llama_model_load_from_file(fd_path, mparams);
        if (model == nullptr) {
            int dup_fd = dup(static_cast<int>(fd));
            if (dup_fd >= 0) {
                lseek(dup_fd, 0, SEEK_SET);
                FILE * dup_file = fdopen(dup_fd, "rb");
                if (dup_file != nullptr) {
                    mparams.load_mode = LLAMA_LOAD_MODE_NONE;
                    model = llama_model_load_from_file_ptr(dup_file, mparams);
                    std::fclose(dup_file);
                } else {
                    close(dup_fd);
                }
            }
        }

        if (model == nullptr) {
            LOGE("Failed to load GGUF model from FD %d", static_cast<int>(fd));
            return JNI_FALSE;
        }

        const int32_t n_ctx = contextLen > 128 ? contextLen : 1024;
        const int32_t n_threads = threads > 0 ? threads : 4;

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = static_cast<uint32_t>(n_ctx);
        cparams.n_batch = static_cast<uint32_t>(n_ctx < 512 ? n_ctx : 512);
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;
        cparams.no_perf = true;

        llama_context * ctx = llama_init_from_model(model, cparams);
        if (ctx == nullptr) {
            LOGE("Failed to create llama_context from FD");
            llama_model_free(model);
            return JNI_FALSE;
        }

        g_engine.model = model;
        g_engine.ctx = ctx;
        g_engine.context_len = n_ctx;
        g_engine.threads = n_threads;
        g_engine.cached_tokens.clear();
        LOGI("Loaded GGUF model via SAF FD (ctx=%d, threads=%d)", n_ctx, n_threads);
        return JNI_TRUE;
    } catch (const std::bad_alloc &) {
        LOGE("OOM loading GGUF model from FD");
        unload_locked();
        return JNI_FALSE;
    } catch (...) {
        LOGE("Native exception loading GGUF model from FD");
        unload_locked();
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeGetModelDescription(
        JNIEnv * env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr) {
        return env->NewStringUTF("");
    }
    char buf[256] = {0};
    llama_model_desc(g_engine.model, buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

JNIEXPORT jstring JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeGetChatTemplate(
        JNIEnv * env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr) {
        return env->NewStringUTF("");
    }
    const char * tmpl = llama_model_chat_template(g_engine.model, nullptr);
    return env->NewStringUTF(tmpl != nullptr ? tmpl : "");
}

JNIEXPORT jint JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeCountTokens(
        JNIEnv * env,
        jobject /* thiz */,
        jstring text) {
    if (text == nullptr) return 0;
    const char * cstr = env->GetStringUTFChars(text, nullptr);
    if (cstr == nullptr) return 0;
    std::string input(cstr);
    env->ReleaseStringUTFChars(text, cstr);

    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr || input.empty()) return 0;
    const llama_vocab * vocab = llama_model_get_vocab(g_engine.model);
    int32_t n = -llama_tokenize(
            vocab,
            input.c_str(),
            static_cast<int32_t>(input.size()),
            nullptr,
            0,
            true,
            true);
    return n > 0 ? static_cast<jint>(n) : 0;
}

JNIEXPORT void JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeClearKvCache(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    g_engine.cached_tokens.clear();
    if (g_engine.ctx != nullptr) {
        llama_memory_t mem = llama_get_memory(g_engine.ctx);
        if (mem != nullptr) {
            llama_memory_clear(mem, true);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeUnloadModel(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    g_engine.cancel_requested.store(true);
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    unload_locked();
    g_engine.cancel_requested.store(false);
}

JNIEXPORT void JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeCancelGeneration(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    g_engine.cancel_requested.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeGenerateStream(
        JNIEnv * env,
        jobject /* thiz */,
        jstring systemPrompt,
        jstring userPrompt,
        jint maxTokens,
        jfloat temperature,
        jobject tokenCallback) {
    if (systemPrompt == nullptr || userPrompt == nullptr) {
        return env->NewStringUTF("");
    }
    const char * sys_c = env->GetStringUTFChars(systemPrompt, nullptr);
    const char * usr_c = env->GetStringUTFChars(userPrompt, nullptr);
    std::string sys_str = sys_c ? sys_c : "";
    std::string usr_str = usr_c ? usr_c : "";
    if (sys_c) env->ReleaseStringUTFChars(systemPrompt, sys_c);
    if (usr_c) env->ReleaseStringUTFChars(userPrompt, usr_c);

    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr || g_engine.ctx == nullptr) {
        return env->NewStringUTF("");
    }
    try {
        std::string formatted = build_prompt(g_engine.model, sys_str.c_str(), usr_str.c_str());
        std::string out = run_generation_locked(
                env, formatted, maxTokens, temperature, "", false, tokenCallback);
        return env->NewStringUTF(out.c_str());
    } catch (const std::bad_alloc &) {
        LOGE("OOM during nativeGenerateStream");
        unload_locked();
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("Native exception during nativeGenerateStream");
        g_engine.cached_tokens.clear();
        return env->NewStringUTF("");
    }
}

JNIEXPORT jstring JNICALL
Java_com_example_llm_LlamaNativeBridge_nativeGenerateRawStream(
        JNIEnv * env,
        jobject /* thiz */,
        jstring formattedPrompt,
        jint maxTokens,
        jfloat temperature,
        jstring gbnfGrammar,
        jboolean reuseKvCache,
        jobject tokenCallback) {
    if (formattedPrompt == nullptr) {
        return env->NewStringUTF("");
    }
    const char * prompt_c = env->GetStringUTFChars(formattedPrompt, nullptr);
    std::string prompt_str = prompt_c ? prompt_c : "";
    if (prompt_c) env->ReleaseStringUTFChars(formattedPrompt, prompt_c);

    std::string grammar_str;
    if (gbnfGrammar != nullptr) {
        const char * g_c = env->GetStringUTFChars(gbnfGrammar, nullptr);
        if (g_c) {
            grammar_str = g_c;
            env->ReleaseStringUTFChars(gbnfGrammar, g_c);
        }
    }

    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr || g_engine.ctx == nullptr) {
        return env->NewStringUTF("");
    }
    try {
        std::string out = run_generation_locked(
                env,
                prompt_str,
                maxTokens,
                temperature,
                grammar_str,
                reuseKvCache == JNI_TRUE,
                tokenCallback);
        return env->NewStringUTF(out.c_str());
    } catch (const std::bad_alloc &) {
        LOGE("OOM during nativeGenerateRawStream");
        unload_locked();
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("Native exception during nativeGenerateRawStream");
        g_engine.cached_tokens.clear();
        return env->NewStringUTF("");
    }
}

} // extern "C"
