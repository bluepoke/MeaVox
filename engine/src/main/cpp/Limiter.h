// SPDX-License-Identifier: GPL-3.0-or-later
//
// True-peak limiter on the sum of a Mix (#44), shared by the Mix Preview (PlaybackEngine) and the
// Offline Render (OfflineMixRender), so both stay identical (ADR 7). At backing 0 dB a mastered song
// and a loud voice add up beyond full scale; the limiter keeps the true peak at kCeilingDbtp. Under
// heavy limiting the moving gain lets it overshoot by up to about 0.3 dB; no sample goes above the ceiling.
//
// The true peak is estimated per frame from the samples and three points between each and the next
// (4× oversampling, windowed-sinc). The gain each frame needs is held for the look-ahead and
// smoothed with a moving average of the same length, so it has come down by the time the peak goes
// out; it recovers with a release. Below the ceiling the gain is exactly 1: the audio leaves as it
// came, only [latency()] frames later.
//
// No platform dependencies, so it can be built and checked on a host as well. Not thread-safe.
#pragma once

#include <cstdint>
#include <vector>

namespace meavox {

class TruePeakLimiter {
public:
    static constexpr float kCeilingDbtp = -1.0f;

    // Prepares for [sampleRate] and resets.
    void configure(int32_t sampleRate);

    // Forgets the audio held, e.g. when playback jumps: the next frames come out after silence.
    void reset();

    // Limits interleaved stereo [out] in place; each frame comes out [latency()] frames later.
    void process(float *out, int32_t numFrames);

    // Frames the output lags the input.
    int32_t latency() const { return mLatency; }

private:
    static constexpr int kTaps = 12;           // per oversampled point: samples n−5 … n+6
    static constexpr int kTapsAhead = kTaps / 2;
    static constexpr int kPhases = 3;          // points between two samples (4× oversampling)

    float truePeak() const;

    float mCeiling = 1.0f;
    float mRelease = 1.0f;  // per frame, towards the needed gain when it rises
    int32_t mLookAhead = 1;
    int32_t mLatency = 0;
    float mTaps[kPhases][kTaps] = {};

    // The last kTaps input frames per channel, for the true peak (ring, newest at mHistoryAt − 1).
    float mHistory[2][kTaps] = {};
    int mHistoryAt = 0;

    std::vector<float> mNeeded;     // gain needed per frame, the last mLookAhead (ring)
    std::vector<float> mSmoothing;  // released gain per frame, the last mLookAhead (ring)
    double mSmoothingSum = 0;
    int32_t mSmoothingBelow = 0;    // of those, how many are below 1
    int32_t mGainAt = 0;
    float mReleased = 1.0f;

    std::vector<float> mDelay;      // interleaved stereo, mLatency + 1 frames (ring)
    int32_t mDelayAt = 0;
};

}  // namespace meavox
