package com.adsstudio

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}

@Composable
fun StudioBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(StudioColors.Ink, StudioColors.InkSoft, StudioColors.Ink)
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(StudioColors.Orange.copy(alpha = 0.17f), Color.Transparent),
                    center = Offset(size.width * 0.9f, -size.height * 0.02f),
                    radius = size.width * 1.15f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(StudioColors.Cyan.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.02f, size.height * 0.62f),
                    radius = size.width * 0.95f
                )
            )
            val random = Random(11)
            val points = List(380) {
                Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
            }
            drawPoints(
                points = points,
                pointMode = PointMode.Points,
                color = Color.White.copy(alpha = 0.04f),
                strokeWidth = 1.0.dp.toPx()
            )
        }
        content()
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(10.dp),
                    ambientColor = StudioColors.Orange.copy(alpha = 0.45f),
                    spotColor = StudioColors.Orange.copy(alpha = 0.45f)
                )
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(StudioColors.Orange, StudioColors.Coral))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("ADS", style = StudioType.wordmark, color = StudioColors.Text)
        Spacer(Modifier.width(5.dp))
        Text("STUDIO", style = StudioType.wordmark, color = StudioColors.Orange)
    }
}

@Composable
fun LocalBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(StudioColors.Surface.copy(alpha = 0.72f))
            .border(1.dp, StudioColors.BorderSoft, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(StudioColors.Green)
        )
        Spacer(Modifier.width(6.dp))
        Text("ЛОКАЛЬНО", style = StudioType.label, color = StudioColors.TextMuted)
    }
}

@Composable
fun SectionHeader(
    number: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = StudioType.mono, color = StudioColors.Orange)
        Spacer(Modifier.width(10.dp))
        Text(title, style = StudioType.section, color = StudioColors.Text)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(StudioColors.BorderSoft)
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun CountPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = StudioType.monoSmall,
        color = StudioColors.Orange,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(StudioColors.Orange.copy(alpha = 0.10f))
            .border(1.dp, StudioColors.Orange.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    borderBrush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(StudioColors.SurfaceRaised, StudioColors.Surface)))
            .then(
                if (borderBrush != null) Modifier.border(1.dp, borderBrush, shape)
                else Modifier.border(1.dp, StudioColors.BorderSoft, shape)
            )
            .padding(18.dp),
        content = content
    )
}

fun Modifier.dashedBorder(
    color: Color,
    radius: Dp = 24.dp,
    strokeWidth: Dp = 1.4.dp
): Modifier = drawBehind {
    val stroke = strokeWidth.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx() - stroke / 2f),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(
                intervals = floatArrayOf(12.dp.toPx(), 9.dp.toPx()),
                phase = 0f
            )
        )
    )
}

@Composable
fun PrimaryAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.PlayArrow
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "ctaScale"
    )
    val shape = RoundedCornerShape(18.dp)
    val indication = LocalIndication.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = StudioColors.Orange.copy(alpha = 0.35f),
                spotColor = StudioColors.Orange.copy(alpha = 0.35f)
            )
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.linearGradient(listOf(StudioColors.Orange, StudioColors.Coral))
                } else {
                    Brush.linearGradient(listOf(StudioColors.SurfaceHigh, StudioColors.SurfaceHigh))
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = indication,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.White else StudioColors.TextDim,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) Color.White else StudioColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SmallAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArrow: Boolean = true
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.linearGradient(listOf(StudioColors.Orange, StudioColors.Coral))
                } else {
                    Brush.linearGradient(listOf(StudioColors.SurfaceHigh, StudioColors.SurfaceHigh))
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else StudioColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showArrow) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ArrowForward,
                contentDescription = null,
                tint = if (enabled) Color.White else StudioColors.TextDim,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun RatioCard(
    ratio: OutputRatio,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) StudioColors.Orange else StudioColors.BorderSoft,
        animationSpec = tween(180),
        label = "ratioBorder"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) StudioColors.Orange.copy(alpha = 0.10f) else StudioColors.Surface,
        animationSpec = tween(180),
        label = "ratioBg"
    )
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(Modifier.size(32.dp)) {
            val ratioValue = ratio.width.toFloat() / ratio.height.toFloat()
            val maxW = size.width * 0.82f
            val maxH = size.height * 0.82f
            val w: Float
            val h: Float
            if (ratioValue >= 1f) {
                w = maxW
                h = maxW / ratioValue
            } else {
                h = maxH
                w = maxH * ratioValue
            }
            val topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f)
            if (selected) {
                drawRoundRect(
                    color = StudioColors.Orange.copy(alpha = 0.16f),
                    topLeft = topLeft,
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
            }
            drawRoundRect(
                color = if (selected) StudioColors.Orange else StudioColors.TextDim,
                topLeft = topLeft,
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 1.4.dp.toPx())
            )
        }
        Text(
            ratio.label,
            style = StudioType.mono,
            color = if (selected) StudioColors.Text else StudioColors.TextMuted
        )
        Text(
            ratio.hint,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) StudioColors.Orange else StudioColors.TextDim,
            maxLines = 1
        )
    }
}

