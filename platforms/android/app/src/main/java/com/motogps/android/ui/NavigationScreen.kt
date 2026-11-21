package com.motogps.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.motogps.android.R
import androidx.core.content.ContextCompat
import com.motogps.android.map.SavedMapInfo
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.viewmodel.PlaceSearchResult
import com.motogps.android.viewmodel.SavedCircularTour
import com.motogps.android.viewmodel.TourSession
import com.motogps.android.viewmodel.RouteOption
import com.motogps.android.viewmodel.RoadDnaSummary
import org.json.JSONArray
import kotlin.math.roundToInt
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

// Keep action icons within the configured material-icons-core set (Check/Place).
// Numbered stop markers are drawn separately from the route geometry.
// They do not affect Valhalla waypoints or the ESP32 BLE payload.
private fun waypointMarkerIcon(map: MapView, number: Int): BitmapDrawable {
    val diameter = 58
    val bitmap = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 102, 73)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(29f, 29f, 24f, paint)
    paint.color = Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawCircle(29f, 29f, 24f, paint)
    paint.style = Paint.Style.FILL
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = android.graphics.Typeface.create(
        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    paint.textSize = 26f
    canvas.drawText(number.toString(), 29f, 38f, paint)
    return BitmapDrawable(map.resources, bitmap)
}

// Recolor only the osmdroid base tiles (day/night toggle): overlays, route lines and markers stay
// untouched. The same cached MAPNIK tiles are reused in both modes.
private val nightMapFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
    -0.20f, -0.20f, -0.20f, 0f, 185f,
    -0.19f, -0.19f, -0.19f, 0f, 193f,
    -0.18f, -0.18f, -0.18f, 0f, 190f,
     0f,     0f,     0f,    1f,   0f
)))

private fun applyMapNightMode(map: MapView, night: Boolean) {
    map.overlayManager.tilesOverlay.setColorFilter(if (night) nightMapFilter else null)
    map.invalidate()
}

// Lightweight typographic match for the circular ESP splash: bold italic
// sans-serif and a mint-green CHAT, without adding a font asset to the APK.
@Composable
private fun MotoChatAppBarTitle() {
    val green = MotoChatColors.Green
    val white = MaterialTheme.colorScheme.onSurface
    val wordmark = buildAnnotatedString {
        withStyle(SpanStyle(color = white)) { append("MOTO ") }
        withStyle(SpanStyle(color = green)) { append("CHAT") }
        withStyle(SpanStyle(color = white)) { append(" GPS") }
    }
    Text(
        text = wordmark,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.ExtraBold,
            fontStyle = FontStyle.Italic
        ),
        maxLines = 1
    )
}

@Composable
fun RouteMapView(
    geoPoints: List<GeoPoint>,
    routeFitKey: String,
    nightMode: Boolean = true,
    currentLocation: Pair<Double, Double>? = null,
    destinationLocation: Pair<Double, Double>? = null,
    waypoints: List<PlaceSearchResult> = emptyList(),
    onWaypointLongPress: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Route-fitting is deliberately one-shot per route ID, not per recomposition.\n    // The AndroidView.update callback runs on unrelated Compose changes (GPS,
    // dialog state, theme). Fit the whole route only when its identity changes.
    // A plain remembered cell avoids recomposition just to record the fitted key.
    val lastFitKey = remember { arrayOfNulls<String>(1) }
    AndroidView(
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                applyMapNightMode(this, nightMode)
                setMultiTouchControls(true)
            }
        },
        update = { mapView ->
            applyMapNightMode(mapView, nightMode)
            mapView.overlays.clear()
            if (onWaypointLongPress != null) {
                mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        if (p == null) return false
                        onWaypointLongPress(p.latitude, p.longitude)
                        return true
                    }
                }))
            }
            val viewPoints = mutableListOf<GeoPoint>()
            if (geoPoints.size >= 2) {
                mapView.overlays.add(Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = android.graphics.Color.rgb(80, 217, 149) // Dark Touring route green
                    outlinePaint.strokeWidth = 12f
                })
                viewPoints.addAll(geoPoints)
            }
            if (currentLocation != null && currentLocation.first in -90.0..90.0 &&
                currentLocation.second in -180.0..180.0) {
                val here = GeoPoint(currentLocation.first, currentLocation.second)
                mapView.overlays.add(Marker(mapView).apply {
                    position = here
                    title = "La mia posizione"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
                if (geoPoints.size < 2) viewPoints.add(here)
            }
            waypoints.forEachIndexed { index, stop ->
                val via = GeoPoint(stop.latitude, stop.longitude)
                mapView.overlays.add(Marker(mapView).apply {
                    position = via
                    title = "Tappa ${index + 1}: ${stop.name}"
                    icon = waypointMarkerIcon(mapView, index + 1)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
                if (geoPoints.size < 2) viewPoints.add(via)
            }
            if (destinationLocation != null && destinationLocation.first in -90.0..90.0 &&
                destinationLocation.second in -180.0..180.0) {
                val target = GeoPoint(destinationLocation.first, destinationLocation.second)
                mapView.overlays.add(Marker(mapView).apply {
                    position = target
                    title = "🏁 Arrivo"
                    icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_finish_flag)
                    setAnchor(9f / 48f, 45f / 48f)
                })
                if (geoPoints.size < 2) viewPoints.add(target)
            }
            if (lastFitKey[0] != routeFitKey) {
                lastFitKey[0] = routeFitKey
                if (viewPoints.size >= 2) {
                    val bounds = BoundingBox.fromGeoPoints(viewPoints)
                    mapView.post {
                        // If a newer preview replaced this one before the UI
                        // callback runs, never refit an obsolete route.
                        if (lastFitKey[0] == routeFitKey) {
                            mapView.zoomToBoundingBox(bounds, true, 64)
                        }
                    }
                } else if (viewPoints.size == 1) {
                    mapView.controller.setCenter(viewPoints.first())
                    mapView.controller.setZoom(15.0)
                }
            }
            mapView.invalidate()
        },
        modifier = modifier
    )
}

