// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * Text for the UI that is worded only when it is shown, in the user's language (#43): what view
 * models and workers hold instead of finished strings.
 */
sealed interface UiText {
    /** A string resource; [args] may be [UiText] themselves. */
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Text that isn't translated, e.g. a server's or a library's own message. */
    data class Verbatim(val text: String) : UiText

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Resource(id, args.toList())
    }
}

fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Verbatim -> text
    is UiText.Resource -> resources.getString(id, *args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray())
}

/** Worded again when the configuration (and so the language) changes. */
@Composable
fun UiText.resolve(): String = resolve(LocalResources.current)
