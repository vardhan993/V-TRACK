package com.vtrack.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Vertical bar chart with an optional goal line, used for the weekly step/calorie view.
 * Everything is drawn with Canvas — no charting dependency, so the app stays offline
 * and dependency-light while remaining fully themeable.
 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    highlightColor: Color = MaterialTheme.colorScheme.secondary,
    goal: Float? = null,
    goalColor: Color = MaterialTheme.colorScheme.outline,
    onBarClick: ((Int) -> Unit)? = null,
) {
    val animation by animateFloatAsState(1f, animationSpec = tween(700), label = "bars")
    val maxValue = maxOf(values.maxOrNull() ?: 0f, goal ?: 0f, 1f)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val labelSpace = 0f
                val chartHeight = size.height - labelSpace
                val count = values.size.coerceAtLeast(1)
                val gap = size.width * 0.035f
                val barWidth = (size.width - gap * (count - 1)) / count

                goal?.let {
                    val y = chartHeight - (it / maxValue) * chartHeight * 0.92f
                    drawLine(
                        color = goalColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
                    )
                }

                values.forEachIndexed { index, value ->
                    val fraction = (value / maxValue).coerceIn(0f, 1f) * animation
                    val barHeight = (chartHeight * 0.92f * fraction).coerceAtLeast(if (value > 0f) 4f else 0f)
                    val left = index * (barWidth + gap)
                    val top = chartHeight - barHeight
                    if (barHeight > 0f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    if (isLastBar(index, values)) highlightColor else barColor,
                                    (if (isLastBar(index, values)) highlightColor else barColor).copy(alpha = 0.55f),
                                ),
                            ),
                            topLeft = Offset(left, top),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2.4f, barWidth / 2.4f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labels.forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (onBarClick != null && index == labels.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.width(28.dp),
                )
            }
        }
    }
}

private fun isLastBar(index: Int, values: List<Float>): Boolean = index == values.lastIndex

/** Smooth line chart with a filled gradient area; used for trends (weight, pace, coins). */
@Composable
fun LineChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillAlpha: Float = 0.18f,
    labels: List<String> = emptyList(),
) {
    val animation by animateFloatAsState(1f, animationSpec = tween(800), label = "line")
    val maxValue = (points.maxOrNull() ?: 1f).takeIf { it > 0f } ?: 1f
    val minValue = points.minOrNull() ?: 0f

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            if (points.size < 2) return@Canvas
            val stepX = size.width / (points.size - 1)
            val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
            val path = Path()
            val fillPath = Path()
            points.forEachIndexed { index, value ->
                val x = stepX * index
                val normalized = (value - minValue) / range
                val y = size.height - (normalized * size.height * 0.85f * animation) - size.height * 0.08f
                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(size.width, size.height)
            fillPath.close()
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = fillAlpha), Color.Transparent),
                ),
            )
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            points.lastOrNull()?.let { last ->
                val normalized = (last - minValue) / range
                val y = size.height - (normalized * size.height * 0.85f * animation) - size.height * 0.08f
                drawCircle(color = lineColor, radius = 9f, center = Offset(size.width, y))
                drawCircle(color = Color.White, radius = 4f, center = Offset(size.width, y))
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                labels.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Horizontal progress rows used for "goal completion" style breakdowns. */
@Composable
fun BreakdownBar(
    segments: List<Triple<String, Float, Color>>,
    modifier: Modifier = Modifier,
) {
    val total = segments.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
        ) {
            val radius = size.height / 2f
            drawRoundRect(
                color = Color(0x22FFFFFF),
                cornerRadius = CornerRadius(radius, radius),
            )
            var offsetX = 0f
            segments.forEach { (_, value, color) ->
                val width = (value / total) * size.width
                if (width > 0f) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(offsetX, 0f),
                        size = Size(width, size.height),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    offsetX += width
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            segments.forEach { (label, value, color) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(color)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = com.vtrack.app.core.util.Formatters.compact(value.toLong()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Tiny sparkline for card headers. */
@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier.height(32.dp).fillMaxWidth()) {
        if (points.size < 2) return@Canvas
        val max = points.maxOrNull() ?: 1f
        val min = points.minOrNull() ?: 0f
        val range = (max - min).takeIf { it > 0.0001f } ?: 1f
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = stepX * index
            val y = size.height - ((value - min) / range) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
fun ChartLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
