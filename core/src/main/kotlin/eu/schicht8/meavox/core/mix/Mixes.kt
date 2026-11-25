// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.mix

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.LoudnessMeter
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.TakeRepository
import eu.schicht8.meavox.core.session.backingAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlin.math.roundToInt

/**
 * A Track's Mixes (GLOSSARY: Mix, ADR 7): the default one made when a Take is kept, more of them
 * from any Take, and edits of their Mix Settings, which never touch the Take.
 */
class Mixes(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val mixes: MixRepository,
    private val files: TrackFiles,
    private val loudness: LoudnessMeter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newMixId: () -> MixId = { MixId(UUID.randomUUID().toString()) },
) {
    /** The Track's Mixes, oldest first. */
    fun observe(track: TrackId): Flow<List<Mix>> = mixes.observe(track)

    /**
     * A new Mix of the kept [take], over what the Track plays now (its [backingAudio]), with the
     * levels matched like a default Mix (#44).
     */
    suspend fun create(take: TakeId): Mix {
        val stored = checkNotNull(takes.get(take)) { "no Take ${take.value}" }
        val track = checkNotNull(tracks.get(stored.track)) { "no Track ${stored.track.key}" }
        return add(stored, track.backingAudio())
    }

    /** Stores new Mix Settings for the Mix; the Take is never changed (ADR 7). */
    suspend fun updateSettings(id: MixId, settings: MixSettings) {
        val mix = mixes.get(id) ?: return
        mixes.update(mix.copy(settings = settings))
    }

    /**
     * Match Level (#44): the Mix's Backing Audio back at its own level (0 dB) and the Take as loud as
     * it again, measured over the Backing Audio the Mix plays now (e.g. after switching it). Only the
     * two levels change, on the Mix as stored once they are measured; returns its Mix Settings then, or
     * null if it is gone. Mixes never change their levels on their own.
     * @throws MixUnavailable if the Mix can't be played now.
     */
    suspend fun matchLevel(id: MixId): MixSettings? {
        val parts = resolve(id)
        val vocalDb = matchedVocalDb(parts.take, parts.backing)
        // As stored now: resolving may have switched its Instrumental, and its settings may have changed meanwhile.
        val mix = mixes.get(id) ?: return null
        val matched = mix.settings.copy(backingDb = 0f, vocalDb = vocalDb)
        mixes.update(mix.copy(settings = matched))
        return matched
    }

    /**
     * Switches the Mix to the Track's Instrumental or to its Original Audio. Returns false, and
     * changes nothing, for the Instrumental while the Track has none (e.g. a Vocal-Free Track, ADR 14).
     */
    suspend fun chooseBacking(id: MixId, choice: BackingChoice): Boolean {
        val mix = mixes.get(id) ?: return false
        val backing = when (choice) {
            BackingChoice.OriginalAudio -> BackingAudio.OriginalAudio
            BackingChoice.Instrumental -> tracks.get(mix.track)?.instrumentalModel?.let(BackingAudio::Instrumental) ?: return false
        }
        mixes.update(mix.copy(backing = backing))
        return true
    }

    /** Deletes the Mix; its Take stays. */
    suspend fun delete(id: MixId) = mixes.delete(id)

    /**
     * Adds a Mix of [take] over [backing] with the levels matched (#44): the Backing Audio at its own
     * level, the Take as loud as it. The default Mix made when a Take is kept is one, over the Backing
     * Audio it was sung over.
     */
    internal suspend fun add(take: Take, backing: BackingAudio): Mix {
        val mix = Mix(
            id = newMixId(),
            track = take.track,
            take = take.id,
            name = nextName(take.track),
            backing = backing,
            settings = MixSettings(backingDb = 0f, vocalDb = matchedVocalDb(take, backing)),
            createdAt = clock(),
        )
        mixes.add(mix)
        return mix
    }

    /**
     * The vocal level that makes the dry [take] as loud as [backing] at its own level, where it was
     * sung: from its Start Position until it ends (#44). Vocal Effects aren't measured; their gain is
     * part of the preset. In whole dB, as the slider sets it, and within the vocal range. 0 dB when
     * either has no measurable loudness (a silent or very short Take) or can't be read, so keeping a
     * Take never fails for it.
     */
    private suspend fun matchedVocalDb(take: Take, backing: BackingAudio): Float {
        val (backingLufs, takeLufs) = try {
            loudness.integratedLoudness(files.fileOf(take.track, backing), take.startFrame, take.startFrame + take.frames) to
                loudness.integratedLoudness(files.take(take.track, take.id))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The file can't be read or decoded: as if it had no measurable loudness.
            return 0f
        }
        if (backingLufs == null || takeLufs == null) return 0f
        val matched = backingLufs - takeLufs + MATCH_TARGET_LU
        return matched.roundToInt().toFloat().coerceIn(MixSettings.MIN_VOCAL_DB, MixSettings.MAX_VOCAL_DB)
    }

    /** The Mix's Take and Backing Audio as the Track is now (see [resolveMix]). */
    internal suspend fun resolve(id: MixId): MixParts = resolveMix(id, mixes, tracks, takes, files)

    /** Deletes the Mixes of a Take that is being deleted. */
    internal suspend fun deleteForTake(take: TakeId) = mixes.deleteForTake(take)

    private suspend fun nextName(track: TrackId): String {
        val names = mixes.observe(track).first().mapTo(HashSet()) { it.name }
        var n = names.size + 1
        while ("Mix $n" in names) n++
        return "Mix $n"
    }

    companion object {
        /**
         * How much louder than the Backing Audio a matched Take sounds, in LU (#44): tuned by ear, from
         * 0 (the voice as loud as the backing).
         */
        const val MATCH_TARGET_LU = 0f
    }
}
