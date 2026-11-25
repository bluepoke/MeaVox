// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.MicrophoneUnavailable
import eu.schicht8.meavox.core.session.NotPlayableYet
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val files = InMemoryTrackFiles()
    private val settings = InMemorySettingsRepository()
    private val instrumental = files.stems(trackId, MODEL).instrumental
    private val engine = FakeAudioEngine(mapOf(files.originalAudio(trackId) to SONG_MS, instrumental to SONG_MS))
    private val mixes = Mixes(tracks, takes, InMemoryMixRepository(), files, FakeLoudnessMeter())
    private val sessions = Sessions(tracks, takes, files, engine, settings, mixes, clock = { NOW })

    private suspend fun addTrack(pipeline: TrackPipelineState, lastTranspose: Transpose = Transpose.None) =
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", SONG_MS, pipeline, lastTranspose = lastTranspose))

    @Test
    fun `a Session on a Downloaded Track sings over its Original Audio`() = runTest {
        addTrack(TrackPipelineState.Downloaded)

        val session = sessions.open(trackId, backgroundScope)

        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
        assertTrue(engine.inSession)
    }

    @Test
    fun `a Session can't start before the Original Audio is stored`() = runTest {
        addTrack(TrackPipelineState.Added)

        assertFailsWith<NotPlayableYet> { sessions.open(trackId, backgroundScope) }
    }

    @Test
    fun `recording from any position captures a Take starting there and plays it back in Review`() = runTest {
        val session = openDownloaded()
        session.seekTo(30_000)

        session.record()
        engine.advance(10_000)
        session.stop()

        val review = assertIs<SessionState.Review>(session.state.value)
        assertEquals(30_000, review.take.startPositionMs)
        assertEquals(10_000, review.take.durationMs)
        // Review plays the Take against the Backing Audio right away, aligned by the engine's estimate.
        assertTrue(engine.playback.value.playingTake)
        assertEquals(engine.reportedLatencyFrames, engine.lastAlignmentFrames)
    }

    @Test
    fun `keeping a Take stores its audio, Start Position and route and lists it for the Track`() = runTest {
        val session = openDownloaded()
        engine.outputRoute = OutputRoute.Usb
        recordTake(session, fromMs = 30_000, lengthMs = 10_000)

        val kept = session.keep()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(listOf(kept), session.takes.first())
        assertEquals("Take 1", kept.name)
        assertEquals(30_000, kept.startPositionMs)
        assertEquals(10_000, kept.durationMs)
        assertEquals(OutputRoute.Usb, kept.route)
        assertEquals(NOW, kept.recordedAt)
        assertEquals(kept.frames, engine.writtenTakes[files.take(trackId, kept.id)]?.frames)
        assertNull(engine.heldTake)
    }

    @Test
    fun `a Take in Review can be played again after its playback ended`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 8_000, lengthMs = 2_000)
        engine.advance(2_000)
        runCurrent()
        assertFalse(engine.playback.value.playing)

        session.replay()

        assertIs<SessionState.Review>(session.state.value)
        assertTrue(engine.playback.value.playingTake)
        assertEquals(8_000, engine.playback.value.positionMs)
    }

    @Test
    fun `pausing Review playback keeps the Take in Review`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 4_000)

        session.pause()

        assertIs<SessionState.Review>(session.state.value)
        assertFalse(engine.playback.value.playing)
        assertEquals(4_000, engine.heldTake?.durationMs)
    }

    @Test
    fun `Review plays the Backing Audio 20 dB down by default so the voice can be heard`() = runTest {
        val session = openDownloaded()

        assertEquals(-20f, session.reviewBackingDb.value)
        assertEquals(-20f, engine.reviewBackingGainDb)
    }

    @Test
    fun `the Review Balance can be changed and is remembered`() = runTest {
        val first = openDownloaded()
        first.setReviewBackingDb(-27f)
        assertEquals(-27f, engine.reviewBackingGainDb)
        first.close()

        val second = sessions.open(trackId, backgroundScope)

        assertEquals(-27f, second.reviewBackingDb.value)
        assertEquals(-27f, engine.reviewBackingGainDb)
    }

    @Test
    fun `discarding a Take in Review stores nothing`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 5_000)

        session.discard()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(emptyList(), session.takes.first())
        assertEquals(emptyMap(), engine.writtenTakes)
        assertNull(engine.heldTake)
    }

    @Test
    fun `record again discards the Take and records a new one from the same Start Position`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 42_000, lengthMs = 5_000)

        session.recordAgain()

        assertEquals(SessionState.Recording(42_000, Transpose.None), session.state.value)
        engine.advance(3_000)
        session.stop()
        val review = assertIs<SessionState.Review>(session.state.value)
        assertEquals(42_000, review.take.startPositionMs)
        assertEquals(3_000, review.take.durationMs)
        assertEquals(emptyList(), session.takes.first())
    }

    @Test
    fun `kept Takes are numbered in order`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 1_000)
        session.keep()
        session.seekTo(0)
        recordTake(session, fromMs = 0, lengthMs = 1_000)

        session.keep()

        assertEquals(listOf("Take 1", "Take 2"), session.takes.first().map { it.name })
    }

    @Test
    fun `a recording that reaches the end of the song goes to Review by itself`() = runTest {
        val session = openDownloaded()
        session.seekTo(SONG_MS - 4_000)
        session.record()

        engine.advance(10_000)
        runCurrent()

        val review = assertIs<SessionState.Review>(session.state.value)
        assertEquals(4_000, review.take.durationMs)
    }

    @Test
    fun `with the countdown on, recording starts after three seconds`() = runTest {
        settings.setCountdownEnabled(true)
        val session = openDownloaded()
        session.seekTo(10_000)

        launch { session.record() }
        runCurrent()
        assertEquals(SessionState.CountingDown(10_000, secondsLeft = 3), session.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SessionState.CountingDown(10_000, secondsLeft = 2), session.state.value)
        assertFalse(engine.playback.value.playing)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(SessionState.Recording(10_000, Transpose.None), session.state.value)
        assertTrue(engine.playback.value.recording)
    }

    @Test
    fun `stopping during the countdown records nothing`() = runTest {
        val session = openDownloaded()
        session.setCountdownEnabled(true)

        launch { session.record() }
        advanceTimeBy(1_500)
        session.stop()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
        assertFalse(engine.playback.value.recording)
    }

    @Test
    fun `the countdown setting is remembered`() = runTest {
        val first = openDownloaded()
        first.setCountdownEnabled(true)
        first.close()

        val second = sessions.open(trackId, backgroundScope)

        assertTrue(second.countdownEnabled.value)
    }

    @Test
    fun `recording without a usable microphone fails and leaves the Session idle`() = runTest {
        val session = openDownloaded()
        engine.microphoneAvailable = false

        assertFailsWith<MicrophoneUnavailable> { session.record() }
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun `a Session on a Ready Track sings over the Instrumental`() = runTest {
        addTrack(TrackPipelineState.Ready(MODEL))

        val session = sessions.open(trackId, backgroundScope)

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
    }

    @Test
    fun `the Session switches to the Instrumental when Separation finishes, keeping its place`() = runTest {
        val session = openDownloaded()
        session.seekTo(20_000)
        session.play()

        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(20_000, engine.playback.value.positionMs)
        assertTrue(engine.playback.value.playing)
    }

    @Test
    fun `a Take is recorded and reviewed over one Backing Audio, so the switch waits until Review ends`() = runTest {
        val session = openDownloaded()
        session.record()
        engine.advance(5_000)

        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        session.stop()
        runCurrent()
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)

        session.keep()
        runCurrent()

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
    }

    @Test
    fun `record again sings over the Instrumental that became ready during Review`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 15_000, lengthMs = 5_000)
        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()

        session.recordAgain()

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(SessionState.Recording(15_000, Transpose.None), session.state.value)
        assertEquals(15_000, engine.playback.value.positionMs)
    }

    @Test
    fun `the input gain is remembered per microphone and applied to the one in use`() = runTest {
        val session = openDownloaded()
        session.enableMicrophone()
        runCurrent()
        session.setInputGain(6f)
        assertEquals(6f, engine.inputGainDb)

        engine.switchInputDevice(InputDevice.Usb)
        runCurrent()
        assertEquals(0f, session.inputGainDb.value)
        assertEquals(0f, engine.inputGainDb)
        session.setInputGain(-3f)

        engine.switchInputDevice(InputDevice.BuiltIn)
        runCurrent()
        assertEquals(6f, session.inputGainDb.value)
        assertEquals(6f, engine.inputGainDb)
    }

    @Test
    fun `a kept Take can be renamed`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 1_000)
        val take = session.keep()

        session.rename(take.id, "  Best so far ")

        assertEquals(listOf("Best so far"), session.takes.first().map { it.name })
    }

    @Test
    fun `deleting a kept Take removes it and its audio`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 1_000)
        val take = session.keep()

        session.delete(take.id)

        assertEquals(emptyList(), session.takes.first())
        assertEquals(listOf(files.take(trackId, take.id)), files.deletedTakes)
    }

    @Test
    fun `playing a kept Take plays its audio against the Backing Audio from its Start Position`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 12_000, lengthMs = 3_000)
        val take = session.keep()
        session.seekTo(90_000)

        session.play(take.id)

        assertEquals(SessionState.PlayingTake(take), session.state.value)
        assertTrue(engine.playback.value.playingTake)
        assertEquals(12_000, engine.playback.value.positionMs)
        assertEquals(take.reportedLatencyFrames, engine.lastAlignmentFrames)

        engine.advance(3_000)
        runCurrent()
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun `changing the Transpose during free playback is heard at once, without restarting`() = runTest {
        val session = openDownloaded()
        assertEquals(Transpose.None, session.transpose.value)
        session.seekTo(20_000)
        session.play()
        engine.advance(1_000)

        session.setTranspose(Transpose(-3))

        assertEquals(Transpose(-3), session.transpose.value)
        assertEquals(Transpose(-3), engine.audibleTranspose)
        assertTrue(engine.playback.value.playing)
        assertEquals(21_000, engine.playback.value.positionMs)
    }

    @Test
    fun `a Take is sung at the Transpose set when recording starts and is kept with it`() = runTest {
        val session = openDownloaded()
        session.setTranspose(Transpose(2))
        session.record()
        engine.advance(1_000)

        session.setTranspose(Transpose(5))

        assertEquals(Transpose(2), engine.audibleTranspose)
        engine.advance(1_000)
        session.stop()
        assertEquals(Transpose(2), assertIs<SessionState.Review>(session.state.value).transpose)
        val kept = session.keep()
        assertEquals(Transpose(2), kept.transpose)
        assertEquals(listOf(Transpose(2)), session.takes.first().map { it.transpose })
        // The control keeps its new value for the next Take.
        assertEquals(Transpose(5), session.transpose.value)
    }

    @Test
    fun `Review plays the Backing Audio at the Take's Transpose, whatever the control is set to`() = runTest {
        val session = openDownloaded()
        session.setTranspose(Transpose(-4))
        recordTake(session, fromMs = 5_000, lengthMs = 3_000)
        assertEquals(Transpose(-4), engine.audibleTranspose)

        session.setTranspose(Transpose(1))
        assertEquals(Transpose(-4), engine.audibleTranspose)
        engine.advance(3_000)
        runCurrent()
        session.replay()

        assertTrue(engine.playback.value.playingTake)
        assertEquals(Transpose(-4), engine.audibleTranspose)
        session.discard()
        session.play()
        assertEquals(Transpose(1), engine.audibleTranspose)
    }

    @Test
    fun `a kept Take plays back at its own Transpose and keeps it when the control changes`() = runTest {
        val session = openDownloaded()
        session.setTranspose(Transpose(3))
        recordTake(session, fromMs = 0, lengthMs = 2_000)
        val take = session.keep()
        session.setTranspose(Transpose(-6))

        session.play(take.id)

        assertEquals(Transpose(-6), session.transpose.value)
        assertEquals(Transpose(3), engine.audibleTranspose)
        assertEquals(listOf(Transpose(3)), session.takes.first().map { it.transpose })
    }

    @Test
    fun `leaving a Session drops its Transpose from plain playback, and the next Session opens at the Last Transpose`() = runTest {
        val first = openDownloaded()
        first.setTranspose(Transpose(4))
        first.close()
        assertEquals(Transpose.None, engine.audibleTranspose)

        val second = sessions.open(trackId, backgroundScope)
        second.play()

        assertEquals(Transpose(4), second.transpose.value)
        assertEquals(Transpose(4), engine.audibleTranspose)
    }

    @Test
    fun `changing the Transpose control sets the Track's Last Transpose, also when no Take is recorded`() = runTest {
        val session = openDownloaded()

        session.setTranspose(Transpose(3))
        session.setTranspose(Transpose(-2))

        assertEquals(Transpose(-2), tracks.get(trackId)?.lastTranspose)
    }

    @Test
    fun `a Session on a new Track opens at Transpose 0`() = runTest {
        val session = openDownloaded()

        assertEquals(Transpose.None, session.transpose.value)
        assertEquals(Transpose.None, tracks.get(trackId)?.lastTranspose)
    }

    @Test
    fun `a Session opens at the Last Transpose stored for the Track, as after an app restart`() = runTest {
        addTrack(TrackPipelineState.Downloaded, lastTranspose = Transpose(-3))

        val session = sessions.open(trackId, backgroundScope)

        assertEquals(Transpose(-3), session.transpose.value)
        assertEquals(Transpose(-3), engine.playbackTranspose)
    }

    @Test
    fun `a Session left already changes neither the engine nor the Last Transpose`() = runTest {
        val left = openDownloaded()
        left.setTranspose(Transpose(2))
        val current = sessions.open(trackId, backgroundScope) // closes the one before

        left.setTranspose(Transpose(5)) // e.g. a tap that was still on its way

        assertEquals(Transpose(2), tracks.get(trackId)?.lastTranspose)
        assertEquals(Transpose(2), current.transpose.value)
        assertEquals(Transpose(2), engine.playbackTranspose)
    }

    @Test
    fun `the Last Transpose never changes kept Takes or their Mixes`() = runTest {
        val session = openDownloaded()
        session.setTranspose(Transpose(2))
        recordTake(session, fromMs = 0, lengthMs = 5_000)
        val kept = session.keep()
        val mix = mixes.observe(trackId).first().single()

        session.setTranspose(Transpose(-4))

        assertEquals(Transpose(2), takes.get(kept.id)?.transpose)
        assertEquals(mix, mixes.observe(trackId).first().single())
        assertTrue(session.play(kept.id))
        assertEquals(Transpose(2), engine.audibleTranspose)
    }

    @Test
    fun `record again sings the new Take at the Transpose the control is set to now`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 15_000, lengthMs = 5_000)
        session.setTranspose(Transpose(-2))

        session.recordAgain()

        assertEquals(SessionState.Recording(15_000, Transpose(-2)), session.state.value)
        assertEquals(Transpose(-2), engine.audibleTranspose)
    }

    @Test
    fun `choosing the Original Audio during free playback switches at the same position, still playing`() = runTest {
        addTrack(TrackPipelineState.Ready(MODEL))
        val session = sessions.open(trackId, backgroundScope)
        session.seekTo(42_000)
        session.play()

        assertTrue(session.chooseBacking(BackingChoice.OriginalAudio))
        runCurrent()

        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
        assertEquals(42_000, engine.playback.value.positionMs)
        assertTrue(engine.playback.value.playing)
    }

    @Test
    fun `the Backing Audio choice is kept for the Track and used when it is opened again`() = runTest {
        addTrack(TrackPipelineState.Ready(MODEL))
        sessions.open(trackId, backgroundScope).apply {
            chooseBacking(BackingChoice.OriginalAudio)
            close()
        }

        val again = sessions.open(trackId, backgroundScope)

        assertEquals(BackingChoice.OriginalAudio, tracks.get(trackId)?.backingChoice)
        assertEquals(BackingAudio.OriginalAudio, again.backing.value)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
    }

    @Test
    fun `a Backing Audio chosen while a Take is recorded is loaded only once Review ends`() = runTest {
        addTrack(TrackPipelineState.Ready(MODEL))
        val session = sessions.open(trackId, backgroundScope)
        session.record()
        engine.advance(5_000)

        session.chooseBacking(BackingChoice.OriginalAudio)
        runCurrent()
        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        session.stop()
        runCurrent()
        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)

        session.discard()
        runCurrent()

        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
    }

    @Test
    fun `a Backing Audio chosen while a kept Take plays is loaded once its playback ends`() = runTest {
        addTrack(TrackPipelineState.Ready(MODEL))
        val session = sessions.open(trackId, backgroundScope)
        recordTake(session, fromMs = 10_000, lengthMs = 2_000)
        val kept = session.keep()
        assertTrue(session.play(kept.id))

        session.chooseBacking(BackingChoice.OriginalAudio)
        runCurrent()
        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        engine.advance(2_000)
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
    }

    @Test
    fun `the Instrumental can't be chosen before the Track has one`() = runTest {
        addTrack(TrackPipelineState.Separating(MODEL))
        val session = sessions.open(trackId, backgroundScope)
        session.chooseBacking(BackingChoice.OriginalAudio)

        assertFalse(session.chooseBacking(BackingChoice.Instrumental))

        assertEquals(BackingChoice.OriginalAudio, tracks.get(trackId)?.backingChoice)
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
    }

    @Test
    fun `when Separation finishes, a Session whose choice is the Original Audio stays on it`() = runTest {
        val session = openDownloaded()
        session.chooseBacking(BackingChoice.OriginalAudio)
        session.play()

        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()

        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
        assertTrue(engine.playback.value.playing)
    }

    @Test
    fun `a Vocal-Free Track sings over its Original Audio, whatever the choice`() = runTest {
        tracks.addIfAbsent(
            Track(trackId, "Fly Me to the Moon (Karaoke)", null, SONG_MS, TrackPipelineState.Ready(model = null), vocalFree = true),
        )
        val session = sessions.open(trackId, backgroundScope)

        assertEquals(BackingChoice.Instrumental, tracks.get(trackId)?.backingChoice)
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertFalse(session.chooseBacking(BackingChoice.Instrumental))
    }

    @Test
    fun `opening another Track closes the Session before, and closing that one later leaves the new Session alone`() = runTest {
        addTrack(TrackPipelineState.Downloaded)
        val otherId = TrackId(SourceId("fake"), "v2")
        tracks.addIfAbsent(Track(otherId, "Blue Moon", null, SONG_MS, TrackPipelineState.Downloaded))
        val first = sessions.open(trackId, backgroundScope)
        first.play()

        sessions.open(otherId, backgroundScope)
        // e.g. the first Track's screen is torn down after the second one opened
        first.close()
        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()

        assertTrue(engine.inSession)
        assertEquals(files.originalAudio(otherId), engine.playback.value.loaded)
        assertFalse(engine.playback.value.playing)
    }

    @Test
    fun `opening a Session reports how far the Backing Audio is prepared, up to completion`() = runTest {
        addTrack(TrackPipelineState.Downloaded)
        val progress = mutableListOf<Float>()

        sessions.open(trackId, backgroundScope) { progress += it }

        assertEquals(1f, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `the Track counts as sung once a Take starts recording, not when the Session opens`() = runTest {
        val session = openDownloaded()
        assertNull(tracks.get(trackId)?.lastSessionAt)

        session.record()

        assertEquals(NOW, tracks.get(trackId)?.lastSessionAt)
    }

    private suspend fun recordTake(session: Session, fromMs: Long, lengthMs: Long) {
        session.seekTo(fromMs)
        session.record()
        engine.advance(lengthMs)
        session.stop()
    }

    private suspend fun TestScope.openDownloaded(): Session {
        addTrack(TrackPipelineState.Downloaded)
        return sessions.open(trackId, backgroundScope)
    }

    private companion object {
        const val SONG_MS = 180_000L
        const val NOW = 1_700_000_000_000L
        val MODEL = SeparationModelId("fast")
    }
}
