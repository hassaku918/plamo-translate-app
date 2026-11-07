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
