// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.mix

import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.session.BackingAudio
import kotlinx.coroutines.flow.Flow

/**
 * Non-destructive combination of one Backing Audio, one Take and [settings] (GLOSSARY: Mix, ADR 7).
 * Only the Mix Settings, the name and the Backing Audio change; the Take never does. The Mix spans
 * what Review plays: from the Take's Start Position until the Take ends.
 */
data class Mix(
    val id: MixId,
    val track: TrackId,
    val take: TakeId,
    val name: String,
    /** The Instrumental (by Separation Model) or the Original Audio (ADR 7, ADR 14). */
    val backing: BackingAudio,
    val settings: MixSettings,
    /** Epoch milliseconds. */
    val createdAt: Long,
)

@JvmInline
value class MixId(val value: String)

/** Levels, alignment and effects of a Mix (GLOSSARY: Mix Settings). */
data class MixSettings(
    /** Level of the Backing Audio in dB, [MIN_BACKING_DB]..0. */
    val backingDb: Float,
    /** Level of the Take in dB, [MIN_VOCAL_DB]..[MAX_VOCAL_DB]. */
    val vocalDb: Float = 0f,
    /** Vocal Nudge: the Take sounds this much later (negative: earlier), on top of Latency Calibration. */
    val vocalNudgeMs: Int = 0,
    /** The Vocal Effects on the Take (#16); none by default. */
    val effects: VocalEffects = VocalEffects.None,
    /**
     * How much of the Guide Vocal (the Vocal Stem) is blended into the Instrumental, 0..1 (#16): 0 is
     * none, 1 the vocals as loud as Separation found them, so the Instrumental sounds like the
     * Original Audio. It is part of the Backing Audio: transposed with it, and its level follows
     * [backingDb]. Only over an Instrumental ([guideVocalUnavailable]).
     */
    val guideVocalLevel: Float = 0f,
) {
    init {
        require(backingDb in MIN_BACKING_DB..0f) { "backing level $backingDb dB is outside $MIN_BACKING_DB..0" }
        require(vocalDb in MIN_VOCAL_DB..MAX_VOCAL_DB) { "vocal level $vocalDb dB is outside $MIN_VOCAL_DB..$MAX_VOCAL_DB" }
        require(vocalNudgeMs in -MAX_NUDGE_MS..MAX_NUDGE_MS) { "Vocal Nudge $vocalNudgeMs ms is outside ±$MAX_NUDGE_MS" }
        require(guideVocalLevel in 0f..1f) { "Guide Vocal level $guideVocalLevel is outside 0..1" }
    }

    companion object {
        const val MIN_BACKING_DB = -40f
        const val MIN_VOCAL_DB = -20f
        const val MAX_VOCAL_DB = 12f
        const val MAX_NUDGE_MS = 200

        /** Mix Settings with each value moved into its range. */
        fun clamped(
            backingDb: Float,
            vocalDb: Float,
            vocalNudgeMs: Int,
            effects: VocalEffects = VocalEffects.None,
            guideVocalLevel: Float = 0f,
        ) = MixSettings(
            backingDb.coerceIn(MIN_BACKING_DB, 0f),
            vocalDb.coerceIn(MIN_VOCAL_DB, MAX_VOCAL_DB),
            vocalNudgeMs.coerceIn(-MAX_NUDGE_MS, MAX_NUDGE_MS),
            effects,
            guideVocalLevel.coerceIn(0f, 1f),
        )
    }
}

/**
 * Frames the Take is moved earlier against the timeline when it plays in a Mix: its sample i sounds at
 * Start Position + i − alignment (spec #1). That is the Latency Calibration offset minus the Vocal
 * Nudge; until Latency Calibration (#9), the offset is the engine's estimate from recording.
 */
fun Take.alignmentFrames(settings: MixSettings): Long = reportedLatencyFrames - settings.vocalNudgeMs.toLong() * sampleRate / 1000

/** Persistence of Mixes. Implemented by the data layer (Room); faked in tests. */
interface MixRepository {
    /** The Track's Mixes, oldest first, updated as they change. */
    fun observe(track: TrackId): Flow<List<Mix>>

    suspend fun get(id: MixId): Mix?

    suspend fun add(mix: Mix)

    /** Replaces the stored Mix with the same [Mix.id]; does nothing if there is none. */
    suspend fun update(mix: Mix)

    suspend fun delete(id: MixId)

    /** Deletes the Mixes of one Take. */
    suspend fun deleteForTake(take: TakeId)

    /** Deletes all of the Track's Mixes. */
    suspend fun deleteAll(track: TrackId)
}
