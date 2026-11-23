// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui

import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.export.BackingExportUnavailable
import eu.schicht8.meavox.core.mix.MixUnavailable
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason

/**
 * A [FailureReason] as the user reads it (#43): its kind worded in their language, naming the
 * service where there is one, and the error's own message after it where that adds something.
 */
fun FailureReason.text(): UiText {
    val serviceName: Any = service ?: UiText.of(R.string.failure_service_fallback)
    val sentence = when (kind) {
        FailureKind.SourceNotInApp -> return UiText.of(R.string.failure_source_not_in_app, detail.orEmpty())
        FailureKind.NoDownloadableAudio -> UiText.of(R.string.failure_no_downloadable_audio, serviceName)
        FailureKind.TrackUnavailable -> UiText.of(R.string.failure_track_unavailable, serviceName)
        FailureKind.CaptchaRequired -> UiText.of(R.string.failure_captcha_required, serviceName)
        FailureKind.ServiceUnreachable -> UiText.of(R.string.failure_service_unreachable, serviceName)
        FailureKind.ServiceChanged -> UiText.of(R.string.failure_service_changed, serviceName)
        FailureKind.NoData -> UiText.of(R.string.failure_no_data)
        FailureKind.LinkExpired -> UiText.of(R.string.failure_link_expired)
        // The detail is the HTTP status, part of the sentence.
        FailureKind.ServerError -> return service?.let { UiText.of(R.string.failure_service_error, it, detail.orEmpty()) }
            ?: UiText.of(R.string.failure_server_error, detail.orEmpty())
        FailureKind.ServerBusy -> return service?.let { UiText.of(R.string.failure_service_busy, it, detail.orEmpty()) }
            ?: UiText.of(R.string.failure_server_busy, detail.orEmpty())
        FailureKind.ConnectionBroke -> UiText.of(R.string.failure_connection_broke)
        FailureKind.DownloadIncomplete -> UiText.of(R.string.failure_download_incomplete)
        FailureKind.UnknownModel -> return UiText.of(R.string.failure_unknown_model, detail.orEmpty())
        FailureKind.ModelCorrupt -> UiText.of(R.string.failure_model_corrupt)
        FailureKind.ModelDownloadFailed -> UiText.of(R.string.failure_model_download_failed)
        FailureKind.AudioUndecodable -> UiText.of(R.string.failure_audio_undecodable)
        FailureKind.StemsNotStored -> UiText.of(R.string.failure_stems_not_stored)
        FailureKind.OutOfMemory -> UiText.of(R.string.failure_out_of_memory)
        FailureKind.AnswerUnreadable -> UiText.of(R.string.failure_answer_unreadable, serviceName)
        // Nobody foresaw it: a general sentence, and its own message says the rest.
        FailureKind.Unexpected -> UiText.of(R.string.failure_unexpected)
    }
    return detail?.takeIf { it.isNotBlank() }?.let { UiText.of(R.string.failure_with_detail, sentence, it) } ?: sentence
}

/** Why a Mix can't be played or exported, as the user reads it. */
fun MixUnavailable.text(): UiText = UiText.of(
    when (why) {
        MixUnavailable.Why.MixDeleted -> R.string.unavailable_mix_deleted
        MixUnavailable.Why.TrackDeleted -> R.string.unavailable_track_deleted
        MixUnavailable.Why.TakeDeleted -> R.string.unavailable_take_deleted
        MixUnavailable.Why.InstrumentalNotReady -> R.string.unavailable_instrumental_not_ready
    },
)

/** Why a Backing Export can't be made, as the user reads it. */
fun BackingExportUnavailable.text(): UiText = UiText.of(
    when (why) {
        BackingExportUnavailable.Why.TrackDeleted -> R.string.unavailable_track_deleted
        BackingExportUnavailable.Why.InstrumentalNotReady -> R.string.unavailable_instrumental_not_ready
        BackingExportUnavailable.Why.NotDownloaded -> R.string.unavailable_not_downloaded
    },
)

/** An error nobody foresaw: a general sentence with its own message. */
fun Throwable.text(): UiText = FailureReason.unexpected(this).text()
