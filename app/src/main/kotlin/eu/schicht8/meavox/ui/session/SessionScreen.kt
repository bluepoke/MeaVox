// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.export.ExportBacking
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.lyrics.Lrc
import eu.schicht8.meavox.core.lyrics.LyricsOffset
import eu.schicht8.meavox.core.lyrics.LyricsRepository
import eu.schicht8.meavox.core.lyrics.MatchLyrics
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.lyrics.candidate
import eu.schicht8.meavox.core.mix.ExportMix
import eu.schicht8.meavox.core.mix.GuideVocalUnavailable
import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.pipeline.TrackPipeline
import eu.schicht8.meavox.core.pipeline.VocalFreeToggle
import eu.schicht8.meavox.core.port.CapturedTake
import eu.schicht8.meavox.core.port.InputLevel
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.Playback
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.LatencyCorrection
import eu.schicht8.meavox.core.session.MonitoringUnavailable
import eu.schicht8.meavox.core.session.PracticeLoop
import eu.schicht8.meavox.core.session.Session
import eu.schicht8.meavox.core.session.SessionOutput
import eu.schicht8.meavox.core.session.SessionState
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.covers.CoverImages
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.ui.UiText
import eu.schicht8.meavox.ui.covers.CoverTile
import eu.schicht8.meavox.ui.formatTime
import eu.schicht8.meavox.ui.label
import eu.schicht8.meavox.ui.lyrics.KaraokeView
import eu.schicht8.meavox.ui.lyrics.PageView
import eu.schicht8.meavox.ui.lyrics.details
import eu.schicht8.meavox.ui.lyrics.name
import eu.schicht8.meavox.ui.models.ConfirmAction
import eu.schicht8.meavox.ui.models.DownloadNote
import eu.schicht8.meavox.ui.models.ModelPicker
import eu.schicht8.meavox.ui.models.confirmLabel
import eu.schicht8.meavox.ui.percent
import eu.schicht8.meavox.ui.resolve
import eu.schicht8.meavox.ui.text
import eu.schicht8.meavox.ui.theme.MeaVoxButtonShape
import eu.schicht8.meavox.ui.theme.MeaVoxTheme
import eu.schicht8.meavox.ui.verticalScrollbar
import kotlin.math.log10
import kotlin.math.roundToInt

