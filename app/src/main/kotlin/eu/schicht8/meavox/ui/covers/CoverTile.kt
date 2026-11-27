// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.covers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.covers.CoverImages

/**
 * A Track's Cover Art as a square of [size] (#49), or a placeholder of the same size while it has none
 * ([version] null) or it isn't decoded yet; [images] null shows the placeholder only (previews).
 */
@Composable
fun CoverTile(track: TrackId, version: Long?, images: CoverImages?, size: Dp, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState(version?.let { images?.cached(track, it, sizePx) }, track, version, sizePx) {
        value = if (version == null || images == null) null else images.load(track, version, sizePx)
    }
    val shape = MaterialTheme.shapes.small
    val noCover = stringResource(R.string.library_no_cover)
    val loaded = image
    if (loaded != null) {
        Image(
            loaded,
            contentDescription = stringResource(R.string.library_cover),
            modifier = modifier.size(size).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = noCover },
            contentAlignment = Alignment.Center,
        ) {
            Text("♪", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
