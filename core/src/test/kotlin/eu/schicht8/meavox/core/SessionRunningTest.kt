// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whether something runs in the Session (#48), which keeps the screen on: the Track's screen must
 * not time out while the singer listens, waits for the countdown, sings or hears a Take or a Mix back.
 */
class SessionRunningTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val files = InMemoryTrackFiles()
    private val engine = FakeAudioEngine(mapOf(files.originalAudio(trackId) to SONG_MS))
    private val mixes = Mixes(tracks, takes, InMemoryMixRepository(), files, FakeLoudnessMeter())
    private val sessions = Sessions(tracks, takes, files, engine, InMemorySettingsRepository(), mixes, clock = { 0 })

    private suspend fun TestScope.open(): Session {
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", SONG_MS, TrackPipelineState.Downloaded))
        return sessions.open(trackId, backgroundScope)
    }

    private suspend fun Session.isRunning(): Boolean = running.first()

    @Test
    fun `a Session runs while its Backing Audio plays, not when opened or paused`() = runTest {
        val session = open()
        assertFalse(session.isRunning(), "just opened")

        session.play()
        assertTrue(session.isRunning(), "playing")

        session.pause()
        assertFalse(session.isRunning(), "paused")
    }

    @Test
    fun `the countdown runs, although no audio plays yet, and so does the recording after it`() = runTest {
        val session = open()
        session.setCountdownEnabled(true)

        launch { session.record() }
        runCurrent()
        assertIs<SessionState.CountingDown>(session.state.value)
        assertFalse(engine.playback.value.playing, "the countdown is silent")
        assertTrue(session.isRunning(), "counting down")

        advanceTimeBy(3_000)
        runCurrent()
        assertIs<SessionState.Recording>(session.state.value)
        assertTrue(session.isRunning(), "recording")
    }

    @Test
    fun `stopping the countdown stops the Session running`() = runTest {
        val session = open()
        session.setCountdownEnabled(true)
        launch { session.record() }
        runCurrent()

        session.stop()

        assertFalse(session.isRunning())
    }

    @Test
    fun `Review runs while the Take plays back, and stops running when it has played to its end`() = runTest {
        val session = open()
        session.record()
        engine.advance(2_000)
        session.stop()
        assertIs<SessionState.Review>(session.state.value)
        assertTrue(session.isRunning(), "Review plays the Take")

        engine.advance(2_000)
        runCurrent()

        assertIs<SessionState.Review>(session.state.value)
        assertFalse(session.isRunning(), "the Take has played to its end")
    }

    @Test
    fun `pausing Review stops the Session running, although the Take stays in Review`() = runTest {
        val session = open()
        session.record()
        engine.advance(4_000)
        session.stop()

        session.pause()

        assertIs<SessionState.Review>(session.state.value)
        assertFalse(session.isRunning())
    }

    @Test
    fun `a kept Take and a Mix Preview run while they play, not once stopped`() = runTest {
        val session = open()
        session.record()
        engine.advance(5_000)
        session.stop()
        val take = session.keep()
        assertFalse(session.isRunning(), "kept, back to free playback, paused")

        session.play(take.id)
        assertIs<SessionState.PlayingTake>(session.state.value)
        assertTrue(session.isRunning(), "the kept Take plays")
        session.stop()
        assertFalse(session.isRunning(), "the kept Take stopped")

        session.previewMix(mixes.observe(trackId).first().single().id)
        assertIs<SessionState.PreviewingMix>(session.state.value)
        assertTrue(session.isRunning(), "the Mix Preview plays")
        session.stop()
        assertFalse(session.isRunning(), "the Mix Preview stopped")
    }

    private companion object {
        const val SONG_MS = 180_000L
    }
}
