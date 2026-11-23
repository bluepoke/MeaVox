// SPDX-License-Identifier: GPL-3.0-or-later
#include "PlaybackEngine.h"

#include <FLAC/stream_encoder.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <thread>

#define LOG_TAG "MeaVoxEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace meavox {

namespace {
constexpr int64_t kMaxBlocksWithoutEstimate = 4096;  // ~100 min at 44.1 kHz
constexpr float kClipLevel = 0.999f;
constexpr int64_t kTakeHeadroomSeconds = 5;          // recording may outlast an estimated duration
constexpr int kFlacBitsPerSample = 24;
constexpr int kFlacChunkFrames = 4096;
}  // namespace

PlaybackEngine::~PlaybackEngine() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    closeStreamLocked();
    freeBlocksLocked();
}

void PlaybackEngine::freeBlocksLocked() {
    for (float *block : mBlocks) delete[] block;
    std::vector<float *>().swap(mBlocks);
    mDecoded.store(0);
    freeGuideLocked();  // it belongs to the Backing Audio
}

// Only while the callback doesn't read the Guide Vocal: the stream is closed or the Take doesn't play.
void PlaybackEngine::freeGuideLocked() {
    mGuideDecoded.store(0);
    for (float *block : mGuideBlocks) delete[] block;
    std::vector<float *>().swap(mGuideBlocks);
}

PlaybackEngine::GuidedFrames::GuidedFrames(const StereoFrames &backing, const StereoFrames &guide)
    : mBacking(backing), mGuide(guide), mScratch(static_cast<size_t>(kScratchFrames) * 2) {}

const float *PlaybackEngine::GuidedFrames::at(int64_t frame, int64_t &contiguous) const {
    const float *backing = mBacking.at(frame, contiguous);
    // Past what is decoded of the Guide Vocal, the Backing Audio plays alone.
    if (mLevel <= 0.0f || frame >= mGuideDecoded) return backing;
    int64_t guideContiguous = 0;
    const float *guide = mGuide.at(frame, guideContiguous);
    const int64_t n = std::min({contiguous, guideContiguous, mGuideDecoded - frame, kScratchFrames});
    for (int64_t i = 0; i < n * 2; ++i) mScratch[static_cast<size_t>(i)] = backing[i] + guide[i] * mLevel;
    contiguous = n;
    return mScratch.data();
}

// ---------------------------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------------------------

void PlaybackEngine::beginLoad(int channels, int sampleRate, int64_t estimatedFrames) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    closeStreamLocked();  // the callback is guaranteed not to run past this point
    freeBlocksLocked();   // release the previous song before decoding the next
    std::vector<float>().swap(mTake);
    mTakeFrames.store(0);
    mTransport.store(Transport::Idle);
    mPosition.store(0);
    mSeekRequest.store(-1);
    mComplete.store(false);
    mEstimated.store(std::max<int64_t>(estimatedFrames, 0));
    // Generous table so appends never reallocate it (duration estimates of e.g. VBR MP3 can be off).
    const int64_t maxBlocks = estimatedFrames > 0 ? estimatedFrames * 5 / 4 / kBlockFrames + 16 : kMaxBlocksWithoutEstimate;
    mBlocks.reserve(static_cast<size_t>(maxBlocks));
    mLoadChannels = std::max(1, channels);
    mSampleRate.store(sampleRate);
    configureStretchLocked(sampleRate);
    // A Session keeps its streams running, now at the new file's rate.
    if (mSession) openSessionStreamsLocked();
}

bool PlaybackEngine::appendLoad(const float *interleaved, int frames) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const int ch = mLoadChannels;
    if (ch <= 0 || frames <= 0) return ch > 0;
    int64_t decoded = mDecoded.load();
    bool fits = true;
    for (int i = 0; i < frames; ++i) {
        const int64_t block = decoded / kBlockFrames;
        if (block >= static_cast<int64_t>(mBlocks.size())) {
            if (mBlocks.size() == mBlocks.capacity()) {
                LOGW("audio longer than reserved; truncated at %lld frames", static_cast<long long>(decoded));
                fits = false;
                break;
            }
            mBlocks.push_back(new float[kBlockFrames * 2]);  // within capacity: no reallocation
        }
        float *dst = mBlocks[static_cast<size_t>(block)] + (decoded % kBlockFrames) * 2;
        const float l = interleaved[i * ch];
        dst[0] = l;
        dst[1] = ch > 1 ? interleaved[i * ch + 1] : l;  // mono -> both; >2 -> first two
        ++decoded;
    }
    mDecoded.store(decoded, std::memory_order_release);  // publish after the samples are written
    return fits;
}

