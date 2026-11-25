// SPDX-License-Identifier: GPL-3.0-or-later
//
// Offline Render (#14, #24, spec #1): Backing Audio plus a Take (a Mix) or the Backing Audio alone
// (a Backing Export) into PCM, through the same BackingRenderer and mixTake() as real-time playback
// (Review, the Mix Preview, a Session at a Transpose). The output is timeline frames [from, to) as
// interleaved stereo float, or every frame of the Backing Audio with to = kUntilEnd.
//
// A Mix's Vocal Effects (#16) go on the Take; its Guide Vocal (a Vocal Stem, fed in step with the
// Backing Audio) is added to the Backing Audio before it is transposed, as in the Mix Preview.
//
// A Mix's sum goes through the true-peak limiter the Mix Preview shares (#44). Its look-ahead delays
// the audio, so the render runs on that many frames past [to] and drops as many from its start: the
// output stays aligned with the timeline. A Backing Export isn't limited: it is the Backing Audio as it is.
//
// Streaming: the Take is held whole (mono), but the Backing Audio is fed in order from frame 0 and
// only a short window of it is kept, so a Session holding the same song in memory isn't doubled.
//
// No platform dependencies, so it can be built and checked on a host as well. Not thread-safe.
#pragma once

#include <cstdint>
#include <vector>

#include "BackingRenderer.h"
#include "Limiter.h"

namespace meavox {

class OfflineMixRender {
public:
    // Take sample j sounds at timeline frame takeStart + j − alignment; gains are linear. With [to]
    // at kUntilEnd it renders until the Backing Audio ends (a Backing Export).
    static constexpr int64_t kUntilEnd = INT64_MAX;

    // With [limit], the sum goes through the true-peak limiter (a Mix; not a Backing Export).
    OfflineMixRender(int32_t sampleRate, int64_t from, int64_t to, int64_t takeStart, int64_t alignment, int transpose,
                     float backingGain, float takeGain, VocalEffects effects = {}, float guideLevel = 0.0f,
                     bool limit = false);

    // Appends Guide Vocal audio (stereo; mono to both), in order from timeline frame 0. Each Backing
    // Audio frame is added to the Guide Vocal frame of the same index, so feed it first.
    void appendGuide(const float *interleaved, int32_t frames, int32_t channels);

    // Appends Take audio (the first channel of each frame).
    void appendTake(const float *interleaved, int32_t frames, int32_t channels);

    // Appends Backing Audio (1..n channels; mono to both, beyond two the first two), in order from
    // timeline frame 0, and renders as far as it allows.
    void appendBacking(const float *interleaved, int32_t frames, int32_t channels);

    // No more Backing Audio comes: what follows its end is silence. Renders the rest.
    void endBacking();

    // Moves up to [maxFrames] rendered frames into [out]; returns how many.
    int32_t drain(float *out, int32_t maxFrames);

    // Every frame up to [to] is rendered (it may still need draining).
    bool rendered() const { return mPos >= renderTo(); }

    // More Backing Audio must be fed to render the rest.
    bool needsBacking() const { return !rendered() && !mBackingEnded; }

private:
    static constexpr int32_t kRenderFrames = 1024;

    // The window of Backing Audio kept: interleaved stereo frames from mWindowStart.
    class Window : public StereoFrames {
    public:
        const float *at(int64_t frame, int64_t &contiguous) const override {
            contiguous = end() - frame;
            return frames.data() + static_cast<size_t>(frame - start) * 2;
        }
        int64_t end() const { return start + static_cast<int64_t>(frames.size() / 2); }
        void dropBefore(int64_t frame);

        int64_t start = 0;
        std::vector<float> frames;
    };

    void renderAvailable();

    // Where rendering stops: [to], and the limiter's latency on, which is dropped from the start.
    int64_t renderTo() const { return mLimit && mTo != kUntilEnd ? mTo + mLimiter.latency() : mTo; }

    BackingRenderer mBacking;
    VocalChain mEffects;
    TruePeakLimiter mLimiter;
    const bool mLimit;
    int64_t mSkip = 0;  // limited frames still to drop: the limiter's latency, at the start
    Window mWindow;
    const float mGuideLevel;
    std::vector<float> mGuide;  // interleaved stereo Guide Vocal frames fed ahead of the Backing Audio
    size_t mGuideRead = 0;      // samples of mGuide already added
    int64_t mFed = 0;  // Backing Audio frames received, from timeline frame 0
    bool mBackingEnded = false;
    bool mStarted = false;

    int64_t mTo;
    const int64_t mTakeStart;
    const int64_t mAlignment;
    const int mTranspose;
    const float mBackingGain;
    const float mTakeGain;
    int64_t mPos;

    std::vector<float> mTake;
    std::vector<float> mOutput;  // interleaved stereo, rendered and not yet drained
    size_t mOutputRead = 0;      // samples of mOutput already drained
};

}  // namespace meavox
