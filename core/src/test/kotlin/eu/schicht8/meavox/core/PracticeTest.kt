// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.mix.GuideVocalUnavailable
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.session.PracticeLoop
import eu.schicht8.meavox.core.session.Session
import eu.schicht8.meavox.core.session.SessionState
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.core.testing.FakeAudioEngine
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

/** Learning a song in a Session (#18, ADR 9): the Practice Loop and the Guide Vocal. */
class PracticeTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val files = InMemoryTrackFiles()
    private val settings = InMemorySettingsRepository()
    private val instrumental = files.stems(trackId, MODEL).instrumental
    private val vocalStem = files.stems(trackId, MODEL).vocalStem
    private val engine = FakeAudioEngine(mapOf(files.originalAudio(trackId) to SONG_MS, instrumental to SONG_MS))
    private val mixes = Mixes(tracks, takes, InMemoryMixRepository(), files, settings)
    private val sessions = Sessions(tracks, takes, files, engine, settings, mixes, clock = { NOW })

    // --- Practice Loop ---

    @Test
    fun `a Practice Loop from A to B repeats free playback without recording, at any Transpose`() = runTest {
        val session = openReady()
        session.seekTo(30_000)
        session.markLoopStart()
        session.seekTo(40_000)

        assertTrue(session.markLoopEnd())
        assertEquals(PracticeLoop(30_000, 40_000), session.practiceLoop.value)
        // It starts over from A right away.
        assertEquals(30_000, engine.playback.value.positionMs)
        session.play()
        session.setTranspose(Transpose(2))
        engine.advance(12_000)

        assertEquals(32_000, engine.playback.value.positionMs)
        assertTrue(engine.playback.value.playing)
        assertFalse(engine.playback.value.recording)
        assertEquals(Transpose(2), engine.audibleTranspose)
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun `B needs an A at least a second before it`() = runTest {
        val session = openReady()
        session.seekTo(40_000)
        assertFalse(session.markLoopEnd())

        session.markLoopStart()
        session.seekTo(40_500)

        assertFalse(session.markLoopEnd())
        assertEquals(PracticeLoop(40_000), session.practiceLoop.value)
        assertNull(engine.practiceLoop)
    }

    @Test
    fun `moving A to less than a second before B unsets B`() = runTest {
        val session = openReady()
        loop(session, 30_000, 40_000)

        session.seekTo(39_500)
        session.markLoopStart()

        assertEquals(PracticeLoop(39_500), session.practiceLoop.value)
        assertNull(engine.practiceLoop)
    }

    @Test
    fun `without the loop, playback plays on past B`() = runTest {
        val session = openReady()
        loop(session, 30_000, 40_000)

        session.clearPracticeLoop()
        session.play()
        engine.advance(12_000)

        assertNull(session.practiceLoop.value)
        assertEquals(42_000, engine.playback.value.positionMs)
    }

    @Test
    fun `a Take recorded while a loop is set runs straight on, and the loop is still there afterwards`() = runTest {
        val session = openReady()
        loop(session, 30_000, 40_000)
        session.seekTo(35_000)

        session.record()
        engine.advance(10_000)
        session.stop()

        assertEquals(10_000, assertIs<SessionState.Review>(session.state.value).take.durationMs)
        session.discard()
        assertEquals(PracticeLoop(30_000, 40_000), session.practiceLoop.value)
        assertEquals(30_000L..40_000L, engine.practiceLoop)
    }

    @Test
    fun `the loop ends with the Session`() = runTest {
        val session = openReady()
        loop(session, 30_000, 40_000)

        session.close()

        assertNull(engine.practiceLoop)
    }

    private suspend fun loop(session: Session, startMs: Long, endMs: Long) {
        session.seekTo(startMs)
        session.markLoopStart()
        session.seekTo(endMs)
        assertTrue(session.markLoopEnd())
    }

    // --- Guide Vocal ---

    @Test
    fun `the Guide Vocal blends the Vocal Stem into free playback at its level`() = runTest {
        val session = openReady()

        session.setGuideVocalLevel(0.3f)

        assertEquals(0.3f, session.guideVocalLevel.value)
        assertEquals(vocalStem, engine.guideVocal)
        assertEquals(0.3f, engine.guideVocalLevel)
    }

    @Test
    fun `switching to the Original Audio drops the Guide Vocal, and back to the Instrumental brings it again`() = runTest {
        val session = openReady()
        session.setGuideVocalLevel(0.5f)

        session.chooseBacking(BackingChoice.OriginalAudio)
        runCurrent()
        assertEquals(GuideVocalUnavailable.OverOriginalAudio, session.guideVocalUnavailable.value)
        assertNull(engine.guideVocal)
        assertEquals(0f, engine.guideVocalLevel)

        session.chooseBacking(BackingChoice.Instrumental)
        runCurrent()
        assertNull(session.guideVocalUnavailable.value)
        assertEquals(vocalStem, engine.guideVocal)
        assertEquals(0.5f, engine.guideVocalLevel)
    }

    @Test
    fun `a Vocal-Free Track has no Guide Vocal, and says why`() = runTest {
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon (Karaoke)", null, SONG_MS, TrackPipelineState.Ready(model = null), vocalFree = true))
        val session = sessions.open(trackId, backgroundScope)

        session.setGuideVocalLevel(0.5f)

        assertEquals(GuideVocalUnavailable.VocalFree, session.guideVocalUnavailable.value)
        assertNull(engine.guideVocal)
        assertEquals(0f, engine.guideVocalLevel)
    }

    @Test
    fun `marking the Track Vocal-Free during the Session drops the Guide Vocal`() = runTest {
        val session = openReady()
        session.setGuideVocalLevel(0.5f)

        tracks.update(trackId) { it.copy(vocalFree = true, pipeline = TrackPipelineState.Ready(model = null)) }
        runCurrent()

        assertEquals(GuideVocalUnavailable.VocalFree, session.guideVocalUnavailable.value)
        assertNull(engine.guideVocal)
    }

    @Test
    fun `the Guide Vocal plays on while recording over headphones, but Review and kept Takes play without it`() = runTest {
        val session = openReady()
        engine.outputRoute = OutputRoute.Wired
        session.setGuideVocalLevel(0.4f)

        session.record()
        assertEquals(0.4f, engine.guideVocalLevel)
        engine.advance(5_000)
        session.stop()
        assertIs<SessionState.Review>(session.state.value)
        assertEquals(0f, engine.guideVocalLevel)

        val take = session.keep()
        assertEquals(0.4f, engine.guideVocalLevel)
        // The Take is the microphone alone; its Mix starts without a Guide Vocal, too.
        assertEquals(0f, mixes.observe(trackId).first().single().settings.guideVocalLevel)

        session.play(take.id)
        assertEquals(0f, engine.guideVocalLevel)
    }

    @Test
    fun `recording over the speaker silences the Guide Vocal, so the microphone can't pick it up`() = runTest {
        val session = openReady()
        engine.outputRoute = OutputRoute.Speaker
        session.setGuideVocalLevel(0.4f)

        session.record()
        assertEquals(0f, engine.guideVocalLevel)
        engine.advance(5_000)
        session.stop()
        session.discard()

        assertEquals(0.4f, engine.guideVocalLevel)
    }

    @Test
    fun `a Mix Preview plays its own Guide Vocal level, and the Session's comes back after it`() = runTest {
        val session = openReady()
        session.record()
        engine.advance(5_000)
        session.stop()
        session.keep()
        session.setGuideVocalLevel(0.4f)

        session.previewMix(mixes.observe(trackId).first().single().id)
        assertEquals(0f, engine.guideVocalLevel)
        session.stop()

        assertEquals(vocalStem, engine.guideVocal)
        assertEquals(0.4f, engine.guideVocalLevel)
    }

    @Test
    fun `recording over an output that may not be headphones silences the Guide Vocal too`() = runTest {
        val session = openReady()
        engine.outputRoute = OutputRoute.Other
        session.setGuideVocalLevel(0.4f)

        session.record()

        assertEquals(0f, engine.guideVocalLevel)
    }

    @Test
    fun `raising the Guide Vocal for the first time during a recording brings it in after the Take`() = runTest {
        val session = openReady()
        session.record()

        session.setGuideVocalLevel(0.4f)
        assertNull(engine.guideVocal)
        engine.advance(5_000)
        session.stop()
        session.discard()

        assertEquals(vocalStem, engine.guideVocal)
        assertEquals(0.4f, engine.guideVocalLevel)
    }

    @Test
    fun `turning the Guide Vocal down to 0 keeps its Vocal Stem, so turning it up again is immediate`() = runTest {
        val session = openReady()
        session.setGuideVocalLevel(0.4f)

        session.setGuideVocalLevel(0f)

        assertEquals(vocalStem, engine.guideVocal)
        assertEquals(0f, engine.guideVocalLevel)
    }

    private suspend fun TestScope.openReady(): Session {
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", SONG_MS, TrackPipelineState.Ready(MODEL)))
        return sessions.open(trackId, backgroundScope)
    }

    private companion object {
        const val SONG_MS = 180_000L
        const val NOW = 1_700_000_000_000L
        val MODEL = SeparationModelId("fast")
    }
}
