// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.library.StagedFile
import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.model.AppDirectory
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.Stems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * [TrackFiles] under an app-private [root] (e.g. `filesDir/tracks`). Each Track gets one
 * directory named by a hash of its identity, so source IDs never need escaping. Staging files
 * live in the same file system, so committing is an atomic rename.
 */
class AppTrackFiles(private val root: File) : TrackFiles {
    private val stagingDir = File(root, ".staging")

    override fun originalAudio(track: TrackId): AudioFile = AudioFile(File(dirOf(track), "original").path)

    // Model IDs are catalogue names (e.g. "fast"); hashed like Track keys so they need no escaping.
    override fun stems(track: TrackId, model: SeparationModelId): Stems {
        val dir = File(dirOf(track), "stems/${hash(model.value)}")
        return Stems(AudioFile(File(dir, "instrumental.flac").path), AudioFile(File(dir, "vocals.flac").path))
    }

    override fun separationCheckpoint(track: TrackId, model: SeparationModelId): AppDirectory =
        AppDirectory(File(dirOf(track), "stems/${hash(model.value)}.checkpoint").path)

    // In the Track's directory, so it goes with the Track.
    private fun coverOf(track: TrackId): File = File(dirOf(track), "cover.jpg")

    override suspend fun storeCover(track: TrackId, cover: CoverArt) = withContext(Dispatchers.IO) {
        val target = coverOf(track)
        val previous = target.lastModified() // 0 without one
        target.parentFile?.mkdirs()
        // Written beside it first, so a cover is never read half-written.
        val part = File(target.path + ".part")
        part.writeBytes(cover.jpeg)
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("could not move $part to $target")
        }
        // Its time of change is its version (see coverVersion): strictly later than the cover it replaces,
        // also when both are stored within the same millisecond. Should the file system refuse, the time
        // the file was written stays, which tells covers apart unless two land in one millisecond.
        target.setLastModified(maxOf(System.currentTimeMillis(), previous + 1))
        changes.update { it + 1 }
        Unit
    }

    override suspend fun deleteCover(track: TrackId) {
        withContext(Dispatchers.IO) { coverOf(track).delete() }
        changes.update { it + 1 }
    }

    override suspend fun cover(track: TrackId): CoverArt? = withContext(Dispatchers.IO) {
        coverOf(track).takeIf { it.isFile }?.let { CoverArt(it.readBytes()) }
    }

    // Each stored cover gets a later time of change than the one before (see storeCover).
    override suspend fun coverVersion(track: TrackId): Long? = withContext(Dispatchers.IO) {
        coverOf(track).takeIf { it.isFile }?.lastModified()
    }

    private val changes = MutableStateFlow(0L)

    override val coversChanged: StateFlow<Long> = changes

    override fun take(track: TrackId, take: TakeId): AudioFile =
        AudioFile(File(dirOf(track), "takes/${hash(take.value)}.flac").path)

    override suspend fun deleteStems(track: TrackId, model: SeparationModelId) {
        withContext(Dispatchers.IO) {
            val stems = stems(track, model)
            File(stems.instrumental.path).parentFile?.deleteRecursively()
            File(separationCheckpoint(track, model).path).deleteRecursively()
        }
    }

    override suspend fun sizeOf(file: AudioFile): Long = withContext(Dispatchers.IO) { File(file.path).length() }

    override suspend fun sizeOf(directory: AppDirectory): Long = withContext(Dispatchers.IO) {
        File(directory.path).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    override suspend fun deleteTake(track: TrackId, take: TakeId) {
        withContext(Dispatchers.IO) { File(take(track, take).path).delete() }
    }

    override suspend fun stage(content: InputStream): StagedFile = withContext(Dispatchers.IO) {
        stagingDir.mkdirs()
        val file = File(stagingDir, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(file.outputStream().buffered(), digest).use { out -> content.copyTo(out) }
        } catch (e: Throwable) { // includes cancellation
            file.delete()
            throw e
        }
        StagedFile(AudioFile(file.path), digest.digest().toHex())
    }

    override fun downloadPart(track: TrackId, audioKey: String): AudioFile =
        AudioFile(File(dirOf(track), "download-${hash(audioKey)}.part").path)

    override suspend fun discardOtherDownloads(track: TrackId, keep: AudioFile) {
        withContext(Dispatchers.IO) {
            dirOf(track).listFiles { f -> f.name.startsWith("download-") && f.path != keep.path }?.forEach { it.delete() }
        }
    }

    override suspend fun commitDownload(track: TrackId, part: AudioFile) = withContext(Dispatchers.IO) {
        val target = File(originalAudio(track).path)
        target.parentFile?.mkdirs()
        if (!File(part.path).renameTo(target)) throw IOException("could not move ${part.path} to $target")
    }

    override suspend fun commitOriginalAudio(staged: StagedFile, track: TrackId) = withContext(Dispatchers.IO) {
        val target = File(originalAudio(track).path)
        target.parentFile?.mkdirs()
        if (!File(staged.file.path).renameTo(target)) throw IOException("could not move ${staged.file.path} to $target")
    }

    override suspend fun discard(staged: StagedFile) {
        withContext(Dispatchers.IO) { File(staged.file.path).delete() }
    }

    override suspend fun discardAbandonedStaging() {
        withContext(Dispatchers.IO) { stagingDir.listFiles()?.forEach { it.delete() } }
    }

    override suspend fun deleteAll(track: TrackId) {
        withContext(Dispatchers.IO) { dirOf(track).deleteRecursively() }
        changes.update { it + 1 }
    }

    override suspend fun deleteAllExcept(keep: Set<TrackId>) {
        withContext(Dispatchers.IO) {
            val kept = keep.mapTo(HashSet()) { dirOf(it).name }
            // Hidden directories (staging) are not Tracks.
            root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name !in kept }?.forEach { it.deleteRecursively() }
        }
        changes.update { it + 1 }
    }

    private fun dirOf(track: TrackId): File = File(root, hash(track.key))

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(16).toByteArray().toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
