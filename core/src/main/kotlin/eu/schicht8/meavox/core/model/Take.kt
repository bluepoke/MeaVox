// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.model

/**
 * One microphone recording of the user singing over a Track (GLOSSARY: Take). Its audio, timing
 * and [transpose] never change after recording (ADR 5, ADR 7); only its [name] can. Positions are frames on the Session timeline at
 * [sampleRate]; the audio is stored as mono FLAC at that rate (ADR 8).
 */
data class Take(
    val id: TakeId,
    val track: TrackId,
    val name: String,
    /** Start Position: where on the Track timeline the Take begins. */
    val startFrame: Long,
    val frames: Long,
    val sampleRate: Int,
    /** The output route it was recorded on; its Latency Correction is the route's (#9). */
    val route: OutputRoute,
    /** The engine's round-trip estimate at recording (ADR 13). */
    val reportedLatencyFrames: Long,
    /** The Transpose it was sung at; its Backing Audio always plays at it (ADR 5). */
    val transpose: Transpose,
    /** Epoch milliseconds. */
    val recordedAt: Long,
    /**
     * The Latency Correction of its route and output path when it was kept, set by ear in Review (#9, ADR 23).
     * Kept with the Take: correcting the route later doesn't move it.
     */
    val latencyCorrectionMs: Int = 0,
) {
    val startPositionMs: Long get() = startFrame * 1000 / sampleRate
    val durationMs: Long get() = frames * 1000 / sampleRate

    /** How late the microphone is against the playback: the reported round trip plus the Latency Correction. */
    val latencyFrames: Long get() = latencyFrames(reportedLatencyFrames, latencyCorrectionMs, sampleRate)
}

/** [reportedFrames] plus a Latency Correction of [correctionMs] at [sampleRate] (#9). */
fun latencyFrames(reportedFrames: Long, correctionMs: Int, sampleRate: Int): Long = reportedFrames + correctionMs.toLong() * sampleRate / 1000

@JvmInline
value class TakeId(val value: String)

/** Kind of output a Session plays through (GLOSSARY: the Latency Correction is per route). */
enum class OutputRoute { Speaker, Wired, Usb, Bluetooth, Other }

/**
 * Which output path a Session plays through (ADR 16): the normal one, or the low-latency one while
 * Monitoring is on. Their latencies differ, so the Latency Correction is per route and path (#9).
 */
enum class OutputPath { Normal, LowLatency }

/** Kind of microphone; the input gain is set per kind (ADR 9). */
enum class InputDevice { BuiltIn, Wired, Usb, Bluetooth, Other }