/** The one screen per Track: its status and Lyrics, and the Session on it (#34). */
@Composable
fun SessionRoute(
    id: TrackId,
    tracks: TrackRepository,
    sessions: Sessions,
    pipeline: TrackPipeline,
    lyrics: LyricsRepository,
    match: MatchLyrics,
    lyricsOffset: LyricsOffset,
    vocalFreeToggle: VocalFreeToggle,
    choices: ModelChoices,
    mixes: Mixes,
    exportMix: ExportMix,
    exportBacking: ExportBacking,
    covers: CoverImages,
    onChooseLyrics: () -> Unit,
    onChooseCover: () -> Unit,
) {
    val viewModel: SessionViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SessionViewModel(id, tracks, sessions, pipeline, lyrics, match, lyricsOffset, vocalFreeToggle, choices, mixes, exportMix, exportBacking) }
        },
    )
    val context = LocalContext.current
    fun micAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // The permission is asked for only when the user first records (spec #1, story 85).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.onMicrophoneAllowed()
            viewModel.record()
        } else {
            viewModel.onMicrophoneDenied()
        }
    }
    // Monitoring needs the microphone too (#9).
    val monitoringPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.onMicrophoneAllowed()
            viewModel.setMonitoring(true)
        } else {
            viewModel.onMicrophoneDenied()
        }
    }
    val track by viewModel.track.collectAsStateWithLifecycle()
    val loadProgress by viewModel.loadProgress.collectAsStateWithLifecycle()
    val openError by viewModel.openError.collectAsStateWithLifecycle()
    val backing by viewModel.backing.collectAsStateWithLifecycle()
    // Once allowed, open the microphone right away so the meter shows before recording.
    LaunchedEffect(backing != null) {
        if (backing != null && micAllowed()) viewModel.onMicrophoneAllowed()
    }
    val running by viewModel.running.collectAsStateWithLifecycle()
    KeepScreenOn(running)
    val message by viewModel.message.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.resolve(resources))
            viewModel.messageShown()
        }
    }
    val modelPick by viewModel.modelPick.collectAsStateWithLifecycle()
    modelPick?.let { pick -> ModelPickDialog(pick, track, viewModel::pickModel, viewModel::confirmModelPick) }
    val confirmVocalFree by viewModel.confirmVocalFree.collectAsStateWithLifecycle()
    confirmVocalFree?.let { bytes ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmVocalFree(false) },
            title = { Text(stringResource(R.string.session_vocal_free_title)) },
            text = {
                Text(
                    stringResource(R.string.session_vocal_free_text, Formatter.formatShortFileSize(context, bytes)),
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmVocalFree(true) }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.session_delete_stems)) } },
            dismissButton = { TextButton(onClick = { viewModel.confirmVocalFree(false) }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val gain by viewModel.inputGainDb.collectAsStateWithLifecycle()
    val countdown by viewModel.countdownEnabled.collectAsStateWithLifecycle()
    val reviewBacking by viewModel.reviewBackingDb.collectAsStateWithLifecycle()
    val takes by viewModel.takes.collectAsStateWithLifecycle()
    val trackLyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val offsetMs by viewModel.lyricsOffsetMs.collectAsStateWithLifecycle()
    val karaoke by viewModel.karaoke.collectAsStateWithLifecycle()
    val transpose by viewModel.transpose.collectAsStateWithLifecycle()
    val practiceLoop by viewModel.practiceLoop.collectAsStateWithLifecycle()
    val guideVocalLevel by viewModel.guideVocalLevel.collectAsStateWithLifecycle()
    val output by viewModel.output.collectAsStateWithLifecycle()
    val latencyCorrection by viewModel.latencyCorrection.collectAsStateWithLifecycle()
    val guideVocalUnavailable by viewModel.guideVocalUnavailable.collectAsStateWithLifecycle()
    val coverVersion by remember(id) { covers.versionOf(id) }.collectAsStateWithLifecycle(null)
    val mixes by viewModel.trackMixes.collectAsStateWithLifecycle()
    val backingExport by viewModel.backingExport.collectAsStateWithLifecycle()
    backingExport?.let { dialog ->
        track?.let { shown ->
            BackingExportSheet(
                dialog, shown,
                BackingExportActions(
                    onLayer = viewModel::chooseBackingExportLayer,
                    onTranspose = viewModel::setBackingExportTranspose,
                    onExport = viewModel::startBackingExport,
                    onCancelExport = viewModel::cancelBackingExport,
                    onClose = viewModel::closeBackingExport,
                ),
            )
        }
    }
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    editor?.let { open ->
        val mix = mixes.firstOrNull { it.id == open.mix }
        val shownTrack = track
        if (mix != null && shownTrack != null) {
            MixEditorSheet(
                open, mix, takes.firstOrNull { it.id == mix.take }, shownTrack, state, playback,
                MixEditorActions(
                    onSettings = viewModel::editMixSettings,
                    onSettingsDone = viewModel::saveMixSettings,
                    onMatchLevel = viewModel::matchMixLevel,
                    onBacking = viewModel::chooseMixBacking,
                    onPreview = viewModel::previewMix,
                    onStop = viewModel::stop,
                    onExport = viewModel::exportMix,
                    onCancelExport = viewModel::cancelExport,
                    onClose = viewModel::closeMixEditor,
                ),
            )
        }
    }

    SessionScreen(
        openError = openError,
        snackbar = snackbar,
        content = SessionContent(
            track, loadProgress, state, backing, playback, input, gain, countdown, reviewBacking, takes, trackLyrics, offsetMs, karaoke, transpose,
            modelName = choices::nameOf,
            mixes = mixes,
            practiceLoop = practiceLoop,
            guideVocalLevel = guideVocalLevel,
            output = output,
            latencyCorrection = latencyCorrection,
            guideVocalUnavailable = guideVocalUnavailable,
            coverVersion = coverVersion,
        ),
        covers = covers,
        actions = SessionActions(
            onTogglePlay = viewModel::togglePlay,
            onSeek = viewModel::seekTo,
            onRecord = { if (micAllowed()) viewModel.record() else permission.launch(Manifest.permission.RECORD_AUDIO) },
            onStop = viewModel::stop,
            onReplay = viewModel::replay,
            onPause = viewModel::pause,
            onKeep = viewModel::keep,
            onDiscard = viewModel::discard,
            onRecordAgain = viewModel::recordAgain,
            onCountdown = viewModel::setCountdownEnabled,
            onGain = viewModel::setInputGain,
            onReviewBalance = viewModel::setReviewBackingDb,
            onPlayTake = viewModel::playTake,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onShowKaraoke = viewModel::showKaraoke,
            onNudgeLyricsOffset = viewModel::nudgeLyricsOffset,
            onResetLyricsOffset = viewModel::resetLyricsOffset,
            onTranspose = viewModel::setTranspose,
            onChooseBacking = viewModel::chooseBacking,
            onRetry = viewModel::retry,
            onConfirmLyrics = viewModel::confirmLyrics,
            onChooseLyrics = onChooseLyrics,
            onChooseCover = onChooseCover,
            onVocalFree = viewModel::setVocalFree,
            onReseparate = viewModel::startReseparating,
            onNewMix = viewModel::newMix,
            onEditMix = viewModel::editMix,
            onDeleteMix = viewModel::deleteMix,
            onBackingExport = viewModel::openBackingExport,
            onMarkLoopStart = viewModel::markLoopStart,
            onMarkLoopEnd = viewModel::markLoopEnd,
            onClearLoop = viewModel::clearPracticeLoop,
            onGuideVocal = viewModel::setGuideVocalLevel,
            onLatencyCorrection = viewModel::setLatencyCorrection,
            onMonitoring = { on -> if (!on || micAllowed()) viewModel.setMonitoring(on) else monitoringPermission.launch(Manifest.permission.RECORD_AUDIO) },
            onMonitoringLevel = viewModel::setMonitoringLevel,
        ),
    )
}

/**
 * The model picker for re-separating the Track or unmarking it Vocal-Free (ADR 19), with the
 * Default Separation Model pre-selected. Re-separating with the model the Track already has (or is
 * being separated with) does nothing, so it can't be confirmed.
 */
@Composable
private fun ModelPickDialog(pick: ModelPick, track: Track?, onPick: (SeparationModelId) -> Unit, onConfirm: (Boolean) -> Unit) {
    // Deleted stems are no Instrumental: re-separating with their model makes them again (ADR 17).
    val current = track?.vocalRemovalModel?.takeIf { track.pipeline !is TrackPipelineState.StemsDeleted }
    val selected = pick.options.firstOrNull { it.id == pick.selected }
    val reseparate = pick.purpose == ModelPick.Purpose.Reseparate
    AlertDialog(
        onDismissRequest = { onConfirm(false) },
        title = { Text(stringResource(if (reseparate) R.string.session_reseparate_title else R.string.session_separate_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reseparate) {
                    // The Instrumental the Track has keeps playing until the new one is there; going back
                    // to it needs no Separation (ADR 22).
                    val instrumental = track?.instrumentalModel
                    val text = when {
                        instrumental == null -> stringResource(R.string.session_reseparate_text)
                        pick.selected == instrumental -> stringResource(R.string.session_reseparate_text_back, selected?.name ?: instrumental.value)
                        else -> stringResource(R.string.session_reseparate_text_keeps)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
                ModelPicker(pick.options, pick.selected, onPick, track?.durationMs, current = current.takeIf { reseparate })
                DownloadNote(selected)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(true) },
                enabled = !(reseparate && pick.selected == current),
                shape = MeaVoxButtonShape,
            ) { Text(confirmLabel(if (reseparate) ConfirmAction.Reseparate else ConfirmAction.RemoveVocals, selected)) }
        },
        dismissButton = { TextButton(onClick = { onConfirm(false) }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) } },
    )
}

data class SessionContent(
    /** Null until the Track is loaded. */
    val track: Track?,
    /** How much of the Backing Audio is prepared while the Session opens, 0..1. */
    val loadProgress: Float,
    val state: SessionState,
    /** What plays now; null until the Session is open. */
    val backing: BackingAudio?,
    val playback: Playback,
    val input: InputLevel,
    val inputGainDb: Float,
    val countdownEnabled: Boolean,
    /** Backing Audio level while a Take plays back, in dB (Review Balance). */
    val reviewBackingDb: Float,
    val takes: List<Take>,
    val lyrics: TrackLyrics?,
    val lyricsOffsetMs: Long,
    /** Karaoke View rather than Page View, when the Lyrics are synced. */
    val karaoke: Boolean,
    /** The Transpose control: free playback and the next Take. */
    val transpose: Transpose,
    /** A Separation Model's name in the picker ("Fast", "Best"). */
    val modelName: (SeparationModelId) -> String = { it.value },
    /** The Track's Mixes, oldest first (#14). */
    val mixes: List<Mix> = emptyList(),
    /** The Practice Loop, once A is marked (#18). */
    val practiceLoop: PracticeLoop? = null,
    /** The Guide Vocal in the headphones, 0..1, and why it can't be heard now, if it can't (#18). */
    val guideVocalLevel: Float = 0f,
    val guideVocalUnavailable: GuideVocalUnavailable? = null,
    /** Which Cover Art the Track has ([eu.schicht8.meavox.core.library.TrackFiles.coverVersion]); null without one. */
    val coverVersion: Long? = null,
    /** The Session's output, its round trip and Monitoring (#9); null until the Session is open. */
    val output: SessionOutput? = null,
    /** The Latency Correction of the Take in Review (#9). */
    val latencyCorrection: LatencyCorrection? = null,
)

data class SessionActions(
    val onTogglePlay: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onRecord: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onReplay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onKeep: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onRecordAgain: () -> Unit = {},
    val onCountdown: (Boolean) -> Unit = {},
    val onGain: (Float) -> Unit = {},
    val onReviewBalance: (Float) -> Unit = {},
    val onPlayTake: (TakeId) -> Unit = {},
    val onRename: (TakeId, String) -> Unit = { _, _ -> },
    val onDelete: (TakeId) -> Unit = {},
    val onShowKaraoke: (Boolean) -> Unit = {},
    val onNudgeLyricsOffset: (Long) -> Unit = {},
    val onResetLyricsOffset: () -> Unit = {},
    val onTranspose: (Transpose) -> Unit = {},
    val onChooseBacking: (BackingChoice) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onConfirmLyrics: () -> Unit = {},
    val onChooseLyrics: () -> Unit = {},
    /** Opens the cover search (#51). */
    val onChooseCover: () -> Unit = {},
    val onVocalFree: (Boolean) -> Unit = {},
    val onReseparate: () -> Unit = {},
    val onNewMix: (TakeId) -> Unit = {},
    val onEditMix: (Mix) -> Unit = {},
    val onDeleteMix: (MixId) -> Unit = {},
    val onBackingExport: () -> Unit = {},
    val onMarkLoopStart: () -> Unit = {},
    val onMarkLoopEnd: () -> Unit = {},
    val onClearLoop: () -> Unit = {},
    val onGuideVocal: (Float) -> Unit = {},
    val onLatencyCorrection: (Int) -> Unit = {},
    val onMonitoring: (Boolean) -> Unit = {},
    val onMonitoringLevel: (Float) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    openError: UiText?,
    snackbar: SnackbarHostState,
    content: SessionContent,
    actions: SessionActions,
    /** Where the cover next to the title comes from; null shows the placeholder only (previews). */
    covers: CoverImages? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // The cover left of the title and artist, the way to the cover search at the right edge (#51).
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        content.track?.let { CoverTile(it.id, content.coverVersion, covers, HEADER_COVER_SIZE) }
                        TrackTitle(content.track)
                    }
                },
                actions = {
                    if (content.track != null) TextButton(onClick = actions.onChooseCover) { Text(stringResource(R.string.session_cover_search)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Lyrics on top, a few lines tall; the Track's status and the Session's controls take the rest
        // below. Each part scrolls on its own.
        Column(Modifier.fillMaxSize().padding(padding)) {
            LyricsPane(content, actions, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            HorizontalDivider()
            val controlsScroll = rememberScrollState()
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScrollbar(controlsScroll, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    .verticalScroll(controlsScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SessionControls(openError, content, actions)
            }
        }
    }
}

/** Karaoke View for synced Lyrics (switchable to Page View), Page View otherwise. */
@Composable
private fun LyricsPane(content: SessionContent, actions: SessionActions, modifier: Modifier) {
    val synced = content.lyrics.candidate()?.synced
    val lines = remember(synced) { synced?.let(Lrc::parse).orEmpty() }
    if (lines.isEmpty()) return PageView(content.lyrics, modifier)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Choice(stringResource(R.string.session_view_karaoke), content.karaoke, index = 0, count = 2) { actions.onShowKaraoke(true) }
            Choice(stringResource(R.string.session_view_page), !content.karaoke, index = 1, count = 2) { actions.onShowKaraoke(false) }
        }
        if (content.karaoke) {
            KaraokeView(
                lines, content.playback, content.lyricsOffsetMs, actions.onNudgeLyricsOffset, actions.onResetLyricsOffset,
                Modifier.fillMaxWidth(),
            )
        } else {
            PageView(content.lyrics, Modifier.fillMaxWidth())
        }
    }
}

/** One segment of a switch (Karaoke | Page, the Backing Audio): 6 dp corners, the chosen one in muted gold, no check mark. */
@Composable
internal fun SingleChoiceSegmentedButtonRowScope.Choice(
    label: String,
    selected: Boolean,
    index: Int,
    count: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = SegmentedButtonDefaults.itemShape(index, count, baseShape = MaterialTheme.shapes.extraSmall),
        icon = {},
    ) { Text(label) }
}

