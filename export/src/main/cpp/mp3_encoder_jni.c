// SPDX-License-Identifier: GPL-3.0-or-later
// MP3 encoding through LAME (ADR 8) for eu.schicht8.meavox.export.Mp3Encoder. LAME is its own shared library
// (libmp3lame.so), linked dynamically for the LGPL. The handle is a lame_t; Kotlin owns the file and writes the
// bytes these calls hand back.
#include <jni.h>
#include <lame.h>
#include <stdint.h>

static lame_t handle(jlong h) { return (lame_t) (intptr_t) h; }

// Returns the encoder, or 0 if LAME rejects the settings. vbr: quality V2; else constant 320 kbps. Both write
// the Xing/LAME tag frame (filled in by nativeLameTag), which tells players the length and the encoder delay.
JNIEXPORT jlong JNICALL
Java_eu_schicht8_meavox_export_Mp3Encoder_nativeOpen(JNIEnv *env, jclass cls, jint sampleRate, jboolean vbr) {
    lame_t lame = lame_init();
    if (lame == NULL) return 0;
    lame_set_in_samplerate(lame, sampleRate);
    lame_set_num_channels(lame, 2);
    lame_set_mode(lame, JOINT_STEREO);
    if (vbr) {
        lame_set_VBR(lame, vbr_default);
        lame_set_VBR_quality(lame, 2.0f);
    } else {
        lame_set_VBR(lame, vbr_off);
        lame_set_brate(lame, 320);
    }
    lame_set_bWriteVbrTag(lame, 1);
    lame_set_write_id3tag_automatic(lame, 0); // the ID3 tag is written in Kotlin
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return 0;
    }
    return (jlong) (intptr_t) lame;
}

// Encodes `frames` interleaved stereo float frames (±1 full scale) into `out`; returns the bytes written there,
// or a negative LAME error.
JNIEXPORT jint JNICALL
Java_eu_schicht8_meavox_export_Mp3Encoder_nativeEncode(JNIEnv *env, jclass cls, jlong h, jfloatArray interleaved,
                                                       jint frames, jbyteArray out) {
    jfloat *pcm = (*env)->GetPrimitiveArrayCritical(env, interleaved, NULL);
    jbyte *mp3 = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    int n = lame_encode_buffer_interleaved_ieee_float(handle(h), pcm, frames, (unsigned char *) mp3,
                                                       (*env)->GetArrayLength(env, out));
    (*env)->ReleasePrimitiveArrayCritical(env, out, mp3, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, interleaved, pcm, JNI_ABORT);
    return n;
}

// Encodes what LAME still holds into `out`; returns the bytes written there, or a negative LAME error.
JNIEXPORT jint JNICALL
Java_eu_schicht8_meavox_export_Mp3Encoder_nativeFlush(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    jbyte *mp3 = (*env)->GetByteArrayElements(env, out, NULL);
    int n = lame_encode_flush(handle(h), (unsigned char *) mp3, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, mp3, 0);
    return n;
}

// The final Xing/LAME tag frame, to be written over the empty one the stream started with; returns its size,
// which is larger than `out` if `out` is too small.
JNIEXPORT jint JNICALL
Java_eu_schicht8_meavox_export_Mp3Encoder_nativeLameTag(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    jbyte *frame = (*env)->GetByteArrayElements(env, out, NULL);
    size_t n = lame_get_lametag_frame(handle(h), (unsigned char *) frame, (size_t) (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, frame, 0);
    return (jint) n;
}

JNIEXPORT void JNICALL
Java_eu_schicht8_meavox_export_Mp3Encoder_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    lame_close(handle(h));
}
