// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.pipeline.NotificationPrompt
import kotlinx.coroutines.launch

/**
 * Explains and asks for the notification permission once, when the first Track starts Separating
 * (#27), so its progress notification shows (ADR 10). Android 13+ only; earlier versions need no
 * permission. Declining changes nothing: Separation runs the same and the Library shows its progress.
 */
@Composable
fun NotificationPermissionPrompt(prompt: NotificationPrompt) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val due by remember(prompt) { prompt.due() }.collectAsStateWithLifecycle(initialValue = false)
    val markAsked = { scope.launch { prompt.markAsked() }; Unit }
    // While the system prompt shows, the explanation is hidden. Only its answer, whatever it is,
    // counts as asked: if the prompt never answers (e.g. the process dies), the app asks again.
    var requesting by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requesting = false
        markAsked()
    }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!due || granted || requesting) return

    AlertDialog(
        // Declining the explanation is an answer too: never asked again (#27).
        onDismissRequest = markAsked,
        title = { Text(stringResource(R.string.notifications_title)) },
        text = {
            Text(
                stringResource(R.string.notifications_text),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                requesting = true
                request.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(stringResource(R.string.notifications_allow)) }
        },
        dismissButton = { TextButton(onClick = markAsked) { Text(stringResource(R.string.notifications_not_now)) } },
    )
}
