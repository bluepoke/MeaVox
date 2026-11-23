# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.24)
project(meavox_engine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Signalsmith Stretch: real-time Transpose (ADR 5).
FetchContent_Declare(
        signalsmith-stretch
        GIT_REPOSITORY https://github.com/Signalsmith-Audio/signalsmith-stretch.git
        GIT_TAG 1.4.0
        GIT_SHALLOW ON
)
FetchContent_MakeAvailable(signalsmith-stretch)

# libFLAC (BSD-3-Clause): Takes (mono) and stems (stereo) are stored as FLAC (ADR 8). Only the C encoder/decoder
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
FetchContent_Declare(
        flac
        GIT_REPOSITORY https://github.com/xiph/flac.git
        GIT_TAG 1.4.3
        GIT_SHALLOW ON
)
FetchContent_MakeAvailable(flac)

# Oboe via prefab: low-latency full-duplex audio (ADR 4).
find_package(oboe REQUIRED CONFIG)

add_library(meavox_engine SHARED
        BackingRenderer.cpp
        PlaybackEngine.cpp
        OfflineMixRender.cpp
        VocalEffects.cpp
        jni_bridge.cpp
        mix_render_jni.cpp
        flac_files.cpp
)

target_link_libraries(meavox_engine PRIVATE oboe::oboe signalsmith-stretch FLAC log android)

# Signalsmith Stretch is ~10x slower unoptimised; keep DSP optimised even in debug builds.
target_compile_options(meavox_engine PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
