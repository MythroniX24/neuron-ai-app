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
#include <algorithm>
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
#if NEURON_HAVE_MTMD
#include "mtmd.h"
#include "mtmd-helper.h"
#endif

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
// Configured context window of the ACTIVE context (n_ctx).
uint32_t g_ctxSize = 0;
// Milestone 8: shadow copy of the tokens currently held in sequence 0 of the
// KV cache (prompt tokens + everything generated since). This is what makes
// prompt-prefix reuse possible: the next turn re-renders the same
// conversation, so most of it is already computed and only the new tail needs
// decoding. Verified against the real token list before reuse — Kotlin may
// only ever LOWER the reuse length, never invent it.
std::vector<llama_token> g_cachedTokens;
int g_cachedPos = 0;
#if NEURON_HAVE_MTMD
// Milestone 9: vision projector (mmproj). Only created when the user imported
// an mmproj file NEXT TO the model — a text-only GGUF never gets one, so the
// router can refuse images honestly instead of failing deep inside a decode.
mtmd_context *g_mctx = nullptr;
#endif

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
#if NEURON_HAVE_MTMD
    // The projector borrows the text model's vocab/weights — it MUST go
    // before the model itself is freed.
    if (g_mctx != nullptr) {
        mtmd_free(g_mctx);
        g_mctx = nullptr;
    }
#endif
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
    g_ctxSize = 0;
    g_cachedTokens.clear();
    g_cachedPos = 0;
}

// Decodes [tokens[from..n)) at absolute positions [from..n) in sequence 0.
// Returns llama_decode's code, or 1 when the batch can't be built.
int decodeRange(llama_context *ctx, const std::vector<llama_token> &tokens, int from) {
    const int n = static_cast<int>(tokens.size());
    const int count = n - from;
    if (count <= 0) {
        return 0;
    }
    llama_batch batch = llama_batch_init(count, 0, 1);
    if (batch.token == nullptr) {
        return 1;
    }
    for (int i = 0; i < count; ++i) {
        batch.token[i] = tokens[from + i];
        batch.pos[i] = from + i;
        // n_seq_id/seq_id are PER-TOKEN arrays; the position itself lives in
        // batch.pos, so there is no separate per-sequence position to set.
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        // Only the final position needs logits (greedy decode samples from it);
        // every entry is written because llama_batch_init leaves them unset.
        batch.logits[i] = (i == count - 1);
    }
    const int rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    return rc;
}

// Decodes a single token at an absolute position (the generation step).
int decodeToken(llama_context *ctx, llama_token token, int pos) {
    llama_batch batch = llama_batch_init(1, 0, 1);
    if (batch.token == nullptr) {
        return 1;
    }
    batch.token[0] = token;
    batch.pos[0] = pos;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = true;
    const int rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    return rc;
}

// Greedy generation from the logits already present in [ctx] at position
// [startPos]. Shared by the text path and the vision path so both stream
// identically. Emits every piece through [onText]; returns tokens generated.
int generateLoop(JNIEnv *env, jobject jCallback, jmethodID onText, llama_context *ctx,
                 const llama_vocab *vocab, int startPos, int maxTokens) {
    const int nCtx = static_cast<int>(g_ctxSize);
    char pieceBuf[256];
    int generated = 0;
    int pos = startPos;
    llama_token next = sampleGreedy(ctx, vocab);
    while (generated < maxTokens) {
        if (llama_vocab_is_eog(vocab, next)) break;

        const int n = llama_token_to_piece(vocab, next, pieceBuf, sizeof(pieceBuf), 0, true);
        if (n < 0) break;
        if (n > 0) {
            jstring piece = env->NewStringUTF(std::string(pieceBuf, static_cast<size_t>(n)).c_str());
            if (piece != nullptr) {
                env->CallVoidMethod(jCallback, onText, piece);
                env->DeleteLocalRef(piece);
            }
        }
        ++generated;

        if (nCtx > 0 && pos >= nCtx) break; // context full — stop cleanly
        if (decodeToken(ctx, next, pos) != 0) break;
        // The generated token joins the shadow copy so the NEXT turn can
        // reuse it instead of recomputing the answer.
        g_cachedTokens.push_back(next);
        ++pos;
        next = sampleGreedy(ctx, vocab);
    }
    g_cachedPos = pos;
    return generated;
}