/** The size of the cover next to the Track's title (#51). */
private val HEADER_COVER_SIZE = 40.dp

/** The Track's title, with its artist below if known. */
@Composable
private fun TrackTitle(track: Track?) {
    Column {
        Text(track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        track?.artist?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The lower half, in the order of #34: status, Lyrics, Backing Audio, the Session, Vocal-Free. */
@Composable
private fun SessionControls(openError: UiText?, content: SessionContent, actions: SessionActions) {
    val track = content.track ?: return LinearProgressIndicator(Modifier.fillMaxWidth())
    PipelineStatus(track, content.modelName, actions.onRetry)
    VocalRemovalAndLyrics(track, content, actions)
    when {
        openError != null -> Text(stringResource(R.string.session_cant_play, openError.resolve()), color = MaterialTheme.colorScheme.error)
        // Nothing to play until the Original Audio is stored; the Session opens by itself then (ADR 10).
        !track.hasOriginalAudio -> Unit
        content.backing == null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.session_preparing, percent(LocalResources.current, content.loadProgress)), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { content.loadProgress }, modifier = Modifier.fillMaxWidth())
        }
        else -> {
            SessionSettings(track, content, actions)
            Position(content.state, content.playback, content.practiceLoop, actions.onSeek)
            Transport(content.state, content.playback, content.reviewBackingDb, content.latencyCorrection, actions)
            // The microphone hears the speaker (#9).
            val recordingOrAboutTo = content.state == SessionState.Idle || content.state is SessionState.CountingDown ||
                content.state is SessionState.Recording
            if (recordingOrAboutTo && content.output?.withoutHeadphones == true) {
                Text(stringResource(R.string.session_without_headphones), style = MaterialTheme.typography.bodySmall, color = MeaVoxTheme.colors.record)
            }
            GuideVocalControl(
                content.guideVocalLevel, content.guideVocalUnavailable, R.string.session_guide_vocal_hint,
                onChange = actions.onGuideVocal,
            )
            Microphone(content.input, content.inputGainDb, actions.onGain)
            content.output?.let { OutputPanel(it, content.state == SessionState.Idle, actions) }
            SettingRow(stringResource(R.string.session_countdown, Session.COUNTDOWN_SECONDS), content.countdownEnabled, actions.onCountdown)
            Takes(content.takes, content.state, actions)
            MixesSection(content.mixes, content.takes, actions.onEditMix, actions.onDeleteMix)
        }
    }
    // A Backing Export needs only the Original Audio (#24), not an open Session.
    if (track.hasOriginalAudio) BackingExportRow(actions.onBackingExport)
    VocalFreeToggle(track.vocalFree, actions.onVocalFree)
}

/**
 * Instrumental | Original Audio, kept for the Track. The Instrumental can't be chosen until vocal
 * removal is done; the Original Audio plays meanwhile.
 */
