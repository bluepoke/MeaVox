// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.port.LoudnessMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * The Audio Engine's loudness measurement ([LoudnessMeter], #44) on the native `IntegratedLoudness`, after
 * ITU-R BS.1770 / EBU R128. The file is decoded as for playback ([AudioDecoder]) and only the asked
 * span is measured; decoding stops once it is passed.
 */
class NativeLoudnessMeter : LoudnessMeter {
    override suspend fun integratedLoudness(audio: AudioFile, fromFrame: Long, toFrame: Long): Float? =
        runInterruptible(Dispatchers.IO) {
            val sink = SpanSink(fromFrame, toFrame)
            try {
                try {
                    AudioDecoder.decode(audio.path, sink)
                } catch (_: SpanPassed) {
                    // Everything in the span is measured.
                }
                sink.integrated()
            } finally {
                sink.release()
            }
        }

    /** Feeds the decoded frames in [from] until [to] to a native meter, made at the decoded rate. */
    private class SpanSink(private val from: Long, private val to: Long) : AudioDecoder.Sink {
        private var handle = 0L
        private var channels = 1
        private var decoded = 0L

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            this.channels = channels
            handle = NativeLoudness.create(sampleRate)
        }

        override fun append(interleaved: FloatArray, frames: Int) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("measuring cancelled")
            val start = (from - decoded).coerceIn(0, frames.toLong()).toInt()
            val end = (to - decoded).coerceIn(0, frames.toLong()).toInt()
            if (end > start) NativeLoudness.add(handle, interleaved, start, end - start, channels)
            decoded += frames
            if (decoded >= to) throw SpanPassed()
        }

        /** The loudness measured, null if none. */
        fun integrated(): Float? =
            if (handle == 0L) null else NativeLoudness.integrated(handle).takeUnless { it.isNaN() }?.toFloat()

        fun release() {
            if (handle != 0L) NativeLoudness.release(handle)
            handle = 0L
        }
    }

    /** Ends decoding early: the span is passed. */
    private class SpanPassed : RuntimeException()
}
