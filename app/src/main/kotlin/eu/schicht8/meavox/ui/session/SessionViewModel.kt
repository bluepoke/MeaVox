// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.schicht8.meavox.core.export.BackingExportOptions
import eu.schicht8.meavox.core.export.BackingExportUnavailable
import eu.schicht8.meavox.core.export.ExportBacking
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.lyrics.LyricsOffset
import eu.schicht8.meavox.core.lyrics.LyricsRepository
import eu.schicht8.meavox.core.lyrics.MatchLyrics
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.mix.ExportMix
import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.MixUnavailable
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.pipeline.TrackPipeline
import eu.schicht8.meavox.core.pipeline.VocalFreeToggle
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportedFile
import eu.schicht8.meavox.core.port.InputLevel
import eu.schicht8.meavox.core.port.Playback
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.MicrophoneUnavailable
import eu.schicht8.meavox.core.session.Session
import eu.schicht8.meavox.core.session.SessionState
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.separating.ModelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Track screen: the Track's status, Lyrics and Vocal-Free toggle, and the [Session] on it,
 * which opens as soon as the Original Audio is stored (ADR 10) and stays open while the screen is
 * on the back stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    private val id: TrackId,
    tracks: TrackRepository,
    sessions: Sessions,
    private val pipeline: TrackPipeline,
    lyrics: LyricsRepository,
    private val match: MatchLyrics,
    private val lyricsOffset: LyricsOffset,
    private val vocalFree: VocalFreeToggle,
    private val choices: ModelChoices,
    private val mixes: Mixes,
    private val exportMix: ExportMix,
    private val exportBacking: ExportBacking,
) : ViewModel() {
    /** The Track as it is now: Track Pipeline, Vocal-Free, Backing Audio choice. */
    val track: StateFlow<Track?> = tracks.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Track's Lyrics; they may still change (e.g. a search finishing). */
    val lyrics: StateFlow<TrackLyrics?> = lyrics.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Track's Lyrics Offset in ms (ADR 6). */
    val lyricsOffsetMs: StateFlow<Long> = lyricsOffset.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _karaoke = MutableStateFlow(true)
    /** Karaoke View (if the Lyrics are synced) rather than Page View; for this screen only. */
    val karaoke: StateFlow<Boolean> = _karaoke

    fun showKaraoke(karaoke: Boolean) {
        _karaoke.value = karaoke
    }

    fun nudgeLyricsOffset(deltaMs: Long) {
        viewModelScope.launch { lyricsOffset.nudge(id, deltaMs) }
    }

    fun resetLyricsOffset() {
        viewModelScope.launch { lyricsOffset.reset(id) }
    }

    /** Confirms the suggested Lyrics (ADR 6). */
    fun confirmLyrics() {
        viewModelScope.launch { match.confirm(id) }
    }

    /** Runs a failed step of the Track Pipeline again. */
    fun retry() {
        viewModelScope.launch { pipeline.retry(id) }
    }

    private val _confirmVocalFree = MutableStateFlow<Long?>(null)

    /** Bytes of stems that marking the Track Vocal-Free would delete, while waiting for the user to confirm. */
    val confirmVocalFree: StateFlow<Long?> = _confirmVocalFree

    /**
     * The Vocal-Free toggle (ADR 14): turning it on asks first if stems would be deleted; turning it
     * off asks which model removes the vocals (ADR 19).
     */
    fun setVocalFree(on: Boolean) {
        viewModelScope.launch {
            if (!on) return@launch openModelPick(ModelPick.Purpose.Unmark)
            val bytes = vocalFree.stemBytes(id)
            if (bytes > 0) _confirmVocalFree.value = bytes else vocalFree.mark(id)
        }
    }

    private val _modelPick = MutableStateFlow<ModelPick?>(null)

    /** The model picker while it is open, with the Default Separation Model pre-selected (ADR 19). */
    val modelPick: StateFlow<ModelPick?> = _modelPick

    /** Opens the model picker to separate the Track again (ADR 19). */
    fun startReseparating() {
        viewModelScope.launch { openModelPick(ModelPick.Purpose.Reseparate) }
    }

    private suspend fun openModelPick(purpose: ModelPick.Purpose) {
        _modelPick.value = ModelPick(purpose, choices.options(), choices.default.get())
    }

    fun pickModel(model: SeparationModelId) {
        _modelPick.value = _modelPick.value?.copy(selected = model)
    }

    /** Confirms the picked model: confirming also consents to downloading it, if it needs that. */
    fun confirmModelPick(confirmed: Boolean) {
        val pick = _modelPick.value ?: return
        _modelPick.value = null
        if (!confirmed) return
        viewModelScope.launch {
            when (pick.purpose) {
                ModelPick.Purpose.Reseparate -> pipeline.reseparate(id, pick.selected)
                ModelPick.Purpose.Unmark -> vocalFree.unmark(id, pick.selected)
            }
        }
    }

    fun confirmVocalFree(confirmed: Boolean) {
        _confirmVocalFree.value = null
        if (confirmed) viewModelScope.launch { vocalFree.mark(id) }
    }

    private val session = MutableStateFlow<Session?>(null)

    private val _loadProgress = MutableStateFlow(0f)
    /** How much of the Backing Audio is prepared, 0..1, while the Session opens. */
    val loadProgress: StateFlow<Float> = _loadProgress

    private val _openError = MutableStateFlow<String?>(null)
    /** Why the Session couldn't open, if it couldn't. */
    val openError: StateFlow<String?> = _openError

    private val _message = MutableStateFlow<String?>(null)
    /** A one-off message for the user (shown once, then [messageShown]). */
    val message: StateFlow<String?> = _message

    val state: StateFlow<SessionState> = from(SessionState.Idle) { it.state }
    val playback: StateFlow<Playback> = from(Playback.Idle) { it.playback }
    val input: StateFlow<InputLevel> = from(InputLevel.Off) { it.input }
    val inputGainDb: StateFlow<Float> = from(0f) { it.inputGainDb }

    /** What plays now; null until the Session is open. */
    val backing: StateFlow<BackingAudio?> = from(null) { it.backing }
    val countdownEnabled: StateFlow<Boolean> = from(false) { it.countdownEnabled }
    val reviewBackingDb: StateFlow<Float> = from(Session.DEFAULT_REVIEW_BACKING_DB) { it.reviewBackingDb }
    val takes: StateFlow<List<Take>> = from(emptyList()) { it.takes }
    val transpose: StateFlow<Transpose> = from(Transpose.None) { it.transpose }

    init {
        viewModelScope.launch {
            // A Track still downloading opens once its Original Audio is stored (ADR 10).
            track.first { it?.hasOriginalAudio == true }
            try {
                session.value = sessions.open(id, viewModelScope) { _loadProgress.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _openError.value = e.message ?: e::class.simpleName
            }
        }
    }

    private fun <T> from(initial: T, select: (Session) -> Flow<T>): StateFlow<T> =
        session.filterNotNull().flatMapLatest(select).stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    /** The Backing Audio choice (Instrumental / Original Audio), kept for the Track. */
    fun chooseBacking(choice: BackingChoice) = act { it.chooseBacking(choice) }

    /** The microphone permission is granted: open the microphone for the meter. */
    fun onMicrophoneAllowed() = act { it.enableMicrophone() }

    fun onMicrophoneDenied() {
        _message.value = "MeaVox needs access to the microphone to record a Take."
    }

    fun messageShown() {
        _message.value = null
    }

    fun togglePlay() = act { if (playback.value.playing) it.pause() else it.play() }

    fun seekTo(positionMs: Long) = act { it.seekTo(positionMs) }

    fun record() = act { it.record() }

    fun stop() = act { it.stop() }

    fun replay() = act { it.replay() }

    fun pause() = act { it.pause() }

    fun keep() = act { it.keep() }

    fun discard() = act { it.discard() }

    fun recordAgain() = act { it.recordAgain() }

    fun playTake(take: TakeId) = act {
        if (!it.play(take)) _message.value = "This Take can't be played."
    }

    fun rename(take: TakeId, name: String) = act { it.rename(take, name) }

    fun delete(take: TakeId) = act { it.delete(take) }

    fun setCountdownEnabled(enabled: Boolean) = act { it.setCountdownEnabled(enabled) }

    fun setInputGain(gainDb: Float) = act { it.setInputGain(gainDb) }

    fun setReviewBackingDb(gainDb: Float) = act { it.setReviewBackingDb(gainDb) }

    fun setTranspose(transpose: Transpose) = act { it.setTranspose(transpose) }

    private fun act(block: suspend (Session) -> Unit) {
        val open = session.value ?: return
        viewModelScope.launch {
            try {
                block(open)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicrophoneUnavailable) {
                _message.value = "The microphone can't be opened. Check that MeaVox may use it."
            } catch (e: MixUnavailable) {
                _message.value = e.reason
            } catch (e: Exception) {
                _message.value = e.message ?: e::class.simpleName
            }
        }
    }

    // --- Mixes (#14) ---

    /** The Track's Mixes, oldest first. */
    val trackMixes: StateFlow<List<Mix>> = mixes.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _editor = MutableStateFlow<MixEditor?>(null)

    /** The Mix Editor while it is open. */
    val editor: StateFlow<MixEditor?> = _editor

    private var exportJob: Job? = null

    /** A new Mix of a kept Take, opened in the editor. */
    fun newMix(take: TakeId) {
        viewModelScope.launch { editMix(mixes.create(take)) }
    }

    /** Opens the Mix Editor on [mix]; its stored Mix Settings are the starting point. */
    fun editMix(mix: Mix) {
        if (_editor.value != null) closeMixEditor()
        _editor.value = MixEditor(mix.id, mix.settings)
    }

    /** Mix Settings being edited: heard at once in a running preview, stored by [saveMixSettings]. */
    fun editMixSettings(settings: MixSettings) {
        _editor.update { it?.copy(settings = settings) }
        act { it.setPreviewSettings(settings) }
    }

    /** Stores the Mix Settings being edited (e.g. when a slider is let go). */
    fun saveMixSettings() {
        val editor = _editor.value ?: return
        viewModelScope.launch { mixes.updateSettings(editor.mix, editor.settings) }
    }

    fun chooseMixBacking(choice: BackingChoice) {
        val editor = _editor.value ?: return
        viewModelScope.launch {
            if (!mixes.chooseBacking(editor.mix, choice)) _message.value = "The instrumental isn't ready yet."
        }
    }

    /** Plays the Mix being edited as it will be exported, with the settings being edited. */
    fun previewMix() {
        val editor = _editor.value ?: return
        act {
            mixes.updateSettings(editor.mix, editor.settings)
            if (!it.previewMix(editor.mix)) _message.value = "This Mix can't be played."
        }
    }

    /** Exports the Mix being edited in [format] to Music/MeaVox (#14, #15). Leaving the screen cancels it. */
    fun exportMix(format: ExportFormat) {
        val editor = _editor.value ?: return
        if (editor.export is ExportProgress.Running) return
        exportJob = viewModelScope.launch {
            mixes.updateSettings(editor.mix, editor.settings)
            updateExport(editor.mix, ExportProgress.Running(0f))
            val result = try {
                // Progress comes from the render's thread; a late one must not outlive the Export.
                val file = exportMix.export(editor.mix, format) { progress ->
                    if (isActive) updateExport(editor.mix, ExportProgress.Running(progress))
                }
                ExportProgress.Done(file)
            } catch (e: CancellationException) {
                updateExport(editor.mix, null)
                throw e
            } catch (e: MixUnavailable) {
                ExportProgress.Failed(e.reason)
            } catch (e: Exception) {
                ExportProgress.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
            updateExport(editor.mix, result)
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
    }

    /** Shows [export] in the editor, if it is still open on [mix]. */
    private fun updateExport(mix: MixId, export: ExportProgress?) {
        _editor.update { if (it?.mix == mix) it.copy(export = export) else it }
    }

    /** Closes the editor: stores its settings, stops the preview and cancels an Export still running. */
    fun closeMixEditor() {
        saveMixSettings()
        cancelExport()
        if (state.value is SessionState.PreviewingMix) stop()
        _editor.value = null
    }

    fun deleteMix(mix: MixId) {
        if (_editor.value?.mix == mix) closeMixEditor()
        viewModelScope.launch { mixes.delete(mix) }
    }

    // --- Backing Export (#24) ---

    private val _backingExport = MutableStateFlow<BackingExportDialog?>(null)

    /** The Backing Export sheet while it is open. */
    val backingExport: StateFlow<BackingExportDialog?> = _backingExport

    private var backingExportJob: Job? = null

    /** Opens the Backing Export sheet at the Backing Audio the Track plays and its Last Transpose. */
    fun openBackingExport() {
        viewModelScope.launch {
            val options = exportBacking.options(id)
            if (options.layers.isEmpty()) return@launch
            _backingExport.value = BackingExportDialog(options, options.layer, options.transpose)
        }
    }

    fun chooseBackingExportLayer(layer: BackingChoice) {
        _backingExport.update { it?.takeIf { layer in it.options.layers }?.copy(layer = layer) ?: it }
    }

    /** The Transpose of this export only; the Last Transpose stays. */
    fun setBackingExportTranspose(transpose: Transpose) {
        _backingExport.update { it?.copy(transpose = transpose) }
    }

    /** Exports the chosen layer in [format] to Music/MeaVox. Closing the sheet or leaving the screen cancels it. */
    fun startBackingExport(format: ExportFormat) {
        val dialog = _backingExport.value ?: return
        if (dialog.export is ExportProgress.Running) return
        backingExportJob = viewModelScope.launch {
            fun show(export: ExportProgress?) = _backingExport.update { it?.copy(export = export) }
            show(ExportProgress.Running(0f))
            val result = try {
                val file = exportBacking.export(id, dialog.layer, dialog.transpose, format) { progress ->
                    if (isActive) show(ExportProgress.Running(progress))
                }
                ExportProgress.Done(file)
            } catch (e: CancellationException) {
                show(null)
                throw e
            } catch (e: BackingExportUnavailable) {
                ExportProgress.Failed(e.reason)
            } catch (e: Exception) {
                ExportProgress.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
            show(result)
        }
    }

    fun cancelBackingExport() {
        backingExportJob?.cancel()
        backingExportJob = null
    }

    fun closeBackingExport() {
        cancelBackingExport()
        _backingExport.value = null
    }

    override fun onCleared() {
        // Does nothing if the next Track's Session has closed it already.
        session.value?.close()
    }
}

/** The Mix Editor: the Mix, the Mix Settings being edited, and its Export, if one was started. */
data class MixEditor(val mix: MixId, val settings: MixSettings, val export: ExportProgress? = null)

/** The Backing Export sheet: what it offers, the layer and Transpose chosen, and its Export, if started. */
data class BackingExportDialog(
    val options: BackingExportOptions,
    val layer: BackingChoice,
    val transpose: Transpose,
    val export: ExportProgress? = null,
)

/** Where an Export from the Mix Editor or the Backing Export sheet is. */
sealed interface ExportProgress {
    data class Running(val progress: Float) : ExportProgress

    data class Done(val file: ExportedFile) : ExportProgress

    data class Failed(val reason: String) : ExportProgress
}

/** The model picker on the Track screen (ADR 19): for re-separating, or for unmarking Vocal-Free. */
data class ModelPick(val purpose: Purpose, val options: List<ModelOption>, val selected: SeparationModelId) {
    enum class Purpose { Reseparate, Unmark }
}