void PlaybackEngine::endLoad() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mLoadChannels = 0;
    mComplete.store(true, std::memory_order_release);  // after the last mDecoded publication
    LOGI("loaded %lld frames at %d Hz", static_cast<long long>(mDecoded.load()), mSampleRate.load());
}

void PlaybackEngine::discardLoad() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    closeStreamLocked();
    freeBlocksLocked();
    std::vector<float>().swap(mTake);
    mTakeFrames.store(0);
    mTransport.store(Transport::Idle);
    mPosition.store(0);
    mLoadChannels = 0;
    mComplete.store(false);
    mEstimated.store(0);
}

// Only while no callback can run (the stream is closed).
void PlaybackEngine::configureStretchLocked(int32_t sampleRate) {
    mEffects.configure(sampleRate);
    const int32_t lead = mBacking.configure(sampleRate);
    LOGI("Transpose: stretcher at %d Hz, read-ahead %d frames", sampleRate, lead);
}

int64_t PlaybackEngine::durationFrames() const {
    const int64_t decoded = mDecoded.load();
    return mComplete.load() ? decoded : std::max(decoded, mEstimated.load());
}

// ---------------------------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------------------------

void PlaybackEngine::enterIdleLocked() {
    mTransport.store(Transport::Idle);
    if (!mStream) return;  // no callback can be running
    mIdleAck.store(false);
    for (int i = 0; i < 100 && !mIdleAck.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    if (!mIdleAck.load()) LOGW("audio thread did not acknowledge Idle within 200 ms");
}

// [transpose] is used by recording and Take playback; plain playback follows mPlayTranspose.
bool PlaybackEngine::startTransportLocked(int64_t fromFrame, Transport transport, int transpose) {
    if (mDecoded.load() <= 0) return false;
    const int64_t clamped = std::clamp<int64_t>(fromFrame, 0, std::max<int64_t>(durationFrames(), 0));
    mPosition.store(clamped);
    mSeekRequest.store(clamped);
    mTransportTranspose.store(std::clamp(transpose, -kMaxTranspose, kMaxTranspose));
    if (!mStream && !startStreamLocked()) return false;
    mTransport.store(transport, std::memory_order_release);
    return true;
}

bool PlaybackEngine::play() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    int64_t from = mPosition.load();
    if (mComplete.load() && from >= mDecoded.load()) from = 0;  // replay from the start after the end
    return startTransportLocked(from, Transport::Playing, 0);
}

void PlaybackEngine::pause() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    if (!mSession) closeStreamLocked();  // plain playback: save battery while paused
}

void PlaybackEngine::setTranspose(int semitones) {
    mPlayTranspose.store(std::clamp(semitones, -kMaxTranspose, kMaxTranspose));
}

void PlaybackEngine::seekTo(int64_t frame) {
    const int64_t clamped = std::clamp<int64_t>(frame, 0, std::max<int64_t>(durationFrames(), 0));
    // Position first: a callback that consumes the request then stores a later position.
    mPosition.store(clamped);
    mSeekRequest.store(clamped);
}

// ---------------------------------------------------------------------------------------------
// Streams
// ---------------------------------------------------------------------------------------------

bool PlaybackEngine::startStreamLocked() {
    if (mStream) return true;
    if (mSession) return openSessionStreamsLocked();
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            // Plain playback: favour battery over latency.
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(mSampleRate.load())
            // Oboe resamples band-limited to the device rate.
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result result = builder.openStream(mStream);
    if (result == oboe::Result::OK) result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("could not start output: %s", oboe::convertToText(result));
        if (mStream) mStream->close();
        mStream.reset();
        return false;
    }
    return true;
}