// Resolves the streaming callback's method id; nullptr when unavailable.
jmethodID resolveTextCallback(JNIEnv *env, jobject jCallback) {
    if (jCallback == nullptr) return nullptr;
    jclass cbClass = env->GetObjectClass(jCallback);
    if (cbClass == nullptr) return nullptr;
    return env->GetMethodID(cbClass, "text", "(Ljava/lang/String;)V");
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
        jint threads, jboolean useGpu, jstring jMmprojPath, jobjectArray jErrOut) {
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
    std::string mmprojPath;
    if (jMmprojPath != nullptr) {
        const char *mm = env->GetStringUTFChars(jMmprojPath, nullptr);
        mmprojPath = mm != nullptr ? mm : "";
        env->ReleaseStringUTFChars(jMmprojPath, mm);
    }

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
    // Milestone 8: quantized KV cache (Q8_0) on EVERY path, not just GPU.
    // Halves KV memory traffic for ~0.1 bit of quality difference, which
    // means more layers stay resident on the GPU and a longer chat fits.
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;
    if (gpu) {
        // Attention runs on the GPU: flash attention halves KV memory traffic
        // and is markedly faster there.
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
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
    g_ctxSize = cparams.n_ctx;
    g_cachedTokens.clear();
    g_cachedPos = 0;

#if NEURON_HAVE_MTMD
    // Milestone 9: attach the vision projector when the caller found one
    // beside the model. A failure here is reported instead of silently
    // degrading to a text-only context.
    if (!mmprojPath.empty()) {
        mtmd_context_params mparams_mtmd = mtmd_context_params_default();
        mparams_mtmd.use_gpu = gpu;
        mparams_mtmd.n_threads = cparams.n_threads;
        mparams_mtmd.print_timings = false;
        mparams_mtmd.warmup = false;
        mparams_mtmd.flash_attn_type = cparams.flash_attn_type;
        // Must match LocalEngineLoader.MEDIA_MARKER on the Kotlin side. mtmd
        // defaults to "<__media__>"; if the prompt we build does not contain
        // the exact marker mtmd expects, mtmd_tokenize() counts zero media
        // markers against one bitmap and rejects every image as a mismatch.
        mparams_mtmd.media_marker = "<|image|>";
        g_mctx = mtmd_init_from_file(mmprojPath.c_str(), model, mparams_mtmd);
        if (g_mctx == nullptr) {
            return fail(-9, "Vision projector (mmproj) could not be loaded for this model");
        }
        if (!mtmd_support_vision(g_mctx)) {
            mtmd_free(g_mctx);
            g_mctx = nullptr;
            return fail(-9, "That projector file does not support image input");
        }
        LOGI("vision projector attached: %s", mmprojPath.c_str());
    }
#endif
    const char *tmpl = llama_model_chat_template(model, nullptr);
    g_loadedChatTemplate = tmpl != nullptr ? std::string(tmpl) : std::string();
    g_loadedFtype = llama_model_ftype(model);
    LOGI("loaded '%s' (gpu=%d, ctx=%d, threads=%d)",
         modelPath.c_str(), gpu ? 1 : 0, (int) cparams.n_ctx, (int) cparams.n_threads);
    return 0;
#else
    (void)jPath; (void)contextTokens; (void)threads; (void)useGpu; (void)jMmprojPath;
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

// Tokenizes a chat-template-rendered prompt into ids. The Kotlin side owns
// the prefix bookkeeping (pure, unit-tested), so tokenization happens ONCE
// here and the ids go straight back for the prefix comparison.
JNIEXPORT jintArray JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeTokenize(
        JNIEnv *env, jobject, jstring jPrompt) {
#if NEURON_HAVE_LLAMA
    if (jPrompt == nullptr) return env->NewIntArray(0);
    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt = promptChars != nullptr ? promptChars : "";
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr) return env->NewIntArray(0);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // First call with a null buffer returns the required size (negative).
    const int needed = llama_tokenize(vocab, prompt.c_str(),
                                      static_cast<int32_t>(prompt.size()),
                                      nullptr, 0, /* add_special */ true,
                                      /* parse_special */ true);
    if (needed >= 0) return env->NewIntArray(0);

    std::vector<llama_token> tokens(static_cast<size_t>(-needed));
    const int n_tokens = llama_tokenize(vocab, prompt.c_str(),
                                        static_cast<int32_t>(prompt.size()),
                                        tokens.data(), static_cast<int32_t>(tokens.size()),
                                        true, true);
    if (n_tokens <= 0) return env->NewIntArray(0);

    jintArray out = env->NewIntArray(n_tokens);
    if (out == nullptr) return nullptr;
    std::vector<jint> asInt(static_cast<size_t>(n_tokens));
    for (int i = 0; i < n_tokens; ++i) {
        asInt[static_cast<size_t>(i)] = static_cast<jint>(tokens[static_cast<size_t>(i)]);
    }
    env->SetIntArrayRegion(out, 0, n_tokens, asInt.data());
    return out;
#else
    (void)jPrompt;
    return env->NewIntArray(0);
#endif
}

// Tokens currently held in the KV cache (sequence 0): the prompt of the last
// turn plus everything generated since. Kotlin compares its freshly
// tokenized prompt against this to decide how much can be skipped.
JNIEXPORT jintArray JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeCachedTokens(
        JNIEnv *env, jobject) {
#if NEURON_HAVE_LLAMA
    std::lock_guard<std::mutex> lock(g_mutex);
    const int n = static_cast<int>(g_cachedTokens.size());
    jintArray out = env->NewIntArray(n);
    if (out == nullptr || n == 0) return out;
    std::vector<jint> asInt(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) {
        asInt[static_cast<size_t>(i)] = static_cast<jint>(g_cachedTokens[static_cast<size_t>(i)]);
    }
    env->SetIntArrayRegion(out, 0, n, asInt.data());
    return out;
#else
    return env->NewIntArray(0);
#endif
}

// Streams generation from ALREADY TOKENIZED ids; each decoded piece is
// delivered via Callback.text. [reusePrefix] is how many leading tokens the
// caller believes are already in the KV cache (0 = decode everything).
//
// Milestone 8: the reuse length is VERIFIED against the shadow copy here —
// the caller can only ever lower it, never make the cache claim tokens it
// doesn't hold. Re-processing the whole conversation every turn was by far
// the biggest source of perceived slowness on a phone CPU.
//
// Returns tokens generated, or a negative error code:
//   -3 model not loaded  -4 tokenization failed  -5 prompt exceeded context
JNIEXPORT jint JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeGenerateTokens(
        JNIEnv *env, jobject, jintArray jTokens, jint maxTokens, jint reusePrefix,
        jobject jCallback) {
#if NEURON_HAVE_LLAMA
    if (jTokens == nullptr || jCallback == nullptr) return -1;

    const int n_tokens = env->GetArrayLength(jTokens);
    if (n_tokens <= 0) return -4;
    std::vector<llama_token> tokens(static_cast<size_t>(n_tokens));
    {
        std::vector<jint> asInt(static_cast<size_t>(n_tokens));
        env->GetIntArrayRegion(jTokens, 0, n_tokens, asInt.data());
        for (int i = 0; i < n_tokens; ++i) {
            tokens[static_cast<size_t>(i)] = static_cast<llama_token>(asInt[static_cast<size_t>(i)]);
        }
    }

    jmethodID onText = resolveTextCallback(env, jCallback);
    if (onText == nullptr) return -2;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_ctx == nullptr) return -3;

    const int nCtx = static_cast<int>(g_ctxSize);
    if (nCtx > 0 && n_tokens > nCtx) return -5;

    // Verified common prefix: the longest run of identical ids at the start of
    // both the shadow cache and this prompt, capped by what the caller asked.
    int keep = 0;
    if (reusePrefix > 0) {
        size_t common = 0;
        while (common < g_cachedTokens.size() && common < tokens.size() &&
               g_cachedTokens[common] == tokens[common]) {
            ++common;
        }
        keep = static_cast<int>(std::min<size_t>(common, static_cast<size_t>(reusePrefix)));
        if (keep > 0 && !llama_memory_seq_rm(llama_get_memory(g_ctx), 0, keep, -1)) {
            // Partial sequence removal refused: fall back to a full decode.
            keep = 0;
        }
    }

    if (decodeRange(g_ctx, tokens, keep) != 0) return -5;
    g_cachedTokens = tokens;
    g_cachedPos = n_tokens;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const int generated = generateLoop(env, jCallback, onText, g_ctx, vocab, n_tokens, maxTokens);
    return generated;
#else
    (void)jTokens; (void)maxTokens; (void)reusePrefix; (void)jCallback;
    return -100;
#endif
}

