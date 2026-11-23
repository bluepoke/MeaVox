// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Turns an Export's rendered stereo float frames into one format's file. */
internal interface PcmEncoder {
    /** Appends [frames] frames from [interleaved] (`frames * 2` samples, ±1 full scale). */
    fun write(interleaved: FloatArray, frames: Int)

    /** Writes what is still buffered and completes the file, also where it was written before (headers, tags). */
    fun finish()

    /** Frees what the encoder holds outside the heap; safe after [finish], on its own and twice. */
    fun release() {}
}

/** Writes all of [buffer] at the channel's position; a single write may take only part of it. */
internal fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}