// Session streams run as long as the Session is open, so recording and metering start at once
// and the input/output relation is settled when a Take begins (ADR 4).
//
// The output takes the normal path, like plain playback: devices apply their speaker processing
// (e.g. Dolby Atmos) only there, and the low-latency path sounds thin without it. Latency only
// matters for Monitoring; Takes are aligned by timestamps and Latency Calibration (ADR 16).
bool PlaybackEngine::openSessionStreamsLocked() {
    closeStreamLocked();
    const int32_t rate = mSampleRate.load();
    if (rate <= 0) return false;  // nothing loaded yet; opened by the next load

    oboe::AudioStreamBuilder out;
    out.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(rate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result result = out.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("could not open Session output: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }
    if (mInputWanted) {
        oboe::AudioStreamBuilder in;
        in.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setSampleRate(rate)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
                ->setInputPreset(oboe::InputPreset::VoicePerformance)
                // Room for the backlog an output xrun leaves behind (see trackXRuns()).
                ->setBufferCapacityInFrames(mStream->getBufferCapacityInFrames() * 2)
                ->setErrorCallback(this);
        result = in.openStream(mInput);
        if (result != oboe::Result::OK) {
            LOGE("could not open microphone: %s", oboe::convertToText(result));
            mInput.reset();
        } else {
            const auto capacity = static_cast<size_t>(std::max(mStream->getBufferCapacityInFrames(), 4096));
            mInputBuffer.assign(capacity, 0.0f);
            mDropBuffer.assign(capacity, 0.0f);
            mDrainLeft = kDrainCallbacks;
            mCushion = false;
            if (mInput->requestStart() != oboe::Result::OK) {
                LOGE("could not start microphone");
                mInput->close();
                mInput.reset();
            }
        }
    }
    mInputOpen.store(mInput != nullptr);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("could not start Session output: %s", oboe::convertToText(result));
        closeStreamLocked();
        return false;
    }
    LOGI("Session streams: rate=%d burst=%d buffer=%d excl=%d input=%d", rate, mStream->getFramesPerBurst(),
         mStream->getBufferSizeInFrames(), mStream->getSharingMode() == oboe::SharingMode::Exclusive,
         mInput != nullptr);
    return true;
}

void PlaybackEngine::closeStreamLocked() {
    if (mStream) {
        mStream->stop();  // synchronous: no callback runs after this returns
        mStream->close();
        mStream.reset();
    }
    if (mInput) {
        mInput->stop();
        mInput->close();
        mInput.reset();
    }
    mInputOpen.store(false);
}

void PlaybackEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGW("stream error: %s", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(mControlMutex);
    // Input and output may both report; only react to the streams we currently own.
    if (stream != mStream.get() && stream != mInput.get()) return;
    // Oboe closed this stream; close the other one too. The transport stops (a recording ends
    // with what it captured), as media players pause on a route change.
    if (stream == mStream.get()) mStream.reset();
    if (stream == mInput.get()) mInput.reset();
    mTransport.store(Transport::Idle);
    closeStreamLocked();
    if (mSession) {
        // Headphones plugged in or out: reopen on the new route so the Session keeps working.
        openSessionStreamsLocked();
    }
}

int32_t PlaybackEngine::outputDeviceId() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    return mStream ? mStream->getDeviceId() : 0;
}

int32_t PlaybackEngine::inputDeviceId() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    return mInput ? mInput->getDeviceId() : 0;
}

// ---------------------------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------------------------

void PlaybackEngine::setSessionMode(bool enabled) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (enabled == mSession) return;
    enterIdleLocked();
    closeStreamLocked();
    mSession = enabled;
    if (enabled) {
        openSessionStreamsLocked();
    } else {
        mInputWanted = false;
        mPlayTranspose.store(0);  // Transpose belongs to the Session; plain playback is untouched
        std::vector<float>().swap(mTake);
        mTakeFrames.store(0);
    }
}

bool PlaybackEngine::enableInput() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mSession) return false;
    if (mInput) return true;
    enterIdleLocked();
    mInputWanted = true;
    // The microphone is read on the output callback: reopen both together.
    openSessionStreamsLocked();
    if (!mInput) mInputWanted = false;  // e.g. no permission; try again on the next call
    return mInput != nullptr;
}

void PlaybackEngine::setInputGain(float linear) { mInputGain.store(std::clamp(linear, 0.0f, 16.0f)); }

void PlaybackEngine::setReviewBackingGain(float linear) { mReviewBackingGain.store(std::clamp(linear, 0.0f, 1.0f)); }

