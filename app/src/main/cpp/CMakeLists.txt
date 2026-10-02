cmake_minimum_required(VERSION 3.22)
project(neuron-native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Performance defaults (milestone 5): the JNI bridge itself is trivial, the
# heavy lifting is llama.cpp/ggml — what matters here is how THOSE are built.
#   - Release -O3 with inlining; debug native builds are 3-10x slower.
#   - ARM64 CPU: GGML_CPU_ALL_VARIANTS builds one optimized kernel set per ISA
#     level and the best match for the actual device is picked at load time
#     via HWCAP (dotprod, i8mm, SVE…). No global -march: it would override the
#     per-variant flags (last -march wins) and poison older cores.
#   - GPU: Vulkan backend built as a runtime-loaded .so (GGML_BACKEND_DL).
#   - x86_64 emulator builds: baseline CPU backend, no Vulkan.
# ---------------------------------------------------------------------------
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
# LTO deliberately OFF: CMake's clang IPO path selects the gold linker +
# LLVMgold.so plugin, which the NDK toolchain does not ship (its lld would
# handle LTO natively but CMake's check picks gold on Linux). Plain -O3
# Release keeps the wins that matter; GGML_LTO stays at its default OFF.
# NOTE: NO global -march here — GGML_CPU_ALL_VARIANTS (below) compiles a
# dedicated kernel set per ISA level (armv8-a, armv8.2+dotprod, i8mm, sve…)
# and clang's last -march wins; a global march would poison the baseline
# variant with instructions older cores cannot execute.
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# ---------------------------------------------------------------------------
# llama.cpp sources — pinned fetch (fetched at configure time, commit-pinned
# so builds are reproducible; never a moving branch). When the runner has no
# network access the build degrades: the JNI bridge still compiles and the
# Kotlin side reports the engine as unavailable (LocalAiProvider falls back
# to a clear "engine unavailable" error).
# ---------------------------------------------------------------------------
set(LLAMA_PIN "d775ebf363f777bc862a8659b6c55eec9419cf35"
    CACHE STRING "llama.cpp commit to build against")
set(LLAMA_SRC "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp-src" CACHE PATH "llama.cpp source dir")

if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    find_package(Git QUIET)
    if(NOT GIT_FOUND)
        message(WARNING "git not found — native inference disabled for this build")
    else()
    message(STATUS "Fetching llama.cpp @ ${LLAMA_PIN}")
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" clone --depth 1
            https://github.com/ggml-org/llama.cpp.git "${LLAMA_SRC}"
        RESULT_VARIABLE clone_result
    )
    if(clone_result EQUAL 0)
        execute_process(
            COMMAND "${GIT_EXECUTABLE}" -C "${LLAMA_SRC}" fetch --depth 1 origin "${LLAMA_PIN}"
            RESULT_VARIABLE fetch_result
        )
        if(fetch_result EQUAL 0)
            execute_process(
                COMMAND "${GIT_EXECUTABLE}" -C "${LLAMA_SRC}" checkout --detach FETCH_HEAD
                RESULT_VARIABLE checkout_result
            )
            if(NOT checkout_result EQUAL 0)
                message(WARNING "Could not pin llama.cpp to ${LLAMA_PIN} — building HEAD")
            endif()
        else()
            message(WARNING "Could not fetch pin ${LLAMA_PIN} — building HEAD")
        endif()
    else()
        message(WARNING "llama.cpp clone failed — native inference disabled for this build")
    endif()
    endif()
endif()

