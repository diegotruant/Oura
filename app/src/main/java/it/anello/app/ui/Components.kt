package it.anello.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.anello.app.data.SleepStage
import it.anello.app.data.StageSpan
import it.anello.app.score.Contributor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

@Composable
fun DateHeader(date: LocalDate, onChange: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(date.minusDays(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Giorno precedente", tint = TextMain)
        }
        Text(
            when (date) {
                today -> "Oggi"
                today.minusDays(1) -> "Ieri"
                else -> date.format(dayFmt).replaceFirstChar { it.uppercase() }
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = TextMain,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = { if (date < today) onChange(date.plusDays(1)) }, enabled = date < today) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Giorno successivo", tint = if (date < today) TextMain else TextDim)
        }
    }
}

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(16.dp)
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextDim)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
fun ScoreRing(label: String, value: Int?, color: Color, size: Dp = 96.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (value != null) {
                    drawArc(color, -90f, 360f * value / 100f, false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text(value?.toString() ?: "–", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextDim, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ContributorRows(items: List<Contributor>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { c ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(c.label, color = TextMain, modifier = Modifier.weight(1f))
                    Text(c.value, color = TextDim)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { c.points.toFloat() / c.max },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = color,
                    trackColor = color.copy(alpha = 0.15f),
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text(label, color = TextDim, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

/** Grafico a barre semplice; i valori null lasciano un buco. */
@Composable
fun BarChart(values: List<Float?>, color: Color, labels: List<String> = emptyList(), height: Dp = 120.dp) {
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1f
    val min = values.filterNotNull().minOrNull() ?: 0f
    // Asse da zero se i valori sono "quantità", altrimenti zoom sulla variazione.
    val floor = if (min > max * 0.6f) min * 0.9f else 0f
    Column {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val barW = (slot * 0.62f).coerceAtLeast(2f)
            values.forEachIndexed { i, v ->
                if (v != null) {
                    val h = ((v - floor) / (max - floor)).coerceIn(0.03f, 1f) * size.height
                    drawRoundRect(
                        color = if (i == values.lastIndex) color else color.copy(alpha = 0.65f),
                        topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 3, barW / 3),
                    )
                }
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { Text(it, color = TextDim, fontSize = 11.sp) }
            }
        }
    }
}

fun stageColor(s: SleepStage) = when (s) {
    SleepStage.AWAKE -> AwakeColor
    SleepStage.REM -> RemColor
    SleepStage.LIGHT -> LightColor
    SleepStage.DEEP -> DeepColor
}

/** Ipnogramma: una riga per fase, dall'alto Sveglio → REM → Leggero → Profondo. */
@Composable
fun Hypnogram(spans: List<StageSpan>) {
    if (spans.isEmpty()) {
        Text("Fasi del sonno non disponibili per questa notte.", color = TextDim)
        return
    }
    val order = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    val start = spans.first().startMs
    val end = spans.maxOf { it.endMs }
    Row {
        Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceAround) {
            listOf("Sveglio", "REM", "Leggero", "Profondo").forEach {
                Text(it, color = TextDim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(140.dp)) {
            val rowH = size.height / order.size
            val total = (end - start).toFloat().coerceAtLeast(1f)
            spans.forEach { s ->
                val row = order.indexOf(s.stage)
                val x = (s.startMs - start) / total * size.width
                val w = ((s.endMs - s.startMs) / total * size.width).coerceAtLeast(1.5f)
                drawRoundRect(
                    color = stageColor(s.stage),
                    topLeft = Offset(x, row * rowH + rowH * 0.15f),
                    size = Size(w, rowH * 0.7f),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            }
        }
    }
}
