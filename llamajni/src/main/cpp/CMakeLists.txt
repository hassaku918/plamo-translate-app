cmake_minimum_required(VERSION 3.22.1)
project("llamajni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is cloned next to the Android project root by CI / local setup:
#   <repo>/llama.cpp
#   <repo>/llamajni/...
set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp")
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. Clone ggml-org/llama.cpp into the repo root.")
endif()

# --- Pin llama.cpp to a fixed commit (reproducible builds) -----------------
# CI clones llama.cpp master, and upstream API changes have already broken
# this build once (removed use_mmap/use_mlock fields). The workflow file
# cannot pin the clone from here, so the pin is enforced at configure time:
# if the checkout is a git repo and HEAD differs from the pinned commit,
# fetch and check out the pinned commit BEFORE add_subdirectory() below.
# Pinned commit: master HEAD used by the last known-good CI build
# (run 42393945460, 2026-11-07) and still master HEAD when pinned.
# Bump deliberately after verifying a newer llama.cpp builds and loads.
set(LLAMA_CPP_PINNED_COMMIT "e95426f16982054c379ca7bdc8186996dd22f0fe")
if(EXISTS "${LLAMA_SRC}/.git")
    execute_process(
        COMMAND git -C "${LLAMA_SRC}" rev-parse HEAD
        OUTPUT_VARIABLE _llama_head
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET)
    if(NOT _llama_head STREQUAL LLAMA_CPP_PINNED_COMMIT)
        message(STATUS "llama.cpp: HEAD ${_llama_head} != pinned ${LLAMA_CPP_PINNED_COMMIT}; checking out pinned commit")
        execute_process(
            COMMAND git -C "${LLAMA_SRC}" fetch --depth 1 origin "${LLAMA_CPP_PINNED_COMMIT}"
            RESULT_VARIABLE _fetch_rc)
        if(NOT _fetch_rc EQUAL 0)
            # Fallback for clones where fetching an arbitrary SHA is refused
            execute_process(
                COMMAND git -C "${LLAMA_SRC}" fetch --unshallow origin
                RESULT_VARIABLE _fetch_rc)
        endif()
        if(NOT _fetch_rc EQUAL 0)
            message(FATAL_ERROR "llama.cpp: failed to fetch pinned commit ${LLAMA_CPP_PINNED_COMMIT}")
        endif()
        execute_process(
            COMMAND git -C "${LLAMA_SRC}" checkout --detach "${LLAMA_CPP_PINNED_COMMIT}"
            RESULT_VARIABLE _checkout_rc)
        if(NOT _checkout_rc EQUAL 0)
            message(FATAL_ERROR "llama.cpp: failed to check out pinned commit ${LLAMA_CPP_PINNED_COMMIT}")
        endif()
        execute_process(
            COMMAND git -C "${LLAMA_SRC}" rev-parse HEAD
            OUTPUT_VARIABLE _llama_head_after
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET)
        if(NOT _llama_head_after STREQUAL LLAMA_CPP_PINNED_COMMIT)
            message(FATAL_ERROR "llama.cpp: HEAD is ${_llama_head_after}, expected pinned ${LLAMA_CPP_PINNED_COMMIT}")
        endif()
    endif()
    message(STATUS "llama.cpp pinned at ${LLAMA_CPP_PINNED_COMMIT}")
endif()

# Keep the Android build lean
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(llamajni SHARED jni_llama.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
)

target_link_libraries(llamajni
    llama
    android
    log
)
