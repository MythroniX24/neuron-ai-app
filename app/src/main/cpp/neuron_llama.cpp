// Neuron-AI native inference bridge.
//
// The ONLY native surface Kotlin touches. Loads GGUF weights through
// llama.cpp's model-loading path (mmap) and runs greedy-decoded streaming
// text generation. No code from model files is ever executed — weights are
// data, loaded only as tensors.
//
// Built two ways (CMakeLists.txt):
//   NEURON_HAVE_LLAMA=1   full bridge against llama.cpp
//   NEURON_HAVE_LLAMA=0   stub: reports "engine unavailable" instead of
//                         breaking builds where llama.cpp could not be fetched
//   NEURON_HAVE_VULKAN    (1|0) — Vulkan GPU backend compiled in (milestone 5)

#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NeuronLlama", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NeuronLlama", __VA_ARGS__)
#else
#define LOGE(...) ((void)0)
#define LOGI(...) ((void)0)
#endif

#if defined(__ANDROID__)
#include <dlfcn.h>
#endif

#if NEURON_HAVE_LLAMA
#include "llama.h"

namespace {

// Process-wide single-active-model state. The Kotlin loader enforces the
// same constraint; mirroring it natively means a leaked handle can never
// leave two large models resident.
std::mutex g_mutex;
bool g_backendsInitialized = false;
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
std::string g_loadedChatTemplate;
int g_loadedFtype = -1;

#if defined(__ANDROID__) && NEURON_HAVE_VULKAN
// Vulkan driver/loader presence — probed once, result cached. We dlopen
// WITHOUT loading any bundled copy: the NDK's libvulkan.so is a build-time
// stub and is never packaged, so the linker resolves the platform loader in
// /system, whose platform allocator the GPU drivers expect (bundling a
// loader copy instead can install a mismatched host allocator and crash —
// CWE-789; the official llama.cpp Android binding hit this on Android 15).
bool vulkanDriverPresent() {
    static const bool present = [] {
#if !defined(__x86_64__)
        void *handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            LOGI("Vulkan: no platform loader (%s)", dlerror());
            return false;
        }
        // Deliberately NOT dlclose'd: the loader must stay resident for the
        // backend's device enumeration later in the process lifetime.
        return true;
#else
        return false; // emulator images: CPU-only
#endif
    }();
    return present;
}
#else
bool vulkanDriverPresent() { return false; }
#endif // __ANDROID__ && NEURON_HAVE_VULKAN

// Greedy (temperature 0) decode of the next token from the last logits.
llama_token sampleGreedy(llama_context *ctx, const llama_vocab *vocab) {
    const float *logits = llama_get_logits_ith(ctx, -1);
    const int vocabSize = llama_vocab_n_tokens(vocab);
    int best = 0;
    float bestVal = logits[0];
    for (int i = 1; i < vocabSize; ++i) {
        if (logits[i] > bestVal) {
            bestVal = logits[i];
            best = i;
        }
    }
    return static_cast<llama_token>(best);
}

void unloadLocked() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_loadedChatTemplate.clear();
    g_loadedFtype = -1;
}

} // namespace
#endif // NEURON_HAVE_LLAMA

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeIsAvailable(JNIEnv *, jobject) {
#if NEURON_HAVE_LLAMA
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// GPU acceleration usable in THIS build AND on THIS device (backend compiled
// in + a Vulkan loader/driver actually present).
JNIEXPORT jboolean JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeGpuAvailable(JNIEnv *, jobject) {
#if NEURON_HAVE_LLAMA && NEURON_HAVE_VULKAN
    return vulkanDriverPresent() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeVersion(JNIEnv *env, jobject) {
#if NEURON_HAVE_LLAMA
#if NEURON_HAVE_VULKAN
    return env->NewStringUTF("llama.cpp (bundled, Vulkan GPU enabled)");
#else
    return env->NewStringUTF("llama.cpp (bundled, CPU)");
#endif
#else
    return env->NewStringUTF("unavailable");
#endif
}

// One-time discovery of runtime backends (ggml's GGML_BACKEND_DL model):
// dlopen every libggml-{cpu,vulkan}-*.so found in [dir], scoring CPU variants
// and keeping only the best match. No-op when already initialized.
JNIEXPORT void JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeInitBackends(
        JNIEnv *env, jobject, jstring jDir) {
#if NEURON_HAVE_LLAMA
    const char *dir = env->GetStringUTFChars(jDir, nullptr);
    if (dir != nullptr) {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_backendsInitialized) {
            ggml_backend_load_all_from_path(dir);
            g_backendsInitialized = true;
        }
        env->ReleaseStringUTFChars(jDir, dir);
    }
#endif
}

// Loads (mmap) a GGUF model, replacing any previously loaded one — the
// single-active constraint lives HERE too, not only in Kotlin.
// Returns 0 on success, negative on failure with errOut[0] set to a
// human-readable reason.
JNIEXPORT jint JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeLoad(
        JNIEnv *env, jobject, jstring jPath, jint contextTokens,
        jint threads, jboolean useGpu, jobjectArray jErrOut) {
#if NEURON_HAVE_LLAMA
    if (!g_backendsInitialized) {
        if (jErrOut != nullptr && env->GetArrayLength(jErrOut) > 0) {
            env->SetObjectArrayElement(jErrOut, 0,
                env->NewStringUTF("Backends were not initialized"));
        }
        return -7;
    }
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    std::string modelPath = path != nullptr ? path : "";
    env->ReleaseStringUTFChars(jPath, path);

    auto fail = [&](jint code, const char *message) {
        if (jErrOut != nullptr && env->GetArrayLength(jErrOut) > 0) {
            env->SetObjectArrayElement(jErrOut, 0, env->NewStringUTF(message));
        }
        LOGE("load failed (%d): %s", (int) code, message);
        unloadLocked();
        llama_backend_free();
        return code;
    };

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model != nullptr) {
        return 0; // already loaded; Kotlin calls unload first to switch
    }

    llama_backend_init();

    const bool gpu = useGpu == JNI_TRUE;
    if (gpu && !vulkanDriverPresent()) {
        return fail(-6, "No Vulkan GPU driver is available on this device");
    }

    llama_model_params mparams = llama_model_default_params();
    // 999 = offload every layer that fits; llama.cpp falls back to CPU for
    // whatever the GPU cannot hold, so partial offload just works.
    mparams.n_gpu_layers = gpu ? 999 : 0;
    // Memory-map by default (large models never fully resident up front);
    // mlock off so the OS can reclaim under memory pressure.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        return fail(-1, "Model file could not be loaded (corrupt or unsupported GGUF)");
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(contextTokens > 0 ? contextTokens : 2048);
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    if (gpu) {
        // Attention runs on the GPU: flash attention halves KV memory traffic
        // and is markedly faster there; quantized KV (Q8_0) shrinks the cache
        // ~2x with negligible quality loss — more layers fit in the limited
        // phone GPU memory.
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return fail(-2, gpu
            ? "Not enough memory to run this model (GPU offload was requested)"
            : "Not enough memory to run this model on this device");
    }

    g_model = model;
    g_ctx = ctx;
    const char *tmpl = llama_model_chat_template(model, nullptr);
    g_loadedChatTemplate = tmpl != nullptr ? std::string(tmpl) : std::string();
    g_loadedFtype = llama_model_ftype(model);
    LOGI("loaded '%s' (gpu=%d, ctx=%d, threads=%d)",
         modelPath.c_str(), gpu ? 1 : 0, (int) cparams.n_ctx, (int) cparams.n_threads);
    return 0;
#else
    (void)jPath; (void)contextTokens; (void)threads; (void)useGpu;
    if (jErrOut != nullptr && env->GetArrayLength(jErrOut) > 0) {
        env->SetObjectArrayElement(jErrOut, 0,
            env->NewStringUTF("Native inference engine was not built into this APK"));
    }
    return -100;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeUnload(JNIEnv *, jobject) {
#if NEURON_HAVE_LLAMA
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr || g_model != nullptr) {
        unloadLocked();
        llama_backend_free();
    }
    return JNI_TRUE;
#else
    return JNI_TRUE;
#endif
}

