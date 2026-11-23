// SPDX-License-Identifier: GPL-3.0-or-later
//
// The Backing Audio DSP shared by real-time playback (PlaybackEngine) and the Offline Render of a
// Mix (OfflineMixRender), so Review, the Mix Preview and an Export sound the same (spec #1, ADR 7).
//
// Transpose (ADR 5): the Backing Audio goes through Signalsmith Stretch, bypassed at 0 semitones.
// The stretcher reads ahead of the position by its own latency and is primed with outputSeek, so
// its output stays sample-aligned with the timeline: Takes need no extra alignment. Switching to or
// from 0 crossfades over one call.
//
// No platform dependencies, so it can be built and checked on a host as well.
#pragma once

#include <cstdint>
#include <vector>

#include "VocalEffects.h"
#include "signalsmith-stretch/signalsmith-stretch.h"

namespace meavox {

constexpr int kMaxTranspose = 6;  // ADR 5: −6…+6 semitones

// Read access to decoded Backing Audio as interleaved stereo frames.
class StereoFrames {
public:
    virtual ~StereoFrames() = default;
    // Frame [frame] (below what the caller passes as decoded) and how many frames follow it contiguously (≥ 1).
    virtual const float *at(int64_t frame, int64_t &contiguous) const = 0;
};

// Renders Backing Audio for a span of the timeline at a Transpose. Not thread-safe: one caller at a time.
class BackingRenderer {
public:
    // Prepares the stretcher for [sampleRate]; returns its read-ahead in frames. Resets the Transpose state.
    int32_t configure(int32_t sampleRate);

    // Writes the Backing Audio for timeline frames from [pos] into [out] (zeroed, interleaved stereo)
    // at [transpose] and returns how many frames the position advances. [decoded] frames of [source]
    // are readable; once [complete], nothing more comes. [seeked] marks a jump in the timeline (seek,
    // transport start): the stretcher starts afresh there, without a crossfade.
    int32_t render(const StereoFrames &source, int64_t pos, int64_t decoded, bool complete, bool seeked, int transpose,
                   float *out, int32_t numFrames);

    int32_t lead() const { return mLead; }

private:
    static constexpr int kChunk = 1024;  // frames per stretcher call

    void copyDirect(const StereoFrames &source, int64_t pos, int64_t decoded, float *out, int32_t numFrames,
                    int32_t &written) const;
    void readPlanar(const StereoFrames &source, int64_t from, int64_t decoded, int32_t frames, float *left,
                    float *right) const;
    void setStretchTranspose(int semitones);
    void prime(const StereoFrames &source, int64_t pos, int64_t decoded, int transpose);
    void mixStretched(const StereoFrames &source, int64_t pos, int64_t decoded, float *out, int32_t numFrames,
                      bool fadeIn, bool fadeOut);

    signalsmith::stretch::SignalsmithStretch<float> mStretch;
    int32_t mRate = 0;
    int32_t mLead = 0;        // input read ahead of the position, so output lines up with it
    std::vector<float> mIn;   // planar L | R, sized for priming and one chunk
    std::vector<float> mOut;  // planar L | R, one chunk
    int mApplied = 0;         // what the Backing Audio is played at; 0 = stretcher bypassed
    bool mCut = false;        // the next stretched output starts fresh, without a crossfade
};

// Take playback over Backing Audio already in [out] (interleaved stereo, timeline frames from
// [firstFrame]): out = out × backingGain + take × takeGain, where Take sample j sounds at timeline
// frame takeStart + j − alignment. Used by Review, the Mix Preview and the Offline Render. With
// [effects] set to any Vocal Effects, the Take goes through them first, one sample per timeline
// frame (silence outside the Take, so a reverb rings on after it); without, it is mixed as it is.
void mixTake(float *out, int32_t numFrames, int64_t firstFrame, int64_t takeStart, int64_t alignment, const float *take,
             int64_t takeFrames, float backingGain, float takeGain, VocalChain *effects = nullptr);

// Clips interleaved stereo output to full scale.
void clipOutput(float *out, int32_t numFrames);

}  // namespace meavox
