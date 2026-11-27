// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.licenses

import eu.schicht8.meavox.BuildConfig
import eu.schicht8.meavox.FlavorNotices
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.separation.SeparationCatalogue

/**
 * Work by others that the app contains, or downloads when the user picks it, with its license
 * (#21). [texts] are asset paths under `licenses/` (see `LicenseTexts` in `app/build.gradle.kts`).
 */
data class ThirdPartyNotice(
    /** The work's own name, the same in every language. */
    val name: String,
    /** The license as its SPDX expression; null for work without a license. */
    val license: String?,
    val texts: List<String>,
    val url: String,
    val kind: Kind = Kind.Contained,
    /** The Separation Model these are the weights of, whose tier name (Fast, Medium, Best) the screen shows in the UI's language. */
    val model: SeparationModelId? = null,
) {
    enum class Kind {
        /** Part of the app. */
        Contained,

        /** Part of the app, linked dynamically so it can be replaced; its source is offered (LGPL). */
        ReplaceableLibrary,

        /** A Separation Model's weights, downloaded on first use; not part of the app. */
        ModelWeights,

        /** A Separation Model's weights that have no license; downloaded only if the user picks the model. */
        UnlicensedModelWeights,
    }
}

/** What the third-party licenses screen lists for this app: what both apps contain, then what only this one does. */
object ThirdPartyNotices {
    private const val APACHE = "Apache-2.0.txt"

    /** The app itself, first. */
    val app = ThirdPartyNotice("MeaVox", "GPL-3.0-or-later", listOf("GPL-3.0.txt"), "https://github.com/bluepoke/MeaVox")

    private val common = listOf(
        ThirdPartyNotice("Oboe", "Apache-2.0", listOf(APACHE), "https://github.com/google/oboe"),
        ThirdPartyNotice("Signalsmith Stretch", "MIT", listOf("MIT-SignalsmithStretch.txt"), "https://github.com/Signalsmith-Audio/signalsmith-stretch"),
        ThirdPartyNotice("Signalsmith Linear", "MIT", listOf("MIT-SignalsmithLinear.txt"), "https://github.com/Signalsmith-Audio/linear"),
        ThirdPartyNotice("libFLAC", "BSD-3-Clause", listOf("BSD-3-Clause-libFLAC.txt"), "https://xiph.org/flac/"),
        ThirdPartyNotice(
            "LAME",
            "LGPL-2.0-or-later",
            listOf("LGPL-2.0-LAME.txt"),
            // The source as built into this version: the tag it is released from.
            "https://github.com/bluepoke/MeaVox/tree/v${BuildConfig.VERSION_NAME}/third_party/lame-3.100",
            ThirdPartyNotice.Kind.ReplaceableLibrary,
        ),
        ThirdPartyNotice(
            "ONNX Runtime",
            "MIT",
            listOf("MIT-ONNXRuntime.txt", "ONNXRuntime-ThirdPartyNotices.txt"),
            "https://onnxruntime.ai",
        ),
        ThirdPartyNotice(
            "Apache Commons Compress, IO, Codec and Lang",
            "Apache-2.0",
            listOf(APACHE, "NOTICE-ApacheCommons.txt"),
            "https://commons.apache.org",
        ),
        ThirdPartyNotice(
            "AndroidX, Jetpack Compose, Room, WorkManager, Guava ListenableFuture",
            "Apache-2.0",
            listOf(APACHE),
            "https://developer.android.com/jetpack/androidx",
        ),
        ThirdPartyNotice(
            "LLVM libc++ (libc++_shared)",
            "Apache-2.0 WITH LLVM-exception",
            listOf("Apache-2.0-WITH-LLVM-exception-libcxx.txt"),
            "https://libcxx.llvm.org",
        ),
        ThirdPartyNotice(
            "Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, JetBrains annotations, JSpecify",
            "Apache-2.0",
            listOf(APACHE),
            "https://kotlinlang.org",
        ),
        ThirdPartyNotice(
            "desugar_jdk_libs",
            "GPL-2.0-only WITH Classpath-exception-2.0",
            listOf("GPL-2.0-with-Classpath-Exception-desugar.txt"),
            "https://github.com/google/desugar_jdk_libs",
        ),
    )

    /** The weights of each Separation Model the catalogue offers (ADR 15, ADR 19). */
    val models: Map<SeparationModelId, ThirdPartyNotice> = mapOf(
        SeparationCatalogue.spleeter2Stems.id to ThirdPartyNotice(
            "Spleeter 2-stems",
            "MIT",
            listOf("MIT-Spleeter.txt"),
            "https://github.com/deezer/spleeter",
            ThirdPartyNotice.Kind.ModelWeights,
            SeparationCatalogue.spleeter2Stems.id,
        ),
        SeparationCatalogue.uvrKaraoke.id to uvr("UVR MDX-Net Karaoke", SeparationCatalogue.uvrKaraoke.id),
        SeparationCatalogue.uvrInstHq4.id to uvr("UVR MDX-Net Inst HQ 4", SeparationCatalogue.uvrInstHq4.id),
    )

    private fun uvr(name: String, model: SeparationModelId) = ThirdPartyNotice(
        name,
        license = null,
        listOf("UVR-weights-no-license.txt"),
        "https://github.com/TRvlvr/model_repo",
        ThirdPartyNotice.Kind.UnlicensedModelWeights,
        model,
    )

    /** Everything the screen lists, in its order. */
    val all: List<ThirdPartyNotice> =
        listOf(app) + common + FlavorNotices.notices + SeparationCatalogue.models.mapNotNull { models[it.id] }
}
