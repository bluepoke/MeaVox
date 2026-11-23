// SPDX-License-Identifier: GPL-3.0-or-later
#include "VocalEffects.h"

#include <algorithm>
#include <cmath>

namespace meavox {

namespace {
constexpr float kPi = 3.14159265358979f;

// High-pass: removes rumble and handling noise below the voice.
constexpr float kHighPassHz = 100.0f;
constexpr float kHighPassQ = 0.70710678f;  // Butterworth

// Compression: gentle, evens out the voice without pumping.
constexpr float kThresholdDb = -18.0f;
constexpr float kRatio = 3.0f;
constexpr float kKneeDb = 6.0f;
constexpr float kMakeUpDb = 3.0f;
constexpr float kAttackSeconds = 0.005f;
constexpr float kReleaseSeconds = 0.150f;

// Freeverb's tuning at 44.1 kHz (Jezar at Dreampoint, public domain), scaled to the sample rate.
constexpr int kCombTuning[] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllPassTuning[] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;
constexpr float kInputGain = 0.015f * 2.0f;  // Freeverb's fixed gain on L + R, here the mono Take twice
constexpr float kDenormal = 1e-20f;

struct ReverbPreset {
    float feedback;  // the room size: how long it rings
    float damp;      // how fast its highs die away
    float wet;       // how much of it is heard against the dry voice
};
// Freeverb's room size r and damping d map to feedback 0.28 r + 0.7 and damp 0.4 d; wet is Freeverb's × 3.
constexpr ReverbPreset kRoom{0.28f * 0.55f + 0.7f, 0.4f * 0.50f, 3.0f * 0.12f};
constexpr ReverbPreset kHall{0.28f * 0.85f + 0.7f, 0.4f * 0.35f, 3.0f * 0.16f};

size_t scaled(int frames, int32_t rate) {
    return static_cast<size_t>(std::max(1L, std::lround(static_cast<double>(frames) * rate / 44100.0)));
}
}  // namespace

void VocalChain::Comb::resize(size_t length) {
    mBuffer.assign(length, 0.0f);
    clear();
}

void VocalChain::Comb::clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mIndex = 0;
    mStore = 0.0f;
}

float VocalChain::Comb::process(float in, float feedback, float damp) {
    const float out = mBuffer[mIndex];
    mStore = out * (1.0f - damp) + mStore * damp;
    if (std::fabs(mStore) < kDenormal) mStore = 0.0f;
    mBuffer[mIndex] = in + mStore * feedback;
    if (++mIndex == mBuffer.size()) mIndex = 0;
    return out;
}

void VocalChain::AllPass::resize(size_t length) {
    mBuffer.assign(length, 0.0f);
    clear();
}

void VocalChain::AllPass::clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mIndex = 0;
}

float VocalChain::AllPass::process(float in) {
    float buffered = mBuffer[mIndex];
    if (std::fabs(buffered) < kDenormal) buffered = 0.0f;
    mBuffer[mIndex] = in + buffered * 0.5f;
    if (++mIndex == mBuffer.size()) mIndex = 0;
    return buffered - in;
}

void VocalChain::configure(int32_t sampleRate) {
    if (sampleRate <= 0) return;
    if (sampleRate != mRate) {
        mRate = sampleRate;
        // RBJ cookbook high-pass.
        const float w0 = 2.0f * kPi * kHighPassHz / static_cast<float>(sampleRate);
        const float cosW = std::cos(w0);
        const float alpha = std::sin(w0) / (2.0f * kHighPassQ);
        const float a0 = 1.0f + alpha;
        mB0 = (1.0f + cosW) / 2.0f / a0;
        mB1 = -(1.0f + cosW) / a0;
        mB2 = (1.0f + cosW) / 2.0f / a0;
        mA1 = -2.0f * cosW / a0;
        mA2 = (1.0f - alpha) / a0;
        mAttack = std::exp(-1.0f / (kAttackSeconds * static_cast<float>(sampleRate)));
        mRelease = std::exp(-1.0f / (kReleaseSeconds * static_cast<float>(sampleRate)));
        for (int i = 0; i < kCombs; ++i) {
            mCombL[i].resize(scaled(kCombTuning[i], sampleRate));
            mCombR[i].resize(scaled(kCombTuning[i] + kStereoSpread, sampleRate));
        }
        for (int i = 0; i < kAllPasses; ++i) {
            mAllPassL[i].resize(scaled(kAllPassTuning[i], sampleRate));
            mAllPassR[i].resize(scaled(kAllPassTuning[i] + kStereoSpread, sampleRate));
        }
    }
    reset();
}

void VocalChain::reset() {
    mZ1 = mZ2 = 0.0f;
    mEnvelope = 0.0f;
    clearReverb();
}

void VocalChain::clearReverb() {
    for (auto &c : mCombL) c.clear();
    for (auto &c : mCombR) c.clear();
    for (auto &a : mAllPassL) a.clear();
    for (auto &a : mAllPassR) a.clear();
}

void VocalChain::set(const VocalEffects &effects) {
    // A reverb switched on starts empty rather than with what it held when it was switched off.
    if (mEffects.reverb == Reverb::Dry && effects.reverb != Reverb::Dry) clearReverb();
    mEffects = effects;
}

float VocalChain::highPass(float x) {
    const float y = mB0 * x + mZ1;
    mZ1 = mB1 * x - mA1 * y + mZ2;
    mZ2 = mB2 * x - mA2 * y;
    return y;
}

float VocalChain::compress(float x) {
    const float level = std::fabs(x);
    const float coefficient = level > mEnvelope ? mAttack : mRelease;
    mEnvelope = coefficient * mEnvelope + (1.0f - coefficient) * level;
    const float inDb = 20.0f * std::log10(mEnvelope + 1e-9f);
    const float over = inDb - kThresholdDb;
    float outDb;
    if (2.0f * over < -kKneeDb) {
        outDb = inDb;
    } else if (2.0f * std::fabs(over) <= kKneeDb) {
        const float k = over + kKneeDb / 2.0f;
        outDb = inDb + (1.0f / kRatio - 1.0f) * k * k / (2.0f * kKneeDb);
    } else {
        outDb = kThresholdDb + over / kRatio;
    }
    return x * std::pow(10.0f, (outDb - inDb + kMakeUpDb) / 20.0f);
}

void VocalChain::process(float in, float &left, float &right) {
    float x = in;
    if (mEffects.highPass) x = highPass(x);
    if (mEffects.compression) x = compress(x);
    left = x;
    right = x;
    if (mEffects.reverb == Reverb::Dry) return;
    const ReverbPreset &p = mEffects.reverb == Reverb::Hall ? kHall : kRoom;
    const float input = x * kInputGain;
    float wetL = 0.0f;
    float wetR = 0.0f;
    for (int i = 0; i < kCombs; ++i) {
        wetL += mCombL[i].process(input, p.feedback, p.damp);
        wetR += mCombR[i].process(input, p.feedback, p.damp);
    }
    for (int i = 0; i < kAllPasses; ++i) {
        wetL = mAllPassL[i].process(wetL);
        wetR = mAllPassR[i].process(wetR);
    }
    left += wetL * p.wet;
    right += wetR * p.wet;
}

}  // namespace meavox
