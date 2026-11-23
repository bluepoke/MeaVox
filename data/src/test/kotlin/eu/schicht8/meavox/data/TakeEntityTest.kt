// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.Transpose
import kotlin.test.Test
import kotlin.test.assertEquals

class TakeEntityTest {
    private fun take(transpose: Transpose) = Take(
        id = TakeId("t1"),
        track = TrackId(SourceId("youtube"), "abc"),
        name = "Take 1",
        startFrame = 1_440_000,
        frames = 480_000,
        sampleRate = 48_000,
        route = OutputRoute.Usb,
        reportedLatencyFrames = 4_800,
        transpose = transpose,
        recordedAt = 1_700_000_000_000,
    )

    @Test
    fun `a Take is stored and read back with its Transpose`() {
        for (semitones in listOf(-6, -1, 0, 2, 6)) {
            val take = take(Transpose(semitones))
            assertEquals(take, take.toEntity().toTake(), "$semitones")
        }
    }

    @Test
    fun `a Take is stored and read back with the Latency Correction it was kept with`() {
        for (ms in listOf(-120, 0, 35)) {
            val take = take(Transpose.None).copy(latencyCorrectionMs = ms)
            assertEquals(take, take.toEntity().toTake(), "$ms")
        }
    }
}
