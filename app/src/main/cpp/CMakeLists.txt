cmake_minimum_required(VERSION 3.22.1)
project(vocal_world LANGUAGES CXX)

include(FetchContent)

# Pin WORLD to an exact upstream commit so APK builds are reproducible.
FetchContent_Declare(
    world_source
    GIT_REPOSITORY https://github.com/mmorise/World.git
    GIT_TAG d625e7608ca23a870018f01e7c562ac683d9847f
)
FetchContent_GetProperties(world_source)
if(NOT world_source_POPULATED)
    FetchContent_Populate(world_source)
endif()

# miniaudio is used only as a resilient import fallback. It has built-in
# decoding for WAV, FLAC and MP3 and supports Android with no external runtime
# dependencies. Pin the source to v0.11.25's exact commit.
FetchContent_Declare(
    miniaudio_source
    GIT_REPOSITORY https://github.com/mackron/miniaudio.git
    GIT_TAG 9634bedb5b5a2ca38c1ee7108a9358a4e233f14d
)
FetchContent_GetProperties(miniaudio_source)
if(NOT miniaudio_source_POPULATED)
    FetchContent_Populate(miniaudio_source)
endif()

add_library(world_core STATIC
    ${world_source_SOURCE_DIR}/src/cheaptrick.cpp
    ${world_source_SOURCE_DIR}/src/common.cpp
    ${world_source_SOURCE_DIR}/src/d4c.cpp
    ${world_source_SOURCE_DIR}/src/dio.cpp
    ${world_source_SOURCE_DIR}/src/fft.cpp
    ${world_source_SOURCE_DIR}/src/matlabfunctions.cpp
    ${world_source_SOURCE_DIR}/src/stonemask.cpp
    ${world_source_SOURCE_DIR}/src/synthesis.cpp
)

target_include_directories(world_core PUBLIC ${world_source_SOURCE_DIR}/src)
target_compile_features(world_core PUBLIC cxx_std_17)
set_target_properties(world_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(vocal_world SHARED
    native_vocoder.cpp
    native_audio_decoder.cpp
)
target_include_directories(vocal_world PRIVATE ${miniaudio_source_SOURCE_DIR})
target_compile_features(vocal_world PRIVATE cxx_std_17)
target_compile_options(vocal_world PRIVATE -O3 -ffast-math)
target_link_libraries(vocal_world PRIVATE world_core log)
