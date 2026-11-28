package com.motogps.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.motogps.android.viewmodel.ExplorerWeather
import com.motogps.android.viewmodel.RouteOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ExplorerWeatherPreview(options: List<RouteOption>) {
    if (options.isEmpty() || options.any { it.explorerMetrics == null }) return
    var open by remember(options) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Meteo itinerari · confronta")
    }
    if (open) ExplorerWeatherDialog(options) { open = false }
}

@Composable
private fun ExplorerWeatherDialog(options: List<RouteOption>, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val plans = remember(options) { ExplorerWeather.plans(options) }
    val start = remember { System.currentTimeMillis() / 1000 }
    var hours by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var forecast by remember { mutableStateOf<ExplorerWeather.Forecast?>(null) }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    val departure = start + hours * 3600L
    val profiles = remember(forecast, departure) { forecast?.profiles(departure).orEmpty() }
    val best = remember(profiles) { ExplorerWeather.leastPrecipitation(profiles) }
    val valid = plans.all { it.samples.isNotEmpty() }
    fun load(force: Boolean) {
        loading = true
        error = false
        scope.launch {
            try {
                forecast = withContext(Dispatchers.IO) { ExplorerWeather.fetch(context, plans, force) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            } finally {
                loading = false
            }
        }
    }
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.95f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(24.dp),
        containerColor = MotoChatColors.Surface,
        onDismissRequest = close,
        title = { Text("Explorer · Meteo itinerari") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Partenza prevista", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (h in listOf(0, 1, 2, 3, 6)) {
                        FilterChip(selected = hours == h, onClick = { hours = h },
                            label = { Text(if (h == 0) "Ora" else "+$h h") })
                    }
                }
                Text("Partenza: " + weatherTime(departure), style = MaterialTheme.typography.bodySmall)
                Text("Per il meteo vengono inviate a Open-Meteo solo le coordinate campionate dei percorsi.",
                    style = MaterialTheme.typography.bodySmall, color = MotoChatColors.TextMuted)
                OutlinedButton(onClick = { load(forecast != null || error) }, enabled = !loading && valid,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (forecast == null) "Carica previsioni" else "Aggiorna previsioni")
                }
                if (!valid) Text("Geometria non utilizzabile per il meteo.", color = MaterialTheme.colorScheme.error)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Text("Meteo non disponibile. Gli itinerari offline restano utilizzabili.",
                    color = MaterialTheme.colorScheme.error)
                forecast?.let {
                    Text((if (it.cached) "Dati in cache · " else "Scaricati · ") + weatherTime(it.fetchedAt),
                        style = MaterialTheme.typography.labelSmall, color = MotoChatColors.TextMuted)
                }
                for ((index, profile) in profiles.withIndex()) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MotoChatColors.SurfaceRaised) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleSmall)
                            if (index == best) Text("Minore probabilità massima di precipitazioni",
                                color = MotoChatColors.Green, style = MaterialTheme.typography.labelMedium)
                            val values = profile.readings
                            if (values.isNotEmpty()) {
                                Text("Temperature: " + values.minOf { it.temperature }.roundToInt() +
                                    "–" + values.maxOf { it.temperature }.roundToInt() + " °C")
                                Text("Probabilità precipitazioni max: " + profile.maxProbability!!.roundToInt() +
                                    "% · raffiche max: " + profile.maxGust!!.roundToInt() + " km/h",
                                    style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (point in profile.points) {
                                        val p = point.reading?.probability
                                        val color = when {
                                            p == null -> MotoChatColors.Outline
                                            p >= 60 -> androidx.compose.ui.graphics.Color(0xFF4389CE)
                                            p >= 30 -> androidx.compose.ui.graphics.Color(0xFFD9A441)
                                            else -> MotoChatColors.Green
                                        }
                                        Surface(Modifier.weight(1f).height(8.dp),
                                            shape = RoundedCornerShape(2.dp), color = color) {}
                                    }
                                }
                            }
                            if (!profile.complete) Text("Copertura parziale: " + values.size + "/" +
                                profile.points.size + " campioni", color = MotoChatColors.TextMuted)
                            TextButton(onClick = { expanded = if (index in expanded) expanded - index else expanded + index }) {
                                Text(if (index in expanded) "Nascondi profilo" else "Mostra profilo")
                            }
                            if (index in expanded) for (point in profile.points) {
                                val r = point.reading
                                Text(String.format(Locale.ITALY, "%.0f km · %s", point.sample.km, weatherTime(point.eta)),
                                    style = MaterialTheme.typography.labelMedium)
                                Text(if (r == null) "Dati non disponibili" else
                                    String.format(Locale.ITALY, "%s · %.0f °C · %.0f%% · %.1f mm/h · vento %.0f km/h da %.0f° · raffiche %.0f km/h",
                                        weatherLabel(r.code), r.temperature, r.probability, r.precipitation, r.wind, r.direction, r.gust),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (profiles.isNotEmpty()) Text("Profilo: verde <30%, ambra 30–59%, blu ≥60%, grigio senza dati. " +
                    "Orari stimati in proporzione alla distanza e alla durata Valhalla, senza soste. " +
                    "Previsioni orarie indicative, non garanzia di sicurezza o condizioni della strada.",
                    style = MaterialTheme.typography.bodySmall, color = MotoChatColors.TextMuted)
                Text("Dati meteorologici: Open-Meteo.com (CC BY 4.0). API gratuita per uso non commerciale.",
                    style = MaterialTheme.typography.labelSmall, color = MotoChatColors.TextMuted)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Chiudi") } }
    )
}

private fun weatherTime(epoch: Long): String =
    DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.ITALY).withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epoch))

private fun weatherLabel(code: Int): String = when (code) {
    0 -> "Sereno"
    1, 2 -> "Parzialmente nuvoloso"
    3 -> "Coperto"
    45, 48 -> "Nebbia"
    51, 53, 55, 56, 57 -> "Pioviggine"
    61, 63, 65, 66, 67, 80, 81, 82 -> "Pioggia"
    71, 73, 75, 77, 85, 86 -> "Neve"
    95, 96, 99 -> "Temporale"
    else -> "Meteo n.d."
}
