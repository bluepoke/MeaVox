// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.splash

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.ui.theme.MeaVoxPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Splash: a sung waveform on an oscilloscope that curls into the MeaVox wordmark.
 *
 * The trace is cut into one piece per letter contour (9 pieces, each as long as its share of the
 * total outline). Every piece morphs point by point from its stretch of the waveform into its
 * contour, left to right, while the voice keeps moving. Then the outline and "Vox" take the
 * metallic gold of the logo and one glint runs across it. About 3.2 s; a tap skips it.
 * Shows nothing and finishes at once when animations are off (Settings › Remove animations).
 *
 * Same algorithm and constants as the web preview (docs/logo/splash-preview.html).
 */
@Composable
fun SplashAnimation(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val finish by rememberUpdatedState(onFinished)
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (animationsOff) return@LaunchedEffect finish()
        val start = withFrameNanos { it }
        while (time < Timeline.END) {
            time = min(Timeline.END, (withFrameNanos { it } - start) / 1e9f)
        }
        finish()
    }
    if (animationsOff) return
    val logo = remember { SplashLogo() }
    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { finish() } },
    ) {
        drawRect(BLACK)
        logo.draw(this, time, maxLogoWidth = 520.dp.toPx())
    }
}

private object Timeline {
    const val ATTACK = 0.35f
    const val LEAD_START = 1.0f
    const val LEAD_DURATION = 0.6f
    const val MORPH_START = 1.15f
    const val MORPH_DURATION = 0.75f
    const val STAGGER = 0.35f
    const val FILL_START = 2.15f
    const val FILL_DURATION = 0.5f
    const val GLINT_START = 2.35f
    const val GLINT_DURATION = 0.65f
    const val END = 3.2f
}

private val BLACK = MeaVoxPalette.Bg
private val GOLD = MeaVoxPalette.Gold
private val GRID = MeaVoxPalette.InkMuted
private val GLINT = Color(0xFFFFFAE6)
/** The metallic gold of docs/logo/meavox-logo.svg. */
private val GOLD_STOPS = arrayOf(
    0f to Color(0xFFF6D77A),
    0.22f to Color(0xFFFFF4CC),
    0.40f to MeaVoxPalette.Gold,
    0.53f to Color(0xFF9C6A12),
    0.60f to MeaVoxPalette.GoldDeep,
    0.80f to Color(0xFFEBC14F),
    1f to Color(0xFFF9E3A0),
)

/** Waveform amplitude, logo units (the caps are 70 tall). */
private const val AMP = 70f
private const val LOGO_WIDTH_FRACTION = 0.82f
private const val VERTICAL_POSITION = 0.46f
private const val TAU = (2 * PI).toFloat()

/** One letter contour, sampled from its leftmost point; [points] is closed (last = first). */
private class Contour(val points: FloatArray, val minX: Float, val maxX: Float, val length: Float) {
    val n get() = points.size / 2 - 1
    var sliceStart = 0f
    var sliceEnd = 0f
    var delay = 0f
}

private class SplashLogo {
    private val meaPath = parse(LogoOutline.mea.joinToString(""))
    private val voxPath = parse(LogoOutline.vox.joinToString(""))
    private val contours = (LogoOutline.mea + LogoOutline.vox)
        .map(::sample)
        .sortedWith(compareBy<Contour> { it.minX }.thenByDescending { it.length })
    private val trace = Path()
    private val goldBrush = Brush.verticalGradient(
        *GOLD_STOPS,
        startY = LogoOutline.TOP - LogoOutline.STROKE / 2,
        endY = LogoOutline.BOTTOM + LogoOutline.STROKE / 2,
    )
    private val centerX = (LogoOutline.LEFT + LogoOutline.RIGHT) / 2
    private val centerY = (LogoOutline.TOP + LogoOutline.BOTTOM) / 2
    private val logoWidth = LogoOutline.RIGHT - LogoOutline.LEFT

    // The waveform's horizontal extent in logo units: the screen edges, set per frame.
    private var traceLeft = 0f
    private var traceRight = 0f

