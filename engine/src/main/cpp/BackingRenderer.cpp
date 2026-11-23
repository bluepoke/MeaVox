// SPDX-License-Identifier: GPL-3.0-or-later
#include "BackingRenderer.h"

#include <algorithm>

namespace meavox {

namespace {
constexpr float kTonalityLimitHz = 8000.0f;  // Transpose: keep tonal treatment below this (as in spike #2)

// Planar stereo for Signalsmith Stretch: channel c starts at data + c * stride.
struct Planar {
    float *data;
    int32_t stride;
    float *operator[](int c) const { return data + c * stride; }
};
}  // namespace

int32_t BackingRenderer::configure(int32_t sampleRate) {
    mApplied = 0;
    mCut = false;
    if (sampleRate <= 0 || sampleRate == mRate) return mLead;
    mStretch.presetCheaper(2, static_cast<float>(sampleRate), true);
    mLead = mStretch.outputSeekLength(1.0f);
    mIn.assign(static_cast<size_t>(std::max(mLead, kChunk)) * 2, 0.0f);
    mOut.assign(static_cast<size_t>(kChunk) * 2, 0.0f);
    mRate = sampleRate;
    return mLead;
}

void BackingRenderer::copyDirect(const StereoFrames &source, int64_t pos, int64_t decoded, float *out, int32_t numFrames,
                                 int32_t &written) const {
    // Copy what is decoded; past that, output silence and wait (still decoding).
    while (written < numFrames && pos < decoded) {
        int64_t contiguous = 0;
        const float *src = source.at(pos, contiguous);
        const auto n = static_cast<int32_t>(std::min<int64_t>({numFrames - written, decoded - pos, contiguous}));
        std::copy_n(src, static_cast<size_t>(n) * 2, out + static_cast<size_t>(written) * 2);
        written += n;
        pos += n;
    }
}

void BackingRenderer::readPlanar(const StereoFrames &source, int64_t from, int64_t decoded, int32_t frames, float *left,
                                 float *right) const {
    int32_t i = 0;
    while (i < frames) {
        const int64_t f = from + i;
        if (f >= decoded) {  // past the decoded audio (or the end): silence
            std::fill(left + i, left + frames, 0.0f);
            std::fill(right + i, right + frames, 0.0f);
            return;
        }
        int64_t contiguous = 0;
        const float *src = source.at(f, contiguous);
        const auto n = static_cast<int32_t>(std::min<int64_t>({frames - i, decoded - f, contiguous}));
        for (int32_t k = 0; k < n; ++k) {
            left[i + k] = src[2 * k];
            right[i + k] = src[2 * k + 1];
        }
        i += n;
    }
}

void BackingRenderer::setStretchTranspose(int semitones) {
    mStretch.setTransposeSemitones(static_cast<float>(semitones), kTonalityLimitHz / static_cast<float>(mRate));
}

// Restarts the stretcher at [pos]: the next process() output is timeline frame pos, pitch-shifted.
void BackingRenderer::prime(const StereoFrames &source, int64_t pos, int64_t decoded, int transpose) {
    setStretchTranspose(transpose);
    const auto stride = static_cast<int32_t>(mIn.size() / 2);
    readPlanar(source, pos, decoded, mLead, mIn.data(), mIn.data() + stride);
    mStretch.outputSeek(Planar{mIn.data(), stride}, mLead);
}

// Stretched Backing Audio for timeline frames pos…pos+numFrames, mixed over [out]: replacing it,
// or crossfading from what [out] holds (fadeIn) or to it (fadeOut) across the call.
void BackingRenderer::mixStretched(const StereoFrames &source, int64_t pos, int64_t decoded, float *out, int32_t numFrames,
                                   bool fadeIn, bool fadeOut) {
    const auto inStride = static_cast<int32_t>(mIn.size() / 2);
    float *outL = mOut.data();
    float *outR = mOut.data() + kChunk;
    for (int32_t done = 0; done < numFrames;) {
        const int32_t n = std::min(kChunk, numFrames - done);
        // The stretcher's input runs mLead ahead of the timeline position it outputs.
        readPlanar(source, pos + done + mLead, decoded, n, mIn.data(), mIn.data() + inStride);
        mStretch.process(Planar{mIn.data(), inStride}, n, Planar{mOut.data(), kChunk}, n);
        for (int32_t k = 0; k < n; ++k) {
            const int32_t i = done + k;
            const float ramp = static_cast<float>(i + 1) / static_cast<float>(numFrames);
            const float w = fadeIn ? ramp : fadeOut ? 1.0f - ramp : 1.0f;
            out[2 * i] = out[2 * i] * (1.0f - w) + outL[k] * w;
            out[2 * i + 1] = out[2 * i + 1] * (1.0f - w) + outR[k] * w;
        }
        done += n;
    }
}

int32_t BackingRenderer::render(const StereoFrames &source, int64_t pos, int64_t decoded, bool complete, bool seeked,
                                int transpose, float *out, int32_t numFrames) {
    if (mRate <= 0) transpose = 0;
    transpose = std::clamp(transpose, -kMaxTranspose, kMaxTranspose);
    // A jump in the timeline (seek, transport start) cuts over: the stretcher starts afresh there.
    if (seeked) mCut = true;
    const bool cut = mCut;
    const int from = cut ? 0 : mApplied;
    int32_t written = 0;
    if (from == 0 && transpose == 0) {
        mApplied = 0;
        mCut = false;
        copyDirect(source, pos, decoded, out, numFrames, written);
        return written;
    }
    // Stretching reads ahead of the position; until that is decoded, wait (silence) like plain playback.
    // Take playback runs on meanwhile, so the stretcher starts afresh once the audio is there.
    if (!complete && pos + mLead + numFrames > decoded) {
        mCut = true;
        return 0;
    }
    mCut = false;
    written = complete ? static_cast<int32_t>(std::clamp<int64_t>(decoded - pos, 0, numFrames)) : numFrames;
    if (from == 0) {
        // Leaving the bypass: crossfade in from the direct audio, unless this is a cut.
        prime(source, pos, decoded, transpose);
        int32_t direct = 0;
        if (!cut) copyDirect(source, pos, decoded, out, numFrames, direct);
        mixStretched(source, pos, decoded, out, numFrames, !cut, false);
    } else if (transpose == 0) {
        // Back to the bypass: crossfade out to the direct audio.
        int32_t direct = 0;
        copyDirect(source, pos, decoded, out, numFrames, direct);
        mixStretched(source, pos, decoded, out, numFrames, false, true);
    } else {
        // From one Transpose to another: the stretcher changes live without a glitch (spike #2).
        if (transpose != from) setStretchTranspose(transpose);
        mixStretched(source, pos, decoded, out, numFrames, false, false);
    }
    mApplied = transpose;
    std::fill(out + static_cast<size_t>(written) * 2, out + static_cast<size_t>(numFrames) * 2, 0.0f);  // past the end
    return written;
}

void mixTake(float *out, int32_t numFrames, int64_t firstFrame, int64_t takeStart, int64_t alignment, const float *take,
             int64_t takeFrames, float backingGain, float takeGain, VocalChain *effects) {
    if (effects && effects->effects().any()) {
        for (int32_t i = 0; i < numFrames; ++i) {
            const int64_t j = firstFrame + i - takeStart + alignment;
            float left = 0.0f;
            float right = 0.0f;
            effects->process((j >= 0 && j < takeFrames) ? take[j] : 0.0f, left, right);
            out[2 * i] = out[2 * i] * backingGain + left * takeGain;
            out[2 * i + 1] = out[2 * i + 1] * backingGain + right * takeGain;
        }
        return;
    }
    for (int32_t i = 0; i < numFrames; ++i) {
        const int64_t j = firstFrame + i - takeStart + alignment;
        const float v = (j >= 0 && j < takeFrames) ? take[j] * takeGain : 0.0f;
        out[2 * i] = out[2 * i] * backingGain + v;
        out[2 * i + 1] = out[2 * i + 1] * backingGain + v;
    }
}

void clipOutput(float *out, int32_t numFrames) {
    for (int32_t i = 0; i < numFrames * 2; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);
}

}  // namespace meavox
