// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.mix

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.library.sizeOfStems
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.MixRender
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.TakeRepository
import kotlinx.coroutines.flow.first

/** What a Mix plays now: its Take over [backing], which may differ from the stored Mix after re-separation. */
internal data class MixParts(val mix: Mix, val track: Track, val take: Take, val backing: BackingAudio) {
    /** Frames the Take is moved earlier (its latency with the Latency Correction, minus the Vocal Nudge). */
    fun alignmentFrames(settings: MixSettings = mix.settings): Long = take.alignmentFrames(settings)

    /** The Vocal Stem blended in as the Guide Vocal, if the Mix can have one and wants it. */
    fun guideVocal(files: TrackFiles, settings: MixSettings = mix.settings): AudioFile? {
        val instrumental = backing as? BackingAudio.Instrumental ?: return null
        if (settings.guideVocalLevel <= 0f || track.guideVocalUnavailable(backing) != null) return null
        return files.stems(track.id, instrumental.model).vocalStem
    }

    /**
     * The Offline Render of the Mix: the span Review plays, from the Start Position until the Take
     * ends, and on for the reverb's tail.
     */
    fun render(files: TrackFiles): MixRender {
        val settings = mix.settings
        val alignment = alignmentFrames(settings)
        val from = take.startFrame
        val guideVocal = guideVocal(files)
        return MixRender(
            backing = files.fileOf(track.id, backing),
            take = files.take(track.id, take.id),
            sampleRate = take.sampleRate,
            takeStartFrame = take.startFrame,
            alignmentFrames = alignment,
            transpose = take.transpose,
            backingGainDb = settings.backingDb,
            takeGainDb = settings.vocalDb,
            fromFrame = from,
            toFrame = maxOf(from, take.startFrame + take.frames - alignment) + settings.effects.tailFrames(take.sampleRate),
            effects = settings.effects,
            guideVocal = guideVocal,
            guideVocalLevel = if (guideVocal != null) settings.guideVocalLevel else 0f,
        )
    }
}

/** The file of [backing] for [track]: its Original Audio, or the Instrumental of a Separation Model. */
internal fun TrackFiles.fileOf(track: TrackId, backing: BackingAudio): AudioFile = when (backing) {
    BackingAudio.OriginalAudio -> originalAudio(track)
    is BackingAudio.Instrumental -> stems(track, backing.model).instrumental
}

/**
 * Switches the Track's Mixes on an Instrumental to the Original Audio, which keeps them aligned:
 * stems are sample-aligned with it (ADR 14).
 */
internal suspend fun MixRepository.switchToOriginalAudio(track: TrackId) {
    observe(track).first().filter { it.backing is BackingAudio.Instrumental }.forEach { update(it.copy(backing = BackingAudio.OriginalAudio)) }
}

/**
 * The Mix's Take and Backing Audio as the Track is now. A Mix keeps the Instrumental of its Separation
 * Model while those stems are stored; once they are gone it switches to the Track's current
 * Instrumental and is stored with it (ADR 7: stems are sample-aligned with the Original Audio).
 * @throws MixUnavailable if the Mix, its Take or its Instrumental isn't there.
 */
internal suspend fun resolveMix(
    id: MixId,
    mixes: MixRepository,
    tracks: TrackRepository,
    takes: TakeRepository,
    files: TrackFiles,
): MixParts {
    val mix = mixes.get(id) ?: throw MixUnavailable(MixUnavailable.Why.MixDeleted)
    val track = tracks.get(mix.track) ?: throw MixUnavailable(MixUnavailable.Why.TrackDeleted)
    val take = takes.get(mix.take) ?: throw MixUnavailable(MixUnavailable.Why.TakeDeleted)
    val backing = when (val wanted = mix.backing) {
        // A Vocal-Free Track has no Instrumental (ADR 14); its Mixes were switched when it was marked.
        BackingAudio.OriginalAudio -> wanted
        is BackingAudio.Instrumental -> when {
            track.vocalFree -> BackingAudio.OriginalAudio
            track.instrumentalModel == wanted.model || files.sizeOfStems(track.id, wanted.model) > 0 -> wanted
            else -> {
                val current = track.instrumentalModel ?: throw MixUnavailable(MixUnavailable.Why.InstrumentalNotReady)
                BackingAudio.Instrumental(current).also { mixes.update(mix.copy(backing = it)) }
            }
        }
    }
    return MixParts(mix, track, take, backing)
}

/** A Mix can't be played or exported now, because of [why]; the UI words it (#43). */
class MixUnavailable(val why: Why) : IllegalStateException(why.name) {
    enum class Why {
        MixDeleted,
        TrackDeleted,
        TakeDeleted,

        /** The Mix's Instrumental is gone and the Track has no other one yet (e.g. it is being made again). */
        InstrumentalNotReady,
    }
}