// TRUE when a vision projector is attached to the ACTIVE model — the only
// honest signal the router has for "this model can actually read a picture".
JNIEXPORT jboolean JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeVisionAvailable(
        JNIEnv *, jobject) {
#if NEURON_HAVE_LLAMA && NEURON_HAVE_MTMD
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_mctx != nullptr) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

// Milestone 9: image + prompt → answer. [jPrompt] must contain the model's
// media marker ("<|image|>" for most chat templates — the caller gets it from
// the chat template), [jImage] the raw encoded file bytes; mtmd decodes and
// embeds the picture itself.
//
// Prefix reuse is deliberately NOT attempted here: the cache now holds image
// EMBEDDINGS interleaved with text, which no token comparison can match. The
// shadow cache is cleared so the next text turn recomputes cleanly instead of
// trusting a stale prefix.
//
// Returns tokens generated, or a negative error code:
//  -3 no model  -6 no projector loaded  -9 projector/image/prompt failure
JNIEXPORT jint JNICALL
Java_com_neuron_ai_data_local_LocalEngineLoader_nativeGenerateMultimodal(
        JNIEnv *env, jobject, jstring jPrompt, jbyteArray jImage,
        jint maxTokens, jobject jCallback) {
#if NEURON_HAVE_LLAMA && NEURON_HAVE_MTMD
    if (jPrompt == nullptr || jImage == nullptr || jCallback == nullptr) return -1;

    jmethodID onText = resolveTextCallback(env, jCallback);
    if (onText == nullptr) return -2;

    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt = promptChars != nullptr ? promptChars : "";
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    const jsize imageLen = env->GetArrayLength(jImage);
    if (imageLen <= 0) return -9;
    std::vector<unsigned char> image(static_cast<size_t>(imageLen));
    env->GetByteArrayRegion(jImage, 0, imageLen, reinterpret_cast<jbyte *>(image.data()));

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_ctx == nullptr) return -3;
    if (g_mctx == nullptr) return -6;

    mtmd_helper_bitmap_wrapper wrapper = mtmd_helper_bitmap_init_from_buf(
        g_mctx, image.data(), image.size(), /*placeholder*/ false,
        mtmd_helper_init_opt_default());
    if (wrapper.bitmap == nullptr) {
        return -9; // unsupported/corrupt image bytes
    }

    int generated = 0;
    {
        mtmd_input_chunks *chunks = mtmd_input_chunks_init();
        if (chunks == nullptr) {
            mtmd_bitmap_free(wrapper.bitmap);
            return -9;
        }
        mtmd_input_text text;
        text.text = prompt.c_str();
        text.text_len = prompt.size();
        text.add_special = true;
        text.parse_special = true;
        const mtmd_bitmap *bitmaps[1] = { wrapper.bitmap };
        // Returns 1 when the number of bitmaps doesn't match the number of
        // media markers in the prompt — a template mismatch the user must fix.
        const int32_t rc = mtmd_tokenize(g_mctx, chunks, &text, bitmaps, 1);
        if (rc != 0) {
            mtmd_input_chunks_free(chunks);
            mtmd_bitmap_free(wrapper.bitmap);
            return -9;
        }
        llama_pos n_past = 0;
        // Handles non-causal masking and M-RoPE internally; needs the raw
        // llama_context, so it must run under the lock.
        const int32_t evalRc = mtmd_helper_eval_chunks(
            g_mctx, g_ctx, chunks, /*n_past*/ 0, /*seq_id*/ 0,
            /*n_batch*/ 512, /*logits_last*/ true, &n_past);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(wrapper.bitmap);
        if (evalRc != 0) return -9;

        // The cache now holds embeddings: forget the token shadow so the next
        // turn starts from a clean slate instead of a bogus prefix match.
        g_cachedTokens.clear();
        generated = generateLoop(env, jCallback, onText, g_ctx,
                                 llama_model_get_vocab(g_model),
                                 static_cast<int>(n_past), maxTokens);
        g_cachedTokens.clear();
        g_cachedPos = static_cast<int>(n_past);
    }
    return generated;
#else
    (void)jPrompt; (void)jImage; (void)maxTokens; (void)jCallback;
    return -6; // this build has no mtmd / no projector
#endif
}

} // extern "C"