@Composable
private fun BackingChoiceControl(track: Track, onChoose: (BackingChoice) -> Unit, modifier: Modifier = Modifier) {
    val available = track.instrumentalModel != null
    val shown = shownBackingChoice(track)
    SettingGroup(stringResource(R.string.session_sing_over), modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Choice(stringResource(R.string.backing_instrumental), shown == BackingChoice.Instrumental, index = 0, count = 2, enabled = available) {
                onChoose(BackingChoice.Instrumental)
            }
            Choice(stringResource(R.string.backing_original), shown == BackingChoice.OriginalAudio, index = 1, count = 2) {
                onChoose(BackingChoice.OriginalAudio)
            }
        }
    }
}

/** The Backing Audio the choice shows: the Track's, but the Original Audio until there is an Instrumental. */
private fun shownBackingChoice(track: Track): BackingChoice =
    if (track.instrumentalModel != null) track.backingChoice else BackingChoice.OriginalAudio

/** What the Backing Audio choice has to say, if anything: no Instrumental yet, or it switches after the Take. */
@Composable
private fun backingHint(track: Track, playing: BackingAudio?): String? {
    if (track.vocalFree || playing == null) return null
    val available = track.instrumentalModel != null
    val shown = shownBackingChoice(track)
    val playingShown = (playing is BackingAudio.Instrumental) == (shown == BackingChoice.Instrumental)
    return when {
        !available -> stringResource(R.string.session_instrumental_later)
        // Never under a Take being recorded, reviewed or played back (ADR 20).
        !playingShown -> stringResource(R.string.session_switches_after_take)
        else -> null
    }
}

/**
 * Where the Track is in the Track Pipeline (ADR 10), and which earlier Instrumental is used while it
 * is re-separated (ADR 22); what plays is the Backing Audio choice's business.
 */
@Composable
private fun PipelineStatus(track: Track, modelName: (SeparationModelId) -> String, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (val pipeline = track.pipeline) {
        is TrackPipelineState.Separating -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pipeline.label(), style = MaterialTheme.typography.bodyMedium)
            val progress = pipeline.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            }
            PreviousInstrumental(track, modelName, R.string.session_previous_instrumental_meanwhile)
        }
        is TrackPipelineState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.session_pipeline_failed, pipeline.label(), pipeline.reason.text().resolve()), color = MeaVoxTheme.colors.record, style = MaterialTheme.typography.bodyMedium)
            PreviousInstrumental(track, modelName, R.string.session_previous_instrumental_still)
            NeutralOutlinedButton(stringResource(R.string.action_retry), onRetry, Modifier.height(44.dp))
        }
        is TrackPipelineState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pipeline.label(), style = MaterialTheme.typography.bodyMedium)
            pipeline.retryReason?.let { Text(stringResource(R.string.session_last_try, it.text().resolve()), style = MaterialTheme.typography.bodySmall, color = muted) }
            val progress = pipeline.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        is TrackPipelineState.Ready -> Unit
        // Opening the Track brings deleted stems back, so this shows only for a moment (ADR 17).
        TrackPipelineState.Added, TrackPipelineState.Downloaded, is TrackPipelineState.StemsDeleted ->
            Text(pipeline.label(), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** The Instrumental used while the Track is re-separated, or after that failed (ADR 22), worded by [text]. */
@Composable
private fun PreviousInstrumental(track: Track, modelName: (SeparationModelId) -> String, @StringRes text: Int) {
    val model = track.instrumentalModel ?: return
    Text(stringResource(text, modelName(model)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Which Separation Model removes the Track's vocals, and the way to pick another (ADR 19). Not for a
 * Vocal-Free Track, which isn't separated (ADR 14), nor before the Original Audio is stored.
 */
@Composable
private fun VocalRemoval(
    track: Track,
    modelName: (SeparationModelId) -> String,
    onReseparate: () -> Unit,
    /** The button below the model, as the Lyrics' buttons are, rather than at the right edge. */
    stacked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // After a failed Separation, the model Retry separates with.
    val model = track.vocalRemovalModel ?: track.separationModel.takeIf { track.pipeline is TrackPipelineState.Failed }
    val label = @Composable {
        Text(stringResource(R.string.import_vocal_removal), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        model?.let { Text(modelName(it), style = rowLabel()) }
    }
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            label()
            NeutralOutlinedButton(stringResource(R.string.confirm_reseparate), onReseparate, Modifier.height(44.dp))
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { label() }
            NeutralOutlinedButton(stringResource(R.string.confirm_reseparate), onReseparate, Modifier.height(44.dp))
        }
    }
}

/**
 * Vocal removal and the Lyrics: side by side, each half the width, where there is room; one below the
 * other on a narrow screen. A Vocal-Free Track, or one without its Original Audio yet, has no vocal
 * removal to show, so the Lyrics take the width.
 */
@Composable
private fun VocalRemovalAndLyrics(track: Track, content: SessionContent, actions: SessionActions) {
    val vocalRemoval = !track.vocalFree && track.hasOriginalAudio
    BoxWithConstraints {
        if (vocalRemoval && maxWidth >= SIDE_BY_SIDE) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                VocalRemoval(track, content.modelName, actions.onReseparate, stacked = true, modifier = Modifier.weight(1f))
                LyricsStatus(content.lyrics, actions.onConfirmLyrics, actions.onChooseLyrics, Modifier.weight(1f))
            }
        } else {
            // The spacing of the column these sections sit in.
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vocalRemoval) VocalRemoval(track, content.modelName, actions.onReseparate)
                LyricsStatus(content.lyrics, actions.onConfirmLyrics, actions.onChooseLyrics)
            }
        }
    }
}

/** Which Lyrics the Track has, and how to confirm or change them (ADR 6). */
@Composable
private fun LyricsStatus(lyrics: TrackLyrics?, onConfirm: () -> Unit, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.session_lyrics), style = MaterialTheme.typography.labelLarge, color = muted)
        when (lyrics) {
            null, TrackLyrics.Searching -> {
                // Waits for a network; the user needn't.
                Text(stringResource(R.string.session_searching), style = small, color = muted)
                NeutralOutlinedButton(stringResource(R.string.action_search), onChoose, Modifier.height(44.dp))
            }
            is TrackLyrics.Suggested -> {
                Text(stringResource(R.string.session_lyrics_suggested, lyrics.lyrics.name()), style = rowLabel())
                Text(lyrics.lyrics.details(), style = small, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Gold text, not a gold surface: Keep is the screen's one gold surface.
                    OutlinedButton(onClick = onConfirm, shape = MeaVoxButtonShape, modifier = Modifier.height(44.dp)) { Text(stringResource(R.string.action_confirm)) }
                    NeutralOutlinedButton(stringResource(R.string.session_lyrics_choose_another), onChoose, Modifier.height(44.dp))
                }
            }
            is TrackLyrics.Confirmed -> {
                Text(lyrics.lyrics.name(), style = rowLabel())
                Text(lyrics.lyrics.details(), style = small, color = muted)
                NeutralOutlinedButton(stringResource(R.string.action_change), onChoose, Modifier.height(44.dp))
            }
            is TrackLyrics.NotFound -> {
                Text(stringResource(R.string.session_lyrics_not_found, lyrics.query.title), style = small, color = muted)
                NeutralOutlinedButton(stringResource(R.string.action_search), onChoose, Modifier.height(44.dp))
            }
            is TrackLyrics.Failed -> {
                Text(stringResource(R.string.session_lyrics_failed, lyrics.reason.text().resolve()), style = small, color = MeaVoxTheme.colors.record)
                NeutralOutlinedButton(stringResource(R.string.action_search), onChoose, Modifier.height(44.dp))
            }
        }
    }
}