@Composable
fun NeuralGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        StudioColors.Cyan.copy(alpha = 0.16f),
                        StudioColors.Violet.copy(alpha = 0.16f)
                    )
                )
            )
            .border(1.dp, StudioColors.Cyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val nodes = listOf(
                Offset(size.width * 0.16f, size.height * 0.5f),
                Offset(size.width * 0.5f, size.height * 0.16f),
                Offset(size.width * 0.5f, size.height * 0.84f),
                Offset(size.width * 0.84f, size.height * 0.5f)
            )
            val links = listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3)
            links.forEach { (a, b) ->
                drawLine(
                    color = StudioColors.Cyan.copy(alpha = 0.45f),
                    start = nodes[a],
                    end = nodes[b],
                    strokeWidth = 1.1.dp.toPx()
                )
            }
            nodes.forEachIndexed { index, point ->
                drawCircle(
                    color = if (index == 3) StudioColors.Orange else StudioColors.Cyan,
                    radius = if (index == 3) 2.8.dp.toPx() else 2.1.dp.toPx(),
                    center = point
                )
            }
        }
    }
}

@Composable
fun AiCard(
    enabled: Boolean,
    ready: Boolean,
    onToggle: (Boolean) -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    val border = Brush.linearGradient(
        listOf(
            StudioColors.Cyan.copy(alpha = 0.45f),
            StudioColors.Violet.copy(alpha = 0.30f)
        )
    )
    StudioCard(modifier = modifier, borderBrush = border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuralGlyph()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Нейросеть",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.Text
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (enabled) {
                                    if (ready) StudioColors.Green else StudioColors.Violet
                                } else {
                                    StudioColors.TextDim
                                }
                            )
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        enabled && ready -> "2 × Qwen 2.5 · 0.5B · готовы"
                        enabled -> "2 × Qwen 2.5 · 0.5B · ~1 ГБ в памяти"
                        else -> "Выключена · нарезка по эвристике"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextMuted
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (enabled) {
                        "Критик задаёт вопросы, редактор отвечает и выбирает. Смысл, звук, движение."
                    } else {
                        "Включи, чтобы два агента проверили моменты по смыслу и звуку."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextDim
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = interactive,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = StudioColors.Ink,
                    checkedTrackColor = StudioColors.Cyan,
                    uncheckedThumbColor = StudioColors.TextMuted,
                    uncheckedTrackColor = StudioColors.SurfaceHigh,
                    uncheckedBorderColor = StudioColors.Border
                )
            )
        }

        if (enabled) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTag("Критик", "вопросы")
                AgentTag("Редактор", "выбор")
            }
        }
    }
}

@Composable
private fun AgentTag(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(StudioColors.SurfaceHigh.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(StudioColors.Cyan)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = StudioColors.Text
        )
        Spacer(Modifier.width(5.dp))
        Text(
            subtitle,
            style = StudioType.monoSmall,
            color = StudioColors.TextDim
        )
    }
}

@Composable
fun AdCard(
    adSet: Boolean,
    adPreview: Bitmap?,
    adDurationMs: Long?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(StudioColors.Surface)
            .dashedBorder(
                color = if (adSet) StudioColors.Orange.copy(alpha = 0.55f)
                else StudioColors.Border,
                radius = 24.dp
            )
            .clickable(enabled = enabled && !adSet, onClick = onPick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (adSet) Color.Transparent else StudioColors.SurfaceHigh
                ),
            contentAlignment = Alignment.Center
        ) {
            if (adPreview != null) {
                Image(
                    bitmap = adPreview.asImageBitmap(),
                    contentDescription = "Видео-реклама",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = if (adSet) Icons.Rounded.PlayArrow else Icons.Rounded.Add,
                    contentDescription = null,
                    tint = StudioColors.Orange,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (adSet) "Видео-реклама добавлена" else "Добавить видео-рекламу",
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.Text
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (adSet) {
                    val seconds = ((adDurationMs ?: 0L) / 1000L).coerceAtLeast(1L)
                    "$seconds с · на 0:30, 1:30, 2:30…"
                } else {
                    "MP4 до 10 секунд · в середине каждой минуты"
                },
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextMuted
            )
        }
        if (adSet) {
            TextButton(onClick = onClear, enabled = enabled) {
                Text("Убрать", color = StudioColors.TextMuted)
            }
        }
    }
}

