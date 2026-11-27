# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.24)
project(meavox_export LANGUAGES C)

# LAME 3.100 (LGPL-2.0-or-later): the MP3 encoder (ADR 8). Built as its own shared library, which the JNI library
# links dynamically, so it can be replaced as the LGPL requires. Only the encoder library is built (no frontend, no
# mpglib decoder). Its sources are vendored unmodified from the release tarball in third_party/ (see
# third_party/lame-3.100.md), so the build fetches nothing (#21); the tarball has no CMake build, so they are
# listed here.
set(lame_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/lame-3.100)

add_library(mp3lame SHARED
        ${lame_SOURCE_DIR}/libmp3lame/bitstream.c
        ${lame_SOURCE_DIR}/libmp3lame/encoder.c
        ${lame_SOURCE_DIR}/libmp3lame/fft.c
        ${lame_SOURCE_DIR}/libmp3lame/gain_analysis.c
        ${lame_SOURCE_DIR}/libmp3lame/id3tag.c
        ${lame_SOURCE_DIR}/libmp3lame/lame.c
        ${lame_SOURCE_DIR}/libmp3lame/newmdct.c
        ${lame_SOURCE_DIR}/libmp3lame/presets.c
        ${lame_SOURCE_DIR}/libmp3lame/psymodel.c
        ${lame_SOURCE_DIR}/libmp3lame/quantize.c
        ${lame_SOURCE_DIR}/libmp3lame/quantize_pvt.c
        ${lame_SOURCE_DIR}/libmp3lame/reservoir.c
        ${lame_SOURCE_DIR}/libmp3lame/set_get.c
        ${lame_SOURCE_DIR}/libmp3lame/tables.c
        ${lame_SOURCE_DIR}/libmp3lame/takehiro.c
        ${lame_SOURCE_DIR}/libmp3lame/util.c
        ${lame_SOURCE_DIR}/libmp3lame/vbrquantize.c
        ${lame_SOURCE_DIR}/libmp3lame/VbrTag.c
        ${lame_SOURCE_DIR}/libmp3lame/version.c
)
target_include_directories(mp3lame
        PUBLIC ${lame_SOURCE_DIR}/include
        PRIVATE ${lame_SOURCE_DIR}/libmp3lame
)
# What configure would find on Android; without config.h LAME needs them spelled out.
target_compile_definitions(mp3lame PRIVATE
        STDC_HEADERS HAVE_STDINT_H HAVE_INTTYPES_H HAVE_LIMITS_H HAVE_ERRNO_H HAVE_FCNTL_H HAVE_MEMCPY HAVE_STRCHR
        ieee754_float32_t=float
)
target_compile_options(mp3lame PRIVATE -O2 -w)
target_link_libraries(mp3lame PRIVATE m)

add_library(meavox_mp3 SHARED mp3_encoder_jni.c)
target_link_libraries(meavox_mp3 PRIVATE mp3lame)
target_compile_options(meavox_mp3 PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
