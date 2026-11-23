// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vocal Effects (#16, GLOSSARY): mix-time presets for the Take, shared by the Mix Preview (PlaybackEngine)
// and the Offline Render (OfflineMixRender) through mixTake(), so an Export sounds like the preview.
// Never used for Monitoring or Review.
//
// The chain, in order: a high-pass filter (2nd-order Butterworth at 100 Hz), gentle compression
// (3:1 above −18 dBFS with a soft knee, 5 ms attack, 150 ms release, +3 dB make-up), and a reverb
// (Room or Hall; the Freeverb structure of eight damped combs and four all-passes per channel, with
// its tuning scaled to the sample rate). The Take is mono; the reverb makes it stereo.
//
// Deterministic: the same samples from a reset() give the same output. No platform dependencies.
// configure() allocates; everything else may run on the audio thread.
#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace meavox {

enum class Reverb : int32_t { Dry = 0, Room = 1, Hall = 2 };

struct VocalEffects {
    Reverb reverb = Reverb::Dry;
    bool compression = false;
    bool highPass = false;

    bool any() const { return reverb != Reverb::Dry || compression || highPass; }
    bool operator==(const VocalEffects &o) const {
        return reverb == o.reverb && compression == o.compression && highPass == o.highPass;
    }
    bool operator!=(const VocalEffects &o) const { return !(*this == o); }
};

class VocalChain {
public:
    // Prepares the filters and reverb for [sampleRate] and resets them.
    void configure(int32_t sampleRate);

    // Clears every filter and reverb state: what comes next sounds as from a fresh start.
    void reset();

    // Switches presets; the state carries on, so a change during playback doesn't click.
    void set(const VocalEffects &effects);

    const VocalEffects &effects() const { return mEffects; }

    // One Take sample in, the effected stereo pair out.
    void process(float in, float &left, float &right);

private:
    class Comb {
    public:
        void resize(size_t length);
        void clear();
        float process(float in, float feedback, float damp);

    private:
        std::vector<float> mBuffer;
        size_t mIndex = 0;
        float mStore = 0.0f;
    };

    class AllPass {
    public:
        void resize(size_t length);
        void clear();
        float process(float in);

    private:
        std::vector<float> mBuffer;
        size_t mIndex = 0;
    };

    static constexpr int kCombs = 8;
    static constexpr int kAllPasses = 4;

    void clearReverb();
    float highPass(float x);
    float compress(float x);

    int32_t mRate = 0;
    VocalEffects mEffects;

    // High-pass biquad (transposed direct form II).
    float mB0 = 1.0f, mB1 = 0.0f, mB2 = 0.0f, mA1 = 0.0f, mA2 = 0.0f;
    float mZ1 = 0.0f, mZ2 = 0.0f;

    // Compressor.
    float mAttack = 0.0f, mRelease = 0.0f;  // one-pole coefficients
    float mEnvelope = 0.0f;                 // linear peak envelope

    // Reverb, per channel.
    std::array<Comb, kCombs> mCombL, mCombR;
    std::array<AllPass, kAllPasses> mAllPassL, mAllPassR;
};

}  // namespace meavox
