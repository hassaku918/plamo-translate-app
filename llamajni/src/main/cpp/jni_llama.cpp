#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llamajni"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
bool g_backend_ok = false;

void free_ctx_locked() {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
}

void free_all_locked() {
    free_ctx_locked();
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hassaku_llamajni_NativeLlama_nativeInit(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_ok) {
        llama_backend_init();
        g_backend_ok = true;
        LOGi("llama_backend_init ok; build=%s", llama_print_system_info());
    }
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_hassaku_llamajni_NativeLlama_nativeLoad(
        JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_ok) {
        llama_backend_init();
        g_backend_ok = true;
    }

    free_all_locked();

    const std::string path = jstring_to_string(env, jpath);
    LOGi("loading model: %s ctx=%d threads=%d", path.c_str(), (int) n_ctx, (int) n_threads);

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.use_mlock = false;
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGe("llama_model_load_from_file failed");
        return env->NewStringUTF("llama_model_load_from_file failed (unsupported arch or bad file)");
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = std::max(256, (int) n_ctx);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = std::max(1, (int) n_threads);
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGe("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        return env->NewStringUTF("llama_init_from_model failed (likely OOM)");
    }

    LOGi("model loaded ok");
    return nullptr; // null string => success
}

JNIEXPORT void JNICALL
Java_com_hassaku_llamajni_NativeLlama_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all_locked();
    LOGi("model unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_hassaku_llamajni_NativeLlama_nativeComplete(
        JNIEnv * env, jclass, jstring jprompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("");
    }

    const std::string prompt = jstring_to_string(env, jprompt);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    // clear memory / KV
    llama_memory_clear(llama_get_memory(g_ctx), true);

    std::vector<llama_token> tokens;
    tokens.resize(prompt.size() + 32);
    const int n_prompt = llama_tokenize(
            vocab,
            prompt.c_str(),
            (int32_t) prompt.size(),
            tokens.data(),
            (int32_t) tokens.size(),
            /*add_special*/ true,
            /*parse_special*/ true);
    if (n_prompt < 0) {
        tokens.resize(-n_prompt);
        const int n2 = llama_tokenize(
                vocab,
                prompt.c_str(),
                (int32_t) prompt.size(),
                tokens.data(),
                (int32_t) tokens.size(),
                true,
                true);
        if (n2 < 0) {
            LOGe("tokenize failed");
            return env->NewStringUTF("");
        }
        tokens.resize(n2);
    } else {
        tokens.resize(n_prompt);
    }

    LOGi("prompt tokens=%zu", tokens.size());

    // evaluate prompt
    for (size_t i = 0; i < tokens.size(); ++i) {
        llama_batch batch = llama_batch_get_one(&tokens[i], 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGe("decode failed at prompt token %zu", i);
            return env->NewStringUTF("");
        }
    }

    // greedy sampler chain
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    out.reserve(1024);
    const int max_new = std::max(1, (int) max_tokens);

    for (int i = 0; i < max_new; ++i) {
        const llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        char buf[256];
        const int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            out.append(buf, buf + n);
        }

        llama_batch batch = llama_batch_get_one((llama_token *) &id, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGe("decode failed during generation");
            break;
        }
    }

    llama_sampler_free(smpl);
    LOGi("generated chars=%zu", out.size());
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_hassaku_llamajni_NativeLlama_nativeIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

} // extern C
