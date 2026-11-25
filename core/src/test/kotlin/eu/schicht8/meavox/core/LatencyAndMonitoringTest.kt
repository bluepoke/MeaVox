// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.session.LatencyCorrection
import eu.schicht8.meavox.core.session.MonitoringUnavailable
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

/** The Latency Correction per route and path, set by ear in Review, and Monitoring (#9, ADR 13, ADR 16, ADR 23). */
class LatencyAndMonitoringTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val files = InMemoryTrackFiles()
    private val settings = InMemorySettingsRepository()
    private val instrumental = files.stems(trackId, MODEL).instrumental
    private val engine = FakeAudioEngine(mapOf(files.originalAudio(trackId) to SONG_MS, instrumental to SONG_MS))
    private val mixes = Mixes(tracks, takes, InMemoryMixRepository(), files, FakeLoudnessMeter())
    private val sessions = Sessions(tracks, takes, files, engine, settings, mixes)

    // --- Latency Correction ---

    @Test
    fun `Review plays a Take on a route without a Latency Correction at the reported round trip, and asks to check the timing`() = runTest {
        val session = openReady()

        recordTake(session)

        assertEquals(LatencyCorrection(OutputRoute.Wired, OutputPath.Normal, ms = 0, checked = false), session.latencyCorrection.value)
        assertEquals(REPORTED, engine.lastAlignmentFrames)
    }

    @Test
    fun `a Latency Correction set by ear in Review moves the Take at once, and the kept Take and its Mix play with it`() = runTest {
        val session = openReady()
        recordTake(session)

        session.setLatencyCorrection(25)

        assertEquals(LatencyCorrection(OutputRoute.Wired, OutputPath.Normal, ms = 25, checked = true), session.latencyCorrection.value)
        assertEquals(REPORTED + 25 * 48, engine.lastAlignmentFrames)
        assertEquals(25, settings.latencyCorrectionMs(OutputRoute.Wired, OutputPath.Normal))
        val take = session.keep()
        assertEquals(25, take.latencyCorrectionMs)
        assertNull(session.latencyCorrection.value)
        session.play(take.id)
        assertEquals(REPORTED + 25 * 48, engine.lastAlignmentFrames)
        session.stop()
        session.previewMix(mixes.observe(trackId).first().single().id)
        assertEquals(REPORTED + 25 * 48, engine.lastAlignmentFrames)
    }

    @Test
    fun `the next Take on the route starts with its Latency Correction, and another route has its own`() = runTest {
        val session = openReady()
        recordTake(session)
        session.setLatencyCorrection(25)
        session.discard()

        recordTake(session)
        assertEquals(LatencyCorrection(OutputRoute.Wired, OutputPath.Normal, ms = 25, checked = true), session.latencyCorrection.value)
        assertEquals(REPORTED + 25 * 48, engine.lastAlignmentFrames)
        session.discard()

        engine.outputRoute = OutputRoute.Usb
        recordTake(session)
        assertEquals(LatencyCorrection(OutputRoute.Usb, OutputPath.Normal, ms = 0, checked = false), session.latencyCorrection.value)
    }

    @Test
    fun `keeping a Take without changing the Latency Correction confirms it for the route`() = runTest {
        val session = openReady()
        recordTake(session)

        session.keep()

        assertEquals(0, settings.latencyCorrectionMs(OutputRoute.Wired, OutputPath.Normal))
        recordTake(session)
        assertEquals(true, session.latencyCorrection.value?.checked)
    }

    @Test
    fun `correcting the route later doesn't move a Take kept before`() = runTest {
        val session = openReady()
        recordTake(session)
        session.setLatencyCorrection(25)
        val first = session.keep()

        recordTake(session)
        session.setLatencyCorrection(40)
        session.keep()
        session.play(first.id)

        assertEquals(REPORTED + 25 * 48, engine.lastAlignmentFrames)
    }

    @Test
    fun `a Latency Correction is limited to a few hundred milliseconds either way`() = runTest {
        val session = openReady()
        recordTake(session)

        session.setLatencyCorrection(10_000)
        assertEquals(Session.MAX_LATENCY_CORRECTION_MS, session.latencyCorrection.value?.ms)
        session.setLatencyCorrection(-10_000)
        assertEquals(-Session.MAX_LATENCY_CORRECTION_MS, session.latencyCorrection.value?.ms)
    }

    @Test
    fun `with Monitoring on, a Take is recorded on the low-latency path, which has its own Latency Correction`() = runTest {
        val session = openReady()
        settings.setLatencyCorrectionMs(OutputRoute.Wired, OutputPath.Normal, 25)
        assertTrue(session.setMonitoring(true))

        recordTake(session)

        val review = assertIs<SessionState.Review>(session.state.value)
        assertEquals(OutputPath.LowLatency, review.take.path)
        assertEquals(LatencyCorrection(OutputRoute.Wired, OutputPath.LowLatency, ms = 0, checked = false), session.latencyCorrection.value)
        assertEquals(LOW_LATENCY_REPORTED, engine.lastAlignmentFrames)
    }

    // --- Monitoring ---

    @Test
    fun `Monitoring starts off, and switching it on over wired headphones reopens the output on the low-latency path`() = runTest {
        val session = openReady()
        assertFalse(session.output.value.monitoring)
        assertEquals(OutputPath.Normal, session.output.value.path)

        assertTrue(session.setMonitoring(true))
        runCurrent()

        assertTrue(engine.monitoring)
        assertTrue(session.output.value.monitoring)
        assertEquals(OutputPath.LowLatency, session.output.value.path)
        assertEquals(1, engine.outputReopens)
        // Monitoring needs the microphone.
        assertTrue(engine.input.value.enabled)

        assertFalse(session.setMonitoring(false))
        runCurrent()
        assertEquals(OutputPath.Normal, session.output.value.path)
    }

    @Test
    fun `Monitoring is available over USB too, and unavailable on the speaker, on Bluetooth and on unknown outputs, with the reason`() = runTest {
        val session = openReady()
        val unavailable = mapOf(
            OutputRoute.Wired to null,
            OutputRoute.Usb to null,
            OutputRoute.Speaker to MonitoringUnavailable.Speaker,
            OutputRoute.Bluetooth to MonitoringUnavailable.Bluetooth,
            OutputRoute.Other to MonitoringUnavailable.UnknownOutput,
        )
        for ((route, reason) in unavailable) {
            engine.outputRoute = route
            runCurrent()
            assertEquals(reason, session.output.value.monitoringUnavailable, "$route")
            if (reason != null) {
                assertFalse(session.setMonitoring(true), "$route")
                assertFalse(engine.monitoring, "$route")
            }
        }
    }

    @Test
    fun `the Monitoring level is kept for the next Session, but Monitoring always starts off`() = runTest {
        val session = openReady()
        session.setMonitoring(true)
        session.setMonitoringLevel(0.3f)
        assertEquals(0.3f, engine.monitoringLevel)
        session.close()

        val next = sessions.open(trackId, backgroundScope)
        runCurrent()

        assertFalse(next.output.value.monitoring)
        assertFalse(engine.monitoring)
        assertEquals(0.3f, next.output.value.monitoringLevel)
        assertEquals(0.3f, engine.monitoringLevel)
    }

    @Test
    fun `Monitoring is heard while recording, and silent while a Take plays back, even paused`() = runTest {
        val session = openReady()
        session.setMonitoring(true)
        session.setMonitoringLevel(0.6f)

        session.record()
        assertEquals(0.6f, engine.monitoringLevel)
        engine.advance(5_000)
        session.stop()
        session.pause()
        assertEquals(0f, engine.monitoringLevel)
        val take = session.keep()
        assertEquals(0.6f, engine.monitoringLevel)

        session.play(take.id)
        assertEquals(0f, engine.monitoringLevel)
        session.stop()
        session.previewMix(mixes.observe(trackId).first().single().id)
        assertEquals(0f, engine.monitoringLevel)
        session.stop()
        assertEquals(0.6f, engine.monitoringLevel)
    }

    @Test
    fun `Monitoring is switched only in free playback, never under a Take`() = runTest {
        val session = openReady()
        session.record()

        assertFalse(session.setMonitoring(true))

        assertFalse(engine.monitoring)
        assertEquals(0, engine.outputReopens)
    }

    @Test
    fun `plugging headphones out during a recording ends it in Review, switches Monitoring off, and the Session goes on`() = runTest {
        val session = openReady()
        session.setMonitoring(true)
        session.record()
        engine.advance(5_000)

        engine.changeRoute(OutputRoute.Speaker)
        runCurrent()

        assertEquals(5_000, assertIs<SessionState.Review>(session.state.value).take.durationMs)
        assertFalse(session.output.value.monitoring)
        assertEquals(OutputRoute.Speaker, session.output.value.route)
        assertEquals(MonitoringUnavailable.Speaker, session.output.value.monitoringUnavailable)
        session.discard()
        recordTake(session)
        assertEquals(OutputRoute.Speaker, assertIs<SessionState.Review>(session.state.value).take.route)
    }

    @Test
    fun `the Session shows the round trip of the route and path in use, with their correction, and warns about delayed Monitoring above 40 ms`() = runTest {
        val session = openReady()
        session.enableMicrophone()
        runCurrent()
        assertEquals(100, session.output.value.roundTripMs)
        assertFalse(session.output.value.corrected)
        // Only Monitoring is delayed by the round trip (ADR 16).
        assertFalse(session.output.value.monitoringDelayed)

        session.setMonitoring(true)
        runCurrent()
        assertEquals(40, session.output.value.roundTripMs)
        assertFalse(session.output.value.monitoringDelayed)

        engine.lowLatencyReportedLatencyFrames = 50L * 48
        runCurrent()
        assertTrue(session.output.value.monitoringDelayed)

        // Corrected by ear on a Take recorded with Monitoring on.
        recordTake(session)
        session.setLatencyCorrection(-15)
        session.discard()
        runCurrent()
        assertEquals(35, session.output.value.roundTripMs)
        assertTrue(session.output.value.corrected)
        assertFalse(session.output.value.monitoringDelayed)
    }

    @Test
    fun `recording without headphones shows a warning`() = runTest {
        val session = openReady()
        val warned = mapOf(
            OutputRoute.Speaker to true,
            OutputRoute.Other to true,
            OutputRoute.Wired to false,
            OutputRoute.Usb to false,
            OutputRoute.Bluetooth to false,
        )
        for ((route, warning) in warned) {
            engine.outputRoute = route
            runCurrent()
            assertEquals(warning, session.output.value.withoutHeadphones, "$route")
        }
    }

    private suspend fun recordTake(session: Session) {
        session.record()
        engine.advance(5_000)
        session.stop()
    }

    private suspend fun TestScope.openReady(): Session {
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", SONG_MS, TrackPipelineState.Ready(MODEL)))
        return sessions.open(trackId, backgroundScope).also { runCurrent() }
    }

    private companion object {
        const val SONG_MS = 180_000L
        val MODEL = SeparationModelId("fast")
        const val REPORTED = 4_800L
        const val LOW_LATENCY_REPORTED = 1_920L
    }
}
