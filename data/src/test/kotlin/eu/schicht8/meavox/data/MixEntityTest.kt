// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.session.BackingAudio
import kotlin.test.Test
import kotlin.test.assertEquals

/** Seam 2 of #16: a Mix's Vocal Effects and Guide Vocal level in its row. */
class MixEntityTest {
    private fun mix(settings: MixSettings) = Mix(
        MixId("m1"), TrackId(SourceId("youtube"), "abc"), TakeId("t1"), "Mix 1",
        BackingAudio.Instrumental(SeparationModelId("fast")), settings, createdAt = 1,
    )

    @Test
    fun `every Vocal Effects preset and the Guide Vocal level are stored and read back`() {
        for (reverb in VocalEffects.Reverb.entries) for (compression in listOf(false, true)) for (highPass in listOf(false, true)) {
            val settings = MixSettings(-12f, 3f, 25, VocalEffects(reverb, compression, highPass), guideVocalLevel = 0.35f)

            assertEquals(mix(settings), mix(settings).toEntity().toMix(), "$settings")
        }
    }

    @Test
    fun `a Mix stored before #16 reads with no Vocal Effects and no Guide Vocal`() {
        val earlier = mix(MixSettings(-12f)).toEntity().copy(reverb = null, compression = false, highPass = false, guideVocalLevel = 0f)

        assertEquals(MixSettings(-12f), earlier.toMix().settings)
    }
}
