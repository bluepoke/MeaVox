// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for eu.schicht8.meavox.engine.NativeLoudness: one owned IntegratedLoudness per handle (#44).
#include <jni.h>

#include "Loudness.h"

using meavox::IntegratedLoudness;

namespace {
IntegratedLoudness &meter(jlong handle) { return *reinterpret_cast<IntegratedLoudness *>(handle); }
}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_eu_schicht8_meavox_engine_NativeLoudness_##name

JNI_FN(jlong, create)(JNIEnv *, jobject, jint sampleRate) { return reinterpret_cast<jlong>(new IntegratedLoudness(sampleRate)); }

JNI_FN(void, add)(JNIEnv *env, jobject, jlong h, jfloatArray data, jint offsetFrames, jint frames, jint channels) {
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return;
    meter(h).add(samples + static_cast<size_t>(offsetFrames) * static_cast<size_t>(channels), frames, channels);
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
}

JNI_FN(jdouble, integrated)(JNIEnv *, jobject, jlong h) { return meter(h).integrated(); }

JNI_FN(void, release)(JNIEnv *, jobject, jlong h) { delete reinterpret_cast<IntegratedLoudness *>(h); }
