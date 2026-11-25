// SPDX-License-Identifier: GPL-3.0-or-later
#include "Limiter.h"

#include <algorithm>
#include <cmath>

namespace meavox {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kLookAheadSeconds = 0.0015;
constexpr double kReleaseSeconds = 0.05;

double sinc(double x) { return x == 0.0 ? 1.0 : std::sin(kPi * x) / (kPi * x); }
}  // namespace

void TruePeakLimiter::configure(int32_t sampleRate) {
    const double rate = std::max(sampleRate, 1);
    mCeiling = std::pow(10.0f, kCeilingDbtp / 20.0f);
    mRelease = static_cast<float>(1.0 - std::exp(-1.0 / (rate * kReleaseSeconds)));
    mLookAhead = std::max<int32_t>(1, static_cast<int32_t>(std::lround(rate * kLookAheadSeconds)));
    // The true peak of frame n needs kTapsAhead frames after it; the gain, mLookAhead − 1 more.
    mLatency = kTapsAhead + mLookAhead - 1;
    // Blackman-windowed sinc at 1/4, 2/4 and 3/4 of the way to the next sample, unity at DC.
    const double halfWidth = kTapsAhead;
    for (int p = 0; p < kPhases; ++p) {
        const double fraction = (p + 1) / 4.0;
        double sum = 0;
        double taps[kTaps];
        for (int k = 0; k < kTaps; ++k) {
            const double t = (k - (kTapsAhead - 1)) - fraction;  // tap k is sample n − 5 + k
            const double w = 0.42 + 0.5 * std::cos(kPi * t / halfWidth) + 0.08 * std::cos(2 * kPi * t / halfWidth);
            taps[k] = sinc(t) * w;
            sum += taps[k];
        }
        for (int k = 0; k < kTaps; ++k) mTaps[p][k] = static_cast<float>(taps[k] / sum);
    }
    mNeeded.assign(static_cast<size_t>(mLookAhead), 1.0f);
    mSmoothing.assign(static_cast<size_t>(mLookAhead), 1.0f);
    mDelay.assign(static_cast<size_t>(mLatency + 1) * 2, 0.0f);
    reset();
}

void TruePeakLimiter::reset() {
    for (auto &channel : mHistory) std::fill(std::begin(channel), std::end(channel), 0.0f);
    mHistoryAt = 0;
    std::fill(mNeeded.begin(), mNeeded.end(), 1.0f);
    std::fill(mSmoothing.begin(), mSmoothing.end(), 1.0f);
    mSmoothingSum = static_cast<double>(mLookAhead);
    mSmoothingBelow = 0;
    mGainAt = 0;
    mReleased = 1.0f;
    std::fill(mDelay.begin(), mDelay.end(), 0.0f);
    mDelayAt = 0;
}

// The true peak of the frame kTapsAhead before the newest: its samples and the points between it and the next.
float TruePeakLimiter::truePeak() const {
    float peak = 0.0f;
    for (const auto &channel : mHistory) {
        // Oldest first: tap k is the k-th oldest of the last kTaps frames.
        float ordered[kTaps];
        for (int k = 0; k < kTaps; ++k) ordered[k] = channel[(mHistoryAt + k) % kTaps];
        peak = std::max(peak, std::fabs(ordered[kTapsAhead - 1]));
        for (const auto &taps : mTaps) {
            float v = 0.0f;
            for (int k = 0; k < kTaps; ++k) v += taps[k] * ordered[k];
            peak = std::max(peak, std::fabs(v));
        }
    }
    return peak;
}

void TruePeakLimiter::process(float *out, int32_t numFrames) {
    if (mDelay.empty()) return;  // not configured
    const auto delayFrames = static_cast<int32_t>(mDelay.size() / 2);
    for (int32_t i = 0; i < numFrames; ++i) {
        float *frame = out + static_cast<size_t>(i) * 2;
        mHistory[0][mHistoryAt] = frame[0];
        mHistory[1][mHistoryAt] = frame[1];
        mHistoryAt = (mHistoryAt + 1) % kTaps;

        // The gain the frame kTapsAhead back needs, held over the look-ahead, released, then smoothed.
        const float peak = truePeak();
        mNeeded[static_cast<size_t>(mGainAt)] = peak > mCeiling ? mCeiling / peak : 1.0f;
        const float held = *std::min_element(mNeeded.begin(), mNeeded.end());
        mReleased = held < mReleased ? held : mReleased + (held - mReleased) * mRelease;
        if (mReleased > 1.0f - 1e-6f) mReleased = 1.0f;
        float &slot = mSmoothing[static_cast<size_t>(mGainAt)];
        mSmoothingSum += static_cast<double>(mReleased) - slot;
        mSmoothingBelow += (mReleased < 1.0f) - (slot < 1.0f);
        slot = mReleased;
        mGainAt = (mGainAt + 1) % mLookAhead;
        // Unity is exact: below the ceiling nothing changes, and the sum doesn't drift.
        if (mSmoothingBelow == 0) mSmoothingSum = static_cast<double>(mLookAhead);
        const float gain = mSmoothingBelow == 0 ? 1.0f : static_cast<float>(mSmoothingSum / mLookAhead);

        // Out goes the frame mLatency back, at that gain.
        float *delayed = mDelay.data() + static_cast<size_t>(mDelayAt) * 2;
        const int32_t oldest = (mDelayAt + 1) % delayFrames;
        delayed[0] = frame[0];
        delayed[1] = frame[1];
        const float *outgoing = mDelay.data() + static_cast<size_t>(oldest) * 2;
        frame[0] = outgoing[0] * gain;
        frame[1] = outgoing[1] * gain;
        mDelayAt = oldest;
    }
}

}  // namespace meavox