/** The Vocal-Free toggle (GLOSSARY: Vocal-Free, ADR 14). */
@Composable
private fun VocalFreeToggle(vocalFree: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.import_no_vocals), style = rowLabel())
            Text(
                stringResource(R.string.session_vocal_free_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = vocalFree, onCheckedChange = onChange)
    }
}

@Composable
private fun Position(state: SessionState, playback: Playback, loop: PracticeLoop?, onSeek: (Long) -> Unit) {
    // While dragging, show the thumb where the finger is; seek once on release.
    var dragMs by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1).toFloat()
    // The Start Position is chosen while idle; a Take's own position runs otherwise (dimmed).
    val idle = state == SessionState.Idle
    val colors = MaterialTheme.colorScheme
    Column(Modifier.alpha(if (idle) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = dragMs ?: playback.positionMs.toFloat().coerceIn(0f, duration),
            onValueChange = { dragMs = it },
            onValueChangeFinished = {
                dragMs?.let { onSeek(it.toLong()) }
                dragMs = null
            },
            valueRange = 0f..duration,
            enabled = idle,
            // Dimmed as a whole rather than greyed out: it stays gold, as the position keeps running.
            colors = SliderDefaults.colors(
                inactiveTrackColor = colors.secondaryContainer,
                disabledThumbColor = colors.primary,
                disabledActiveTrackColor = colors.primary,
                disabledInactiveTrackColor = colors.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        loop?.let { LoopRange(it, duration) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(dragMs?.toLong() ?: playback.positionMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(formatTime(playback.durationMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** The Practice Loop's A–B under the position bar, lined up with its track (A alone as a tick). */
@Composable
private fun LoopRange(loop: PracticeLoop, durationMs: Float) {
    val color = MaterialTheme.colorScheme.primary
    // The slider's track starts and ends half a thumb in from its edges.
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(4.dp)) {
        val start = size.width * (loop.startMs / durationMs).coerceIn(0f, 1f)
        val end = loop.endMs?.let { size.width * (it / durationMs).coerceIn(0f, 1f) } ?: (start + 2.dp.toPx())
        drawRect(color, topLeft = Offset(start, 0f), size = Size((end - start).coerceAtLeast(2.dp.toPx()), size.height))
    }
}

/**
 * The Practice Loop (#18, ADR 9): A and B are marked at the current position, while idle; free
 * playback then repeats A–B. A Take records straight on; the loop stays for afterwards.
 */
@Composable
private fun PracticeLoopControl(loop: PracticeLoop?, state: SessionState, actions: SessionActions, modifier: Modifier = Modifier) {
    val idle = state == SessionState.Idle
    SettingGroup(stringResource(R.string.session_loop), modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton(stringResource(R.string.session_loop_a), stringResource(R.string.session_loop_mark_a), enabled = idle, onClick = actions.onMarkLoopStart)
            StepButton(stringResource(R.string.session_loop_b), stringResource(R.string.session_loop_mark_b), enabled = idle && loop != null, onClick = actions.onMarkLoopEnd)
            TextButton(onClick = actions.onClearLoop, enabled = loop != null, shape = MeaVoxButtonShape) {
                Text(stringResource(R.string.session_loop_off))
            }
        }
    }
}

/** How to set the Practice Loop, or where it runs. */
@Composable
private fun loopHint(loop: PracticeLoop?): String {
    val end = loop?.endMs
    return when {
        loop == null -> stringResource(R.string.session_loop_hint)
        end == null -> stringResource(R.string.session_loop_mark_b_next, formatTime(loop.startMs))
        else -> stringResource(R.string.session_loop_range, formatTime(loop.startMs), formatTime(end))
    }
}

@Composable
private fun Transport(state: SessionState, playback: Playback, reviewBackingDb: Float, correction: LatencyCorrection?, actions: SessionActions) {
    when (state) {
        SessionState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayPauseButton(playback.playing, actions.onTogglePlay)
            RecordButton(stringResource(R.string.session_record_from, formatTime(playback.positionMs)), actions.onRecord, Modifier.weight(1f))
        }
        is SessionState.CountingDown -> Row(
            Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "${state.secondsLeft}",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
            Text(stringResource(R.string.session_recording_from, formatTime(state.startPositionMs)), style = rowLabel(), modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onStop, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) }
        }
        is SessionState.Recording -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val record = MeaVoxTheme.colors.record
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(record, CircleShape)
                Text(
                    stringResource(R.string.session_recording, formatTime(playback.positionMs - state.startPositionMs)),
                    style = MeaVoxTheme.type.recordingTime,
                    color = record,
                )
            }
            StopButton(actions.onStop)
        }
        is SessionState.Review -> Review(state.take, state.transpose, playback, reviewBackingDb, correction, actions)
        is SessionState.PlayingTake -> Panel(spacing = 12.dp) {
            Text(stringResource(R.string.session_playing_take, state.take.name), style = MaterialTheme.typography.titleMedium)
            NeutralOutlinedButton(stringResource(R.string.action_stop), actions.onStop, Modifier.height(44.dp))
            ReviewBalance(reviewBackingDb, actions.onReviewBalance)
        }
        is SessionState.PreviewingMix -> PreviewingMixPanel(state, playback, actions.onStop)
    }
}

/** The Transpose control (ADR 5). A Take being recorded or played back keeps its own Transpose. */
@Composable
private fun TransposeControl(transpose: Transpose, onChange: (Transpose) -> Unit, modifier: Modifier = Modifier) {
    SettingGroup(stringResource(R.string.session_transpose), modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", stringResource(R.string.session_transpose_down), enabled = transpose.semitones > Transpose.MIN) {
                onChange(Transpose(transpose.semitones - 1))
            }
            Text(
                transpose.label(),
                Modifier.width(40.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            StepButton("+", stringResource(R.string.session_transpose_up), enabled = transpose.semitones < Transpose.MAX) {
                onChange(Transpose(transpose.semitones + 1))
            }
        }
    }
}

/** The key the Backing Audio is bound to while a Take records or plays back, if it isn't the control's. */
@Composable
private fun transposeHint(transpose: Transpose, state: SessionState): String? {
    val takeTranspose = when (state) {
        is SessionState.Recording -> state.transpose
        is SessionState.Review -> state.transpose
        is SessionState.PlayingTake -> state.take.transpose
        else -> null
    }
    return if (takeTranspose != null && takeTranspose != transpose) stringResource(R.string.session_transpose_next_take, takeTranspose.label()) else null
}

/**
 * The Backing Audio choice, the Practice Loop and Transpose, set before singing (#53): one row, each
 * label above its controls; on a narrow screen the Backing Audio choice takes a line of its own. A
 * Vocal-Free Track has no Backing Audio choice (ADR 14). The hints follow below, full width, while they
 * have something to say.
 */
@Composable
private fun SessionSettings(track: Track, content: SessionContent, actions: SessionActions) {
    val hints = listOfNotNull(
        backingHint(track, content.backing),
        loopHint(content.practiceLoop),
        transposeHint(content.transpose, content.state),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints {
            // A Vocal-Free Track has no Instrumental, so no Backing Audio choice (ADR 14).
            val showBackingChoice = !track.vocalFree
            if (maxWidth >= SIDE_BY_SIDE) {
                // Top-aligned, so the labels share one line.
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (showBackingChoice) BackingChoiceControl(track, actions.onChooseBacking, Modifier.weight(1f))
                    PracticeLoopControl(content.practiceLoop, content.state, actions)
                    TransposeControl(content.transpose, actions.onTranspose)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showBackingChoice) BackingChoiceControl(track, actions.onChooseBacking, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PracticeLoopControl(content.practiceLoop, content.state, actions)
                        TransposeControl(content.transpose, actions.onTranspose)
                    }
                }
            }
        }
        for (hint in hints) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A setting's label above its controls (#53). */
@Composable
private fun SettingGroup(label: String, modifier: Modifier = Modifier, controls: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = rowLabel())
        controls()
    }
}

