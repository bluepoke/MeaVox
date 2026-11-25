// SPDX-License-Identifier: GPL-3.0-or-later
//
// Integrated loudness after ITU-R BS.1770-4 / EBU R128 (#44): K-weighting (a high shelf and a
// high-pass, two biquads per channel), mean square per 400 ms block with 75 % overlap, an absolute
// gate at −70 LUFS and a relative gate 10 LU below the loudness of the blocks above it. Mono input
// counts on both channels, as a Take plays in a Mix; beyond two channels, the first two count.
//
// No platform dependencies, so it can be built and checked on a host as well. Not thread-safe.
#pragma once

#include <cstdint>
#include <vector>

namespace meavox {

class IntegratedLoudness {
public:
    explicit IntegratedLoudness(int32_t sampleRate);

    // Adds interleaved frames of [channels] channels, in order.
    void add(const float *interleaved, int32_t frames, int32_t channels);

    // The integrated loudness of what was added in LUFS, or NaN if no block passes the gates
    // (silence, or less than one 400 ms block).
    double integrated() const;

private:
    struct Biquad {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        double z1 = 0, z2 = 0;
        double process(double x) {
            const double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    };

    // The K-weighting filter of one channel.
    struct KWeighting {
        Biquad shelf;
        Biquad highPass;
        double process(double x) { return highPass.process(shelf.process(x)); }
    };

    KWeighting mFilters[2];
    int64_t mStepFrames;          // 100 ms: blocks start every step and span four
    int64_t mInStep = 0;          // frames in the step being summed
    double mStepSum = 0;          // weighted square sum of the step being summed
    std::vector<double> mSteps;   // weighted square sum of each complete step
};

}  // namespace meavox
