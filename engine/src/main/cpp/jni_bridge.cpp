// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for eu.schicht8.meavox.engine.NativePlayback. Each engine is an owned native handle.
#include <jni.h>

#include <string>

#include "PlaybackEngine.h"

using meavox::PlaybackEngine;

namespace {

PlaybackEngine &engine(jlong handle) { return *reinterpret_cast<PlaybackEngine *>(handle); }

std::string toString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

// Hands a Java float[] to [use] without copying when the VM allows it.
template <typename Use>
void withSamples(JNIEnv *env, jfloatArray data, Use use) {
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return;
    use(samples);
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_eu_schicht8_meavox_engine_NativePlayback_##name

// Never deleted: lives as long as the process (see PlaybackEngine.h).
JNI_FN(jlong, create)(JNIEnv *, jobject) { return reinterpret_cast<jlong>(new PlaybackEngine()); }

// Headless engines (tests) are freed by their owner: no device stream can call back into them.
JNI_FN(jlong, createHeadless)(JNIEnv *, jobject) { return reinterpret_cast<jlong>(new PlaybackEngine(true)); }

JNI_FN(void, renderHeadless)(JNIEnv *env, jobject, jlong h, jfloatArray out, jint frames) {
    jfloat *samples = env->GetFloatArrayElements(out, nullptr);
    if (!samples) return;
    engine(h).renderHeadless(samples, frames);
    env->ReleaseFloatArrayElements(out, samples, 0);  // copy back
}

JNI_FN(void, destroyHeadless)(JNIEnv *, jobject, jlong h) { delete reinterpret_cast<PlaybackEngine *>(h); }

JNI_FN(void, beginLoad)(JNIEnv *, jobject, jlong h, jint channels, jint sampleRate, jlong estimatedFrames) {
    engine(h).beginLoad(channels, sampleRate, estimatedFrames);
}

JNI_FN(jboolean, appendLoad)(JNIEnv *env, jobject, jlong h, jfloatArray data, jint frames) {
    bool fits = false;
    withSamples(env, data, [&](const jfloat *samples) { fits = engine(h).appendLoad(samples, frames); });
    return fits ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, endLoad)(JNIEnv *, jobject, jlong h) { engine(h).endLoad(); }

JNI_FN(void, discardLoad)(JNIEnv *, jobject, jlong h) { engine(h).discardLoad(); }

JNI_FN(jboolean, play)(JNIEnv *, jobject, jlong h) { return engine(h).play() ? JNI_TRUE : JNI_FALSE; }

JNI_FN(void, pause)(JNIEnv *, jobject, jlong h) { engine(h).pause(); }

JNI_FN(void, seekTo)(JNIEnv *, jobject, jlong h, jlong frame) { engine(h).seekTo(frame); }

JNI_FN(void, setTranspose)(JNIEnv *, jobject, jlong h, jint semitones) { engine(h).setTranspose(semitones); }

JNI_FN(void, setPracticeLoop)(JNIEnv *, jobject, jlong h, jlong startMs, jlong endMs) {
    engine(h).setPracticeLoop(startMs, endMs);
}

JNI_FN(void, clearPracticeLoop)(JNIEnv *, jobject, jlong h) { engine(h).clearPracticeLoop(); }

JNI_FN(jint, transport)(JNIEnv *, jobject, jlong h) { return static_cast<jint>(engine(h).transport()); }

JNI_FN(jboolean, isPlaying)(JNIEnv *, jobject, jlong h) { return engine(h).isPlaying() ? JNI_TRUE : JNI_FALSE; }

JNI_FN(jlong, positionFrames)(JNIEnv *, jobject, jlong h) { return engine(h).positionFrames(); }

JNI_FN(jlong, durationFrames)(JNIEnv *, jobject, jlong h) { return engine(h).durationFrames(); }

JNI_FN(jlong, decodedFrames)(JNIEnv *, jobject, jlong h) { return engine(h).decodedFrames(); }

JNI_FN(jint, sampleRate)(JNIEnv *, jobject, jlong h) { return engine(h).sampleRate(); }

// --- Session ---

JNI_FN(void, setSessionMode)(JNIEnv *, jobject, jlong h, jboolean enabled) { engine(h).setSessionMode(enabled); }

JNI_FN(jboolean, enableInput)(JNIEnv *, jobject, jlong h) { return engine(h).enableInput() ? JNI_TRUE : JNI_FALSE; }

JNI_FN(jboolean, inputEnabled)(JNIEnv *, jobject, jlong h) { return engine(h).inputEnabled() ? JNI_TRUE : JNI_FALSE; }

JNI_FN(void, setInputGain)(JNIEnv *, jobject, jlong h, jfloat linear) { engine(h).setInputGain(linear); }

JNI_FN(void, setReviewBackingGain)(JNIEnv *, jobject, jlong h, jfloat linear) { engine(h).setReviewBackingGain(linear); }

JNI_FN(void, setTakeGain)(JNIEnv *, jobject, jlong h, jfloat linear) { engine(h).setTakeGain(linear); }

JNI_FN(void, setTakeAlignment)(JNIEnv *, jobject, jlong h, jlong alignmentFrames) { engine(h).setTakeAlignment(alignmentFrames); }

JNI_FN(jfloat, inputPeak)(JNIEnv *, jobject, jlong h) { return engine(h).inputPeak(); }

JNI_FN(jlong, clipCount)(JNIEnv *, jobject, jlong h) { return engine(h).clipCount(); }

JNI_FN(jint, outputDeviceId)(JNIEnv *, jobject, jlong h) { return engine(h).outputDeviceId(); }

JNI_FN(jint, inputDeviceId)(JNIEnv *, jobject, jlong h) { return engine(h).inputDeviceId(); }

JNI_FN(jboolean, startRecording)(JNIEnv *, jobject, jlong h, jlong fromFrame, jint transpose) {
    return engine(h).startRecording(fromFrame, transpose) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, stopRecording)(JNIEnv *, jobject, jlong h) { engine(h).stopRecording(); }

JNI_FN(jlong, takeStartFrame)(JNIEnv *, jobject, jlong h) { return engine(h).takeStartFrame(); }

JNI_FN(jlong, takeFrames)(JNIEnv *, jobject, jlong h) { return engine(h).takeFrames(); }

JNI_FN(jlong, takeReportedLatencyFrames)(JNIEnv *, jobject, jlong h) { return engine(h).takeReportedLatencyFrames(); }

JNI_FN(jlong, takeXRunCorrectionFrames)(JNIEnv *, jobject, jlong h) { return engine(h).takeXRunCorrectionFrames(); }

JNI_FN(jboolean, playTake)(JNIEnv *, jobject, jlong h, jlong alignmentFrames, jint transpose) {
    return engine(h).playTake(alignmentFrames, transpose) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, clearTake)(JNIEnv *, jobject, jlong h) { engine(h).clearTake(); }

JNI_FN(void, setVocalEffects)(JNIEnv *, jobject, jlong h, jint reverb, jboolean compression, jboolean highPass,
                              jlong tailFrames) {
    engine(h).setVocalEffects({static_cast<meavox::Reverb>(reverb), compression == JNI_TRUE, highPass == JNI_TRUE}, tailFrames);
}

JNI_FN(jboolean, beginGuideLoad)(JNIEnv *, jobject, jlong h, jint channels, jint sampleRate) {
    return engine(h).beginGuideLoad(channels, sampleRate) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jboolean, appendGuideLoad)(JNIEnv *env, jobject, jlong h, jfloatArray data, jint frames) {
    bool fits = false;
    withSamples(env, data, [&](const jfloat *samples) { fits = engine(h).appendGuideLoad(samples, frames); });
    return fits ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, clearGuide)(JNIEnv *, jobject, jlong h) { engine(h).clearGuide(); }

JNI_FN(void, setGuideLevel)(JNIEnv *, jobject, jlong h, jfloat level) { engine(h).setGuideLevel(level); }

JNI_FN(jlong, guideDecodedFrames)(JNIEnv *, jobject, jlong h) { return engine(h).guideDecodedFrames(); }

JNI_FN(jboolean, beginTakeLoad)(JNIEnv *, jobject, jlong h, jlong startFrame, jint sampleRate, jlong estimatedFrames) {
    return engine(h).beginTakeLoad(startFrame, sampleRate, estimatedFrames) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, appendTakeLoad)(JNIEnv *env, jobject, jlong h, jfloatArray data, jint frames, jint channels) {
    withSamples(env, data, [&](const jfloat *samples) { engine(h).appendTakeLoad(samples, frames, channels); });
}

JNI_FN(jboolean, writeTakeFlac)(JNIEnv *env, jobject, jlong h, jstring path) {
    return engine(h).writeTakeFlac(toString(env, path)) ? JNI_TRUE : JNI_FALSE;
}
