package com.motogps.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Offline-first Road DNA section, with no new ViewModel callbacks or BLE data. */
@Composable
internal fun RoadDnaTerrainPanel(
    terrain: RoadDnaTerrain, fetchingElevation: Boolean = false
) {
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

        HorizontalDivider(color = MotoChatColors.Outline)
        Text("Profilo altimetrico", style = MaterialTheme.typography.titleMedium,
            color = MotoChatColors.Green)
        val elevation = terrain.elevation
        if (elevation == null && fetchingElevation) {
            Text("Recupero online delle quote DEM lungo il percorso…",
                style = MaterialTheme.typography.bodySmall,
                color = MotoChatColors.TextMuted)
        } else if (elevation == null) {
            Text("Quote non disponibili: serve un grafo Valhalla con dati DEM " +
                "e una risposta contenente i campioni di elevazione. " +
                "Non vengono stimate quote dalla sola mappa.",
                style = MaterialTheme.typography.bodySmall,
                color = MotoChatColors.TextMuted)
        } else {
            RoadDnaElevationDetails(elevation)
        }
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
    val valid = points.mapNotNull { it.altitudeMeters }
    if (valid.size < 2) return
    val low = valid.minOrNull() ?: return
    val high = valid.maxOrNull() ?: return
    val first = points.first().distanceMeters
    val spanDistance = max(1.0, points.last().distanceMeters - first)
    val spanAltitude = max(30.0, high - low)
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
        val path = Path()
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
            if (penUp) path.moveTo(x, y) else path.lineTo(x, y)
            penUp = false
        }
        drawPath(path, color = MotoChatColors.Green,
            style = Stroke(width = 2.5.dp.toPx()))
    }
    Text(String.format(Locale.ITALY, "Distanza %.1f km",
        (points.last().distanceMeters - first) / 1000.0),
        style = MaterialTheme.typography.labelSmall,
        color = MotoChatColors.TextMuted)
}