void PlaybackEngine::setTakeGain(float linear) { mTakeGain.store(std::clamp(linear, 0.0f, 16.0f)); }

int64_t PlaybackEngine::reportedLatencyFramesLocked() {
    const int32_t rate = mSampleRate.load();
    if (!mStream || !mInput || rate <= 0) return 0;
    double ms = 0.0;
    auto outLat = mStream->calculateLatencyMillis();
    auto inLat = mInput->calculateLatencyMillis();
    ms += outLat ? outLat.value() : 1000.0 * mStream->getBufferSizeInFrames() / rate;
    ms += inLat ? inLat.value() : 1000.0 * mInput->getFramesPerBurst() / rate;
    return static_cast<int64_t>(ms * rate / 1000.0);
}

bool PlaybackEngine::startRecording(int64_t fromFrame, int transpose) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mSession || !mInput || !mStream || mDecoded.load() <= 0) return false;
    enterIdleLocked();
    const int64_t from = std::clamp<int64_t>(fromFrame, 0, std::max<int64_t>(durationFrames(), 0));
    const int64_t remaining = std::max<int64_t>(durationFrames() - from, 0);
    mTake.assign(static_cast<size_t>(remaining + kTakeHeadroomSeconds * mSampleRate.load()), 0.0f);
    mTakeFrames.store(0);
    mTakeStart = from;
    mTakeLatency = reportedLatencyFramesLocked();
    auto xruns = mStream->getXRunCount();
    mLastOutXRuns = xruns ? xruns.value() : -1;
    mBacklogFloor = INT32_MAX;
    mXRunMeasureLeft = 0;
    mXRunCorrection.store(0);
    mTakeCursor = 0;
    mTakeDeficit = 0;
    mDeficitCallbacks = 0;
    return startTransportLocked(from, Transport::Recording, transpose);
}

void PlaybackEngine::stopRecording() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mTransport.load() == Transport::Recording) enterIdleLocked();
    if (mXRunCorrection.load() > 0) {
        LOGI("Take: corrected %lld frames after output xruns", static_cast<long long>(mXRunCorrection.load()));
    }
}

bool PlaybackEngine::playTake(int64_t alignmentFrames, int transpose) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    if (mTakeFrames.load() <= 0) return false;
    mTakeAlignment.store(alignmentFrames);
    return startTransportLocked(mTakeStart, Transport::PlayingTake, transpose);
}

void PlaybackEngine::clearTake() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const Transport t = mTransport.load();
    if (t == Transport::PlayingTake || t == Transport::Recording) enterIdleLocked();
    std::vector<float>().swap(mTake);
    mTakeFrames.store(0);
}

bool PlaybackEngine::beginTakeLoad(int64_t startFrame, int sampleRate, int64_t estimatedFrames) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const Transport t = mTransport.load();
    if (t == Transport::PlayingTake || t == Transport::Recording) enterIdleLocked();
    std::vector<float>().swap(mTake);
    mTakeFrames.store(0);
    if (sampleRate != mSampleRate.load()) {
        LOGW("Take at %d Hz does not match the Backing Audio at %d Hz", sampleRate, mSampleRate.load());
        return false;
    }
    mTakeStart = startFrame;
    mTake.reserve(static_cast<size_t>(std::max<int64_t>(estimatedFrames, 0)));
    return true;
}

void PlaybackEngine::appendTakeLoad(const float *interleaved, int frames, int channels) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    // Only while idle with respect to the Take (see beginTakeLoad): the callback doesn't read it.
    const int ch = std::max(1, channels);
    for (int i = 0; i < frames; ++i) mTake.push_back(interleaved[i * ch]);
    mTakeFrames.store(static_cast<int64_t>(mTake.size()));
}

