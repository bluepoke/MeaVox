// SPDX-License-Identifier: GPL-3.0-or-later
//
// Production Audio Engine (ADR 4, ADR 13). Plays one audio file (Backing Audio) with play /
// pause / seek. In Session mode (ticket #8) it runs low-latency streams for as long as the
// Session is open: the microphone is read on the output callback, so playback and recording
// share one timeline, and a Take can be recorded from any Start Position and played back
// against the Backing Audio. Built on the design validated by spike #2.
//
// Timeline: frames at the loaded file's sample rate. Streams are opened at that rate and Oboe
// resamples to the device, so Backing Audio, Takes and positions all use one rate.
//
// Progressive loading: playback may start while the file is still being decoded. Decoded audio
// is appended in fixed-size blocks that are never reallocated; the callback reads up to the
// published decoded frame count. Playing past it outputs silence until more is decoded.
//
// Transpose (ADR 5): in a Session the Backing Audio goes through BackingRenderer (Signalsmith
// Stretch, bypassed at 0 semitones), which the Offline Render of a Mix shares, so its output stays
// sample-aligned with the timeline. Plain playback follows setTranspose live; a recording and Take
// playback keep the Transpose they were started with.
//
// Practice Loop (#18): free playback that reaches B goes on at A, through the same BackingRenderer, so
// a transposed loop is as seamless as a plain one.
//
// Headless (engine tests): no device streams; the caller runs the audio callback with renderHeadless()
// on its own thread, so the PCM played can be asserted.
//
// Threading: public methods may be called from any non-audio thread; mutating ones are
// serialised by mControlMutex. The Backing Audio's blocks are freed only while the stream is
// closed; the Guide Vocal's once the callback has acknowledged it no longer reads them
// (releaseGuideLocked), so it comes and goes during playback. The Take buffer is changed only while
// the transport is idle and the callback has acknowledged it (enterIdleLocked). The app's instance
// lives as long as the process (it is never destroyed while Oboe's error thread could still call
// back into it); a headless one, which has no streams, is freed by its owner.
#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "BackingRenderer.h"

namespace meavox {

enum class Transport : int32_t {
    Idle = 0,
    Playing = 1,
    Recording = 2,
    PlayingTake = 3,
};

class PlaybackEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit PlaybackEngine(bool headless = false) : mHeadless(headless) {}
    ~PlaybackEngine() override;

    // Headless only: runs the audio callback once for [frames] of interleaved stereo [out].
    void renderHeadless(float *out, int32_t frames);

    // Starts a new load (stopping playback and releasing the previous song and Take first):
    // decoded PCM at the file's own rate, 1..n channels interleaved. [estimatedFrames] (0 if
    // unknown) is reported as the duration until the load completes.
    void beginLoad(int channels, int sampleRate, int64_t estimatedFrames);
    // Returns false once the reserved capacity is exhausted (the rest is dropped; stop decoding).
    bool appendLoad(const float *interleaved, int frames);
    // Marks the load complete; the duration becomes exact.
    void endLoad();
    // Abandons a load and frees its memory.
    void discardLoad();

    // Returns false if nothing is loaded or the output stream could not be started.
    bool play();
    void pause();
    void seekTo(int64_t frame);
    // Transpose of plain playback in semitones (−6…+6), applied live; reset when the Session ends.
    void setTranspose(int semitones);
    // Practice Loop (#18): free playback before [endMs] goes on at [startMs] once it reaches it,
    // without a gap. Applied live, and kept in ms across loads (each file's rate may differ); cleared
    // when the Session ends.
    void setPracticeLoop(int64_t startMs, int64_t endMs);
    void clearPracticeLoop();

    // --- Session mode ---
    // Entering opens low-latency streams that keep running; leaving closes the microphone and
    // drops the Take.
    void setSessionMode(bool enabled);
    // Opens the microphone (Session mode only; needs the RECORD_AUDIO permission).
    bool enableInput();
    void setInputGain(float linear);
    // Backing Audio gain while the held Take plays (Review Balance); recording is unaffected.
    void setReviewBackingGain(float linear);
    // Gain of the held Take while it plays (a Mix Preview's vocal level); 1 until set.
    void setTakeGain(float linear);
    // Alignment of the playing Take (see playTake), applied from the next callback.
    void setTakeAlignment(int64_t alignmentFrames) { mTakeAlignment.store(alignmentFrames); }
    // Plays from [fromFrame] at [transpose] and records the microphone into a new Take. False without input.
    bool startRecording(int64_t fromFrame, int transpose);
    // Stops recording (if running); the captured Take stays held.
    void stopRecording();
    // Plays the held Take with the Backing Audio at [transpose], from the Take's start until the Take ends.
    bool playTake(int64_t alignmentFrames, int transpose);
    void clearTake();
    // Replaces the held Take with stored audio (mono, at the timeline rate).
    bool beginTakeLoad(int64_t startFrame, int sampleRate, int64_t estimatedFrames);
    void appendTakeLoad(const float *interleaved, int frames, int channels);
    // Writes the held Take as mono 24-bit FLAC. False on I/O or encoder errors.
    bool writeTakeFlac(const std::string &path);

