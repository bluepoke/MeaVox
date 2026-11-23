// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason

/**
 * A [FailureReason] as three columns (version 14, #43): a stable code for its kind, the service, and
 * the detail, which goes in the column that held the whole reason as English text before. A row from
 * before has text there and no code; it reads as [FailureKind.Unexpected] with that text, so it is
 * still shown as it was.
 */
internal data class FailureColumns(val kind: String?, val detail: String?, val service: String?)

internal fun FailureReason?.toColumns(): FailureColumns =
    if (this == null) FailureColumns(null, null, null) else FailureColumns(kind.toCode(), detail, service)

/** The reason stored in the columns; null if there is none. */
internal fun FailureColumns.toReason(): FailureReason? = when {
    kind != null -> FailureReason(kindFromCode(kind), detail, service)
    detail != null -> FailureReason(FailureKind.Unexpected, detail)
    else -> null
}

private fun FailureKind.toCode(): String = when (this) {
    FailureKind.SourceNotInApp -> "source_not_in_app"
    FailureKind.NoDownloadableAudio -> "no_downloadable_audio"
    FailureKind.TrackUnavailable -> "track_unavailable"
    FailureKind.CaptchaRequired -> "captcha_required"
    FailureKind.ServiceUnreachable -> "service_unreachable"
    FailureKind.ServiceChanged -> "service_changed"
    FailureKind.NoData -> "no_data"
    FailureKind.LinkExpired -> "link_expired"
    FailureKind.ServerError -> "server_error"
    FailureKind.ServerBusy -> "server_busy"
    FailureKind.ConnectionBroke -> "connection_broke"
    FailureKind.DownloadIncomplete -> "download_incomplete"
    FailureKind.UnknownModel -> "unknown_model"
    FailureKind.ModelCorrupt -> "model_corrupt"
    FailureKind.ModelDownloadFailed -> "model_download_failed"
    FailureKind.AudioUndecodable -> "audio_undecodable"
    FailureKind.StemsNotStored -> "stems_not_stored"
    FailureKind.OutOfMemory -> "out_of_memory"
    FailureKind.AnswerUnreadable -> "answer_unreadable"
    FailureKind.Unexpected -> "unexpected"
}

/** A code from a later version that this one doesn't know reads as [FailureKind.Unexpected]. */
private fun kindFromCode(code: String): FailureKind = FailureKind.entries.firstOrNull { it.toCode() == code } ?: FailureKind.Unexpected