@Composable
fun ProcessingCard(
    busy: String,
    progress: Float?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        elapsed = 0
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    StudioCard(
        modifier = modifier,
        borderBrush = Brush.linearGradient(
            listOf(
                StudioColors.Orange.copy(alpha = 0.55f),
                StudioColors.Coral.copy(alpha = 0.22f)
            )
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(StudioColors.Orange)
            )
            Spacer(Modifier.width(9.dp))
            Text("В РАБОТЕ", style = StudioType.label, color = StudioColors.Orange)
            Spacer(Modifier.weight(1f))
            Text(formatElapsed(elapsed), style = StudioType.mono, color = StudioColors.TextMuted)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            busy,
            style = MaterialTheme.typography.titleLarge,
            color = StudioColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))
        WaveformScan(progress = progress, reducedMotion = reducedMotion)
        Spacer(Modifier.height(12.dp))
        if (progress != null) {
            StudioProgressBar(progress = progress)
        } else {
            IndeterminateBar()
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Отменить", color = StudioColors.TextMuted)
        }
    }
}

@Composable
fun WaveformScan(progress: Float?, reducedMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "scan")
    val scan by transition.animateFloat(
        initialValue = -0.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanX"
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        val bars = 44
        val gap = size.width / bars
        val barWidth = gap * 0.42f
        val position = (progress ?: scan).coerceIn(0f, 1f)
        for (i in 0 until bars) {
            val phase = i * 0.55f
            val heightFactor = 0.22f + 0.78f * abs(sin(phase))
            val barHeight = heightFactor * size.height * 0.76f
            val x = i * gap + (gap - barWidth) / 2f
            val y = (size.height - barHeight) / 2f
            val active = !reducedMotion && abs((i.toFloat() / bars) - position) < 0.055f
            drawRoundRect(
                color = if (active) StudioColors.Orange else StudioColors.TextDim.copy(alpha = 0.32f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
        if (!reducedMotion) {
            val x = position * size.width
            val glow = 70.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        StudioColors.Orange.copy(alpha = 0.12f),
                        StudioColors.Orange.copy(alpha = 0.48f),
                        StudioColors.Orange.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    startX = x - glow,
                    endX = x + glow
                ),
                topLeft = Offset(x - glow, 0f),
                size = Size(glow * 2f, size.height)
            )
        }
    }
}

@Composable
fun StudioProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(StudioColors.SurfaceHigh)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(999.dp))
                .background(Brush.horizontalGradient(listOf(StudioColors.Orange, StudioColors.Coral)))
        )
    }
}

@Composable
fun IndeterminateBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "indeterminateX"
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        drawRoundRect(
            color = StudioColors.SurfaceHigh,
            cornerRadius = CornerRadius(size.height / 2f)
        )
        val width = size.width * 0.34f
        val start = -width + (size.width + width) * x
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    StudioColors.Orange,
                    StudioColors.Coral,
                    Color.Transparent
                ),
                startX = start,
                endX = start + width
            ),
            topLeft = Offset(start, 0f),
            size = Size(width, size.height),
            cornerRadius = CornerRadius(size.height / 2f)
        )
    }
}

@Composable
fun ClipThumbnail(
    uri: Uri?,
    startMs: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, startMs) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val frame = retriever.getFrameAtTime(
                            startMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                        frame?.let { original ->
                            val maxW = 320f
                            val maxH = 180f
                            val scale = minOf(maxW / original.width, maxH / original.height)
                            val w = (original.width * scale).toInt().coerceAtLeast(1)
                            val h = (original.height * scale).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(original, w, h, true)
                            if (scaled !== original) original.recycle()
                            scaled
                        }
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = modifier.background(StudioColors.SurfaceHigh)
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, StudioColors.Ink.copy(alpha = 0.55f))
                    )
                )
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ClipCard(
    index: Int,
    clip: SuggestedClip,
    videoUri: Uri?,
    enabled: Boolean,
    ratioLabel: String,
    isExporting: Boolean,
    exportProgress: Float?,
    onRename: (String) -> Unit,
    onExport: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(clip.title) { mutableStateOf(clip.title) }
    val accents = listOf(
        StudioColors.Orange,
        StudioColors.Cyan,
        StudioColors.Violet,
        StudioColors.Lime,
        StudioColors.Coral
    )
    val accent = accents[index % accents.size]
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(StudioColors.SurfaceRaised, StudioColors.Surface)
                )
            )
            .border(1.dp, StudioColors.BorderSoft, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(12.dp))
            ClipThumbnail(
                uri = videoUri,
                startMs = clip.startMs,
                modifier = Modifier
                    .width(96.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = videoUri != null) { onPreview() }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (index + 1).toString().padStart(2, '0'),
                        style = StudioType.mono,
                        color = accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatTimecode(clip.startMs)} – ${formatTimecode(clip.endMs)}",
                        style = StudioType.monoSmall,
                        color = StudioColors.TextDim
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    clip.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            clip.text,
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isExporting) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Рендер… ${((exportProgress ?: 0f) * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioColors.Orange
                    )
                    Spacer(Modifier.height(5.dp))
                    StudioProgressBar(progress = exportProgress ?: 0f)
                }
            } else {
                SmallAction(
                    text = "Экспорт $ratioLabel",
                    enabled = enabled,
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onPreview,
                enabled = videoUri != null && enabled && !isExporting
            ) {
                Text(
                    "Смотреть",
                    color = StudioColors.Cyan,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            TextButton(
                onClick = {
                    draft = clip.title
                    editing = true
                },
                enabled = enabled && !isExporting
            ) {
                Text(
                    "Изменить",
                    color = StudioColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = StudioColors.SurfaceRaised,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    "Название клипа",
                    style = MaterialTheme.typography.titleLarge,
                    color = StudioColors.Text
                )
            },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Название") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Orange,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedLabelColor = StudioColors.Orange,
                        unfocusedLabelColor = StudioColors.TextMuted,
                        cursorColor = StudioColors.Orange,
                        focusedTextColor = StudioColors.Text,
                        unfocusedTextColor = StudioColors.Text
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(draft)
                    editing = false
                }) {
                    Text("Сохранить", color = StudioColors.Orange)
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text("Отмена", color = StudioColors.TextMuted)
                }
            }
        )
    }
}

