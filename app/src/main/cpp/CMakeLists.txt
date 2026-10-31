cmake_minimum_required(VERSION 3.22.1)
project(airecord_jni C CXX)

# whisper is unusably slow without optimisation, so always build it optimised,
# even for the debug variant of the app.
set(CMAKE_BUILD_TYPE Release)
add_compile_options(-O3)

set(WHISPER_VERSION "v1.7.5" CACHE STRING "whisper.cpp git tag")
option(AIRECORD_VULKAN "Build whisper.cpp with the ggml Vulkan (GPU) backend" OFF)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ${AIRECORD_VULKAN} CACHE BOOL "" FORCE)

include(FetchContent)
# Override with -DFETCHCONTENT_SOURCE_DIR_WHISPER=/path/to/whisper.cpp for offline builds.
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/${WHISPER_VERSION}.tar.gz
)
FetchContent_MakeAvailable(whisper)

add_library(airecord_jni SHARED whisper_jni.cpp)
target_link_libraries(airecord_jni PRIVATE whisper android log)
target_link_options(airecord_jni PRIVATE "-Wl,-z,max-page-size=16384")
if(AIRECORD_VULKAN)
    target_compile_definitions(airecord_jni PRIVATE AIRECORD_VULKAN=1)
endif()
