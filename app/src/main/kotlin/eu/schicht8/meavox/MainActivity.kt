// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.importing.Importer
import eu.schicht8.meavox.importing.LinkImporter
import eu.schicht8.meavox.importing.PendingImport
import eu.schicht8.meavox.importing.UriLocalFile
import eu.schicht8.meavox.separation.SeparationCatalogue
import eu.schicht8.meavox.ui.NotificationPermissionPrompt
import eu.schicht8.meavox.ui.library.LibraryRoute
import eu.schicht8.meavox.ui.licenses.LicensesRoute
import eu.schicht8.meavox.ui.lyrics.LyricsSearchRoute
import eu.schicht8.meavox.ui.models.ImportSheet
import eu.schicht8.meavox.ui.resolve
import eu.schicht8.meavox.ui.search.SearchRoute
import eu.schicht8.meavox.ui.session.SessionRoute
import eu.schicht8.meavox.ui.settings.SettingsRoute
import eu.schicht8.meavox.ui.splash.SplashAnimation
import eu.schicht8.meavox.ui.storage.StorageRoute
import eu.schicht8.meavox.ui.theme.MeaVoxTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as MeaVoxApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark (MeaVoxTheme): light system-bar icons whatever the system setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val showSplash = savedInstanceState == null && intent?.action != Intent.ACTION_SEND
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MeaVoxTheme {
                val nav = rememberNavController()
                LaunchedEffect(Unit) {
                    container.linkImporter.results.collect { result ->
                        when (result) {
                            is LinkImporter.Result.Found -> container.pendingImport.value = PendingImport.Link(result.candidate)
                            is LinkImporter.Result.Added -> nav.openTrack(result.id)
                            LinkImporter.Result.Unsupported ->
                                Toast.makeText(this@MainActivity, R.string.link_unsupported, Toast.LENGTH_LONG).show()
                            is LinkImporter.Result.Failed ->
                                Toast.makeText(this@MainActivity, getString(R.string.link_failed, result.reason.resolve(resources)), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    container.importer.results.collect { result ->
                        when (result) {
                            // Opens the new Track, or the existing one for content imported before.
                            is Importer.Result.Imported -> nav.openTrack(result.id)
                            is Importer.Result.Failed ->
                                Toast.makeText(this@MainActivity, getString(R.string.import_failed, result.reason.resolve(resources)), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                PendingImportSheet()
                NotificationPermissionPrompt(container.notificationPrompt)
                NavHost(nav, startDestination = LIBRARY) {
                    composable(LIBRARY) {
                        LibraryRoute(
                            container.library, container.renameTrack, container.importer,
                            onPicked = ::askToImport,
                            onOpen = { nav.openTrack(it) },
                            onStorage = { nav.navigate(STORAGE) },
                            onSettings = { nav.navigate(SETTINGS) },
                            onSearch = if (container.sources.isEmpty()) null else ({ nav.navigate(SEARCH) }),
                        )
                    }
                    composable(SETTINGS) {
                        SettingsRoute(container.modelChoices, container.settingsRepository, onLicenses = { nav.navigate(LICENSES) })
                    }
                    composable(LICENSES) {
                        LicensesRoute(modelName = container.modelChoices::nameOf)
                    }
                    composable(STORAGE) {
                        StorageRoute(container.trackStorage, SeparationCatalogue.models.associate { it.id to it.name })
                    }
                    composable(SEARCH) {
                        SearchRoute(container.sources, container.library, container.modelChoices, onAdded = { nav.openTrack(it) })
                    }
                    composable(
                        TRACK,
                        arguments = listOf(
                            navArgument("source") { type = NavType.StringType },
                            navArgument("id") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val args = checkNotNull(entry.arguments)
                        val id = TrackId(SourceId(checkNotNull(args.getString("source"))), checkNotNull(args.getString("id")))
                        // ViewModels here are scoped to this back-stack entry and cleared on Back.
                        SessionRoute(
                            id, container.trackRepository, container.sessions, container.trackPipeline,
                            container.lyricsRepository, container.matchLyrics, container.lyricsOffset, container.vocalFreeToggle,
                            container.modelChoices, container.mixes, container.exportMix, container.exportBacking,
                            onChooseLyrics = { nav.navigate(route(LYRICS, id)) },
                        )
                    }
                    composable(
                        LYRICS,
                        arguments = listOf(navArgument("source") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val args = checkNotNull(entry.arguments)
                        val id = TrackId(SourceId(checkNotNull(args.getString("source"))), checkNotNull(args.getString("id")))
                        LyricsSearchRoute(id, container.matchLyrics, onChosen = { nav.popBackStack() })
                    }
                }
                // Cold start only, not for a song shared into MeaVox: the waveform that curls into
                // the wordmark, over the library while it loads. A tap skips it.
                var splash by rememberSaveable { mutableStateOf(showSplash) }
                AnimatedVisibility(splash, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
                    SplashAnimation(onFinished = { splash = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A file or link shared into MeaVox from another app (Share → MeaVox). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Reopening from recents replays the original intent; its URI grant may be gone.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) return askToImport(uri)
        // A link (network flavor only receives text): a video page shared from a browser or app.
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(container.linkImporter::lookUp)
    }

    /** A picked or shared file: the import sheet asks how to add it (ADR 19). */
    private fun askToImport(uri: Uri) {
        val name = UriLocalFile(contentResolver, uri).displayName ?: "Untitled"
        container.pendingImport.value = PendingImport.File(uri, name)
    }

    /** The import sheet for a picked or shared song, over whatever screen is showing. */
    @Composable
    private fun PendingImportSheet() {
        val pending by container.pendingImport.collectAsStateWithLifecycle()
        val dismiss = { container.pendingImport.value = null }
        when (val import = pending) {
            null -> Unit
            is PendingImport.File -> ImportSheet(import, import.name, null, null, container.modelChoices, onDismiss = dismiss, onAdd = { vocalFree, model ->
                dismiss()
                container.importer.import(import.uri, vocalFree, model)
            })
            is PendingImport.Link -> ImportSheet(
                import,
                import.candidate.title,
                import.candidate.artist,
                import.candidate.durationMs,
                container.modelChoices,
                onDismiss = dismiss,
                onAdd = { vocalFree, model ->
                    dismiss()
                    container.linkImporter.add(import.candidate, vocalFree, model)
                },
            )
        }
    }

    /** Shows a Track's screen with its Session, replacing any Track screen already open (one Track at a time). */
    private fun NavHostController.openTrack(id: TrackId) {
        navigate(route(TRACK, id)) {
            popUpTo(LIBRARY)
        }
    }

    private companion object {
        const val LIBRARY = "library"
        const val SEARCH = "search"
        const val STORAGE = "storage"
        const val SETTINGS = "settings"
        const val LICENSES = "licenses"
        const val TRACK = "track/{source}/{id}"
        const val LYRICS = "lyrics/{source}/{id}"

        /** [pattern] (e.g. [LYRICS]) filled in for [id]. */
        fun route(pattern: String, id: TrackId) =
            pattern.replace("{source}", Uri.encode(id.source.value)).replace("{id}", Uri.encode(id.sourceTrackId))
    }
}