/** The content width from which the Track's screen puts side by side what a narrow screen stacks (#53). */
private val SIDE_BY_SIDE = 600.dp

/** A square 44 dp − / + button. */
@Composable
internal fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MeaVoxButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(44.dp).semantics { contentDescription = description },
    ) { Text(symbol, fontSize = 20.sp) }
}

/**
 * Play and Pause of free playback: green with a triangle to play, gold with two bars while playing. Both
 * labels are laid over each other, the hidden one only keeping its width, so the button never changes size.
 */
@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    val container = if (playing) MaterialTheme.colorScheme.primary else MeaVoxTheme.colors.play
    val content = if (playing) MaterialTheme.colorScheme.onPrimary else MeaVoxTheme.colors.onPlay
    Button(
        onClick = onClick,
        shape = MeaVoxButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.height(TRANSPORT_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            PlayPauseLabel(pause = false, content, Modifier.shownIf(!playing))
            PlayPauseLabel(pause = true, content, Modifier.shownIf(playing))
        }
    }
}

/** Visible and announced when [shown]; otherwise it only takes up its space. */
private fun Modifier.shownIf(shown: Boolean): Modifier = if (shown) this else alpha(0f).clearAndSetSemantics {}

@Composable
private fun PlayPauseLabel(pause: Boolean, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (pause) PauseSymbol(color) else PlaySymbol(color)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (pause) R.string.action_pause else R.string.action_play), style = transportLabel(), fontWeight = FontWeight.Bold)
    }
}

/** A triangle pointing right, the size of the Record button's dot. */
@Composable
private fun PlaySymbol(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawPath(
            Path().apply {
                moveTo(size.width * 0.1f, 0f)
                lineTo(size.width, size.height / 2)
                lineTo(size.width * 0.1f, size.height)
                close()
            },
            color,
        )
    }
}

/** Two upright bars, the size of the Record button's dot. */
@Composable
private fun PauseSymbol(color: Color) {
    Row(Modifier.size(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(2) { Box(Modifier.width(4.dp).fillMaxHeight().background(color)) }
    }
}

/** The wide Record button: red, with the Start Position in its label (ADR 9). */
@Composable
private fun RecordButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MeaVoxTheme.colors
    Button(
        onClick = onClick,
        shape = MeaVoxButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.record, contentColor = colors.onRecord),
        modifier = modifier.height(TRANSPORT_HEIGHT),
    ) {
        Dot(colors.onRecord, CircleShape)
        Spacer(Modifier.width(8.dp))
        Text(label, style = transportLabel(), fontWeight = FontWeight.Bold)
    }
}