@Composable
fun MainMapView(
    currentLocation: Pair<Double, Double>?,
    destinationLocation: Pair<Double, Double>?,
    centerTrigger: Int,
    nightMode: Boolean = true,
    onMapLongPress: (Double, Double) -> Unit,
    onMapViewReady: (MapView) -> Unit,
    modifier: Modifier = Modifier
) {
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    LaunchedEffect(centerTrigger) {
        if (centerTrigger > 0 && mapViewInstance != null && currentLocation != null) {
            val point = GeoPoint(currentLocation.first, currentLocation.second)
            mapViewInstance?.controller?.animateTo(point)
            mapViewInstance?.controller?.setZoom(15.0)
        }
    }

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                applyMapNightMode(this, nightMode)
                setMultiTouchControls(true)
                controller.setZoom(15.0)

                val startPoint = if (currentLocation != null && (currentLocation.first != 0.0 || currentLocation.second != 0.0)) {
                    GeoPoint(currentLocation.first, currentLocation.second)
                } else {
                    GeoPoint(41.9028, 12.4964) // Rome default
                }
                controller.setCenter(startPoint)

                val mapEventsReceiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let {
                            onMapLongPress(it.latitude, it.longitude)
                        }
                        return true
                    }
                }
                overlays.add(MapEventsOverlay(mapEventsReceiver))

                mapViewInstance = this
                onMapViewReady(this)
            }
        },
        update = { mapView ->
            applyMapNightMode(mapView, nightMode)
            mapViewInstance = mapView
            onMapViewReady(mapView)

            mapView.overlays.removeAll { it !is MapEventsOverlay }

            if (currentLocation != null && (currentLocation.first != 0.0 || currentLocation.second != 0.0)) {
                val locMarker = Marker(mapView).apply {
                    position = GeoPoint(currentLocation.first, currentLocation.second)
                    title = "La mia posizione"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(locMarker)
            }

            if (destinationLocation != null) {
                val destMarker = Marker(mapView).apply {
                    position = GeoPoint(destinationLocation.first, destinationLocation.second)
                    title = "🏁 Destinazione finale"
                    icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_finish_flag)
                    setAnchor(9f / 48f, 45f / 48f)
                }
                mapView.overlays.add(destMarker)
            }

            mapView.invalidate()
        },
        modifier = modifier
    )
}


fun extractGeoPoints(
    selectedRoute: RouteOption?,
    currentLocation: Pair<Double, Double>?,
    destination: PlaceSearchResult?
): List<GeoPoint> {
    val geoPoints = mutableListOf<GeoPoint>()

    if (selectedRoute != null) {
        val rawJson = selectedRoute.rawRouteJson
        val geomArray = rawJson.optJSONArray("geometry_gcj02")
            ?: rawJson.optJSONArray("polyline")
            ?: rawJson.optJSONArray("coordinates")

        if (geomArray != null) {
            for (i in 0 until geomArray.length()) {
                val item = geomArray.optJSONObject(i)
                if (item != null) {
                    val lat = item.optDouble("latitude_deg", item.optDouble("latitude", item.optDouble("lat", Double.NaN)))
                    val lon = item.optDouble("longitude_deg", item.optDouble("longitude", item.optDouble("lon", Double.NaN)))
                    if (!lat.isNaN() && !lon.isNaN()) {
                        geoPoints.add(GeoPoint(lat, lon))
                    }
                } else {
                    val coordArr = geomArray.optJSONArray(i)
                    if (coordArr != null && coordArr.length() >= 2) {
                        val lon = coordArr.optDouble(0, Double.NaN)
                        val lat = coordArr.optDouble(1, Double.NaN)
                        if (!lat.isNaN() && !lon.isNaN()) {
                            geoPoints.add(GeoPoint(lat, lon))
                        }
                    }
                }
            }
        }

        if (geoPoints.isEmpty()) {
            val stepsArr = rawJson.optJSONArray("steps") ?: rawJson.optJSONArray("maneuvers")
            if (stepsArr != null) {
                for (i in 0 until stepsArr.length()) {
                    val step = stepsArr.optJSONObject(i) ?: continue
                    val stepPoly = step.opt("polyline")
                    if (stepPoly is JSONArray) {
                        for (j in 0 until stepPoly.length()) {
                            val pt = stepPoly.optJSONObject(j) ?: continue
                            val lat = pt.optDouble("latitude_deg", pt.optDouble("latitude", pt.optDouble("lat", Double.NaN)))
                            val lon = pt.optDouble("longitude_deg", pt.optDouble("longitude", pt.optDouble("lon", Double.NaN)))
                            if (!lat.isNaN() && !lon.isNaN()) {
                                geoPoints.add(GeoPoint(lat, lon))
                            }
                        }
                    }
                }
            }
        }
    }

    // No synthetic straight line: a route exists only when Valhalla returned
    // road-following geometry. The map shows origin/destination markers separately.

    return geoPoints
}