@Composable
fun SuccessCard(
    title: String?,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(StudioColors.Green.copy(alpha = 0.13f), StudioColors.Surface)
                )
            )
            .border(1.dp, StudioColors.Green.copy(alpha = 0.32f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(StudioColors.Green.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = StudioColors.Green,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Клип сохранён",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.Text
                )
                Text(
                    "Movies/AdsStudio",
                    style = StudioType.monoSmall,
                    color = StudioColors.TextMuted
                )
            }
        }
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(
                text = "Открыть",
                enabled = true,
                onClick = onOpen,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioColors.Border)
            ) {
                Text(
                    "Поделиться",
                    color = StudioColors.Text,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun StudioBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (isError) StudioColors.Red else StudioColors.Cyan
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.Text,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Закрыть",
                tint = StudioColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun EmptyClips(modifier: Modifier = Modifier) {
    StudioCard(modifier = modifier) {
        Text(
            "Подходящих моментов не нашлось",
            style = MaterialTheme.typography.titleMedium,
            color = StudioColors.Text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Попробуй видео подлиннее или выключи нейросеть — эвристика может найти другие фрагменты.",
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextMuted
        )
    }
}

@Composable
fun StepStrip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StepItem("01", "Речь", "в текст", Modifier.weight(1f))
        StepItem("02", "ИИ", "выбирает", Modifier.weight(1f))
        StepItem("03", "Клипы", "в галерею", Modifier.weight(1f))
    }
}

@Composable
private fun StepItem(
    number: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(StudioColors.BorderSoft)
        )
        Spacer(Modifier.height(8.dp))
        Text(number, style = StudioType.monoSmall, color = StudioColors.Orange)
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = StudioColors.Text
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextDim
        )
    }
}

@Composable
fun ProjectPickerCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.99f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "pickerScale"
    )
    val shape = RoundedCornerShape(26.dp)
    val indication = LocalIndication.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(StudioColors.SurfaceRaised, StudioColors.Surface)
                )
            )
            .dashedBorder(
                color = StudioColors.Orange.copy(alpha = 0.42f),
                radius = 26.dp,
                strokeWidth = 1.4.dp
            )
            .clickable(
                interactionSource = interaction,
                indication = indication,
                onClick = onClick
            )
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    ambientColor = StudioColors.Orange.copy(alpha = 0.45f),
                    spotColor = StudioColors.Orange.copy(alpha = 0.45f)
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(StudioColors.Orange, StudioColors.Coral))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Выбери видео",
            style = MaterialTheme.typography.headlineMedium,
            color = StudioColors.Text
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "MP4 или MOV. Можно длинное — хоть на час.",
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.TextMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerPill("MP4 · MOV")
            PickerPill("до 2 часов")
        }
    }
}

@Composable
private fun PickerPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = StudioColors.TextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(StudioColors.SurfaceHigh.copy(alpha = 0.7f))
            .border(1.dp, StudioColors.BorderSoft, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
fun Reveal(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val reduced = rememberReducedMotion()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (reduced) {
            visible = true
        } else {
            delay(index * 55L)
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) {
            fadeIn(tween(200))
        } else {
            fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 7 }
        }
    ) {
        content()
    }
}

fun formatTimecode(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

fun formatElapsed(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}