bool PlaybackEngine::writeTakeFlac(const std::string &path) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mTransport.load() == Transport::Recording) enterIdleLocked();
    const int64_t frames = mTakeFrames.load();
    if (frames <= 0) return false;
    FLAC__StreamEncoder *encoder = FLAC__stream_encoder_new();
    if (!encoder) return false;
    bool ok = FLAC__stream_encoder_set_channels(encoder, 1) &&
              FLAC__stream_encoder_set_bits_per_sample(encoder, kFlacBitsPerSample) &&
              FLAC__stream_encoder_set_sample_rate(encoder, static_cast<uint32_t>(mSampleRate.load())) &&
              FLAC__stream_encoder_set_compression_level(encoder, 5) &&
              FLAC__stream_encoder_set_total_samples_estimate(encoder, static_cast<FLAC__uint64>(frames));
    ok = ok && FLAC__stream_encoder_init_file(encoder, path.c_str(), nullptr, nullptr) ==
                       FLAC__STREAM_ENCODER_INIT_STATUS_OK;
    if (ok) {
        constexpr float scale = static_cast<float>((1 << (kFlacBitsPerSample - 1)) - 1);
        std::vector<FLAC__int32> chunk(kFlacChunkFrames);
        for (int64_t done = 0; ok && done < frames; done += kFlacChunkFrames) {
            const auto n = static_cast<uint32_t>(std::min<int64_t>(kFlacChunkFrames, frames - done));
            for (uint32_t i = 0; i < n; ++i) {
                const float v = std::clamp(mTake[static_cast<size_t>(done + i)], -1.0f, 1.0f);
                chunk[i] = static_cast<FLAC__int32>(std::lround(v * scale));
            }
            ok = FLAC__stream_encoder_process_interleaved(encoder, chunk.data(), n);
        }
    }
    // finish() also writes the final STREAMINFO; it must run even after an error to free state.
    ok = FLAC__stream_encoder_finish(encoder) && ok;
    if (!ok) LOGE("FLAC: %s", FLAC__stream_encoder_get_resolved_state_string(encoder));
    FLAC__stream_encoder_delete(encoder);
    return ok;
}

// ---------------------------------------------------------------------------------------------
// Mix Preview (#16)
// ---------------------------------------------------------------------------------------------

void PlaybackEngine::setVocalEffects(VocalEffects effects, int64_t tailFrames) {
    const int32_t bits = static_cast<int32_t>(effects.reverb) | (effects.compression ? 1 << 2 : 0) |
                         (effects.highPass ? 1 << 3 : 0);
    mTakeTail.store(std::max<int64_t>(tailFrames, 0));
    mEffectsBits.store(bits);
}

bool PlaybackEngine::beginGuideLoad(int channels, int sampleRate) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mTransport.load() == Transport::PlayingTake && mGuideDecoded.load() > 0) enterIdleLocked();
    freeGuideLocked();
    if (sampleRate != mSampleRate.load()) {
        LOGW("Guide Vocal at %d Hz does not match the Backing Audio at %d Hz", sampleRate, mSampleRate.load());
        return false;
    }
    // As long as the Backing Audio may be, so appends never reallocate the table the callback reads.
    mGuideBlocks.reserve(std::max(mBlocks.capacity(), mBlocks.size()));
    mGuideChannels = std::max(1, channels);
    return true;
}

bool PlaybackEngine::appendGuideLoad(const float *interleaved, int frames) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const int ch = mGuideChannels;
    if (ch <= 0 || frames <= 0) return ch > 0;
    int64_t decoded = mGuideDecoded.load();
    for (int i = 0; i < frames; ++i) {
        const int64_t block = decoded / kBlockFrames;
        if (block >= static_cast<int64_t>(mGuideBlocks.size())) {
            if (mGuideBlocks.size() == mGuideBlocks.capacity()) {
                mGuideDecoded.store(decoded, std::memory_order_release);
                return false;
            }
            mGuideBlocks.push_back(new float[kBlockFrames * 2]);
        }
        float *dst = mGuideBlocks[static_cast<size_t>(block)] + (decoded % kBlockFrames) * 2;
        const float l = interleaved[i * ch];
        dst[0] = l;
        dst[1] = ch > 1 ? interleaved[i * ch + 1] : l;
        ++decoded;
    }
    mGuideDecoded.store(decoded, std::memory_order_release);  // publish after the samples are written
    return true;
}

void PlaybackEngine::clearGuide() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mTransport.load() == Transport::PlayingTake) enterIdleLocked();
    mGuideChannels = 0;
    freeGuideLocked();
}

// ---------------------------------------------------------------------------------------------
// Audio callback
// ---------------------------------------------------------------------------------------------

