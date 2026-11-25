// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.mix.GuideVocalUnavailable
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.mix.guideVocalUnavailable
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.Session
import eu.schicht8.meavox.core.session.SessionState
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.core.testing.FakeAudioEngine
import eu.schicht8.meavox.core.testing.FakeLoudnessMeter
import eu.schicht8.meavox.core.testing.InMemoryMixRepository
import eu.schicht8.meavox.core.testing.InMemorySettingsRepository
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val mixRepository = InMemoryMixRepository()
    private val files = InMemoryTrackFiles()
    private val settings = InMemorySettingsRepository()
    private val instrumental = files.stems(trackId, FAST).instrumental
    private val engine = FakeAudioEngine(mapOf(files.originalAudio(trackId) to SONG_MS, instrumental to SONG_MS))
    private val loudness = FakeLoudnessMeter()
    private var nextMix = 0
    private val mixes = Mixes(tracks, takes, mixRepository, files, loudness, clock = { NOW }, newMixId = { eu.schicht8.meavox.core.mix.MixId("m${++nextMix}") })
    private var nextTake = 0
    private val sessions = Sessions(tracks, takes, files, engine, settings, mixes, clock = { NOW }, newTakeId = { TakeId("t${++nextTake}") })

    private suspend fun addTrack(pipeline: TrackPipelineState, vocalFree: Boolean = false, choice: BackingChoice = BackingChoice.Instrumental) =
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", SONG_MS, pipeline, vocalFree = vocalFree, backingChoice = choice))

    @Test
    fun `keeping a Take creates a default Mix over the Backing Audio it was sung over, with the Take as loud as it`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        val session = sessions.open(trackId, backgroundScope)
        session.setReviewBackingDb(-14f)
        loudness.loudness[instrumental] = -9f
        loudness.loudness[files.take(trackId, TakeId("t1"))] = -27.4f

        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)

        val mix = mixes.observe(trackId).first().single()
        assertEquals(take.id, mix.take)
        assertEquals("Mix 1", mix.name)
        assertEquals(BackingAudio.Instrumental(FAST), mix.backing)
        // The Backing Audio at its own level, the Take 18.4 dB louder: in whole dB, as the slider sets it. The Review Balance doesn't count.
        assertEquals(MixSettings(backingDb = 0f, vocalDb = 18f, vocalNudgeMs = 0), mix.settings)
        // The Backing Audio is measured where the Take was sung: from its Start Position until it ends.
        assertEquals(
            FakeLoudnessMeter.Measured(instrumental, fromFrame = 30_000 * 48L, toFrame = 40_000 * 48L),
            loudness.measured.single { it.audio == instrumental },
        )
    }

    @Test
    fun `a Take with no measurable loudness, silent or too short, gets a new Mix at vocal 0 dB`() = runTest {
        val session = openReady()
        loudness.loudness[instrumental] = -9f

        keepTake(session, fromMs = 30_000, lengthMs = 300)

        assertEquals(MixSettings(backingDb = 0f, vocalDb = 0f), mixes.observe(trackId).first().single().settings)
    }

    @Test
    fun `a Take is kept even when the Backing Audio can't be measured, with its Mix at vocal 0 dB`() = runTest {
        val session = openReady()
        loudness.loudness[files.take(trackId, TakeId("t1"))] = -27f
        loudness.unreadable += instrumental

        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)

        assertEquals(take, takes.get(take.id))
        assertEquals(MixSettings(backingDb = 0f, vocalDb = 0f), mixes.observe(trackId).first().single().settings)
    }

    @Test
    fun `a vocal level beyond the vocal range is clamped to it`() = runTest {
        val session = openReady()
        loudness.loudness[instrumental] = -6f
        loudness.loudness[files.take(trackId, TakeId("t1"))] = -48f
        loudness.loudness[files.take(trackId, TakeId("t2"))] = 0f

        keepTake(session, fromMs = 0, lengthMs = 5_000)
        keepTake(session, fromMs = 10_000, lengthMs = 5_000)

        assertEquals(listOf(MixSettings.MAX_VOCAL_DB, -6f), mixes.observe(trackId).first().map { it.settings.vocalDb })
        loudness.loudness[instrumental] = -40f
        assertEquals(MixSettings.MIN_VOCAL_DB, mixes.create(TakeId("t2")).settings.vocalDb)
    }

    @Test
    fun `Match level sets both levels again over the Mix's Backing Audio, keeping its other settings`() = runTest {
        val session = openReady()
        loudness.loudness[instrumental] = -9f
        loudness.loudness[files.take(trackId, TakeId("t1"))] = -27f
        keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        val edited = MixSettings(backingDb = -10f, vocalDb = 2f, vocalNudgeMs = 30, effects = VocalEffects(VocalEffects.Reverb.Hall))
        mixes.updateSettings(mix.id, edited)
        loudness.loudness[files.originalAudio(trackId)] = -7f

        // Switching the Backing Audio leaves the levels as they are; only Match level sets them again.
        mixes.chooseBacking(mix.id, BackingChoice.OriginalAudio)
        assertEquals(edited, mixes.observe(trackId).first().single().settings)
        val matched = mixes.matchLevel(mix.id)

        assertEquals(edited.copy(backingDb = 0f, vocalDb = 20f), matched)
        assertEquals(matched, mixes.observe(trackId).first().single().settings)
        assertEquals(
            FakeLoudnessMeter.Measured(files.originalAudio(trackId), 30_000 * 48L, 40_000 * 48L),
            loudness.measured.last { it.audio != files.take(trackId, TakeId("t1")) },
        )
    }

    @Test
    fun `editing a Mix's levels and Vocal Nudge stores them and leaves the Take untouched`() = runTest {
        val session = openReady()
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()

        mixes.updateSettings(mix.id, MixSettings(backingDb = -9f, vocalDb = 3f, vocalNudgeMs = -35))

        assertEquals(MixSettings(backingDb = -9f, vocalDb = 3f, vocalNudgeMs = -35), mixes.observe(trackId).first().single().settings)
        assertEquals(take, takes.get(take.id))
        assertEquals(take.frames, engine.writtenTakes[files.take(trackId, take.id)]?.frames)
    }

    @Test
    fun `a Track can have several Mixes, of the same or different Takes`() = runTest {
        val session = openReady()
        val first = keepTake(session, fromMs = 0, lengthMs = 5_000)
        val second = keepTake(session, fromMs = 60_000, lengthMs = 5_000)

        loudness.loudness[instrumental] = -12f
        loudness.loudness[files.take(trackId, first.id)] = -20.2f

        val another = mixes.create(first.id)

        val all = mixes.observe(trackId).first()
        assertEquals(listOf("Mix 1", "Mix 2", "Mix 3"), all.map { it.name })
        assertEquals(listOf(first.id, second.id, first.id), all.map { it.take })
        assertEquals(another, all.last())
        // A new Mix starts like a default one: over what the Track plays now, with the Take as loud as it.
        assertEquals(BackingAudio.Instrumental(FAST), another.backing)
        assertEquals(MixSettings(backingDb = 0f, vocalDb = 8f), another.settings)
        assertEquals(FakeLoudnessMeter.Measured(instrumental, 0, 5_000 * 48L), loudness.measured.last { it.audio == instrumental })
    }

    @Test
    fun `deleting a Mix leaves its Take and the other Mixes`() = runTest {
        val session = openReady()
        val take = keepTake(session, fromMs = 0, lengthMs = 5_000)
        val kept = mixes.create(take.id)
        val deleted = mixes.observe(trackId).first().first()

        mixes.delete(deleted.id)

        assertEquals(listOf(kept), mixes.observe(trackId).first())
        assertEquals(take, takes.get(take.id))
    }

    @Test
    fun `deleting a Take deletes its Mixes`() = runTest {
        val session = openReady()
        val gone = keepTake(session, fromMs = 0, lengthMs = 5_000)
        val kept = keepTake(session, fromMs = 10_000, lengthMs = 5_000)

        session.delete(gone.id)

        assertEquals(listOf(kept.id), mixes.observe(trackId).first().map { it.take })
    }

    @Test
    fun `a Mix can switch to the Track's Instrumental once it has one, and back`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST), choice = BackingChoice.OriginalAudio)
        val session = sessions.open(trackId, backgroundScope)
        val mix = mixes.create(keepTake(session, fromMs = 0, lengthMs = 5_000).id)
        assertEquals(BackingAudio.OriginalAudio, mix.backing)

        assertTrue(mixes.chooseBacking(mix.id, BackingChoice.Instrumental))
        assertEquals(BackingAudio.Instrumental(FAST), mixes.observe(trackId).first().last().backing)

        assertTrue(mixes.chooseBacking(mix.id, BackingChoice.OriginalAudio))
        assertEquals(BackingAudio.OriginalAudio, mixes.observe(trackId).first().last().backing)
    }

    @Test
    fun `a Mix can't switch to an Instrumental the Track doesn't have`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))
        val session = sessions.open(trackId, backgroundScope)
        val mix = mixes.create(keepTake(session, fromMs = 0, lengthMs = 5_000).id)

        assertFalse(mixes.chooseBacking(mix.id, BackingChoice.Instrumental))
        assertEquals(BackingAudio.OriginalAudio, mixes.observe(trackId).first().last().backing)
    }

    @Test
    fun `previewing a Mix plays its Take over its Backing Audio with the Mix's levels and Nudge, at the Take's Transpose`() = runTest {
        val session = openReady()
        session.setTranspose(Transpose(-3))
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        session.setTranspose(Transpose.None)
        val mix = mixes.observe(trackId).first().single()
        mixes.updateSettings(mix.id, MixSettings(backingDb = -9f, vocalDb = 4f, vocalNudgeMs = 25))

        session.previewMix(mix.id)

        assertIs<SessionState.PreviewingMix>(session.state.value)
        assertTrue(engine.playback.value.playingTake)
        assertEquals(30_000, engine.playback.value.positionMs)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(Transpose(-3), engine.audibleTranspose)
        assertEquals(-9f, engine.reviewBackingGainDb)
        assertEquals(4f, engine.takeGainDb)
        // 25 ms later than the engine's estimate alone (4 800 frames): 4 800 − 1 200.
        assertEquals(take.reportedLatencyFrames - 1_200, engine.lastAlignmentFrames)
    }

    @Test
    fun `Mix Settings changed during the preview are heard at once`() = runTest {
        val session = openReady()
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        session.previewMix(mix.id)

        session.setPreviewSettings(MixSettings(backingDb = -6f, vocalDb = -2f, vocalNudgeMs = -50))

        assertEquals(-6f, engine.reviewBackingGainDb)
        assertEquals(-2f, engine.takeGainDb)
        assertEquals(take.reportedLatencyFrames + 2_400, engine.lastAlignmentFrames)
    }

    @Test
    fun `after the preview the Session plays its own Backing Audio again, at the Review Balance`() = runTest {
        val session = openReady()
        session.setReviewBackingDb(-18f)
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        mixes.chooseBacking(mix.id, BackingChoice.OriginalAudio)
        mixes.updateSettings(mix.id, MixSettings(backingDb = -5f, vocalDb = 6f))

        session.previewMix(mix.id)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
        engine.advance(take.durationMs)
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(BackingAudio.Instrumental(FAST), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(30_000, engine.playback.value.positionMs)
        assertEquals(-18f, engine.reviewBackingGainDb)
        assertEquals(0f, engine.takeGainDb)
    }

    @Test
    fun `stopping the preview returns to free playback`() = runTest {
        val session = openReady()
        keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        session.previewMix(mixes.observe(trackId).first().single().id)

        session.stop()

        assertEquals(SessionState.Idle, session.state.value)
        assertFalse(engine.playback.value.playing)
        assertNull(engine.heldTake)
    }

    @Test
    fun `a Session opened after leaving one during a preview plays Takes as recorded again`() = runTest {
        val first = openReady()
        keepTake(first, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        mixes.updateSettings(mix.id, MixSettings(backingDb = -5f, vocalDb = 9f))
        first.previewMix(mix.id)
        first.close()

        sessions.open(trackId, backgroundScope)

        assertEquals(0f, engine.takeGainDb)
        assertEquals(Session.DEFAULT_REVIEW_BACKING_DB, engine.reviewBackingGainDb)
    }

    @Test
    fun `a new Mix has no Vocal Effects and no Guide Vocal, and editing them stores them`() = runTest {
        val session = openReady()
        keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        val edited = mix.settings.copy(effects = VocalEffects(VocalEffects.Reverb.Room, compression = true), guideVocalLevel = 0.25f)

        assertEquals(VocalEffects.None, mix.settings.effects)
        assertEquals(0f, mix.settings.guideVocalLevel)
        mixes.updateSettings(mix.id, edited)

        assertEquals(edited, mixes.observe(trackId).first().single().settings)
    }

    @Test
    fun `the Guide Vocal can't be set on a Vocal-Free Track, nor over the Original Audio`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        val track = checkNotNull(tracks.get(trackId))

        assertNull(track.guideVocalUnavailable(BackingAudio.Instrumental(FAST)))
        assertEquals(GuideVocalUnavailable.OverOriginalAudio, track.guideVocalUnavailable(BackingAudio.OriginalAudio))
        assertEquals(GuideVocalUnavailable.VocalFree, track.copy(vocalFree = true).guideVocalUnavailable(BackingAudio.OriginalAudio))
    }

    @Test
    fun `previewing a Mix plays its Vocal Effects and its Guide Vocal`() = runTest {
        val session = openReady()
        keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        val effects = VocalEffects(VocalEffects.Reverb.Hall, compression = true, highPass = true)
        mixes.updateSettings(mix.id, mix.settings.copy(effects = effects, guideVocalLevel = 0.4f))

        session.previewMix(mix.id)

        assertEquals(effects, engine.vocalEffects)
        assertEquals(files.stems(trackId, FAST).vocalStem, engine.guideVocal)
        assertEquals(0.4f, engine.guideVocalLevel)
    }

    @Test
    fun `the preview runs on for the reverb's tail`() = runTest {
        val session = openReady()
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        mixes.updateSettings(mix.id, mix.settings.copy(effects = VocalEffects(VocalEffects.Reverb.Hall)))
        session.previewMix(mix.id)

        engine.advance(take.durationMs)
        runCurrent()
        assertIs<SessionState.PreviewingMix>(session.state.value)
        engine.advance(VocalEffects.Reverb.Hall.tailMs.toLong())
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun `raising the Guide Vocal during the preview brings it in, and Takes play dry without it afterwards`() = runTest {
        val session = openReady()
        keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        session.previewMix(mix.id)
        assertNull(engine.guideVocal)

        session.setPreviewSettings(mix.settings.copy(effects = VocalEffects(VocalEffects.Reverb.Room), guideVocalLevel = 0.5f))
        assertEquals(files.stems(trackId, FAST).vocalStem, engine.guideVocal)
        assertEquals(0.5f, engine.guideVocalLevel)
        session.stop()

        assertNull(engine.guideVocal)
        assertEquals(VocalEffects.None, engine.vocalEffects)
    }

    @Test
    fun `a preview over the Original Audio has no Guide Vocal`() = runTest {
        val session = openReady()
        keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        mixes.chooseBacking(mix.id, BackingChoice.OriginalAudio)
        mixes.updateSettings(mix.id, mix.settings.copy(guideVocalLevel = 0.5f))

        session.previewMix(mix.id)

        assertNull(engine.guideVocal)
        assertEquals(0f, engine.guideVocalLevel)
    }

    private suspend fun keepTake(session: Session, fromMs: Long, lengthMs: Long): Take {
        session.seekTo(fromMs)
        session.record()
        engine.advance(lengthMs)
        session.stop()
        return session.keep()
    }

    private suspend fun TestScope.openReady(): Session {
        addTrack(TrackPipelineState.Ready(FAST))
        return sessions.open(trackId, backgroundScope)
    }

    private companion object {
        const val SONG_MS = 180_000L
        const val NOW = 1_700_000_000_000L
        val FAST = SeparationModelId("fast")
    }
}
