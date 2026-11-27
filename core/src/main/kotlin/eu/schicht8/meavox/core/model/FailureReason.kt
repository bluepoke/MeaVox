// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.model

/**
 * Why a step of the Track Pipeline or a Lyrics search failed, or waits to try again. The domain core
 * carries no display text (#43): the UI words [kind] in the user's language. [service] names the
 * Source or Lyrics provider involved ("YouTube", "LRCLIB"), and [detail] is shown as it is: a
 * server's status code, a library's own error message.
 */
data class FailureReason(val kind: FailureKind, val detail: String? = null, val service: String? = null) {
    companion object {
        /** An error nobody foresaw: its own message, or its type when it has none. */
        fun unexpected(error: Throwable): FailureReason =
            FailureReason(FailureKind.Unexpected, error.message ?: error::class.simpleName)
    }
}

/** The kinds of [FailureReason] the UI has words for. */
enum class FailureKind {
    // Downloading the Original Audio from a Source.

    /** The Track's Source isn't in this app (e.g. the local-only flavor); [FailureReason.detail] is its ID. */
    SourceNotInApp,

    /** The Source offers no audio stream that can be downloaded. */
    NoDownloadableAudio,

    /** The Source says the Track isn't available (removed, private, region-locked). */
    TrackUnavailable,

    /** The Source wants a CAPTCHA solved before it answers again. */
    CaptchaRequired,

    /** The Source can't be reached. */
    ServiceUnreachable,

    /** The Source changed so the app can't read it any more; an update may fix it. */
    ServiceChanged,

    /** The server sent no data. */
    NoData,

    /** The download link expired. */
    LinkExpired,

    /** The server answered with an error; [FailureReason.detail] is the HTTP status. */
    ServerError,

    /** The server is busy for now; [FailureReason.detail] is the HTTP status. */
    ServerBusy,

    /** The connection broke off. */
    ConnectionBroke,

    /** The download ended before all of it arrived. */
    DownloadIncomplete,

    // Separation.

    /** The Separation Model isn't in the catalogue; [FailureReason.detail] is its ID. */
    UnknownModel,

    /** The downloaded model file is damaged (e.g. its checksum doesn't match). */
    ModelCorrupt,

    /** The model file could not be downloaded. */
    ModelDownloadFailed,

    /** The Original Audio can't be decoded. */
    AudioUndecodable,

    /** The stems could not be stored. */
    StemsNotStored,

    /** The device ran out of memory. */
    OutOfMemory,

    // Lyrics.

    /** The Lyrics provider (or the cover search, #51) sent an answer that can't be read. */
    AnswerUnreadable,

    /** Anything else; [FailureReason.detail] says what, as the error put it. Also a reason stored before #43. */
    Unexpected,
}
