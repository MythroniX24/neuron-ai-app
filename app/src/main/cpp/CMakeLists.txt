cmake_minimum_required(VERSION 3.22)
project(neuron-native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Performance defaults (milestone 5): the JNI bridge itself is trivial, the
# heavy lifting is llama.cpp/ggml — what matters here is how THOSE are built.
#   - Release -O3 with inlining; debug native builds are 3-10x slower.
#   - ARM64: enable +dotprod,+i8mm,+fp16 (SD835-era 2017+ support these);
#     ggml's runtime ggml_cpu_has_*() checks dispatch the kernels only when
#     the device actually has them. Runtime guards compile OUT the fast paths
#     only when the flags are absent, never at runtime — the guards are
#     branch-per-op, negligible vs tensor math.
#   - x86_64 emulator builds: baseline x86-64 (SIMD via SSE4.2 defaults).
# ---------------------------------------------------------------------------
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON) # LTO
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang" AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(NEURON_ARM_FLAGS "-march=armv8.2-a+dotprod+i8mm+fp16")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${NEURON_ARM_FLAGS} -O3")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${NEURON_ARM_FLAGS} -O3")
endif()

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
    # ggml's runtime feature dispatch (ggml_cpu_has_dotprod/i8mm/fp16) picks
    # the best compiled kernels per device at load time.
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    endif()

    # ---- GPU: Vulkan backend (milestone 5) --------------------------------
    # The NDK provides Vulkan headers + libvulkan stub, but NOT glslc (the
    # GLSL->SPIR-V shader compiler ggml's Vulkan backend compiles at build
    # time). CI installs glslc + Vulkan-Headers beforehand; when either is
    # missing (or the backend fails to configure) we fall back to CPU with a
    # warning — never a broken build. OFFLINE_VULKAN=ON pre-empts the probe
    # (escape hatch for air-gapped configure runs).
    if(ANDROID_ABI STREQUAL "arm64-v8a" AND NOT OFFLINE_VULKAN)
        find_program(NEURON_GLSLC glslc)
        if(NEURON_GLSLC)
            unset(ENV{VULKAN_SDK}) # no host SDK — NDK + system glslc only
            set(GGML_VULKAN ON CACHE BOOL "" FORCE)
            # glslc 2023.8 (Ubuntu noble) lacks the GL_EXT_integer_dot_product
            # probe target; building from KhronosGroup/Vulkan-Headers source
            # (which CI vendors at $VULKAN_HEADERS_DIR) makes the probe pass
            # and the dot-product shaders compile.
            if(DEFINED ENV{VULKAN_HEADERS_DIR} AND EXISTS "$ENV{VULKAN_HEADERS_DIR}/include/vulkan/vulkan.h")
                list(APPEND CMAKE_PREFIX_PATH "$ENV{VULKAN_HEADERS_DIR}")
            endif()
            # ggml's ggml-vulkan/CMakeLists.txt does find_package(SPIRV-Headers
            # CONFIG REQUIRED) — point it at the CI-installed config package.
            if(DEFINED ENV{SPIRV_HEADERS_DIR} AND EXISTS "$ENV{SPIRV_HEADERS_DIR}/share/cmake/SPIRV-Headers")
                list(APPEND CMAKE_PREFIX_PATH "$ENV{SPIRV_HEADERS_DIR}")
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
    # needed — the bridge speaks the raw llama.h API.
    target_link_libraries(neuron_llama PRIVATE llama)
    if(NEURON_HAVE_VULKAN)
        target_link_libraries(neuron_llama PRIVATE ggml-vulkan)
    endif()
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
