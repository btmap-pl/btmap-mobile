package pl.btmap.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.btmap.core.model.Rat
import pl.btmap.mobile.ui.theme.Btmap

// ---- Signal quality -------------------------------------------------------------------------

enum class Quality(val label: String) { EXCELLENT("Świetny"), GOOD("Dobry"), FAIR("Średni"), POOR("Słaby"), BAD("Bardzo słaby") }

/** RSRP / SS-RSRP / RSCP / RSSI → quality, with thresholds per measurement. */
fun powerQuality(dbm: Int?, rat: Rat): Quality? {
    dbm ?: return null
    val (excellent, good, fair, poor) = when (rat) {
        Rat.LTE, Rat.NR -> listOf(-80, -90, -100, -110)
        Rat.WCDMA -> listOf(-70, -85, -95, -105)
        Rat.GSM -> listOf(-65, -75, -85, -95)
    }
    return when {
        dbm >= excellent -> Quality.EXCELLENT
        dbm >= good -> Quality.GOOD
        dbm >= fair -> Quality.FAIR
        dbm >= poor -> Quality.POOR
        else -> Quality.BAD
    }
}

fun sinrQuality(db: Int?): Quality? = db?.let {
    when {
        it >= 20 -> Quality.EXCELLENT
        it >= 13 -> Quality.GOOD
        it >= 5 -> Quality.FAIR
        it >= 0 -> Quality.POOR
        else -> Quality.BAD
    }
}

fun rsrqQuality(db: Int?): Quality? = db?.let {
    when {
        it >= -8 -> Quality.EXCELLENT
        it >= -11 -> Quality.GOOD
        it >= -15 -> Quality.FAIR
        it >= -19 -> Quality.POOR
        else -> Quality.BAD
    }
}

@Composable
fun Quality?.color(): Color = when (this) {
    Quality.EXCELLENT, Quality.GOOD -> Btmap.colors.good
    Quality.FAIR -> Btmap.colors.fair
    Quality.POOR -> Btmap.colors.poor
    Quality.BAD -> Btmap.colors.bad
    null -> MaterialTheme.colorScheme.onSurface
}

/** 0..1 fill of a power meter: -140 dBm empty, -44 dBm full (LTE/NR range). */
fun powerFraction(dbm: Int?): Float = dbm?.let { ((it + 140) / 96f).coerceIn(0f, 1f) } ?: 0f

// ---- Building blocks -----------------------------------------------------------------------

/** Rounded meter with an animated fill in the quality colour. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val animated by animateFloatAsState(fraction, tween(450), label = "meter")
    val track = Btmap.colors.meterTrack
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .drawBehind {
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(
                    color,
                    size = Size(size.width * animated, size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            },
    )
}

/** Small vertical bar used in list rows. */
@Composable
fun MiniBars(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = Btmap.colors.meterTrack
    Canvas(modifier.size(width = 18.dp, height = 12.dp)) {
        val bars = 4
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val lit = (fraction * bars + 0.25f).toInt().coerceIn(if (fraction > 0f) 1 else 0, bars)
        for (i in 0 until bars) {
            val h = size.height * (i + 1) / bars
            drawRoundRect(
                if (i < lit) color else track,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

enum class PillStyle { FILLED, OUTLINED, DASHED }

@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.OUTLINED,
    content: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val base = modifier.clip(shape)
    val decorated = when (style) {
        PillStyle.FILLED -> base.background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.55f), shape)
        PillStyle.OUTLINED -> base.border(1.dp, color.copy(alpha = 0.45f), shape)
        PillStyle.DASHED -> base.drawBehind {
            drawRoundRect(
                color.copy(alpha = 0.55f),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
            )
        }
    }
    Row(decorated.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
        content?.invoke()
    }
}

/** Label above a value, used in the metrics row. */
@Composable
fun Metric(label: String, value: String?, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Btmap.colors.cardRaised)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
        // One line, never wrapped: long readouts ("-101 dBm") step down a size instead.
        val long = (value?.length ?: 0) + unit.length >= 7
        val muted = Btmap.colors.muted
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (value == null) Btmap.colors.faint else color, fontWeight = FontWeight.SemiBold)) {
                    append(value ?: "—")
                }
                if (value != null && unit.isNotEmpty()) {
                    withStyle(SpanStyle(color = muted, fontSize = MaterialTheme.typography.labelSmall.fontSize)) { append(" $unit") }
                }
            },
            style = if (long) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ---- Icons (drawn, so no icon font / extra dependency) -------------------------------------

@Composable
fun PinIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val w = size.width
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, size.height)
            cubicTo(w * 0.1f, size.height * 0.55f, w * 0.12f, size.height * 0.08f, w / 2, size.height * 0.05f)
            cubicTo(w * 0.88f, size.height * 0.08f, w * 0.9f, size.height * 0.55f, w / 2, size.height)
            close()
        }
        drawPath(path, color)
        drawCircle(Color.White.copy(alpha = 0.9f), radius = w * 0.14f, center = Offset(w / 2, size.height * 0.38f))
    }
}

@Composable
fun LiveIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val c = center
        val stroke = Stroke(1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(color, radius = 2.4.dp.toPx(), center = c)
        for ((i, r) in listOf(6.dp, 10.dp).withIndex()) {
            val rp = r.toPx()
            val tl = Offset(c.x - rp, c.y - rp)
            val sz = Size(rp * 2, rp * 2)
            drawArc(color.copy(alpha = 1f - i * 0.3f), -140f, 100f, false, tl, sz, style = stroke)
            drawArc(color.copy(alpha = 1f - i * 0.3f), 40f, 100f, false, tl, sz, style = stroke)
        }
    }
}

@Composable
fun LogIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val s = 1.8.dp.toPx()
        val x0 = size.width * 0.18f
        for (i in 0..2) {
            val y = size.height * (0.28f + i * 0.22f)
            drawCircle(color, radius = s * 0.75f, center = Offset(x0, y))
            drawLine(color, Offset(x0 + s * 2.4f, y), Offset(size.width * 0.86f, y), strokeWidth = s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Half-filled circle: theme switch. */
@Composable
fun ThemeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val r = size.minDimension / 2 - 1.5.dp.toPx()
        drawCircle(color, radius = r, style = Stroke(1.8.dp.toPx()))
        drawArc(color, 90f, 180f, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Btmap.colors.divider))
}

@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 8.dp).size(width = 1.dp, height = 12.dp).background(Btmap.colors.faint))
}
