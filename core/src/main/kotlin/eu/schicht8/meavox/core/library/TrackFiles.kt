// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.model.AppDirectory
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.Stems
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

/** Where a Track's audio files live in app-private storage (ADR 8). */
interface TrackFiles {
    /** Location for the Track's Original Audio, kept exactly as downloaded or imported. */
    fun originalAudio(track: TrackId): AudioFile

    /** Location for the Instrumental and Vocal Stem that Separation with [model] produced. */
    fun stems(track: TrackId, model: SeparationModelId): Stems

    /** Where a running Separation of [track] with [model] keeps its work so far (ADR 15). */
    fun separationCheckpoint(track: TrackId, model: SeparationModelId): AppDirectory

    /** Stores [cover] as the Track's Cover Art (#42), replacing any it had. Deleted with the Track. */
    suspend fun storeCover(track: TrackId, cover: CoverArt)

    /** The Track's Cover Art, or null if it has none. */
    suspend fun cover(track: TrackId): CoverArt?

    /**
     * Which picture the Track's Cover Art is, so that a new one can be told from the old one without
     * reading it (#49): it changes whenever a cover is stored; null while the Track has none.
     */
    suspend fun coverVersion(track: TrackId): Long?

    /**
     * Counts the covers stored, and those removed with their Track, since the app started (#49): it
     * changes after each, and a new collector gets its current value at once, so no change is missed.
     */
    val coversChanged: StateFlow<Long>

    /** Location for a kept Take's audio (mono FLAC, ADR 8). */
    fun take(track: TrackId, take: TakeId): AudioFile

    /**
     * Deletes the Instrumental and Vocal Stem of [model], and any Separation checkpoint for it.
     * They can be made again from the Original Audio (ADR 8, ADR 17).
     */
    suspend fun deleteStems(track: TrackId, model: SeparationModelId)

    /** Bytes [file] takes up; 0 if it doesn't exist. */
    suspend fun sizeOf(file: AudioFile): Long

    /** Bytes everything in [directory] takes up; 0 if it doesn't exist. */
    suspend fun sizeOf(directory: AppDirectory): Long

    /** Deletes a Take's audio file, if it exists. */
    suspend fun deleteTake(track: TrackId, take: TakeId)

    /**
     * Copies [content] into a staging file and hashes it (SHA-256) on the way, because a Local
     * File's identity is only known once its content has been read (ADR 10).
     */
    suspend fun stage(content: InputStream): StagedFile

    /**
     * Where the download of [track]'s audio stream [audioKey] collects its bytes until complete.
     * The same key always gives the same file, so an interrupted download continues there.
     */
    fun downloadPart(track: TrackId, audioKey: String): AudioFile

    /** Deletes [track]'s download parts other than [keep] (left by a stream the Source no longer offers). */
    suspend fun discardOtherDownloads(track: TrackId, keep: AudioFile)

    /** Moves a complete download into place as the Original Audio of [track]. */
    suspend fun commitDownload(track: TrackId, part: AudioFile)

    /** Moves a staged file into place as the Original Audio of [track]. */
    suspend fun commitOriginalAudio(staged: StagedFile, track: TrackId)

    /** Deletes a staged file that is not needed (e.g. the content was already imported). */
    suspend fun discard(staged: StagedFile)

    /** Deletes staged files left behind by imports that never finished (e.g. process death). */
    suspend fun discardAbandonedStaging()

    /** Removes every file the Track owns. */
    suspend fun deleteAll(track: TrackId)

    /** Removes the files of every Track not in [keep]. */
    suspend fun deleteAllExcept(keep: Set<TrackId>)
}

/** Bytes the Instrumental and Vocal Stem of [model] take up; 0 if there are none. */
suspend fun TrackFiles.sizeOfStems(track: TrackId, model: SeparationModelId): Long {
    val stems = stems(track, model)
    return sizeOf(stems.instrumental) + sizeOf(stems.vocalStem)
}

/** A copied file awaiting commit, with the lowercase hex SHA-256 of its content. */
data class StagedFile(val file: AudioFile, val sha256: String)