int PlaybackEngine::readInput(int numFrames) {
    if (!mInput) return 0;
    const auto capacity = static_cast<int32_t>(mInputBuffer.size());
    if (mDrainLeft > 0) {
        // Right after (re)starting, the input holds stale frames: empty it so it starts in step.
        for (int i = 0; i < 64; ++i) {
            auto r = mInput->read(mInputBuffer.data(), capacity, 0);
            if (!r || r.value() <= 0) break;
        }
        if (--mDrainLeft == 0) mCushion = true;
        return 0;
    }
    if (mCushion) {
        // Like Oboe's FullDuplexStream: let one burst of input build up before reading.
        mCushion = false;
        return 0;
    }
    auto r = mInput->read(mInputBuffer.data(), std::min(numFrames, capacity), 0);
    if (!r || r.value() <= 0) return 0;
    const int n = r.value();
    const float gain = mInputGain.load(std::memory_order_relaxed);
    float peak = 0.0f;
    bool clipped = false;
    for (int i = 0; i < n; ++i) {
        float &sample = mInputBuffer[static_cast<size_t>(i)];
        // Clipping at the converter can't be undone by a lower gain, so check before and after.
        clipped = clipped || std::fabs(sample) >= kClipLevel;
        sample *= gain;
        peak = std::max(peak, std::fabs(sample));
    }
    if (clipped || peak >= kClipLevel) mClipCount.fetch_add(1, std::memory_order_relaxed);
    mInputPeak.store(std::max(peak, mInputPeak.load(std::memory_order_relaxed) * 0.9f), std::memory_order_relaxed);
    return n;
}

// ADR 13: an output xrun (underrun) shifts everything played later by the frames lost, while
// the microphone keeps running, so the rest of the Take would be late by that much. The lost
// frames show up as extra input backlog: measure it for a few callbacks after the xrun and drop
// that many input frames, so the microphone is back in step with what the singer hears.
void PlaybackEngine::trackXRuns() {
    auto available = mInput->getAvailableFrames();
    if (!available) return;
    const int32_t backlog = available.value();
    auto xruns = mStream->getXRunCount();
    const int32_t count = xruns ? xruns.value() : -1;

    if (mXRunMeasureLeft > 0) {
        mXRunBacklogMin = std::min(mXRunBacklogMin, backlog);
        if (--mXRunMeasureLeft > 0) return;
        int32_t excess = mXRunBacklogMin - (mBacklogFloor == INT32_MAX ? 0 : mBacklogFloor);
        while (excess > 0) {
            auto r = mInput->read(mDropBuffer.data(), std::min<int32_t>(excess, static_cast<int32_t>(mDropBuffer.size())), 0);
            if (!r || r.value() <= 0) break;
            excess -= r.value();
            mXRunCorrection.fetch_add(r.value(), std::memory_order_relaxed);
        }
        mLastOutXRuns = count;  // xruns during the window are covered by this measurement
        return;
    }
    if (count >= 0 && mLastOutXRuns >= 0 && count > mLastOutXRuns) {
        mXRunMeasureLeft = kXRunMeasureCallbacks;
        mXRunBacklogMin = backlog;
        return;
    }
    mLastOutXRuns = count;
    mBacklogFloor = std::min(mBacklogFloor, backlog);
}

