// SPDX-License-Identifier: GPL-3.0-or-later
#include "OfflineMixRender.h"

#include <algorithm>

namespace meavox {

OfflineMixRender::OfflineMixRender(int32_t sampleRate, int64_t from, int64_t to, int64_t takeStart, int64_t alignment,
                                   int transpose, float backingGain, float takeGain, VocalEffects effects, float guideLevel,
                                   bool limit)
    : mLimit(limit),
      mGuideLevel(guideLevel),
      mTo(std::max(from, to)),
      mTakeStart(takeStart),
      mAlignment(alignment),
      mTranspose(std::clamp(transpose, -kMaxTranspose, kMaxTranspose)),
      mBackingGain(backingGain),
      mTakeGain(takeGain),
      mPos(std::max<int64_t>(from, 0)) {
    mBacking.configure(sampleRate);
    mEffects.configure(sampleRate);
    mEffects.set(effects);
    if (mLimit) {
        mLimiter.configure(sampleRate);
        mSkip = mLimiter.latency();
    }
    mWindow.start = mPos;
}

void OfflineMixRender::appendGuide(const float *interleaved, int32_t frames, int32_t channels) {
    const int32_t ch = std::max(1, channels);
    for (int32_t i = 0; i < frames; ++i) {
        const float l = interleaved[static_cast<size_t>(i) * ch];
        mGuide.push_back(l);
        mGuide.push_back(ch > 1 ? interleaved[static_cast<size_t>(i) * ch + 1] : l);
    }
}

void OfflineMixRender::appendTake(const float *interleaved, int32_t frames, int32_t channels) {
    const int32_t ch = std::max(1, channels);
    mTake.reserve(mTake.size() + static_cast<size_t>(std::max(frames, 0)));
    for (int32_t i = 0; i < frames; ++i) mTake.push_back(interleaved[static_cast<size_t>(i) * ch]);
}

void OfflineMixRender::Window::dropBefore(int64_t frame) {
    const int64_t drop = std::clamp<int64_t>(frame - start, 0, end() - start);
    // Compacted only once the dropped part is large, so feeding stays linear in time.
    if (drop < 1 << 16 && drop * 2 < end() - start) return;
    frames.erase(frames.begin(), frames.begin() + static_cast<long>(drop) * 2);
    start += drop;
}

void OfflineMixRender::appendBacking(const float *interleaved, int32_t frames, int32_t channels) {
    const int32_t ch = std::max(1, channels);
    for (int32_t i = 0; i < frames; ++i, ++mFed) {
        // The Guide Vocal frame of the same index, consumed in step (silence where none was fed).
        float guideL = 0.0f;
        float guideR = 0.0f;
        if (mGuideRead < mGuide.size()) {
            guideL = mGuide[mGuideRead];
            guideR = mGuide[mGuideRead + 1];
            mGuideRead += 2;
        }
        if (mFed < mWindow.start) continue;  // before the rendered span: never read
        const float l = interleaved[static_cast<size_t>(i) * ch];
        const float r = ch > 1 ? interleaved[static_cast<size_t>(i) * ch + 1] : l;
        // As GuidedFrames does in real time: before the stretcher, so the Guide Vocal is transposed too.
        mWindow.frames.push_back(mGuideLevel > 0.0f ? l + guideL * mGuideLevel : l);
        mWindow.frames.push_back(mGuideLevel > 0.0f ? r + guideR * mGuideLevel : r);
    }
    if (mGuideRead == mGuide.size()) {
        mGuide.clear();
        mGuideRead = 0;
    }
    renderAvailable();
}

void OfflineMixRender::endBacking() {
    mBackingEnded = true;
    if (mTo == kUntilEnd) mTo = std::max(mPos, mFed);  // the Backing Audio's length is known now
    renderAvailable();
}

void OfflineMixRender::renderAvailable() {
    const int64_t available = mWindow.end();
    while (mPos < renderTo()) {
        const auto n = static_cast<int32_t>(std::min<int64_t>(kRenderFrames, renderTo() - mPos));
        // As in real-time playback, the stretcher reads mLead ahead; render only once that is here.
        const int64_t needed = mPos + n + (mTranspose != 0 ? mBacking.lead() : 0);
        if (!mBackingEnded && available < needed) break;
        const size_t at = mOutput.size();
        mOutput.resize(at + static_cast<size_t>(n) * 2, 0.0f);
        float *out = mOutput.data() + at;
        // The first call starts the stretcher at the span's start, like Take playback starting there.
        mBacking.render(mWindow, mPos, available, mBackingEnded, !mStarted, mTranspose, out, n);
        mStarted = true;
        mixTake(out, n, mPos, mTakeStart, mAlignment, mTake.data(), static_cast<int64_t>(mTake.size()), mBackingGain,
                mTakeGain, &mEffects);
        if (mLimit) mLimiter.process(out, n);
        clipOutput(out, n);
        // The limiter's first frames are from before the span: drop them, so the output stays aligned.
        if (mSkip > 0) {
            const auto drop = static_cast<int32_t>(std::min<int64_t>(mSkip, n));
            const auto first = mOutput.begin() + static_cast<long>(at);
            mOutput.erase(first, first + static_cast<long>(drop) * 2);
            mSkip -= drop;
        }
        mPos += n;
        mWindow.dropBefore(mPos);
    }
}

int32_t OfflineMixRender::drain(float *out, int32_t maxFrames) {
    const size_t available = (mOutput.size() - mOutputRead) / 2;
    const auto n = static_cast<int32_t>(std::min<size_t>(available, static_cast<size_t>(std::max(maxFrames, 0))));
    std::copy_n(mOutput.data() + mOutputRead, static_cast<size_t>(n) * 2, out);
    mOutputRead += static_cast<size_t>(n) * 2;
    if (mOutputRead == mOutput.size()) {
        mOutput.clear();
        mOutputRead = 0;
    }
    return n;
}

}  // namespace meavox
