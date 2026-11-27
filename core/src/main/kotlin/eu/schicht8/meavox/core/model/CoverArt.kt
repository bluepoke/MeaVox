// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.model

/** A Track's Cover Art as a JPEG picture (ADR 8, #42), compared by its bytes. */
class CoverArt(jpeg: ByteArray) {
    private val bytes = jpeg.copyOf()

    /** The JPEG file's bytes, a copy each time. */
    val jpeg: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is CoverArt && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "CoverArt(${bytes.size} bytes)"
}