oboe::DataCallbackResult PlaybackEngine::onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    // Completion first: if it is set, the decoded count read after it is final (see endLoad()).
    const bool complete = mComplete.load(std::memory_order_acquire);
    const int64_t decoded = mDecoded.load(std::memory_order_acquire);
    const Transport transport = mTransport.load(std::memory_order_acquire);
    // While recording, also catch up on input that arrived late in earlier callbacks.
    const int64_t want = numFrames + (transport == Transport::Recording ? mTakeDeficit : 0);
    const int numIn = readInput(static_cast<int>(std::min<int64_t>(want, INT32_MAX)));
    std::fill(out, out + static_cast<size_t>(numFrames) * 2, 0.0f);

    if (transport == Transport::Idle) {
        mIdleAck.store(true, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

    const int64_t seek = mSeekRequest.exchange(-1);
    const int64_t start = seek >= 0 ? seek : mPosition.load(std::memory_order_relaxed);
    // Plain playback follows the Transpose control live; a Take keeps the one it started with.
    const int transpose = transport == Transport::Playing ? mPlayTranspose.load(std::memory_order_relaxed)
                                                          : mTransportTranspose.load(std::memory_order_relaxed);
    // A Mix Preview's Guide Vocal joins the Backing Audio before the stretcher (#16).
    const int64_t guideDecoded = mGuideDecoded.load(std::memory_order_acquire);
    const float guideLevel = mGuideLevel.load(std::memory_order_relaxed);
    const bool guided = transport == Transport::PlayingTake && guideDecoded > 0 && guideLevel > 0.0f;
    if (guided) mGuided.prepare(guideDecoded, guideLevel);
    const StereoFrames &source = guided ? static_cast<const StereoFrames &>(mGuided) : mBlockFrames;
    const int32_t written = mBacking.render(source, start, decoded, complete, seek >= 0, transpose, out, numFrames);
    int64_t pos = start + written;
    bool finished = complete && pos >= decoded;

    if (transport == Transport::Recording) {
        // Sample i is what came in while timeline frame mTakeStart + i went out. Input is written
        // contiguously but never ahead of the output: frames read while the output waits for
        // decoding are dropped, since the singer heard nothing new.
        const auto capacity = static_cast<int64_t>(mTake.size());
        const int64_t outputCursor = std::min<int64_t>(pos - mTakeStart, capacity);
        const int64_t n = std::min<int64_t>(numIn, outputCursor - mTakeCursor);
        if (mTakeCursor >= 0 && n > 0) {
            std::copy_n(mInputBuffer.data(), static_cast<size_t>(n), mTake.begin() + mTakeCursor);
            mTakeCursor += n;
        }
        // Input short of the output arrived late (read it next time), unless it stays missing:
        // then it was lost, and the Take gets a silent gap instead of shifting earlier.
        mTakeDeficit = std::max<int64_t>(outputCursor - mTakeCursor, 0);
        mDeficitCallbacks = mTakeDeficit > 0 ? mDeficitCallbacks + 1 : 0;
        if (mDeficitCallbacks > kLostInputCallbacks) {
            mTakeCursor = outputCursor;
            mTakeDeficit = 0;
            mDeficitCallbacks = 0;
        }
        if (mTakeCursor > mTakeFrames.load(std::memory_order_relaxed)) {
            mTakeFrames.store(mTakeCursor, std::memory_order_relaxed);
        }
        if (outputCursor >= capacity) finished = true;
        if (mInput) trackXRuns();
    } else if (transport == Transport::PlayingTake) {
        const int64_t takeFrames = mTakeFrames.load(std::memory_order_relaxed);
        const int64_t align = mTakeAlignment.load(std::memory_order_relaxed);
        // Review Balance: the Backing Audio plays quieter, so the voice can be judged. A Mix Preview
        // sets its own levels; the Offline Render mixes the same way.
        // A Mix Preview's Vocal Effects, from a fresh state when the Take starts, like the Offline Render.
        const int32_t bits = mEffectsBits.load(std::memory_order_relaxed);
        const VocalEffects effects{static_cast<Reverb>(bits & 3), (bits & (1 << 2)) != 0, (bits & (1 << 3)) != 0};
        if (seek >= 0) mEffects.reset();
        if (effects != mEffects.effects()) mEffects.set(effects);
        mixTake(out, numFrames, start, mTakeStart, align, mTake.data(), takeFrames,
                mReviewBackingGain.load(std::memory_order_relaxed), mTakeGain.load(std::memory_order_relaxed), &mEffects);
        pos = start + numFrames;  // the Take may run on past the decoded Backing Audio
        // With a reverb, it runs on for the tail.
        if (pos - mTakeStart + align >= takeFrames + mTakeTail.load(std::memory_order_relaxed)) finished = true;
    }

    clipOutput(out, numFrames);
    // A seek that arrived while this callback ran wins over our position.
    if (mSeekRequest.load(std::memory_order_relaxed) < 0) mPosition.store(pos, std::memory_order_relaxed);
    if (finished) {
        // Reached the end; plain playback's stream is stopped by Kotlin, a Session's keeps running.
        Transport expected = transport;
        mTransport.compare_exchange_strong(expected, Transport::Idle);
    }
    return oboe::DataCallbackResult::Continue;
}

}  // namespace meavox