    init {
        // Each contour gets a stretch of the trace in proportion to its outline length.
        val total = contours.sumOf { it.length.toDouble() }.toFloat()
        var x = LogoOutline.LEFT
        for (c in contours) {
            c.sliceStart = x
            x += logoWidth * c.length / total
            c.sliceEnd = x
            c.delay = Timeline.MORPH_START + Timeline.STAGGER * ((c.minX + c.maxX) / 2 - LogoOutline.LEFT) / logoWidth
        }
    }

    fun draw(scope: DrawScope, t: Float, maxLogoWidth: Float) = with(scope) {
        val w = size.width
        val oy = size.height * VERTICAL_POSITION
        val logoPx = min(w * LOGO_WIDTH_FRACTION, maxLogoWidth)
        val s = logoPx / logoWidth
        traceLeft = centerX - (w / 2) / s
        traceRight = centerX + (w / 2) / s

        drawGraticule(t, oy, logoPx / 6.5f)

        withTransform({
            translate(w / 2 - centerX * s, oy - centerY * s)
            scale(s, s, pivot = Offset.Zero)
        }) {
            val stroke = Stroke(LogoOutline.STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val intro = clamp(t / 0.15f)
            val fill = easeOut(clamp((t - Timeline.FILL_START) / Timeline.FILL_DURATION))

            if (fill > 0f) {
                drawPath(meaPath, BLACK, alpha = fill)
                drawPath(meaPath, goldBrush, alpha = fill, style = stroke)
                drawPath(voxPath, goldBrush, alpha = fill)
                drawPath(voxPath, goldBrush, alpha = fill, style = stroke)
            }
            // Phosphor afterglow: two earlier traces, gone once the morph starts.
            val ghost = intro * (1f - smooth(Timeline.MORPH_START - 0.1f, Timeline.MORPH_START + 0.3f, t))
            if (ghost > 0f) {
                for ((dt, a) in GHOSTS) {
                    if (t - dt < 0f) continue
                    buildTrace(t - dt)
                    drawPath(trace, GOLD, alpha = a * ghost, style = stroke)
                }
            }
            if (fill < 1f) {
                buildTrace(t)
                drawPath(trace, GOLD, alpha = 0.22f * intro * (1f - fill), style = Stroke(LogoOutline.STROKE * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(trace, GOLD, alpha = intro * (1f - fill), style = stroke)
            }
            drawGlint(t, stroke)
        }
    }

    private fun DrawScope.drawGraticule(t: Float, oy: Float, div: Float) {
        val fade = 1f - smooth(1.0f, 1.9f, t)
        if (fade <= 0f) return
        val cx = size.width / 2
        var k = -8
        while (k <= 8) {
            val x = cx + k * div
            if (x > 0f && x < size.width) {
                drawLine(GRID, Offset(x, oy - 3 * div), Offset(x, oy + 3 * div), strokeWidth = 1f, alpha = 0.07f * fade)
            }
            k++
        }
        for (row in -3..3) {
            val y = oy + row * div
            drawLine(GRID, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, alpha = (if (row == 0) 0.16f else 0.07f) * fade)
        }
    }

    private fun DrawScope.drawGlint(t: Float, stroke: Stroke) {
        val g = (t - Timeline.GLINT_START) / Timeline.GLINT_DURATION
        if (g <= 0f || g >= 1f) return
        val bx = LogoOutline.LEFT - 60f + (logoWidth + 120f) * easeInOut(g)
        val brush = Brush.linearGradient(
            0f to GLINT.copy(alpha = 0f),
            0.5f to GLINT.copy(alpha = 0.85f),
            1f to GLINT.copy(alpha = 0f),
            start = Offset(bx - 22f, LogoOutline.BOTTOM + 10f),
            end = Offset(bx + 22f, LogoOutline.TOP - 10f),
        )
        drawPath(meaPath, brush, style = stroke)
        drawPath(voxPath, brush)
        drawPath(voxPath, brush, style = stroke)
    }

    /** The trace at time [t]: two leads outside the logo, then one piece per contour. */
    private fun buildTrace(t: Float) {
        trace.rewind()
        val r = easeInOut(clamp((t - Timeline.LEAD_START) / Timeline.LEAD_DURATION))
        addLead(traceLeft + (LogoOutline.LEFT - traceLeft) * r, LogoOutline.LEFT, t)
        addLead(LogoOutline.RIGHT, traceRight - (traceRight - LogoOutline.RIGHT) * r, t)
        for (c in contours) {
            val e = easeInOut(clamp((t - c.delay) / Timeline.MORPH_DURATION))
            val n = c.n
            for (i in 0..n) {
                val sx = c.sliceStart + (c.sliceEnd - c.sliceStart) * i / n
                val x = sx + (c.points[2 * i] - sx) * e
                val y = if (e < 1f) waveY(sx, t) * (1f - e) + c.points[2 * i + 1] * e else c.points[2 * i + 1]
                if (i == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
            }
        }
    }

    private fun addLead(a: Float, b: Float, t: Float) {
        if (b - a < 0.5f) return
        val steps = ceil((b - a) / 1.2f).toInt()
        for (i in 0..steps) {
            val x = a + (b - a) * i / steps
            if (i == 0) trace.moveTo(x, waveY(x, t)) else trace.lineTo(x, waveY(x, t))
        }
    }

    private fun waveY(x: Float, t: Float): Float {
        val xn = (x - traceLeft) / (traceRight - traceLeft)
        return centerY + AMP * envelope(t) * taper(xn) * voice(xn, t)
    }

    private companion object {
        val GHOSTS = listOf(0.08f to 0.12f, 0.04f to 0.22f)

        fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

        /** Samples a contour evenly by length (about every 0.6 units), starting at its leftmost point. */
        fun sample(d: String): Contour {
            val measure = PathMeasure()
            measure.setPath(parse(d), forceClosed = true)
            val length = measure.length
            var offset = 0f
            var bestX = Float.MAX_VALUE
            var bestY = -Float.MAX_VALUE
            for (i in 0 until 200) {
                val p = measure.getPosition(length * i / 200)
                if (p.x < bestX - 1e-3f || (abs(p.x - bestX) < 1e-3f && p.y > bestY)) {
                    bestX = p.x; bestY = p.y; offset = length * i / 200
                }
            }
            val n = max(24, ceil(length / 0.6f).toInt())
            val points = FloatArray((n + 1) * 2)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            for (i in 0..n) {
                val p = measure.getPosition((offset + length * i / n) % length)
                points[2 * i] = p.x
                points[2 * i + 1] = p.y
                minX = min(minX, p.x)
                maxX = max(maxX, p.x)
            }
            return Contour(points, minX, maxX, length)
        }
    }
}

// A sung vowel: fundamental with vibrato, six harmonics blending between two vowel colours.
private val VOWEL_A = floatArrayOf(1f, 0.55f, 0.35f, 0.5f, 0.2f, 0.12f)
private val VOWEL_B = floatArrayOf(1f, 0.25f, 0.6f, 0.15f, 0.3f, 0.08f)
private val HARMONIC_PHASE = floatArrayOf(0f, 0.7f, 1.9f, 0.4f, 2.6f, 1.2f)

private fun voice(xn: Float, t: Float): Float {
    val cycles = 3.0f * (1f + 0.06f * sin(TAU * 5.5f * t)) + 0.4f * sin(TAU * 0.35f * t)
    val phase = TAU * (xn * cycles - 0.9f * t)
    val m = 0.5f + 0.5f * sin(TAU * 0.7f * t + 1.0f)
    var sum = 0f
    for (h in 0 until 6) sum += (VOWEL_A[h] * (1f - m) + VOWEL_B[h] * m) * sin((h + 1) * phase + HARMONIC_PHASE[h])
    return sum / 2f
}

private fun envelope(t: Float) = easeOut(clamp(t / Timeline.ATTACK)) * (1f + 0.08f * sin(TAU * 3.1f * t))
private fun taper(xn: Float) = smooth(0f, 0.06f, xn) * smooth(1f, 0.94f, xn)
private fun clamp(v: Float) = v.coerceIn(0f, 1f)
private fun easeOut(v: Float) = 1f - (1f - v) * (1f - v) * (1f - v)
private fun easeInOut(v: Float) = if (v < 0.5f) 4f * v * v * v else 1f - (-2f * v + 2f).let { it * it * it } / 2f
private fun smooth(a: Float, b: Float, v: Float): Float {
    val x = clamp((v - a) / (b - a))
    return x * x * (3f - 2f * x)
}
