# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.24)
project(meavox_engine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The native dependencies are Git submodules in third_party/ (#21): the build fetches nothing, as
# F-Droid requires. A missing one means `git submodule update --init` wasn't run.
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
foreach(dependency signalsmith-linear signalsmith-stretch flac)
    if(NOT EXISTS ${THIRD_PARTY}/${dependency}/CMakeLists.txt)
        message(FATAL_ERROR "third_party/${dependency} is empty: run `git submodule update --init`")
    endif()
endforeach()
# Anything that would still try to download fails instead.
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)

# Signalsmith Stretch 1.4.0 (MIT): real-time Transpose (ADR 5). Its Signalsmith Linear 0.6.4 (MIT)
# comes first, so Stretch uses it instead of fetching its own.
add_subdirectory(${THIRD_PARTY}/signalsmith-linear ${CMAKE_CURRENT_BINARY_DIR}/signalsmith-linear)
add_subdirectory(${THIRD_PARTY}/signalsmith-stretch ${CMAKE_CURRENT_BINARY_DIR}/signalsmith-stretch)

# libFLAC 1.4.3 (BSD-3-Clause): Takes (mono) and stems (stereo) are stored as FLAC (ADR 8). Only the C encoder/decoder
# library is built, statically, without Ogg, programs, docs or tests.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(BUILD_UTILS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(INSTALL_PKGCONFIG_MODULES OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(WITH_FORTIFY_SOURCE OFF CACHE BOOL "" FORCE)
set(WITH_STACK_PROTECTOR OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/flac ${CMAKE_CURRENT_BINARY_DIR}/flac EXCLUDE_FROM_ALL)

# Oboe via prefab: low-latency full-duplex audio (ADR 4).
find_package(oboe REQUIRED CONFIG)

add_library(meavox_engine SHARED
        BackingRenderer.cpp
        Limiter.cpp
        Loudness.cpp
        PlaybackEngine.cpp
        OfflineMixRender.cpp
        VocalEffects.cpp
        jni_bridge.cpp
        mix_render_jni.cpp
        loudness_jni.cpp
        flac_files.cpp
)

target_link_libraries(meavox_engine PRIVATE oboe::oboe signalsmith-stretch FLAC log android)

# Signalsmith Stretch is ~10x slower unoptimised; keep DSP optimised even in debug builds.
target_compile_options(meavox_engine PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