    // --- Mix Preview (#16) and the Guide Vocal (#16, #18) ---
    // Vocal Effects on the held Take while it plays, applied live (none until set). Take playback
    // runs on [tailFrames] past the Take's end, so a reverb rings out.
    void setVocalEffects(VocalEffects effects, int64_t tailFrames);
    // Starts loading the Guide Vocal: a Vocal Stem at the Backing Audio's rate, added to it before
    // the stretcher in every transport (never into the recorded Take). Drops one held before, without
    // interrupting playback. False if the rate doesn't match.
    bool beginGuideLoad(int channels, int sampleRate);
    // Returns false once the reserved capacity is exhausted (the rest is dropped; stop decoding).
    bool appendGuideLoad(const float *interleaved, int frames);
    void clearGuide();
    // Level of the Guide Vocal against the Backing Audio, 0..1; 0 until set.
    void setGuideLevel(float level) { mGuideLevel.store(level); }
    int64_t guideDecodedFrames() const { return mGuideDecoded.load(); }

    Transport transport() const { return mTransport.load(); }
    bool isPlaying() const { return mTransport.load() != Transport::Idle; }
    int64_t positionFrames() const { return mPosition.load(); }
    int64_t durationFrames() const;
    int64_t decodedFrames() const { return mDecoded.load(); }
    int32_t sampleRate() const { return mSampleRate.load(); }