@Composable
fun MapDownloadProgressBox(
    downloadStatus: String?,
    downloadProgress: Float,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = downloadStatus ?: "Download in corso...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${(downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { downloadProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancelDownload,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Annulla")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsDialog(
    savedMaps: List<SavedMapInfo>,
    photonInstalledBytes: Long,
    photonImporting: Boolean,
    photonStatus: String?,
    onImportPhoton: () -> Unit,
    onDeletePhoton: () -> Unit,
    valhallaInstalledBytes: Long,
    valhallaImporting: Boolean,
    valhallaStatus: String?,
    isNavigating: Boolean,
    onImportValhalla: () -> Unit,
    onCancelValhalla: () -> Unit,
    onDeleteValhalla: () -> Unit,
    onDeleteMap: (String) -> Unit,
    onImportOsmPack: () -> Unit,
    onImportUnifiedPack: () -> Unit,
    downloadStatus: String? = null,
    downloadProgress: Float = 0f,
    isDownloadingMap: Boolean = false,
    onCancelDownload: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var showAdvancedImports by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mappe offline") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("MotoPack Italia", style = MaterialTheme.typography.titleMedium)
                        Text("Un unico file .motopack installa MotoMap, Valhalla e Photon.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("MotoMap: " + if (savedMaps.isNotEmpty()) "installata" else "assente",
                            style = MaterialTheme.typography.labelMedium)
                        Text("Valhalla: " + if (valhallaInstalledBytes > 0L) "installato" else "assente",
                            style = MaterialTheme.typography.labelMedium)
                        Text("Photon: " + if (photonInstalledBytes > 0L) "installato" else "assente",
                            style = MaterialTheme.typography.labelMedium)
                        FilledTonalButton(
                            onClick = onImportUnifiedPack,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            enabled = !isDownloadingMap && !photonImporting && !valhallaImporting && !isNavigating
                        ) {
                            Text("Importa MotoPack (.motopack)")
                        }
                        if (isDownloadingMap) {
                            MapDownloadProgressBox(
                                downloadStatus = downloadStatus,
                                downloadProgress = downloadProgress,
                                onCancelDownload = onCancelDownload
                            )
                        } else if (!downloadStatus.isNullOrBlank()) {
                            Text(downloadStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { showAdvancedImports = !showAdvancedImports },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showAdvancedImports) "Nascondi opzioni avanzate"
                        else "Opzioni avanzate · componenti separati")
                }
                if (showAdvancedImports) {
                    OutlinedButton(onClick = onImportOsmPack,
                        enabled = !isDownloadingMap && !valhallaImporting && !isNavigating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Importa solo MotoMap ZIP")
                    }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Ricerca indirizzi offline — Photon",
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (photonInstalledBytes > 0L)
                                "Indice installato: " +
                                    String.format("%.1f MiB", photonInstalledBytes / 1048576.0)
                            else "Importa il file photon-italy.sqlite preparato sul PC.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!photonStatus.isNullOrBlank())
                            Text(photonStatus, style = MaterialTheme.typography.bodySmall)
                        Text("Dati © OpenStreetMap contributors — ODbL 1.0",
                            style = MaterialTheme.typography.labelSmall)
                        FilledTonalButton(
                            onClick = onImportPhoton,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isNavigating && !photonImporting
                        ) {
                            Text(if (photonImporting) "Importazione in corso..."
                                 else "Importa indice Photon SQLite")
                        }
                        if (photonInstalledBytes > 0L) {
                            OutlinedButton(
                                onClick = onDeletePhoton,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isNavigating && !photonImporting
                            ) { Text("Elimina indice Photon") }
                        }
                    }
                }

                ValhallaRoutingSection(
                    installedBytes = valhallaInstalledBytes,
                    isImporting = valhallaImporting,
                    status = valhallaStatus,
                    isNavigating = isNavigating,
                    onImport = onImportValhalla,
                    onCancelImport = onCancelValhalla,
                    onDelete = onDeleteValhalla
                )

                if (savedMaps.isNotEmpty()) {
                    Text("Mappe stradali salvate", style = MaterialTheme.typography.titleSmall)
                    savedMaps.forEach { mapInfo ->
                        val mbStr = String.format("%.2f MB", mapInfo.sizeBytes / (1024.0 * 1024.0))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mapInfo.fileName, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    (if (mapInfo.isPackage) "Pacchetto ZIP importato" else "File mappa singolo") +
                                        " · " + mbStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onDeleteMap(mapInfo.fileName) },
                                enabled = !isNavigating && !isDownloadingMap && !valhallaImporting
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Elimina mappa")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                } // Advanced per-component controls
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    onSearch: (String) -> Unit,
    onCircularTour: (Int, Int) -> Unit = { _, _ -> },
    onConnectRequest: () -> Unit,
    connectionState: String,
    coffeeTimeoutMinutes: Int = 10,
    onCoffeeTimeoutChange: (Int) -> Unit = {},
    isLoading: Boolean,
    searchResults: List<PlaceSearchResult>,
    routePreview: PlaceSearchResult?,
    routeOptions: List<RouteOption>,
    routeWaypoints: List<PlaceSearchResult> = emptyList(),
    pausedTour: TourSession? = null,
    onPauseTour: () -> Unit = {},
    nextWaypointToSkip: PlaceSearchResult? = null,
    onSkipNextWaypoint: () -> Unit = {},
    onPreviewResumeTour: () -> Unit = {},
    onDiscardPausedTour: () -> Unit = {},
    circularMandatoryStops: List<PlaceSearchResult> = emptyList(),
    savedCircularTours: List<SavedCircularTour> = emptyList(),
    onSaveCircularTour: (String) -> Unit = {},
    onOpenSavedCircularTour: (SavedCircularTour, Boolean) -> Unit = { _, _ -> },
    onDeleteSavedCircularTour: (String) -> Unit = {},
    onAddCircularMandatoryStop: (PlaceSearchResult) -> Unit = {},
    onRemoveCircularMandatoryStop: (Int) -> Unit = {},
    onMoveCircularMandatoryStop: (Int, Int) -> Unit = { _, _ -> },
    waypointSearchResults: List<PlaceSearchResult> = emptyList(),
    waypointSearching: Boolean = false,
    waypointSearchError: String? = null,
    onSearchWaypoint: (String) -> Unit = {},
    onAddWaypoint: (PlaceSearchResult) -> Unit = {},
    onRemoveWaypoint: (Int) -> Unit = {},
    onMoveWaypoint: (Int, Int) -> Unit = { _, _ -> },
    selectedRouteIndex: Int,
    roadDnaSummary: RoadDnaSummary? = null,
    roadDnaLoading: Boolean = false,
    roadDnaError: String? = null,
    onRoadDnaAnalyze: () -> Unit = {},
    routingSource: String? = null,
    routePreviewError: String? = null,
    routePreferences: RoutePreferences = RoutePreferences(),
    onRoutePreferencesChange: (RoutePreferences) -> Unit = {},
    isNavigating: Boolean = false,
    hasArrived: Boolean = false,
    currentLocation: Pair<Double, Double>? = null,
    savedMaps: List<SavedMapInfo> = emptyList(),
    photonInstalledBytes: Long = 0L,
    photonImporting: Boolean = false,
    photonStatus: String? = null,
    onImportPhoton: () -> Unit = {},
    onDeletePhoton: () -> Unit = {},
    valhallaInstalledBytes: Long = 0L,
    valhallaImporting: Boolean = false,
    valhallaStatus: String? = null,
    onImportValhalla: () -> Unit = {},
    onCancelValhalla: () -> Unit = {},
    onDeleteValhalla: () -> Unit = {},
    onRouteOptionSelect: (Int) -> Unit,
    onRouteSelected: (PlaceSearchResult) -> Unit,
    onStartNavigation: (PlaceSearchResult) -> Unit,
    onStopNavigation: () -> Unit = {},
    onBackToSearch: () -> Unit,
    onImportOsmPack: () -> Unit = {},
    onImportUnifiedPack: () -> Unit = {},
    onCorridorTileCount: () -> Int = { 0 },
    onDownloadCorridor: () -> Unit = {},
    onDeleteMap: (String) -> Unit = {},
    downloadStatus: String? = null,
    downloadProgress: Float = 0f,
    isDownloadingMap: Boolean = false,
    onCancelDownload: () -> Unit = {},
    onRefreshMaps: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
    replayRecording: Boolean = false,
    diagnosticMode: Int = 0,
    onUnifiedDiagnosticModeChange: (Int) -> Unit = {}
) {
    var showRoadDnaDialog by remember { mutableStateOf(false) }
    if (showRoadDnaDialog) {
        AlertDialog(
            onDismissRequest = { showRoadDnaDialog = false },
            title = { Text("Road DNA") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (roadDnaLoading) {
                        CircularProgressIndicator()
                        Text("Analisi offline della rotta Valhalla selezionata…")
                    } else if (roadDnaError != null) {
                        Text(roadDnaError, color = MaterialTheme.colorScheme.error)
                    } else if (roadDnaSummary != null) {
                        Text("Guida", style = MaterialTheme.typography.titleMedium)
                        Text("Curve: " + roadDnaSummary.curvature)
                        Text("Geometria: " + roadDnaSummary.routeVertices + " vertici Valhalla")
                        HorizontalDivider()
                        Text("Da scoprire", style = MaterialTheme.typography.titleMedium)
                        if (!roadDnaSummary.indexAvailable) {
                            Text("Indice turistico Road DNA non installato. " +
                                "Importa il nuovo MotoPack v2.")
                        } else {
                            Text("Candidati per categoria nel corridoio indicativo di 2 km:")
                            for ((category, count) in roadDnaSummary.countByCategory
                                .entries.sortedByDescending { it.value }.take(12)) {
                                Text(category + ": " + count)
                            }
                            if (roadDnaSummary.countByCategory.isEmpty())
                                Text("Nessun luogo classificato nel corridoio.")
                            if (roadDnaSummary.examples.isNotEmpty()) {
                                HorizontalDivider()
                                Text("Alcuni luoghi vicini",
                                    style = MaterialTheme.typography.titleSmall)
                                for (p in roadDnaSummary.examples.take(12)) {
                                    Text("• " + p.name + " · " + p.category +
                                        (if (p.approximateArea)
                                            " (area, posizione indicativa)" else ""))
                                }
                            }
                            Text("I conteggi sono indicativi: le aree sono " +
                                "selezionate anche per ingombro geografico, " +
                                "non per visibilità dalla strada. " +
                                "Non rappresentano accessi stradali verificati.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("Seleziona un itinerario Valhalla da analizzare.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRoadDnaDialog = false }) { Text("Chiudi") }
            }
        )
    }
    var showCircularTourDialog by remember { mutableStateOf(false) }
    var circularDistanceKm by remember { mutableFloatStateOf(100f) }
    var circularOrientation by remember { mutableIntStateOf(0) }
    var showSaveCircularDialog by remember { mutableStateOf(false) }
    var savedTourName by remember { mutableStateOf("") }
    var showSavedToursDialog by remember { mutableStateOf(false) }
    if (showSaveCircularDialog) {
        AlertDialog(
            onDismissRequest = { showSaveCircularDialog = false },
            title = { Text("Salva tour per un altro giorno") },
            text = {
                OutlinedTextField(
                    value = savedTourName,
                    onValueChange = { savedTourName = it.take(80) },
                    singleLine = true,
                    label = { Text("Nome del tour") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveCircularTour(savedTourName.trim())
                    showSaveCircularDialog = false
                }, enabled = savedTourName.isNotBlank()) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveCircularDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }
    if (showSavedToursDialog) {
        AlertDialog(
            onDismissRequest = { showSavedToursDialog = false },
            title = { Text("I miei tour salvati") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (savedCircularTours.isEmpty()) {
                        Text("Nessun tour salvato.")
                    }
                    savedCircularTours.forEach { tour ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(tour.title, style = MaterialTheme.typography.titleSmall)
                            Text("${tour.desiredKm} km richiesti · " +
                                "${tour.mandatory.size} luoghi obbligatori · " +
                                "${tour.via.size} tappe",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TextButton(onClick = {
                                    showSavedToursDialog = false
                                    onOpenSavedCircularTour(tour, false)
                                }) { Text("Partenza salvata") }
                                TextButton(onClick = {
                                    showSavedToursDialog = false
                                    onOpenSavedCircularTour(tour, true)
                                }, enabled = currentLocation != null) {
                                    Text("Da qui")
                                }
                                IconButton(onClick = { onDeleteSavedCircularTour(tour.id) }) {
                                    Icon(Icons.Default.Delete,
                                        contentDescription = "Elimina ${tour.title}")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Text("La rotta viene ricalcolata da Valhalla all'apertura. " +
                        "Partenza salvata mostra il progetto originale; " +
                        "Da qui chiude il giro alla posizione GPS attuale.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showSavedToursDialog = false }) {
                    Text("Chiudi")
                }
            }
        )
    }
    var circularPoiQuery by remember { mutableStateOf("") }
    if (showCircularTourDialog) {
        AlertDialog(
            onDismissRequest = { showCircularTourDialog = false },
            title = { Text("Tour circolare con tappe") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Partenza e arrivo dalla posizione GPS. " +
                        "Le tappe scelte da te hanno sempre priorità.")
                    Text("Distanza indicativa: ${circularDistanceKm.toInt()} km")
                    Slider(
                        value = circularDistanceKm,
                        onValueChange = { circularDistanceKm = it },
                        valueRange = 30f..300f, steps = 26
                    )
                    Text("Direzione prevalente")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "O")
                            .forEach { (degrees, label) ->
                                FilterChip(
                                    selected = circularOrientation == degrees,
                                    onClick = { circularOrientation = degrees },
                                    label = { Text(label) }
                                )
                            }
                    }
                    Text("Luoghi da visitare (${circularMandatoryStops.size}/8)",
                        style = MaterialTheme.typography.titleSmall)
                    circularMandatoryStops.forEachIndexed { index, stop ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}. ${stop.name}",
                                modifier = Modifier.weight(1f), maxLines = 2)
                            IconButton(
                                onClick = { onMoveCircularMandatoryStop(index, -1) },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Sposta tappa su")
                            }
                            IconButton(
                                onClick = { onMoveCircularMandatoryStop(index, 1) },
                                enabled = index < circularMandatoryStops.lastIndex
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Sposta tappa giù")
                            }
                            IconButton(onClick = { onRemoveCircularMandatoryStop(index) }) {
                                Icon(Icons.Default.Delete,
                                    contentDescription = "Rimuovi tappa obbligatoria")
                            }
                        }
                    }
                    if (circularMandatoryStops.size < 8) {
                        OutlinedTextField(
                            value = circularPoiQuery,
                            onValueChange = { circularPoiQuery = it },
                            label = { Text("Cerca passo, lago, paese o coordinate") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { onSearchWaypoint(circularPoiQuery) },
                                    enabled = circularPoiQuery.isNotBlank()
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Cerca luogo")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { onSearchWaypoint(circularPoiQuery) })
                        )
                        if (waypointSearching) LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth())
                        if (waypointSearchError != null) Text(
                            waypointSearchError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        waypointSearchResults.take(5).forEach { place ->
                            ListItem(
                                headlineContent = { Text(place.name) },
                                supportingContent = {
                                    Text(listOf(place.displayArea.ifBlank { place.address },
                                        place.distanceLabel).filter { it.isNotBlank() }
                                        .joinToString(" · "), maxLines = 1)
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                },
                                modifier = Modifier.clickable {
                                    onAddCircularMandatoryStop(place)
                                    circularPoiQuery = ""
                                }
                            )
                        }
                    }
                    Text(
                        "Seleziona i luoghi nell'ordine di visita. " +
                            "Con una sola tappa si aggiunge un punto automatico; " +
                            "con più tappe non vengono aggiunte deviazioni artificiali. " +
                            "La lunghezza reale dipenderà da Valhalla.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCircularTourDialog = false
                    onCircularTour(circularDistanceKm.toInt(), circularOrientation)
                }, enabled = !isNavigating && !isLoading) {
                    Text("Calcola tour")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCircularTourDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var corridorConfirmation by remember { mutableStateOf<Int?>(null) }
    var corridorDestination by remember { mutableStateOf<PlaceSearchResult?>(null) }
    if (corridorConfirmation != null && corridorDestination != null) {
        val count = corridorConfirmation!!
        AlertDialog(
            onDismissRequest = { corridorConfirmation = null; corridorDestination = null },
            title = { Text("Prepara strade OpenFreeMap") },
            text = { Text(
                if (count < 0) "Corridoio troppo grande o geometria non disponibile. " +
                    "Puoi navigare senza scaricare: importa un pacchetto MotoMap ZIP per le strade."
                else "Scaricare ${count} tile mancanti del corridoio per uso offline? " +
                    "Limite: 3000 tile / 50 MB, una richiesta alla volta. " +
                    "Valhalla parte subito; le strade grigie saranno visibili al termine del download. " +
                    "Dati © OpenStreetMap contributors / OpenMapTiles / OpenFreeMap."
            ) },
            confirmButton = {
                TextButton(onClick = {
                    val destination = corridorDestination!!
                    corridorConfirmation = null
                    corridorDestination = null
                    onStartNavigation(destination)
                    if (count > 0) onDownloadCorridor()
                }) { Text(if (count > 0) "Avvia e scarica" else "Avvia navigazione") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val destination = corridorDestination!!
                    corridorConfirmation = null
                    corridorDestination = null
                    onStartNavigation(destination)
                }) { Text("Avvia senza download") }
            }
        )
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showCoffeeDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showRoutePreferencesDialog by remember { mutableStateOf(false) }
    var showWaypointsSheet by remember { mutableStateOf(false) }

    var centerOnLocationTrigger by remember { mutableStateOf(0) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }
    var longPressDestination by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val mapPreferences = remember(context) { context.getSharedPreferences("moto_chat_map_style", android.content.Context.MODE_PRIVATE) }
    var nightMapEnabled by remember { mutableStateOf(mapPreferences.getBoolean("night_map", true)) }


    LaunchedEffect(showOfflineDialog) {
        if (showOfflineDialog) {
            onRefreshMaps()
        }
    }

    if (routePreview != null && !isNavigating && showWaypointsSheet) {
        RouteWaypointsSheet(
            waypoints = routeWaypoints,
            searchResults = waypointSearchResults,
            searching = waypointSearching,
            error = waypointSearchError,
            onSearch = onSearchWaypoint,
            onAdd = onAddWaypoint,
            onRemove = onRemoveWaypoint,
            onMove = onMoveWaypoint,
            onDismiss = { showWaypointsSheet = false }
        )
    }

    if (routePreview != null && !isNavigating && showRoutePreferencesDialog) {
        RoutePreferencesDialog(
            preferences = routePreferences,
            onChange = onRoutePreferencesChange,
            onDismiss = { showRoutePreferencesDialog = false }
        )
    }

    if (showCoffeeDialog) {
        var draftMinutes by remember(showCoffeeDialog) {
            mutableFloatStateOf(coffeeTimeoutMinutes.toFloat())
        }
        AlertDialog(
            onDismissRequest = { showCoffeeDialog = false },
            title = { Text("☕ Pausa caffè") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Dopo 30 secondi senza Bluetooth lo schermo si spegne, " +
                            "ma il dispositivo resta raggiungibile. Se torni " +
                            "prima del limite, si riaccende e riprende la navigazione."
                    )
                    Text("Spegnimento dopo: ${draftMinutes.roundToInt()} minuti",
                        style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = draftMinutes,
                        onValueChange = { draftMinutes = it },
                        valueRange = 1f..30f,
                        steps = 28
                    )
                    Text(
                        "Predefinito: 10 minuti · Massimo: 30 minuti. " +
                            "Dopo lo spegnimento serve il pulsante fisico. " +
                            "L'arrivo mantiene lo spegnimento dopo 2 minuti.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCoffeeTimeoutChange(draftMinutes.roundToInt().coerceIn(1, 30))
                    showCoffeeDialog = false
                }) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { showCoffeeDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }

    if (showInfoDialog) {
        MotoChatInfoDialog(onDismiss = { showInfoDialog = false })
    }

    if (showDiagnosticsDialog) {
        var selectedDiagnosticLevel by remember { mutableIntStateOf(1) }
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Diagnostica completa") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Una sessione registra insieme il GPS Android e gli eventi ESP32 " +
                            "ricevuti via Bluetooth. Collega l'ESP32 prima di avviare.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        when {
                            replayRecording && diagnosticMode != 0 ->
                                "Registrazione attiva: GPS + ESP32 (" +
                                    (if (diagnosticMode == 2) "DEBUG" else "BASE") + ")"
                            replayRecording -> "GPS attivo · ESP32 disconnesso o log non disponibile"
                            else -> "Registrazione disattivata"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!replayRecording) {
                        Text("Dettaglio eventi ESP32", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((mode, label) in listOf(1 to "BASE", 2 to "DEBUG")) {
                                OutlinedButton(
                                    onClick = { selectedDiagnosticLevel = mode },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text((if (selectedDiagnosticLevel == mode) "✓ " else "") + label)
                                }
                            }
                        }
                        Text(
                            "Una nuova sessione sostituisce il precedente replay GPS non esportato.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            onUnifiedDiagnosticModeChange(
                                if (replayRecording) 0 else selectedDiagnosticLevel
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text(if (replayRecording) "Ferma diagnostica completa"
                            else "Avvia diagnostica completa")
                    }
                    OutlinedButton(
                        onClick = {
                            showDiagnosticsDialog = false
                            onExportDiagnostics()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = replayRecording || diagnosticMode == 0
                    ) {
                        Text("Ferma ed esporta tutto (ZIP)")
                    }
                    Text(
                        "Lo ZIP contiene log Android, eventi ESP32 e tutte le parti GPS " +
                            "JSONL, utilizzabili nel simulatore. Include coordinate e percorsi: " +
                            "condividilo solo volontariamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) {
                    Text("Chiudi")
                }
            }
        )
    }

    if (showOfflineDialog) {
        OfflineMapsDialog(
            savedMaps = savedMaps,
            photonInstalledBytes = photonInstalledBytes,
            photonImporting = photonImporting,
            photonStatus = photonStatus,
            onImportPhoton = onImportPhoton,
            onDeletePhoton = onDeletePhoton,
            valhallaInstalledBytes = valhallaInstalledBytes,
            valhallaImporting = valhallaImporting,
            valhallaStatus = valhallaStatus,
            isNavigating = isNavigating,
            onImportValhalla = onImportValhalla,
            onCancelValhalla = onCancelValhalla,
            onDeleteValhalla = onDeleteValhalla,
            onDeleteMap = onDeleteMap,
            onImportOsmPack = onImportOsmPack,
            onImportUnifiedPack = onImportUnifiedPack,
            downloadStatus = downloadStatus,
            downloadProgress = downloadProgress,
            isDownloadingMap = isDownloadingMap,
            onCancelDownload = onCancelDownload,
            onDismiss = { showOfflineDialog = false }
        )
    }

    Scaffold(
        topBar = {
            if (routePreview != null) {
                TopAppBar(
                    title = { Text("Percorso") },
                    navigationIcon = {
                        IconButton(onClick = onBackToSearch) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            nightMapEnabled = !nightMapEnabled
                            mapPreferences.edit().putBoolean("night_map", nightMapEnabled).apply()
                        }) {
                            Text(
                                text = if (nightMapEnabled) "☀" else "☾",
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics {
                                    contentDescription = if (nightMapEnabled) "Mostra mappa chiara" else "Mostra mappa notturna"
                                }
                            )
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Altre opzioni")
                            }
                            DropdownMenu(expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(text = { Text("☕ Pausa caffè") },
                                    onClick = { showMoreMenu = false; showCoffeeDialog = true })
                                DropdownMenuItem(text = { Text("Mappe offline") },
                                    onClick = { showMoreMenu = false; showOfflineDialog = true })
                                DropdownMenuItem(text = { Text("Diagnostica e replay GPS") },
                                    onClick = { showMoreMenu = false; showDiagnosticsDialog = true })
                                DropdownMenuItem(text = { Text("Esporta diagnostica") },
                                    onClick = { showMoreMenu = false; onExportDiagnostics() })
                                DropdownMenuItem(text = { Text("Informazioni e licenze") },
                                    onClick = { showMoreMenu = false; showInfoDialog = true })
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { MotoChatAppBarTitle() },
                    actions = {
                        IconButton(onClick = {
                            nightMapEnabled = !nightMapEnabled
                            mapPreferences.edit().putBoolean("night_map", nightMapEnabled).apply()
                        }) {
                            Text(
                                text = if (nightMapEnabled) "☀" else "☾",
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics {
                                    contentDescription = if (nightMapEnabled) "Mostra mappa chiara" else "Mostra mappa notturna"
                                }
                            )
                        }

                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Altre opzioni")
                            }
                            DropdownMenu(expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(text = { Text("☕ Pausa caffè") },
                                    onClick = { showMoreMenu = false; showCoffeeDialog = true })
                                DropdownMenuItem(text = { Text("Mappe offline") },
                                    onClick = { showMoreMenu = false; showOfflineDialog = true })
                                DropdownMenuItem(text = { Text("Diagnostica e replay GPS") },
                                    onClick = { showMoreMenu = false; showDiagnosticsDialog = true })
                                DropdownMenuItem(text = { Text("Esporta diagnostica") },
                                    onClick = { showMoreMenu = false; onExportDiagnostics() })
                                DropdownMenuItem(text = { Text("Informazioni e licenze") },
                                    onClick = { showMoreMenu = false; showInfoDialog = true })
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (routePreview != null) {
                val selectedRoute = routeOptions.getOrNull(selectedRouteIndex)
                val geoPoints = remember(selectedRoute, currentLocation, routePreview) {
                    extractGeoPoints(selectedRoute, currentLocation, routePreview)
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clipToBounds()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    RouteMapView(
                        nightMode = nightMapEnabled,
                        geoPoints = geoPoints,
                        routeFitKey = selectedRoute?.rawRouteJson?.optString("route_id")
                            ?: "preview-without-route",
                        currentLocation = currentLocation,
                        destinationLocation = Pair(routePreview.latitude, routePreview.longitude),
                        waypoints = routeWaypoints,
                        onWaypointLongPress = if (!isNavigating) { lat, lon ->
                            onAddWaypoint(PlaceSearchResult(
                                "Tappa sulla mappa",
                                String.format("Lat: %.4f, Lon: %.4f", lat, lon),
                                "Mappa", lat, lon))
                        } else null,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (hasArrived) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter)
                                .padding(top = 12.dp, start = 12.dp, end = 12.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 4.dp
                        ) {
                            Text(
                                "🏁 Destinazione raggiunta",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    }
                }

                HorizontalDivider()

                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isDownloadingMap) {
                        MapDownloadProgressBox(
                            downloadStatus = downloadStatus,
                            downloadProgress = downloadProgress,
                            onCancelDownload = onCancelDownload
                        )
                    }

                    if (routePreview.displayArea == "Tour circolare" && !isNavigating) {
                        OutlinedButton(
                            onClick = {
                                savedTourName = ""
                                showSaveCircularDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Salva questo tour")
                        }
                    }

                    OutlinedButton(
                        onClick = { showWaypointsSheet = true },
                        enabled = !isNavigating,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tappe intermedie (${routeWaypoints.size})")
                    }

                    OutlinedButton(
                        onClick = {
                            showRoadDnaDialog = true
                            onRoadDnaAnalyze()
                        },
                        enabled = !isNavigating && !isLoading &&
                            routeOptions.getOrNull(selectedRouteIndex) != null,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Road DNA · Guida e luoghi da scoprire")
                    }

                    OutlinedButton(
                        onClick = { showRoutePreferencesDialog = true },
                        enabled = !isNavigating,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Opzioni percorso")
                    }

                    Text(
                        text = routingSource ?: if (isLoading) "Calcolo percorso Valhalla"
                            else "Percorso Valhalla non disponibile",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )

                    if (isLoading) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (routeOptions.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            itemsIndexed(routeOptions) { index, option ->
                                val isSelected = index == selectedRouteIndex
                                val mins = option.durationSeconds / 60
                                val km = String.format("%.1f", option.distanceMeters / 1000.0)
                                ListItem(
                                    headlineContent = { Text(option.name) },
                                    supportingContent = { Text("$mins min · $km km") },
                                    trailingContent = {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selezionato",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable { onRouteOptionSelect(index) }
                                )
                                HorizontalDivider()
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                routePreviewError ?: if (valhallaInstalledBytes == 0L)
                                    "Nessun grafo Valhalla installato. Il pacchetto .motomap.zip mostra le strade sull'ESP32, ma non calcola itinerari."
                                else "Nessun percorso stradale calcolato. Controlla che la destinazione sia coperta dal grafo Valhalla.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "I marcatori sulla mappa indicano solo partenza e destinazione: non sono un itinerario.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (valhallaInstalledBytes == 0L) {
                                OutlinedButton(onClick = { showOfflineDialog = true }) {
                                    Text("Installa grafo Valhalla")
                                }
                            }
                            OutlinedButton(onClick = { onRouteSelected(routePreview) }) {
                                Text("Riprova calcolo percorso")
                            }
                        }
                    }

                    if (isNavigating) {
                        if (!hasArrived && nextWaypointToSkip != null) {
                            var confirmSkip by remember(nextWaypointToSkip) {
                                mutableStateOf(false)
                            }
                            OutlinedButton(
                                onClick = { confirmSkip = true },
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Salta prossima tappa")
                            }
                            if (confirmSkip) {
                                AlertDialog(
                                    onDismissRequest = { confirmSkip = false },
                                    title = { Text("Saltare questa tappa?") },
                                    text = {
                                        Text("Saltare \"${nextWaypointToSkip.name}\"? " +
                                            "Valhalla ricalcolerà il percorso verso la tappa " +
                                            "successiva o la destinazione finale. " +
                                            "Le altre tappe resteranno anche nei percorsi non circolari.")
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirmSkip = false
                                            onSkipNextWaypoint()
                                        }) { Text("Salta tappa") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { confirmSkip = false }) {
                                            Text("Annulla")
                                        }
                                    }
                                )
                            }
                        }
                        if (routePreview.displayArea == "Tour circolare" && !hasArrived) {
                            OutlinedButton(
                                onClick = onPauseTour,
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Pausa tour (riprendi più tardi)")
                            }
                        }
                        Button(
                            onClick = onStopNavigation,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .height(56.dp)
                        ) {
                            Text(if (hasArrived) "Concludi percorso"
                                else "Termina Navigazione",
                                style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Button(
                            onClick = {
                                val missingTiles = onCorridorTileCount()
                                if (missingTiles == 0) {
                                    // All route tiles cached: avoid a redundant dialog.
                                    corridorDestination = null
                                    corridorConfirmation = null
                                    onStartNavigation(routePreview!!)
                                } else {
                                    // Positive = download choice; negative = geometry/error warning.
                                    corridorDestination = routePreview
                                    corridorConfirmation = missingTiles
                                }
                            },
                            enabled = selectedRoute != null && geoPoints.size >= 2 && !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .height(56.dp)
                        ) {
                            Text("Avvia navigazione", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Cerca indirizzo o inserisci coordinate GPS") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            if (searchQuery.isNotBlank()) {
                                onSearch(searchQuery)
                            }
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Cerca")
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        if (searchQuery.isNotBlank()) {
                            onSearch(searchQuery)
                        }
                    })
                )

                if (pausedTour != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                if (pausedTour.paused) "Tour in pausa"
                                else "Tour interrotto: ripristino disponibile",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${pausedTour.completedStops}/${pausedTour.stops.size} tappe completate. " +
                                    "La rotta restante verrà ricalcolata con Valhalla.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = onPreviewResumeTour,
                                    enabled = currentLocation != null && !isLoading
                                ) {
                                    Text("Riprendi da qui")
                                }
                                TextButton(onClick = onDiscardPausedTour) {
                                    Text("Abbandona tour")
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCircularTourDialog = true },
                        enabled = !isLoading && !isNavigating && currentLocation != null,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp)
                    ) {
                        Text("Crea tour")
                    }
                    OutlinedButton(
                        onClick = { showSavedToursDialog = true },
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp)
                    ) {
                        Text("Tour salvati (${savedCircularTours.size})")
                    }
                }

                Text(
                    "Cerca una destinazione, crea un tour o tieni premuto sulla mappa.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                ListItem(
                    headlineContent = { Text("Il mio display") },
                    supportingContent = { Text(connectionState) },
                    leadingContent = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onConnectRequest() }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { centerOnLocationTrigger++ },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("La mia posizione", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = { showOfflineDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Mappe offline", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (isDownloadingMap) {
                    MapDownloadProgressBox(
                        downloadStatus = downloadStatus,
                        downloadProgress = downloadProgress,
                        onCancelDownload = onCancelDownload
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (isLoading) {
                    Box(modifier = Modifier.height(60.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (searchResults.isNotEmpty()) {
                    Text(
                        text = "Risultati di ricerca:",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth()) {
                        items(searchResults) { place ->
                            ListItem(
                                headlineContent = { Text(place.name) },
                                supportingContent = {
                                    val subtitle = listOf(place.displayArea, place.address,
                                        place.distanceLabel).filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                    Text(subtitle)
                                },
                                modifier = Modifier.clickable {
                                    onRouteSelected(place)
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clipToBounds()
                ) {
                    MainMapView(
                        nightMode = nightMapEnabled,
                        currentLocation = currentLocation,
                        destinationLocation = longPressDestination,
                        centerTrigger = centerOnLocationTrigger,
                        onMapLongPress = { lat, lon ->
                            longPressDestination = Pair(lat, lon)
                            val place = PlaceSearchResult(
                                name = "Punto sulla mappa",
                                address = String.format("Lat: %.4f, Lon: %.4f", lat, lon),
                                displayArea = "Mappa",
                                latitude = lat,
                                longitude = lon
                            )
                            onRouteSelected(place)
                        },
                        onMapViewReady = { mapView ->
                            activeMapView = mapView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
