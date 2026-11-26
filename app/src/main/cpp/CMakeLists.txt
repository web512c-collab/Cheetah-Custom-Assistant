cmake_minimum_required(VERSION 3.22.1)
project(cheetah_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_CONNECT OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b12732
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(cheetah_llama SHARED
    cheetah_llama_jni.cpp
)

target_link_libraries(cheetah_llama
    PRIVATE
    llama
    ggml
    android
    log
)

target_compile_options(cheetah_llama PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(cheetah_llama PRIVATE -Wl,--gc-sections)
