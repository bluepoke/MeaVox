// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import android.content.Context
import androidx.work.WorkManager
import eu.schicht8.meavox.core.export.ExportBacking
import eu.schicht8.meavox.core.library.ImportLocalFile
import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.LookUpSharedLink
import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.library.TrackStorage
import eu.schicht8.meavox.core.lyrics.FetchLyrics
import eu.schicht8.meavox.core.lyrics.LyricsOffset
import eu.schicht8.meavox.core.lyrics.LyricsPipeline
import eu.schicht8.meavox.core.lyrics.LyricsRepository
import eu.schicht8.meavox.core.lyrics.MatchLyrics
import eu.schicht8.meavox.core.mix.ExportMix
import eu.schicht8.meavox.core.mix.MixRepository
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.pipeline.DefaultSeparationModel
import eu.schicht8.meavox.core.pipeline.DownloadTrack
import eu.schicht8.meavox.core.pipeline.NotificationPrompt
import eu.schicht8.meavox.core.pipeline.SeparateTrack
import eu.schicht8.meavox.core.pipeline.TrackPipeline
import eu.schicht8.meavox.core.pipeline.VocalFreeToggle
import eu.schicht8.meavox.core.port.AudioEngine
import eu.schicht8.meavox.core.port.LyricsProvider
import eu.schicht8.meavox.core.port.Separator
import eu.schicht8.meavox.core.port.Source
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.core.session.SettingsRepository
import eu.schicht8.meavox.core.session.TakeRepository
import eu.schicht8.meavox.covers.AndroidCoverArtLoader
import eu.schicht8.meavox.data.AppTrackFiles
import eu.schicht8.meavox.data.MeaVoxDatabase
import eu.schicht8.meavox.data.RoomLyricsRepository
import eu.schicht8.meavox.data.RoomMixRepository
import eu.schicht8.meavox.data.RoomSettingsRepository
import eu.schicht8.meavox.data.RoomTakeRepository
import eu.schicht8.meavox.data.RoomTrackRepository
import eu.schicht8.meavox.downloading.HttpMediaDownloader
import eu.schicht8.meavox.engine.NativeLoudnessMeter
import eu.schicht8.meavox.engine.NativeOfflineRenderer
import eu.schicht8.meavox.engine.OboeAudioEngine
import eu.schicht8.meavox.export.MediaStoreExports
import eu.schicht8.meavox.importing.Importer
import eu.schicht8.meavox.importing.LinkImporter
import eu.schicht8.meavox.importing.PendingImport
import eu.schicht8.meavox.lyrics.LrclibProvider
import eu.schicht8.meavox.separating.EngineDecoder
import eu.schicht8.meavox.separating.EngineFlac
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.separating.WorkManagerPipelineJobs
import eu.schicht8.meavox.separation.ModelSeparator
import eu.schicht8.meavox.separation.ModelStore
import eu.schicht8.meavox.separation.SeparationCatalogue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual dependency injection: the one place that wires adapters to the domain core.
 * Everything is created lazily on first use and lives as long as the process.
 */
