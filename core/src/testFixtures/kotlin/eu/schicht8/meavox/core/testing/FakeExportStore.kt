// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportStore
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportWriter
import eu.schicht8.meavox.core.port.ExportedFile

/** Keeps Exports in memory: what was written to each, and whether it was committed or aborted. */
class FakeExportStore : ExportStore {
    class Written(val tags: ExportTags, val format: ExportFormat, val sampleRate: Int, val frames: Long?) {
        val title: String get() = tags.title
        var framesWritten = 0L
        var committed = false
        var aborted = false
    }

    val exports = mutableListOf<Written>()

    override suspend fun create(tags: ExportTags, format: ExportFormat, sampleRate: Int, frames: Long?): ExportWriter {
        val written = Written(tags, format, sampleRate, frames).also { exports += it }
        return object : ExportWriter {
            override fun write(interleaved: FloatArray, frames: Int) {
                check(!written.committed && !written.aborted) { "already finished" }
                written.framesWritten += frames
            }

            override suspend fun commit(): ExportedFile {
                check(written.frames == null || written.framesWritten == written.frames) { "${written.framesWritten} of ${written.frames} frames" }
                written.committed = true
                val (extension, mimeType) = when (format) {
                    is ExportFormat.Wav -> "wav" to "audio/x-wav"
                    is ExportFormat.Mp3 -> "mp3" to "audio/mpeg"
                    ExportFormat.M4a -> "m4a" to "audio/mp4"
                }
                return ExportedFile("content://exports/${exports.indexOf(written)}", "${tags.title}.$extension", mimeType)
            }

            override suspend fun abort() {
                written.aborted = true
            }
        }
    }
}