// Chat template embedded in the loaded model's GGUF metadata ("" when none).
JNIEXPORT jstring JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeChatTemplate(JNIEnv *env, jobject) {
#if NEURON_HAVE_LLAMA
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_loadedChatTemplate.c_str());
#else
    return env->NewStringUTF("");
#endif
}

// Streams generation: each decoded piece is delivered via Callback.text.
// Prompt must already be rendered through the model's chat template by the
// Kotlin caller. Returns tokens generated, or a negative error code:
//   -3 model not loaded  -4 tokenization failed  -5 prompt exceeded context
JNIEXPORT jint JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeGenerate(
        JNIEnv *env, jobject, jstring jPrompt, jint maxTokens, jobject jCallback) {
#if NEURON_HAVE_LLAMA
    if (jPrompt == nullptr || jCallback == nullptr) return -1;

    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt = promptChars != nullptr ? promptChars : "";
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    jclass cbClass = env->GetObjectClass(jCallback);
    if (cbClass == nullptr) return -1;
    jmethodID onText = env->GetMethodID(cbClass, "text", "(Ljava/lang/String;)V");
    if (onText == nullptr) return -2;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_ctx == nullptr) return -3;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // Tokenize: first call with a null buffer returns the required size.
    int n_tokens = llama_tokenize(vocab, prompt.c_str(),
                                  static_cast<int32_t>(prompt.size()),
                                  nullptr, 0, /* add_special */ true,
                                  /* parse_special */ true);
    if (n_tokens >= 0) return -4; // null-buffer probe must return negative

    std::vector<llama_token> tokens(static_cast<size_t>(-n_tokens));
    n_tokens = llama_tokenize(vocab, prompt.c_str(),
                              static_cast<int32_t>(prompt.size()),
                              tokens.data(), static_cast<int32_t>(tokens.size()),
                              true, true);
    if (n_tokens <= 0) return -4;

    if (llama_decode(g_ctx, llama_batch_get_one(tokens.data(), n_tokens)) != 0) {
        return -5; // prompt did not fit the configured context
    }

    char pieceBuf[256];
    int generated = 0;
    llama_token next = sampleGreedy(g_ctx, vocab);
    while (generated < maxTokens) {
        if (llama_vocab_is_eog(vocab, next)) break;

        int n = llama_token_to_piece(vocab, next, pieceBuf, sizeof(pieceBuf), 0, true);
        if (n < 0) break;
        if (n > 0) {
            jstring piece = env->NewStringUTF(std::string(pieceBuf, static_cast<size_t>(n)).c_str());
            if (piece != nullptr) {
                env->CallVoidMethod(jCallback, onText, piece);
                env->DeleteLocalRef(piece);
            }
        }
        ++generated;

        if (llama_decode(g_ctx, llama_batch_get_one(&next, 1)) != 0) break;
        next = sampleGreedy(g_ctx, vocab);
    }
    return generated;
#else
    (void)jPrompt; (void)maxTokens; (void)jCallback;
    return -100;
#endif
}

} // extern "C"
