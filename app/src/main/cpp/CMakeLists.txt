cmake_minimum_required(VERSION 3.22)
project(neuron-native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp sources — pinned fetch (fetched at configure time, commit-pinned
# so builds are reproducible; never a moving branch). When the runner has no
# network access the build degrades: the JNI bridge still compiles and the
# Kotlin side reports the engine as unavailable (LocalAiProvider falls back
# to a clear "engine unavailable" error).
# ---------------------------------------------------------------------------
set(LLAMA_PIN "b4488"
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
if(EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    set(NEURON_HAVE_LLAMA ON)
    # CPU-first (prove correctness before optimizing): GGML native multithreaded
    # build; GPU backends come later (milestone 5). Keep the llama build static
    # and quiet.
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    add_subdirectory("${LLAMA_SRC}" llama.cpp-build EXCLUDE_FROM_ALL)
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
    target_compile_definitions(neuron_llama PRIVATE NEURON_HAVE_LLAMA=1)
else()
    # No llama.cpp available: the bridge compiles to a stub that reports
    # "engine unavailable" at runtime instead of breaking the whole build.
    target_compile_definitions(neuron_llama PRIVATE NEURON_HAVE_LLAMA=0)
endif()