set(NEURON_HAVE_LLAMA OFF)
set(NEURON_HAVE_VULKAN OFF)
if(EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    set(NEURON_HAVE_LLAMA ON)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    # Cross-compilation: the build host is x86, never optimize for it.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    # Runtime-loaded backends (milestone 5): backends ship as separate .so
    # files that ggml DISCOVERS AT RUNTIME from the app's native library
    # directory — per-ISA CPU variants (best HWCAP match: dotprod, i8mm, sve…)
    # and the Vulkan GPU backend (loads only when a driver is present). The
    # same config llama.cpp's official Android binding uses.
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        # Multiple optimized CPU kernels; the fastest one this device supports
        # is picked at load time. x86_64 (emulator) keeps the single baseline
        # CPU backend.
        set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    endif()

    # ---- GPU: Vulkan backend (milestone 5) --------------------------------
    # The NDK provides Vulkan headers + libvulkan stub, but NOT glslc (the
    # GLSL->SPIR-V shader compiler ggml's Vulkan backend compiles at build
    # time). CI installs glslc + the fresh Vulkan-Headers beforehand; when
    # either is missing the Vulkan backend is simply not built (CPU variants
    # remain) — never a broken build. OFFLINE_VULKAN=ON pre-empts the probe.
    # The resulting libggml-vulkan.so ships in the APK and is dlopen'd by
    # ggml's registry AT RUNTIME, only when a Vulkan driver is present.
    if(ANDROID_ABI STREQUAL "arm64-v8a" AND NOT OFFLINE_VULKAN)
        find_program(NEURON_GLSLC glslc)
        if(NEURON_GLSLC)
            unset(ENV{VULKAN_SDK}) # no host SDK — NDK + system glslc only
            set(GGML_VULKAN ON CACHE BOOL "" FORCE)
            # Cross-compiling, the NDK toolchain roots find_package to the NDK
            # sysroot (FIND_ROOT_PATH_MODE_PACKAGE=ONLY) — the host's
            # /usr/local, where CI installs Vulkan-Headers + SPIRV-Headers
            # (see android-ci.yml), is invisible until added as a find root.
            # Env-var overrides remain for local builds.
            list(APPEND CMAKE_FIND_ROOT_PATH "/usr/local")
            if(DEFINED ENV{SPIRV_HEADERS_DIR})
                list(APPEND CMAKE_FIND_ROOT_PATH "$ENV{SPIRV_HEADERS_DIR}")
            endif()
            if(DEFINED ENV{VULKAN_HEADERS_DIR} AND EXISTS "$ENV{VULKAN_HEADERS_DIR}/include/vulkan/vulkan.h")
                list(APPEND CMAKE_PREFIX_PATH "$ENV{VULKAN_HEADERS_DIR}")
            endif()
        else()
            message(WARNING "glslc not found — Vulkan GPU offload disabled, CPU-only build")
        endif()
    endif()

    add_subdirectory("${LLAMA_SRC}" llama.cpp-build EXCLUDE_FROM_ALL)
    if(TARGET ggml-vulkan)
        set(NEURON_HAVE_VULKAN ON)
        message(STATUS "Neuron: Vulkan backend ENABLED")
    else()
        message(STATUS "Neuron: Vulkan backend absent — CPU-only build")
    endif()
endif()

# ---------------------------------------------------------------------------
# JNI bridge: the ONLY native surface Kotlin touches. Loads GGUF weights via
# llama.cpp's model-loading path — never executes anything from model files.
# ---------------------------------------------------------------------------
add_library(neuron_llama SHARED neuron_llama.cpp)

if(NEURON_HAVE_LLAMA)
    # Link only `llama`: the `common` helper lib is tied to examples and not
    # needed — the bridge speaks the raw llama.h API. Backends (CPU variants,
    # Vulkan) are NOT linked — they are dlopen'd by ggml's registry from the
    # app's native lib dir at runtime (GGML_BACKEND_DL above).
    target_link_libraries(neuron_llama PRIVATE llama)
    target_compile_definitions(neuron_llama PRIVATE
        NEURON_HAVE_LLAMA=1
        NEURON_HAVE_VULKAN=$<BOOL:${NEURON_HAVE_VULKAN}>
    )
else()
    # No llama.cpp available: the bridge compiles to a stub that reports
    # "engine unavailable" at runtime instead of breaking the whole build.
    target_compile_definitions(neuron_llama PRIVATE
        NEURON_HAVE_LLAMA=0
        NEURON_HAVE_VULKAN=0
    )
endif()
