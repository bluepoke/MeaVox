// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import java.io.InputStream

/**
 * A file the user picked or shared into the app (GLOSSARY: Local File Source). Unlike a
 * [Source] it is not searched: its content is imported, and its identity is the content hash.
 */
interface LocalFile {
    /** The name the file had where it came from, e.g. `My Way.mp3`, if known. */
    val displayName: String?

    /** Opens the content for reading. The caller closes the stream. */
    fun open(): InputStream
}

/** The title and artist an audio or video file carries in its own tags, if any (#52). */
data class FileTags(val title: String?, val artist: String?)
