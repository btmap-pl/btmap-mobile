package pl.btmap.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
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
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false) },
            label = "metric",
        ) { value ->
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

// ---- Motion --------------------------------------------------------------------------------

/**
 * Number that rolls up or down when it changes, like an odometer, so signal changes are visible
 * at a glance instead of digits just swapping.
 */
@Composable
fun AnimatedNumber(value: Int?, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val animatedColor by animateColorAsState(color, tween(400), label = "numColor")
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val up = (targetState ?: 0) > (initialState ?: 0)
            val dir = if (up) 1 else -1
            (slideInVertically(tween(260)) { h -> dir * h / 2 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(200)) { h -> -dir * h / 2 } + fadeOut(tween(160))) using
                SizeTransform(clip = false)
        },
        modifier = modifier,
        label = "number",
    ) { v ->
        Text(v?.toString() ?: "—", style = style, color = if (v == null) Btmap.colors.faint else animatedColor, maxLines = 1)
    }
}

/** Soft breathing dot for "live" state. */
@Composable
fun PulseDot(color: Color, active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulseScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulseAlpha",
    )
    Canvas(modifier.size(18.dp)) {
        val r = 3.5.dp.toPx()
        if (active) drawCircle(color.copy(alpha = alpha), radius = r * scale)
        drawCircle(color, radius = r)
    }
}

/** Fades and lifts content in the first time it is composed (staggered by [index]). */
@Composable
fun Modifier.enterAnimation(index: Int = 0): Modifier {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        shown.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 28.dp.toPx()
        val s = 0.97f + 0.03f * shown.value
        scaleX = s
        scaleY = s
    }
}

/** Slight press-down scale for tappable surfaces. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/** Gear: settings tab. */
@Composable
fun SettingsIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val c = center
        val outer = size.minDimension * 0.46f
        val inner = size.minDimension * 0.34f
        val teeth = 8
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0 until teeth * 2) {
            // Alternate tooth top / gap, each spanning half a tooth angle, for a crisp outline.
            val r = if (i % 2 == 0) outer else inner
            val a0 = Math.toRadians((i * 360.0 / (teeth * 2)) - 90 - 360.0 / (teeth * 4))
            val a1 = Math.toRadians((i * 360.0 / (teeth * 2)) - 90 + 360.0 / (teeth * 4))
            val p0 = Offset(c.x + r * kotlin.math.cos(a0).toFloat(), c.y + r * kotlin.math.sin(a0).toFloat())
            val p1 = Offset(c.x + r * kotlin.math.cos(a1).toFloat(), c.y + r * kotlin.math.sin(a1).toFloat())
            if (i == 0) path.moveTo(p0.x, p0.y) else path.lineTo(p0.x, p0.y)
            path.lineTo(p1.x, p1.y)
        }
        path.close()
        drawPath(
            path, color,
            style = Stroke(1.7.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
        drawCircle(color, radius = size.minDimension * 0.13f, center = c, style = Stroke(1.7.dp.toPx()))
    }
}

/** Database cylinder: station database settings. */
@Composable
fun DatabaseIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val stroke = Stroke(1.7.dp.toPx())
        val w = size.width * 0.72f
        val left = (size.width - w) / 2
        val h = size.height * 0.22f
        val top = size.height * 0.12f
        val bottom = size.height * 0.88f - h
        drawOval(color, Offset(left, top), Size(w, h), style = stroke)
        drawArc(color, 0f, 180f, false, Offset(left, (top + bottom) / 2), Size(w, h), style = stroke)
        drawArc(color, 0f, 180f, false, Offset(left, bottom), Size(w, h), style = stroke)
        drawLine(color, Offset(left, top + h / 2), Offset(left, bottom + h / 2), stroke.width)
        drawLine(color, Offset(left + w, top + h / 2), Offset(left + w, bottom + h / 2), stroke.width)
    }
}
