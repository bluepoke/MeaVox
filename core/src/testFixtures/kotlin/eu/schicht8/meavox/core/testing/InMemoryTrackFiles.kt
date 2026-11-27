// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.library.StagedFile
import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.model.AppDirectory
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.Stems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.security.MessageDigest

/**
 * [TrackFiles] with synthetic paths and in-memory content; records staging, commits and deletes.
 * File sizes are only what [store] set.
 */
class InMemoryTrackFiles : TrackFiles {
    private val sizes = mutableMapOf<String, Long>()

    /** Pretends [file] exists with [bytes]. */
    fun store(file: AudioFile, bytes: Long) {
        sizes[file.path] = bytes
    }

    /** Pretends [directory] holds [bytes]. */
    fun store(directory: AppDirectory, bytes: Long) {
        sizes[directory.path + "/"] = bytes
    }

    override suspend fun sizeOf(file: AudioFile): Long = sizes[file.path] ?: 0

    override suspend fun sizeOf(directory: AppDirectory): Long = sizes[directory.path + "/"] ?: 0

    /** Stems removed through [deleteStems]. */
    val deletedStems = mutableListOf<Pair<TrackId, SeparationModelId>>()

    override suspend fun deleteStems(track: TrackId, model: SeparationModelId) {
        val stems = stems(track, model)
        sizes.keys.removeAll { it == stems.instrumental.path || it == stems.vocalStem.path || it == separationCheckpoint(track, model).path + "/" }
        deletedStems += track to model
    }

    private var nextStage = 0
    private val staged = mutableMapOf<AudioFile, ByteArray>()
    private val originals = mutableMapOf<TrackId, ByteArray>()
    val deleted = mutableListOf<TrackId>()

    /** Staged files that were neither committed nor discarded. */
    val pendingStaged: Set<AudioFile> get() = staged.keys

    fun originalAudioContent(track: TrackId): ByteArray? = originals[track]

    override fun originalAudio(track: TrackId) = AudioFile("/mem/${track.key}/original")

    override fun stems(track: TrackId, model: SeparationModelId) = Stems(
        instrumental = AudioFile("/mem/${track.key}/${model.value}/instrumental"),
        vocalStem = AudioFile("/mem/${track.key}/${model.value}/vocals"),
    )

    override fun separationCheckpoint(track: TrackId, model: SeparationModelId) =
        AppDirectory("/mem/${track.key}/${model.value}/checkpoint")

    private val covers = mutableMapOf<TrackId, CoverArt>()
    private val coverVersions = mutableMapOf<TrackId, Long>()
    private var nextCoverVersion = 1L
    private val changes = MutableStateFlow(0L)

    override suspend fun storeCover(track: TrackId, cover: CoverArt) {
        covers[track] = cover
        coverVersions[track] = nextCoverVersion++
        changes.update { it + 1 }
    }

    override suspend fun cover(track: TrackId): CoverArt? = covers[track]

    override suspend fun coverVersion(track: TrackId): Long? = coverVersions[track]

    override val coversChanged: StateFlow<Long> = changes

    override fun take(track: TrackId, take: TakeId) = AudioFile("/mem/${track.key}/takes/${take.value}")

    /** Take files removed through [deleteTake]. */
    val deletedTakes = mutableListOf<AudioFile>()

    override suspend fun deleteTake(track: TrackId, take: TakeId) {
        sizes.remove(take(track, take).path)
        deletedTakes += take(track, take)
    }

    override suspend fun stage(content: InputStream): StagedFile {
        val bytes = content.readBytes()
        val file = AudioFile("/mem/staging/${nextStage++}")
        staged[file] = bytes
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return StagedFile(file, sha256)
    }

    private val parts = mutableMapOf<AudioFile, ByteArray>()

    override fun downloadPart(track: TrackId, audioKey: String) = AudioFile("/mem/${track.key}/download-$audioKey")

    /** What a download stored so far in [part]. */
    fun partContent(part: AudioFile): ByteArray = parts[part] ?: ByteArray(0)

    /** Adds [bytes] to the end of [part], as a resumed download does. */
    fun appendToPart(part: AudioFile, bytes: ByteArray) {
        parts[part] = partContent(part) + bytes
    }

    override suspend fun discardOtherDownloads(track: TrackId, keep: AudioFile) {
        parts.keys.removeAll { it != keep && it.path.startsWith("/mem/${track.key}/download-") }
    }

    override suspend fun commitDownload(track: TrackId, part: AudioFile) {
        originals[track] = checkNotNull(parts.remove(part)) { "no download in $part" }
    }

    override suspend fun commitOriginalAudio(staged: StagedFile, track: TrackId) {
        originals[track] = checkNotNull(this.staged.remove(staged.file)) { "not staged: ${staged.file}" }
    }

    override suspend fun discard(staged: StagedFile) {
        this.staged.remove(staged.file)
    }

    override suspend fun discardAbandonedStaging() {
        staged.clear()
    }

    override suspend fun deleteAllExcept(keep: Set<TrackId>) {
        val kept = keep.map { "/mem/${it.key}/" }
        covers.keys.retainAll(keep)
        coverVersions.keys.retainAll(keep)
        changes.update { it + 1 }
        sizes.keys.removeAll { path -> path.startsWith("/mem/") && !path.startsWith("/mem/staging/") && kept.none(path::startsWith) }
    }

    override suspend fun deleteAll(track: TrackId) {
        originals.remove(track)
        covers.remove(track)
        coverVersions.remove(track)
        changes.update { it + 1 }
        sizes.keys.removeAll { it.startsWith("/mem/${track.key}/") }
        deleted += track
    }
}
