cmake_minimum_required(VERSION 3.22.1)
project(jarvis_native LANGUAGES C CXX)
include(FetchContent)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b6096
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(jarvis_llama SHARED jarvis_llama.cpp)
find_library(log-lib log)
target_link_libraries(jarvis_llama PRIVATE llama ${log-lib})
target_compile_features(jarvis_llama PRIVATE cxx_std_17)

# Whisper.cpp is compiled against the same GGML checkout already brought in by llama.cpp.
FetchContent_Declare(whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.6
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(whisper_cpp)
if(NOT whisper_cpp_POPULATED)
    FetchContent_Populate(whisper_cpp)
endif()

add_library(jarvis_whisper SHARED
    ${whisper_cpp_SOURCE_DIR}/src/whisper.cpp
    jarvis_whisper.cpp
)
target_include_directories(jarvis_whisper PRIVATE
    ${whisper_cpp_SOURCE_DIR}/include
    ${whisper_cpp_SOURCE_DIR}/src
    ${whisper_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/ggml/src
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu
)
target_compile_definitions(jarvis_whisper PRIVATE GGML_USE_CPU WHISPER_VERSION="1.7.6")
target_compile_features(jarvis_whisper PRIVATE cxx_std_17)
target_link_libraries(jarvis_whisper PRIVATE ggml ${log-lib} android)
