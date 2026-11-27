// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.licenses

import eu.schicht8.meavox.separation.SeparationCatalogue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam 1 of #21, for both apps: every entry of the third-party licenses screen has its license
 * texts in the app, as the assets of the variant under test hold them.
 */
class ThirdPartyNoticesTest {
    private val texts = File(checkNotNull(System.getProperty("meavox.licenseTexts")) { "run through Gradle" })

    @Test
    fun `every notice's license texts are in the app`() {
        val missing = ThirdPartyNotices.all.flatMap { notice -> notice.texts.filterNot { File(texts, it).isFile }.map { "${notice.name}: $it" } }

        assertTrue(missing.isEmpty(), "missing: $missing")
    }

    @Test
    fun `the weights of every Separation Model on offer are listed, the unlicensed ones as such`() {
        val listed = ThirdPartyNotices.all.filter {
            it.kind == ThirdPartyNotice.Kind.ModelWeights || it.kind == ThirdPartyNotice.Kind.UnlicensedModelWeights
        }

        assertEquals(SeparationCatalogue.models.map { ThirdPartyNotices.models[it.id] }, listed)
        assertEquals(
            listOf(SeparationCatalogue.uvrKaraoke.id, SeparationCatalogue.uvrInstHq4.id),
            SeparationCatalogue.models.filter { ThirdPartyNotices.models[it.id]?.kind == ThirdPartyNotice.Kind.UnlicensedModelWeights }.map { it.id },
        )
    }

    @Test
    fun `what both apps contain is listed, the app itself first, LAME as a replaceable library`() {
        val names = ThirdPartyNotices.all.map { it.name }

        assertEquals("MeaVox", names.first())
        for (expected in listOf("Oboe", "Signalsmith Stretch", "Signalsmith Linear", "libFLAC", "LAME", "ONNX Runtime", "LLVM libc++ (libc++_shared)", "desugar_jdk_libs")) {
            assertTrue(expected in names, "$expected in $names")
        }
        val lame = ThirdPartyNotices.all.single { it.name == "LAME" }
        assertEquals(ThirdPartyNotice.Kind.ReplaceableLibrary, lame.kind)
        assertTrue(lame.url.endsWith("/tree/v${eu.schicht8.meavox.BuildConfig.VERSION_NAME}/third_party/lame-3.100"), lame.url)
    }

    @Test
    fun `each model's weights name the model, and only unlicensed ones go without a license`() {
        for ((id, notice) in ThirdPartyNotices.models) {
            assertEquals(id, notice.model)
            assertEquals(notice.kind == ThirdPartyNotice.Kind.UnlicensedModelWeights, notice.license == null, notice.name)
        }
    }
}
