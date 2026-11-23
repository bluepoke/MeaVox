// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import android.util.Log
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.MixRender
import eu.schicht8.meavox.core.port.OfflineRenderer
import eu.schicht8.meavox.core.port.PcmSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import kotlin.math.pow

/**
 * The Audio Engine's Offline Render ([OfflineRenderer], #14, #24) on the native `OfflineMixRender`,
 * which shares its DSP with real-time playback. Files are decoded as for playback ([AudioDecoder]):
 * a Mix's Take first, whole, then the Backing Audio from its start, streamed through, so only a
 * short part of it is held at a time. A Guide Vocal (#16) is read from its FLAC in step with the
 * Backing Audio, as the Mix Preview reads it. Decoding stops once the span is rendered.
 */
class NativeOfflineRenderer : OfflineRenderer {
    override suspend fun render(render: MixRender, sink: PcmSink) = runInterruptible(Dispatchers.IO) {
        // As in the Mix Preview, a Vocal Stem that can't be used leaves the Mix without a Guide Vocal.
        val guide = render.guideVocal?.let { openGuide(it, render.sampleRate) }
        val handle = NativeMixRender.create(
            render.sampleRate,
            render.fromFrame,
            render.toFrame,
            render.takeStartFrame,
            render.alignmentFrames,
            render.transpose.semitones,
            linear(render.backingGainDb),
            linear(render.takeGainDb),
            render.effects.reverb.native(),
            render.effects.compression,
            render.effects.highPass,
            if (guide != null) render.guideVocalLevel else 0f,
        )
        try {
            AudioDecoder.decode(render.take.path, TakeSink(handle, render.sampleRate))
            val written = renderBacking(handle, render.backing, render.sampleRate, sink, guide)
            if (written != render.frames) throw IOException("rendered $written of ${render.frames} frames")
        } finally {
            guide?.close()
            NativeMixRender.release(handle)
        }
    }

    override suspend fun renderBacking(backing: AudioFile, transpose: Transpose, sink: PcmSink): Long =
        runInterruptible(Dispatchers.IO) {
            val rate = rateOf(backing)
            // No Take, at unit gain, until the Backing Audio ends: the Backing Audio alone, as a Session plays it.
            val handle = NativeMixRender.create(
                rate, 0, NativeMixRender.UNTIL_END, 0, 0, transpose.semitones, 1f, 1f, NativePlayback.REVERB_DRY, false, false, 0f,
            )
            try {
                renderBacking(handle, backing, rate, sink)
            } finally {
                NativeMixRender.release(handle)
            }
        }

    override suspend fun sampleRate(audio: AudioFile): Int = runInterruptible(Dispatchers.IO) { rateOf(audio) }

    /** [vocalStem] opened for the Guide Vocal; null, as the Mix Preview has none then, if it can't be read at [rate]. */
    private fun openGuide(vocalStem: AudioFile, rate: Int): FlacFiles.Reader? {
        val reader = try {
            FlacFiles.reader(File(vocalStem.path))
        } catch (e: IOException) {
            Log.w(TAG, "no Guide Vocal: ${e.message}")
            return null
        }
        if (reader.sampleRate == rate) return reader
        Log.w(TAG, "no Guide Vocal: ${vocalStem.path} at ${reader.sampleRate} Hz doesn't fit the Mix at $rate Hz")
        reader.close()
        return null
    }

    /** The rate the decoder hands out, which is what playback runs at (it may differ from the container's). */
    private fun rateOf(audio: AudioFile): Int {
        var rate = 0
        try {
            AudioDecoder.decode(
                audio.path,
                object : AudioDecoder.Sink {
                    override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
                        rate = sampleRate
                        throw EnoughDecoded()
                    }

                    override fun append(interleaved: FloatArray, frames: Int) = Unit
                },
            )
        } catch (_: EnoughDecoded) {
            // The rate is known.
        }
        if (rate <= 0) throw IOException("no audio in ${audio.path}")
        return rate
    }

    /** Streams [backing] (with [guide] in step, if any) through the render [handle] into [sink]; returns the frames written. */
    private fun renderBacking(handle: Long, backing: AudioFile, rate: Int, sink: PcmSink, guide: FlacFiles.Reader? = null): Long {
        val feed = BackingSink(handle, rate, sink, guide)
        try {
            AudioDecoder.decode(backing.path, feed)
        } catch (_: EnoughDecoded) {
            // Everything needed is decoded.
        }
        NativeMixRender.endBacking(handle)
        feed.drain()
        return feed.written
    }

    private class TakeSink(private val handle: Long, private val rate: Int) : AudioDecoder.Sink {
        private var channels = 1

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            if (sampleRate != rate) throw IOException("Take at $sampleRate Hz doesn't fit the Mix at $rate Hz")
            this.channels = channels
        }

        override fun append(interleaved: FloatArray, frames: Int) = NativeMixRender.appendTake(handle, interleaved, frames, channels)
    }

    private class BackingSink(
        private val handle: Long,
        private val rate: Int,
        private val sink: PcmSink,
        private val guide: FlacFiles.Reader?,
    ) : AudioDecoder.Sink {
        private var channels = 1
        private val out = FloatArray(DRAIN_FRAMES * 2)
        private var guideBlock = FloatArray(0)
        private var guideEnded = false
        var written = 0L
            private set

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            if (sampleRate != rate) throw IOException("Backing Audio at $sampleRate Hz doesn't fit the render at $rate Hz")
            this.channels = channels
        }

        override fun append(interleaved: FloatArray, frames: Int) {
            feedGuide(frames)
            NativeMixRender.appendBacking(handle, interleaved, frames, channels)
            drain()
            if (!NativeMixRender.needsBacking(handle)) throw EnoughDecoded()
        }

        /** The Guide Vocal frames of the same index as the next [frames] Backing Audio frames, fed first. */
        private fun feedGuide(frames: Int) {
            val reader = guide ?: return
            if (guideBlock.size < frames * reader.channels) guideBlock = FloatArray(frames * reader.channels)
            var fed = 0
            while (fed < frames && !guideEnded) {
                val n = reader.read(guideBlock, frames - fed)
                if (n == 0) guideEnded = true else NativeMixRender.appendGuide(handle, guideBlock, n, reader.channels)
                fed += n
            }
        }

        fun drain() {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("render cancelled")
                val n = NativeMixRender.drain(handle, out, DRAIN_FRAMES)
                if (n == 0) return
                sink.write(out, n)
                written += n
            }
        }
    }

    /** Ends decoding early: what was needed is decoded. */
    private class EnoughDecoded : RuntimeException()

    private companion object {
        const val DRAIN_FRAMES = 8192

        fun linear(db: Float): Float = 10f.pow(db / 20f)

        const val TAG = "MeaVoxEngine"
    }
}