/** Stops the recording: red like Record, with a square. */
@Composable
private fun StopButton(onClick: () -> Unit) {
    val colors = MeaVoxTheme.colors
    Button(
        onClick = onClick,
        shape = MeaVoxButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.record, contentColor = colors.onRecord),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = Modifier.height(TRANSPORT_HEIGHT),
    ) {
        Dot(colors.onRecord, RoundedCornerShape(2.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_stop), style = transportLabel(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Review(
    take: CapturedTake,
    transpose: Transpose,
    playback: Playback,
    reviewBackingDb: Float,
    correction: LatencyCorrection?,
    actions: SessionActions,
) {
    Panel(spacing = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.session_review), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.session_review_take, formatTime(take.startPositionMs), formatTime(take.durationMs), transpose.label()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playback.playingTake) {
                NeutralOutlinedButton(stringResource(R.string.action_pause), actions.onPause, Modifier.height(44.dp), horizontalPadding = 14.dp)
            } else {
                NeutralOutlinedButton(stringResource(R.string.session_play_again), actions.onReplay, Modifier.height(44.dp), horizontalPadding = 14.dp)
            }
            // Keep is the one gold surface on the screen.
            Button(onClick = actions.onKeep, shape = MeaVoxButtonShape, modifier = Modifier.weight(1f).height(44.dp)) {
                Text(stringResource(R.string.session_keep), fontWeight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeutralOutlinedButton(stringResource(R.string.session_discard), actions.onDiscard, Modifier.weight(1f).height(44.dp))
            val record = MeaVoxTheme.colors.record
            OutlinedButton(
                onClick = actions.onRecordAgain,
                shape = MeaVoxButtonShape,
                border = BorderStroke(1.dp, record),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = record),
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text(stringResource(R.string.session_record_again)) }
        }
        ReviewBalance(reviewBackingDb, actions.onReviewBalance)
        correction?.let { LatencyCorrectionControl(it, actions.onLatencyCorrection) }
    }
}

/**
 * The Latency Correction of the route and path the Take was recorded on, set by ear (#9, ADR 23). Shown like the
 * Vocal Nudge, later positive: a correction is extra latency, so it moves the voice earlier.
 */
@Composable
private fun LatencyCorrectionControl(correction: LatencyCorrection, onChange: (Int) -> Unit) {
    val output = correction.route.label().let { if (correction.path == OutputPath.LowLatency) stringResource(R.string.session_route_monitoring, it) else it }
    VocalShiftControl(stringResource(R.string.session_latency_correction, output), -correction.ms, Session.MAX_LATENCY_CORRECTION_MS, { onChange(-it) }) {
        // Not yet checked on this output: ask to listen (#9).
        Text(
            stringResource(if (correction.checked) R.string.session_latency_kept else R.string.session_latency_check),
            style = MaterialTheme.typography.bodySmall,
            color = if (correction.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Where the Session plays to, its round trip, and Monitoring (#9, ADR 13, ADR 16): switched only in free playback,
 * and only over wired or USB headphones.
 */
@Composable
private fun OutputPanel(output: SessionOutput, idle: Boolean, actions: SessionActions) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    Panel(spacing = 10.dp) {
        Text(stringResource(R.string.session_output, output.route?.label() ?: stringResource(R.string.device_unknown)), style = MaterialTheme.typography.labelLarge)
        output.roundTripMs?.let {
            Text(stringResource(if (output.corrected) R.string.session_round_trip_corrected else R.string.session_round_trip, it), style = small, color = muted)
        }
        val unavailable = output.monitoringUnavailable
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.session_monitoring), Modifier.weight(1f), style = rowLabel())
            Switch(
                checked = output.monitoring,
                onCheckedChange = actions.onMonitoring,
                // Switching it on opens the microphone, asking for the permission first if needed.
                enabled = idle && (unavailable == null || output.monitoring),
            )
        }
        when {
            unavailable != null -> Text(stringResource(unavailable.text()), style = small, color = muted)
            !output.monitoring -> Text(stringResource(R.string.session_monitoring_hint), style = small, color = muted)
        }
        if (output.monitoringDelayed) {
            Text(stringResource(R.string.session_monitoring_delayed, output.roundTripMs ?: 0), style = small, color = MeaVoxTheme.colors.record)
        }
        if (output.monitoring) {
            var dragLevel by remember { mutableStateOf<Float?>(null) }
            val shown = dragLevel ?: output.monitoringLevel
            Text(stringResource(R.string.session_monitoring_level, percent(LocalResources.current, shown)), style = small, color = muted)
            Slider(
                value = shown,
                onValueChange = {
                    dragLevel = it
                    actions.onMonitoringLevel(it)
                },
                onValueChangeFinished = { dragLevel = null },
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // An interface with its own headphone jack monitors without delay (ADR 13).
        if (output.route == OutputRoute.Usb) Text(stringResource(R.string.session_monitoring_usb_hint), style = small, color = muted)
    }
}

@StringRes
private fun MonitoringUnavailable.text(): Int = when (this) {
    MonitoringUnavailable.Speaker -> R.string.session_monitoring_speaker
    MonitoringUnavailable.Bluetooth -> R.string.session_monitoring_bluetooth
    MonitoringUnavailable.UnknownOutput -> R.string.session_monitoring_unknown
}

/** How loud the Backing Audio plays under the Take while listening back; the Take is unchanged. */
@Composable
private fun ReviewBalance(backingDb: Float, onChange: (Float) -> Unit) {
    // Dragging changes the balance live; it is remembered for the next Sessions.
    var dragDb by remember { mutableStateOf<Float?>(null) }
    val shown = dragDb ?: backingDb
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.session_review_balance, formatDb(shown)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.session_voice), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = shown,
                onValueChange = {
                    dragDb = it.roundToInt().toFloat()
                    onChange(it.roundToInt().toFloat())
                },
                onValueChangeFinished = { dragDb = null },
                valueRange = Session.MIN_REVIEW_BACKING_DB..0f,
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.session_backing), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Microphone(input: InputLevel, gainDb: Float, onGain: (Float) -> Unit) {
    Panel(spacing = 10.dp) {
        if (!input.enabled) {
            Text(
                stringResource(R.string.session_microphone_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        Text(stringResource(R.string.session_microphone, input.device?.label() ?: stringResource(R.string.device_unknown)), style = MaterialTheme.typography.labelLarge)
        // The level beside the gain, each half the width (#53); stacked on a narrow screen.
        BoxWithConstraints {
            if (maxWidth >= SIDE_BY_SIDE) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    InputLevelMeter(input, Modifier.weight(1f))
                    InputGain(gainDb, onGain, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InputLevelMeter(input, Modifier.fillMaxWidth())
                    InputGain(gainDb, onGain, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        if (input.clipping) {
            Text(
                stringResource(R.string.session_clipping),
                color = MeaVoxTheme.colors.record,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The microphone's level, with its scale below. */
@Composable
private fun InputLevelMeter(input: InputLevel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LevelMeter(meterLevel(input.peak), input.clipping)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (mark in listOf("−60 dB", "−30", "0 dB")) {
                Text(mark, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The input gain: dragging changes it live; it is saved for this kind of microphone. */
@Composable
private fun InputGain(gainDb: Float, onGain: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragDb by remember { mutableStateOf<Float?>(null) }
    val shown = dragDb ?: gainDb
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.session_input_gain, formatDb(shown)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shown,
            onValueChange = {
                dragDb = it.roundToInt().toFloat()
                onGain(it.roundToInt().toFloat())
            },
            onValueChangeFinished = { dragDb = null },
            valueRange = Session.MIN_INPUT_GAIN_DB..Session.MAX_INPUT_GAIN_DB,
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The input level as [METER_SEGMENTS] segments: lit ones gold (all red while clipping), unlit
 * ones raised grey, the last [CLIP_SEGMENTS] (the clipping zone) unlit in dark red.
 */
@Composable
private fun LevelMeter(level: Float, clipping: Boolean) {
    val lit = (level * METER_SEGMENTS).roundToInt()
    val scheme = MaterialTheme.colorScheme
    val extra = MeaVoxTheme.colors
    Row(
        Modifier.fillMaxWidth().height(12.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f) },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(METER_SEGMENTS) { segment ->
            val color = when {
                segment < lit -> if (clipping) extra.record else scheme.primary
                segment >= METER_SEGMENTS - CLIP_SEGMENTS -> extra.recordSoft
                else -> scheme.surfaceContainerHigh
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
internal fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = rowLabel())
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Takes(takes: List<Take>, state: SessionState, actions: SessionActions) {
    var renaming by remember { mutableStateOf<Take?>(null) }
    var deleting by remember { mutableStateOf<Take?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.storage_takes, takes.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (takes.isEmpty()) {
            Text(stringResource(R.string.session_takes_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        takes.forEach { take ->
            Panel(spacing = 6.dp, verticalPadding = 12.dp) {
                Text(take.name, style = rowLabel())
                Text(
                    stringResource(R.string.session_take_details, formatTime(take.startPositionMs), formatTime(take.durationMs), take.transpose.label(), take.route.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TakeAction(stringResource(R.string.action_play), MaterialTheme.colorScheme.primary, enabled = state == SessionState.Idle) { actions.onPlayTake(take.id) }
                    TakeAction(stringResource(R.string.session_new_mix), MaterialTheme.colorScheme.onSurface) { actions.onNewMix(take.id) }
                    TakeAction(stringResource(R.string.action_rename), MaterialTheme.colorScheme.onSurface) { renaming = take }
                    TakeAction(stringResource(R.string.action_delete), MaterialTheme.colorScheme.onSurface) { deleting = take }
                }
            }
        }
    }
    renaming?.let { take ->
        var name by remember(take.id) { mutableStateOf(take.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.session_rename_take)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRename(take.id, name)
                    renaming = null
                }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleting?.let { take ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.storage_delete_take_title, take.name)) },
            text = { Text(stringResource(R.string.session_delete_take_text)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(take.id)
                    deleting = null
                }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
internal fun TakeAction(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = MeaVoxButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.height(36.dp),
    ) { Text(label) }
}

/** A card on the raised surface: Review, Microphone, each Take. No shadow; layers come from the surface tones. */
@Composable
internal fun Panel(spacing: Dp, verticalPadding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** An outlined button in ink, not gold: gold is kept for the one primary action. */
@Composable
internal fun NeutralOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    horizontalPadding: Dp = 16.dp,
) {
    OutlinedButton(
        onClick = onClick,
        shape = MeaVoxButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        modifier = modifier,
    ) { Text(label, style = style) }
}

/** The dot of Record and "● Recording", or the square of Stop. */
@Composable
private fun Dot(color: Color, shape: Shape) {
    Box(Modifier.size(12.dp).background(color, shape))
}

/** Labels of the large transport buttons (16 sp, a step above the usual button label). */
@Composable
private fun transportLabel(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp)

/** Row titles: Transpose, the countdown setting, a Take's name. */
@Composable
internal fun rowLabel(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)

private val TRANSPORT_HEIGHT = 52.dp
private const val METER_SEGMENTS = 20
private const val CLIP_SEGMENTS = 3

/** Meter position 0..1 for a peak on a dB scale from [METER_FLOOR_DB] to 0 dBFS, as level meters read. */
private fun meterLevel(peak: Float): Float =
    if (peak <= 0f) 0f else ((20f * log10(peak) - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)

private const val METER_FLOOR_DB = -60f

internal fun formatDb(db: Float): String = if (db > 0) "+${db.roundToInt()} dB" else "${db.roundToInt()} dB"

/** Semitones with a sign, e.g. "+2", "−3", "0". */
internal fun Transpose.label(): String = when {
    semitones > 0 -> "+$semitones"
    semitones < 0 -> "−${-semitones}"
    else -> "0"
}

@Composable
private fun InputDevice.label(): String = stringResource(
    when (this) {
        InputDevice.BuiltIn -> R.string.device_built_in
        InputDevice.Wired -> R.string.device_headset
        InputDevice.Usb -> R.string.device_usb
        InputDevice.Bluetooth -> R.string.device_bluetooth
        InputDevice.Other -> R.string.device_other
    },
)

@Composable
private fun OutputRoute.label(): String = stringResource(
    when (this) {
        OutputRoute.Speaker -> R.string.route_speaker
        OutputRoute.Wired -> R.string.route_wired
        OutputRoute.Usb -> R.string.device_usb
        OutputRoute.Bluetooth -> R.string.device_bluetooth
        OutputRoute.Other -> R.string.route_other
    },
)

@Preview
@Composable
private fun ReviewPreview() {
    val take = CapturedTake(startFrame = 1_440_000, frames = 480_000, sampleRate = 48_000, route = OutputRoute.Wired, reportedLatencyFrames = 4_000)
    val kept = Take(TakeId("1"), TrackId(SourceId("local"), "x"), "Take 1", 0, 960_000, 48_000, OutputRoute.Wired, 4_000, Transpose(2), 0)
    MeaVoxTheme {
        SessionScreen(
            openError = null,
            snackbar = remember { SnackbarHostState() },
            content = SessionContent(
                track = Track(
                    TrackId(SourceId("local"), "x"), "Fly Me to the Moon", "Frank Sinatra", 148_000,
                    TrackPipelineState.Ready(SeparationModelId("fast")), backingChoice = BackingChoice.OriginalAudio,
                ),
                loadProgress = 1f,
                state = SessionState.Review(take, Transpose(-2)),
                backing = BackingAudio.Instrumental(SeparationModelId("fast")),
                playback = Playback(null, playing = true, positionMs = 32_000, durationMs = 180_000, playingTake = true),
                input = InputLevel(enabled = true, device = InputDevice.BuiltIn, peak = 0.6f, clipping = true),
                inputGainDb = 6f,
                countdownEnabled = true,
                reviewBackingDb = -20f,
                takes = listOf(kept),
                lyrics = TrackLyrics.Confirmed(
                    LyricsCandidate("1", "Frank Sinatra", "Fly Me to the Moon", 148_000, "Fly me to the moon\nLet me play among the stars", null),
                ),
                lyricsOffsetMs = 0,
                karaoke = true,
                transpose = Transpose(1),
            ),
            actions = SessionActions(),
        )
    }
}

/**
 * Keeps the screen on while [on] (#48): only the window flag, no wake lock. Leaving the screen or
 * the app going to the background clears it at once, so the system screen timeout applies again.
 */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    LifecycleStartEffect(view, on) {
        view.keepScreenOn = on
        onStopOrDispose { view.keepScreenOn = false }
    }
}

/** An idle Session's content for the settings previews (#53). */
private fun previewContent(vocalFree: Boolean = false) = SessionContent(
    track = Track(
        TrackId(SourceId("local"), "x"), "Fly Me to the Moon", "Frank Sinatra", 148_000,
        TrackPipelineState.Ready(SeparationModelId("fast")), vocalFree = vocalFree,
    ),
    loadProgress = 1f,
    state = SessionState.Idle,
    backing = BackingAudio.Instrumental(SeparationModelId("fast")),
    playback = Playback(null, playing = false, positionMs = 32_000, durationMs = 180_000),
    input = InputLevel(enabled = true, device = InputDevice.BuiltIn, peak = 0.4f, clipping = false),
    inputGainDb = 0f,
    countdownEnabled = true,
    reviewBackingDb = -20f,
    takes = emptyList(),
    lyrics = null,
    lyricsOffsetMs = 0,
    karaoke = false,
    transpose = Transpose(1),
)

/** The row of #53 and the microphone panel, at the width the preview gives. */
@Composable
private fun SessionSettingsAndMicrophone() {
    MeaVoxTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val content = previewContent()
            SessionSettings(checkNotNull(content.track), content, SessionActions())
            Microphone(content.input, content.inputGainDb, onGain = {})
        }
    }
}

@Preview(widthDp = 800, name = "Settings and microphone, tablet")
@Composable
private fun SessionSettingsWidePreview() = SessionSettingsAndMicrophone()

@Preview(widthDp = 360, name = "Settings and microphone, phone")
@Composable
private fun SessionSettingsNarrowPreview() = SessionSettingsAndMicrophone()

@Preview(widthDp = 800, name = "Settings of a Vocal-Free Track")
@Composable
private fun SessionSettingsVocalFreePreview() {
    MeaVoxTheme {
        Column(Modifier.padding(16.dp)) {
            val content = previewContent(vocalFree = true)
            SessionSettings(checkNotNull(content.track), content, SessionActions())
        }
    }
}
