// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

/** Where Exports go: `Music/MeaVox/`, from where they can be shared (ADR 8). */
interface ExportStore {
    /**
     * Starts a new file named after [tags]' title that will hold stereo frames at [sampleRate] in
     * [format]: exactly [frames] of them, or as many as are written when that isn't known up front.
     * It stays hidden from other apps until [ExportWriter.commit].
     */
    suspend fun create(tags: ExportTags, format: ExportFormat, sampleRate: Int, frames: Long?): ExportWriter
}

/** What an Export is tagged with, and named after. Cover art comes with #42. */
data class ExportTags(val title: String, val artist: String? = null)

/** One Export being written: the rendered audio, then [commit] or [abort]. */
interface ExportWriter : PcmSink {
    /** Completes the file and makes it visible; returns where it is. */
    suspend fun commit(): ExportedFile

    /** Removes the unfinished file. */
    suspend fun abort()
}

/** A finished Export: a content URI other apps (e.g. the share sheet) can open, its file name and its MIME type. */
data class ExportedFile(val uri: String, val displayName: String, val mimeType: String)

/** The encoding of an Export (ADR 8). */
sealed interface ExportFormat {
    /** PCM WAV, 16-bit or 24-bit. */
    data class Wav(val bitsPerSample: Int = 16) : ExportFormat {
        init {
            require(bitsPerSample == 16 || bitsPerSample == 24) { "WAV is exported at 16 or 24 bits, not $bitsPerSample" }
        }
    }

    /** MP3 through LAME (ADR 8), VBR V2 by default, tagged with ID3. */
    data class Mp3(val bitrate: Bitrate = Bitrate.VbrV2) : ExportFormat {
        enum class Bitrate {
            /** Variable bit rate at LAME quality V2, about 190 kbps. */
            VbrV2,

            /** Constant 320 kbps, the most MP3 holds. */
            Cbr320,
        }
    }

    /** AAC-LC in an M4A (MP4) file, through the platform's encoder (ADR 8), with MP4 metadata tags. */
    data object M4a : ExportFormat
}
