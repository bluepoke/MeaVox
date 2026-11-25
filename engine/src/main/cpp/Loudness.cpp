// SPDX-License-Identifier: GPL-3.0-or-later
#include "Loudness.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace meavox {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr int kStepsPerBlock = 4;            // 400 ms blocks, 75 % overlap
constexpr double kAbsoluteGateLufs = -70.0;
constexpr double kRelativeGateLu = -10.0;

// The BS.1770 K-weighting filters for any rate, from their analog prototypes (as libebur128 derives
// them); at 48 kHz they give the coefficients the recommendation lists.
void kWeighting(double rate, double shelf[5], double highPass[5]) {
    {
        const double f0 = 1681.974450955533;
        const double gainDb = 3.999843853973347;
        const double q = 0.7071752369554196;
        const double k = std::tan(kPi * f0 / rate);
        const double vh = std::pow(10.0, gainDb / 20.0);
        const double vb = std::pow(vh, 0.4996667741545416);
        const double a0 = 1.0 + k / q + k * k;
        shelf[0] = (vh + vb * k / q + k * k) / a0;
        shelf[1] = 2.0 * (k * k - vh) / a0;
        shelf[2] = (vh - vb * k / q + k * k) / a0;
        shelf[3] = 2.0 * (k * k - 1.0) / a0;
        shelf[4] = (1.0 - k / q + k * k) / a0;
    }
    {
        const double f0 = 38.13547087602444;
        const double q = 0.5003270373238773;
        const double k = std::tan(kPi * f0 / rate);
        const double a0 = 1.0 + k / q + k * k;
        highPass[0] = 1.0;
        highPass[1] = -2.0;
        highPass[2] = 1.0;
        highPass[3] = 2.0 * (k * k - 1.0) / a0;
        highPass[4] = (1.0 - k / q + k * k) / a0;
    }
}

double lufs(double meanSquare) { return -0.691 + 10.0 * std::log10(meanSquare); }
}  // namespace

IntegratedLoudness::IntegratedLoudness(int32_t sampleRate) : mStepFrames(std::max<int64_t>(1, std::llround(sampleRate * 0.1))) {
    double shelf[5];
    double highPass[5];
    kWeighting(std::max(sampleRate, 1), shelf, highPass);
    for (auto &f : mFilters) {
        f.shelf = {shelf[0], shelf[1], shelf[2], shelf[3], shelf[4]};
        f.highPass = {highPass[0], highPass[1], highPass[2], highPass[3], highPass[4]};
    }
}

void IntegratedLoudness::add(const float *interleaved, int32_t frames, int32_t channels) {
    const int32_t ch = std::max(1, channels);
    for (int32_t i = 0; i < frames; ++i) {
        const float *frame = interleaved + static_cast<size_t>(i) * ch;
        if (ch == 1) {
            // A mono Take plays on both channels: twice its square.
            const double y = mFilters[0].process(frame[0]);
            mStepSum += 2.0 * y * y;
        } else {
            const double l = mFilters[0].process(frame[0]);
            const double r = mFilters[1].process(frame[1]);
            mStepSum += l * l + r * r;
        }
        if (++mInStep == mStepFrames) {
            mSteps.push_back(mStepSum);
            mStepSum = 0;
            mInStep = 0;
        }
    }
}

double IntegratedLoudness::integrated() const {
    const auto blockCount = static_cast<int64_t>(mSteps.size()) - (kStepsPerBlock - 1);
    std::vector<double> blocks;  // mean square of each block above the absolute gate
    const double blockFrames = static_cast<double>(mStepFrames * kStepsPerBlock);
    for (int64_t j = 0; j < blockCount; ++j) {
        double sum = 0;
        for (int s = 0; s < kStepsPerBlock; ++s) sum += mSteps[static_cast<size_t>(j + s)];
        const double meanSquare = sum / blockFrames;
        if (meanSquare > 0 && lufs(meanSquare) > kAbsoluteGateLufs) blocks.push_back(meanSquare);
    }
    if (blocks.empty()) return std::numeric_limits<double>::quiet_NaN();
    double sum = 0;
    for (double b : blocks) sum += b;
    const double relativeGate = lufs(sum / static_cast<double>(blocks.size())) + kRelativeGateLu;
    double gated = 0;
    int64_t count = 0;
    for (double b : blocks) {
        if (lufs(b) > relativeGate) {
            gated += b;
            ++count;
        }
    }
    if (count == 0) return std::numeric_limits<double>::quiet_NaN();
    return lufs(gated / static_cast<double>(count));
}

}  // namespace meavox
