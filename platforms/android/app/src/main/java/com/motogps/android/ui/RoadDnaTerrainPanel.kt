package com.motogps.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.motogps.android.viewmodel.RoadDnaElevation
import com.motogps.android.viewmodel.RoadDnaHeightPoint
import com.motogps.android.viewmodel.RoadDnaTerrain
import java.util.Locale
import kotlin.math.max

/** Curve estimates for the selected route in Road DNA. */
@Composable
internal fun RoadDnaTerrainPanel(terrain: RoadDnaTerrain) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text("Analisi curve", style = MaterialTheme.typography.titleMedium,
            color = MotoChatColors.Green)
        Text("Indice di tortuosità: " + terrain.curves.index,
            style = MaterialTheme.typography.bodyMedium)
        Text(String.format(Locale.ITALY,
            "%d curve stimate · %.1f curve/km",
            terrain.curves.total, terrain.curves.densityPerKm),
            style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            TerrainMetric("Dolci", terrain.curves.gentle.toString(), Modifier.weight(1f))
            TerrainMetric("Medie", terrain.curves.medium.toString(), Modifier.weight(1f))
            TerrainMetric("Strette", terrain.curves.tight.toString(), Modifier.weight(1f))
            TerrainMetric("Tornanti*", terrain.curves.hairpins.toString(), Modifier.weight(1f))
        }
        Text("* Tornanti e classificazione stimati solo dalla geometria della rotta: " +
            "non indicano raggio certificato, piega della moto o velocità sicura.",
            style = MaterialTheme.typography.bodySmall,
            color = MotoChatColors.TextMuted)

    }
}

@Composable
private fun TerrainMetric(title: String, number: String, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(number, style = MaterialTheme.typography.titleLarge,
            color = MotoChatColors.Text)
        Text(title, style = MaterialTheme.typography.labelSmall,
            color = MotoChatColors.TextMuted)
    }
}

/**
 * On-demand altitude accordion inside the independently scrollable Road DNA
 * dialog. The route-planning preview stays compact in both orientations.
 */
@Composable
internal fun RoadDnaElevationSection(
    elevation: RoadDnaElevation?,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = MotoChatColors.SurfaceRaised,
        border = BorderStroke(1.dp, MotoChatColors.Outline)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Profilo altimetrico",
                        style = MaterialTheme.typography.titleSmall,
                        color = MotoChatColors.Green)
                    if (!expanded && elevation != null) {
                        Text("Quota " + elevation.minMeters + "–" +
                            elevation.maxMeters + " m · salita +" +
                            elevation.ascentMeters + " m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MotoChatColors.TextMuted)
                    } else if (!expanded) {
                        Text("Quote e dislivelli del percorso",
                            style = MaterialTheme.typography.bodySmall,
                            color = MotoChatColors.TextMuted)
                    }
                }
                Text(if (expanded) "Nascondi ▲" else "Mostra ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MotoChatColors.Text)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        elevation != null -> RoadDnaElevationDetails(elevation)
                        loading -> {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth())
                            Text("Recupero online delle quote DEM lungo il percorso…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MotoChatColors.TextMuted)
                        }
                        else -> Text(
                            "Quote non disponibili. La rotta non contiene dati DEM " +
                                "utilizzabili oppure il servizio online non è raggiungibile. " +
                                "L'itinerario resta invariato.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MotoChatColors.TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoadDnaElevationDetails(info: RoadDnaElevation) {
    Text("Min " + info.minMeters + " m  ·  Max " + info.maxMeters + " m",
        style = MaterialTheme.typography.bodyMedium)
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Salita +" + info.ascentMeters + " m",
            style = MaterialTheme.typography.bodySmall, color = MotoChatColors.Green)
        Text("Discesa -" + info.descentMeters + " m",
            style = MaterialTheme.typography.bodySmall, color = MotoChatColors.TextMuted)
    }
    RoadDnaElevationChart(info.points)
    Text("Quote DEM e dislivelli indicativi: i campioni mancanti sono lasciati vuoti; " +
        "il profilo non è una misura barometrica né una previsione della pendenza " +
        "percorribile in sicurezza.",
        style = MaterialTheme.typography.bodySmall,
        color = MotoChatColors.TextMuted)
}

@Composable
private fun RoadDnaElevationChart(points: List<RoadDnaHeightPoint>) {
    val bounds = remember(points) {
        var minH = Double.POSITIVE_INFINITY
        var maxH = Double.NEGATIVE_INFINITY
        var validCount = 0
        for (p in points) {
            val h = p.altitudeMeters ?: continue
            if (h < minH) minH = h
            if (h > maxH) maxH = h
            validCount++
        }
        if (validCount < 2) null else (minH to maxH)
    } ?: return
    val (low, high) = bounds
    val first = points.first().distanceMeters
    val spanDistance = max(1.0, points.last().distanceMeters - first)
    val spanAltitude = max(30.0, high - low)
    val chartPath = remember { Path() }
    Canvas(modifier = Modifier.fillMaxWidth().height(114.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(MotoChatColors.SurfaceRaised)
        .padding(horizontal = 9.dp, vertical = 12.dp)) {
        for (division in 1..3) {
            val y = size.height * division / 4f
            drawLine(MotoChatColors.Outline,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = 1.dp.toPx())
        }
        chartPath.reset()
        var penUp = true
        for (point in points) {
            val height = point.altitudeMeters
            if (height == null) {
                penUp = true
                continue
            }
            val x = ((point.distanceMeters - first) / spanDistance * size.width).toFloat()
            val y = (size.height -
                ((height - low) / spanAltitude * size.height).toFloat())
            if (penUp) chartPath.moveTo(x, y) else chartPath.lineTo(x, y)
            penUp = false
        }
        drawPath(chartPath, color = MotoChatColors.Green,
            style = Stroke(width = 2.5.dp.toPx()))
    }
    Text(String.format(Locale.ITALY, "Distanza %.1f km",
        (points.last().distanceMeters - first) / 1000.0),
        style = MaterialTheme.typography.labelSmall,
        color = MotoChatColors.TextMuted)
}