    bool inputEnabled() const { return mInputOpen.load(); }
    // Decaying peak after gain, and the number of clipped callbacks so far.
    float inputPeak() const { return mInputPeak.load(); }
    int64_t clipCount() const { return mClipCount.load(); }
    int32_t outputDeviceId();
    int32_t inputDeviceId();
    int64_t takeStartFrame() const { return mTakeStart; }
    int64_t takeFrames() const { return mTakeFrames.load(); }
    // Output + input latency as reported when the last recording started (ADR 13).
    int64_t takeReportedLatencyFrames() const { return mTakeLatency; }
    // Frames of microphone input dropped to undo output xruns during the last recording.
    int64_t takeXRunCorrectionFrames() const { return mXRunCorrection.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int64_t kBlockFrames = 1 << 16;  // ~1.5 s at 44.1 kHz, 512 KiB stereo float
    static constexpr int kDrainCallbacks = 10;        // empty the input after a (re)start
    static constexpr int kXRunMeasureCallbacks = 16;  // measure input backlog after an xrun
    static constexpr int kLostInputCallbacks = 50;    // a deficit this old (~200 ms) was lost, not late

    // The decoded blocks as BackingRenderer reads them.
    class BlockFrames : public StereoFrames {
    public:
        explicit BlockFrames(const std::vector<float *> &blocks) : mBlocks(blocks) {}
        const float *at(int64_t frame, int64_t &contiguous) const override {
            const int64_t inBlock = frame % kBlockFrames;
            contiguous = kBlockFrames - inBlock;
            return mBlocks[static_cast<size_t>(frame / kBlockFrames)] + inBlock * 2;
        }

    private:
        const std::vector<float *> &mBlocks;
    };

    // Free playback within the Practice Loop, as BackingRenderer reads it (#18): from B on, the frames
    // from A again, so the stretcher's read-ahead wraps with it. The last frames before B fade over to
    // those just before A, so going on at A continues the sound without a click. Callback only, after prepare().
    class LoopedFrames : public StereoFrames {
    public:
        LoopedFrames();
        void prepare(const StereoFrames &inner, int64_t start, int64_t end, int64_t fade) {
            mInner = &inner;
            mStart = start;
            mEnd = end;
            mFade = fade;
        }
        const float *at(int64_t frame, int64_t &contiguous) const override;

    private:
        static constexpr int64_t kScratchFrames = 4096;
        const StereoFrames *mInner = nullptr;
        int64_t mStart = 0;
        int64_t mEnd = 0;
        int64_t mFade = 0;
        mutable std::vector<float> mScratch;  // one fade at a time, like GuidedFrames
    };

    // The Backing Audio with the Guide Vocal added at a level, as BackingRenderer reads it (#16): summed
    // before the stretcher, so the Guide Vocal is transposed with it. Callback only, after prepare().
    class GuidedFrames : public StereoFrames {
    public:
        GuidedFrames(const StereoFrames &backing, const StereoFrames &guide);
        void prepare(int64_t guideDecoded, float level) {
            mGuideDecoded = guideDecoded;
            mLevel = level;
        }
        const float *at(int64_t frame, int64_t &contiguous) const override;

    private:
        static constexpr int64_t kScratchFrames = 4096;
        const StereoFrames &mBacking;
        const StereoFrames &mGuide;
        int64_t mGuideDecoded = 0;
        float mLevel = 0.0f;
        mutable std::vector<float> mScratch;  // one sum at a time: BackingRenderer copies before its next at()
    };

    bool startStreamLocked();
    bool openSessionStreamsLocked();
    void closeStreamLocked();
    void freeBlocksLocked();
    void freeGuideLocked();
    void releaseGuideLocked();
    bool practiceLoop(int64_t &start, int64_t &end) const;
    void applyPracticeLoopLocked();
    void enterIdleLocked();
    bool startTransportLocked(int64_t fromFrame, Transport transport, int transpose);
    void configureStretchLocked(int32_t sampleRate);
    int64_t reportedLatencyFramesLocked();

    int readInput(int numFrames);
    void trackXRuns();

    const bool mHeadless = false;
    std::mutex mControlMutex;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::shared_ptr<oboe::AudioStream> mInput;

    // Interleaved stereo blocks. The pointer table is reserved up front, so appending never
    // reallocates it while the callback reads; entries below mDecoded are immutable.
    std::vector<float *> mBlocks;
    int32_t mLoadChannels = 0;  // > 0 while a load is in progress

    std::atomic<int64_t> mDecoded{0};
    std::atomic<int64_t> mEstimated{0};
    std::atomic<bool> mComplete{false};
    std::atomic<int32_t> mSampleRate{0};
    std::atomic<Transport> mTransport{Transport::Idle};
    std::atomic<bool> mIdleAck{false};
    std::atomic<int64_t> mPosition{0};
    std::atomic<int64_t> mSeekRequest{-1};

    // Transpose (ADR 5). The renderer is used by the callback only, except for
    // configureStretchLocked() while the stream is closed.
    std::atomic<int32_t> mPlayTranspose{0};       // plain playback, follows setTranspose()
    std::atomic<int32_t> mTransportTranspose{0};  // recording and Take playback, fixed at their start
    BlockFrames mBlockFrames{mBlocks};
    BackingRenderer mBacking;

    // Practice Loop as set, in ms (none if the end isn't after the start), and as the callback reads
    // it: start << 32 | end in frames at the loaded rate, 0 for none (one atomic, so it never sees half a change).
    int64_t mLoopStartMs = 0;
    int64_t mLoopEndMs = 0;
    std::atomic<int64_t> mLoop{0};
    LoopedFrames mLooped;

    // Session mode.
    bool mSession = false;
    bool mInputWanted = false;
    std::atomic<bool> mInputOpen{false};
    std::atomic<float> mInputGain{1.0f};
    std::atomic<float> mReviewBackingGain{1.0f};
    std::atomic<float> mTakeGain{1.0f};
    std::atomic<float> mInputPeak{0.0f};
    std::atomic<int64_t> mClipCount{0};
    std::vector<float> mInputBuffer;  // sized when the input opens; callback only
    std::vector<float> mDropBuffer;
    int mDrainLeft = 0;
    bool mCushion = false;  // skip one read after draining, so reads don't race the input's writes

    // Take: mono, index i = timeline frame mTakeStart + i while recording.
    std::vector<float> mTake;
    std::atomic<int64_t> mTakeFrames{0};
    int64_t mTakeStart = 0;
    // While recording (audio thread): input is written contiguously at mTakeCursor, which
    // follows the output. A deficit (input arrived late) is read on top of the next callbacks.
    int64_t mTakeCursor = 0;
    int64_t mTakeDeficit = 0;
    int mDeficitCallbacks = 0;
    int64_t mTakeLatency = 0;
    std::atomic<int64_t> mTakeAlignment{0};

    // Mix Preview (#16). The chain is the callback's, configured while the stream is closed; it is
    // reset when Take playback starts and follows mEffectsBits (reverb | compression << 2 | high-pass << 3).
    VocalChain mEffects;
    std::atomic<int32_t> mEffectsBits{0};
    std::atomic<int64_t> mTakeTail{0};
    // Guide Vocal blocks, like mBlocks; read by the callback below mGuideDecoded. The callback sets
    // mGuideAck once it sees none decoded, so releaseGuideLocked() knows the blocks are no longer read.
    std::vector<float *> mGuideBlocks;
    std::atomic<int64_t> mGuideDecoded{0};
    std::atomic<bool> mGuideAck{false};
    int32_t mGuideChannels = 0;  // > 0 while a Guide Vocal load is in progress
    std::atomic<float> mGuideLevel{0.0f};
    BlockFrames mGuideBlockFrames{mGuideBlocks};
    GuidedFrames mGuided{mBlockFrames, mGuideBlockFrames};

    // Xrun correction while recording (audio thread, ADR 13): an output underrun delays the
    // output without delaying the microphone, so the input backlog grows by the frames lost.
    int32_t mLastOutXRuns = -1;
    int32_t mBacklogFloor = INT32_MAX;
    int32_t mXRunBacklogMin = INT32_MAX;
    int mXRunMeasureLeft = 0;
    std::atomic<int64_t> mXRunCorrection{0};
};

}  // namespace meavox
