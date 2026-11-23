// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separation

import eu.schicht8.meavox.core.model.AppDirectory
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.port.SeparationFailed
import eu.schicht8.meavox.core.port.SeparationProgress
import eu.schicht8.meavox.core.port.Separator
import eu.schicht8.meavox.core.port.Stems
import eu.schicht8.meavox.separation.audio.FlacCodec
import eu.schicht8.meavox.separation.audio.PcmDecoder
import eu.schicht8.meavox.separation.audio.UndecodableAudio
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [Separator] that runs the [catalogue]'s models on the device (ADR 3, ADR 15): the model is
 * downloaded on first use, then the Separation runs checkpointed per segment. One Separation runs
 * at a time: a second waits, because each needs most of the CPU and up to about a GB of memory.
 */
class ModelSeparator(
    private val catalogue: List<SeparationModel>,
    private val models: ModelStore,
    private val decoder: PcmDecoder,
    private val flac: FlacCodec,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Separator {
    private val oneAtATime = Mutex()

    override suspend fun separate(
        originalAudio: AudioFile,
        model: SeparationModelId,
        into: Stems,
        checkpoint: AppDirectory,
        onProgress: (SeparationProgress) -> Unit,
    ) = oneAtATime.withLock {
        val entry = catalogue.firstOrNull { it.id == model }
            ?: throw SeparationFailed(FailureReason(FailureKind.UnknownModel, model.value), retryable = false)
        val modelDir = try {
            runInterruptible(Dispatchers.IO) {
                models.install(
                    entry,
                    onDownload = { onProgress(SeparationProgress(SeparationProgress.Stage.DownloadingModel, it)) },
                    onInstall = { onProgress(SeparationProgress(SeparationProgress.Stage.InstallingModel, it)) },
                )
            }
        } catch (e: ModelCorrupt) {
            throw SeparationFailed(FailureReason(FailureKind.ModelCorrupt, e.message), retryable = false, e)
        } catch (e: IOException) {
            throw SeparationFailed(FailureReason(FailureKind.ModelDownloadFailed, e.message), retryable = true, e)
        }
        try {
            runInterruptible(dispatcher) {
                CheckpointedSeparation(
                    original = File(originalAudio.path),
                    model = entry,
                    modelDir = modelDir,
                    instrumental = File(into.instrumental.path),
                    vocalStem = File(into.vocalStem.path),
                    checkpoint = File(checkpoint.path),
                    decoder = decoder,
                    flac = flac,
                    onProgress = { onProgress(SeparationProgress(SeparationProgress.Stage.Separating, it)) },
                ).run()
            }
        } catch (e: UndecodableAudio) {
            throw SeparationFailed(FailureReason(FailureKind.AudioUndecodable, e.message), retryable = false, e)
        } catch (e: IOException) {
            throw SeparationFailed(FailureReason(FailureKind.StemsNotStored, e.message), retryable = false, e)
        }
    }
}