class AppContainer(private val context: Context) {
    /** Work that must outlive any screen (e.g. imports). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: MeaVoxDatabase by lazy { MeaVoxDatabase.create(context) }
    val trackRepository: TrackRepository by lazy { RoomTrackRepository(database) }
    val trackFiles: TrackFiles by lazy { AppTrackFiles(File(context.filesDir, "tracks")) }
    val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    val audioEngine: AudioEngine by lazy { OboeAudioEngine(context) }
    val takeRepository: TakeRepository by lazy { RoomTakeRepository(database) }
    val settingsRepository: SettingsRepository by lazy { RoomSettingsRepository(database) }
    val mixRepository: MixRepository by lazy { RoomMixRepository(database) }

    val library: Library by lazy { Library(trackRepository, takeRepository) }
    val renameTrack: RenameTrack by lazy { RenameTrack(trackRepository, lyricsRepository) }
    val trackStorage: TrackStorage by lazy {
        TrackStorage(trackRepository, takeRepository, mixRepository, trackFiles, pipelineJobs, SeparationCatalogue.models.map { it.id })
    }
    // Cover Art (#42): the Source's picture, or the one an imported file embeds.
    private val coverArtLoader by lazy { AndroidCoverArtLoader() }
    val importLocalFile: ImportLocalFile by lazy { ImportLocalFile(trackRepository, trackFiles, coverArtLoader) }
    val sessions: Sessions by lazy {
        Sessions(trackRepository, takeRepository, trackFiles, audioEngine, settingsRepository, mixes)
    }

    // Mixes and their Export (#14, ADR 7, ADR 8).
    val mixes: Mixes by lazy { Mixes(trackRepository, takeRepository, mixRepository, trackFiles, NativeLoudnessMeter()) }
    private val offlineRenderer by lazy { NativeOfflineRenderer() }
    private val exportStore by lazy { MediaStoreExports(context.contentResolver) }
    val exportMix: ExportMix by lazy {
        ExportMix(trackRepository, takeRepository, mixRepository, trackFiles, offlineRenderer, exportStore, settingsRepository)
    }

    // Backing Export (#24).
    val exportBacking: ExportBacking by lazy { ExportBacking(trackRepository, trackFiles, offlineRenderer, exportStore) }
    val importer: Importer by lazy { Importer(appScope, importLocalFile, trackFiles, context.contentResolver) }

    // Separation (ADR 3, ADR 15): models are downloaded into app storage on first use.
    private val modelStore by lazy { ModelStore(File(context.filesDir, "models")) }
    val separator: Separator by lazy { ModelSeparator(SeparationCatalogue.models, modelStore, EngineDecoder, EngineFlac) }
    val defaultSeparationModel: DefaultSeparationModel by lazy {
        DefaultSeparationModel(settingsRepository, SeparationCatalogue.models.map { it.id }, fallback = SeparationCatalogue.default.id)
    }
    val modelChoices: ModelChoices by lazy {
        ModelChoices(SeparationCatalogue.models, modelStore, defaultSeparationModel) { model ->
            modelNames[model.id]?.let(context::getString) ?: model.name
        }
    }

    /** The tiers' names in the user's language (GLOSSARY: Separation Model, #43). */
    private val modelNames = mapOf(
        SeparationCatalogue.spleeter2Stems.id to R.string.model_fast,
        SeparationCatalogue.uvrKaraoke.id to R.string.model_medium,
        SeparationCatalogue.uvrInstHq4.id to R.string.model_best,
    )
    val separateTrack: SeparateTrack by lazy { SeparateTrack(trackRepository, trackFiles, separator) }
    private val pipelineJobs by lazy { WorkManagerPipelineJobs(workManager) }
    val vocalFreeToggle: VocalFreeToggle by lazy {
        VocalFreeToggle(trackRepository, trackFiles, pipelineJobs, mixRepository, SeparationCatalogue.models.map { it.id })
    }
    /** Stops Separation jobs of earlier versions before the Track Pipeline queues them again (#28). */
    suspend fun stopLegacySeparations() = pipelineJobs.stopLegacySeparations()
    val trackPipeline: TrackPipeline by lazy {
        TrackPipeline(trackRepository, pipelineJobs, unpickedModel = SeparationCatalogue.default.id, downloadFrom = sources.map { it.id }.toSet())
    }
    /** Asks for the notification permission once, when the first Track starts Separating (#27). */
    val notificationPrompt: NotificationPrompt by lazy { NotificationPrompt(trackRepository, settingsRepository) }

    // Network Sources (ADR 1): none in the local flavor.
    val sources: List<Source> by lazy { FlavorSources.create() }
    val downloadTrack: DownloadTrack by lazy { DownloadTrack(trackRepository, trackFiles, sources, HttpMediaDownloader(), coverArtLoader) }
    val linkImporter: LinkImporter by lazy { LinkImporter(appScope, LookUpSharedLink(sources), trackRepository, library) }

    /** A picked or shared song waiting for the import sheet (ADR 19); null when none is. */
    val pendingImport = MutableStateFlow<PendingImport?>(null)

    // Lyrics (ADR 6): searched on LRCLIB for every Track, beside the Track Pipeline.
    val lyricsRepository: LyricsRepository by lazy { RoomLyricsRepository(database) }
    private val lyricsProvider: LyricsProvider by lazy {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
        LrclibProvider(LrclibProvider.userAgent(version))
    }
    val fetchLyrics: FetchLyrics by lazy { FetchLyrics(trackRepository, lyricsRepository, lyricsProvider) }
    val matchLyrics: MatchLyrics by lazy { MatchLyrics(trackRepository, lyricsRepository, lyricsProvider) }
    val lyricsOffset: LyricsOffset by lazy { LyricsOffset(lyricsRepository) }
    val lyricsPipeline: LyricsPipeline by lazy { LyricsPipeline(trackRepository, lyricsRepository, pipelineJobs) }
}
