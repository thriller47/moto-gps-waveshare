package com.motogps.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.motogps.android.viewmodel.RoadDnaPoi
import com.motogps.android.viewmodel.RoadDnaAreaAccess
import com.motogps.android.viewmodel.RoadDnaRoadsideCandidate
import com.motogps.android.viewmodel.RoadDnaOfflineFilter
import com.motogps.android.viewmodel.RoadDnaCategories
import com.motogps.android.viewmodel.RoadDnaOnlineResult
import com.motogps.android.viewmodel.NavigationViewModel.RoadDnaZoneFocus
import com.motogps.android.viewmodel.RoadDnaOnlinePoi
import com.motogps.android.viewmodel.RoadDnaOnline
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
    roadDnaPois: List<RoadDnaPoi> = emptyList(),
    onRoadDnaPoiClick: ((RoadDnaPoi) -> Unit)? = null,
    roadsideProposal: RoadDnaRoadsideCandidate? = null,
    areaAccesses: List<RoadDnaOnlinePoi> = emptyList(),
    onAreaAccessClick: ((RoadDnaOnlinePoi) -> Unit)? = null,
    onlineSuggestions: List<RoadDnaOnlinePoi> = emptyList(),
    onOnlineSuggestionClick: ((RoadDnaOnlinePoi) -> Unit)? = null,
    onMapReady: ((MapView) -> Unit)? = null,
    zoneFocus: RoadDnaZoneFocus? = null,
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
            onMapReady?.invoke(mapView)
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
            // Draw the actual executed circle, not the GPS/route center.
            // This visual overlay does not modify map fit or routing.
            zoneFocus?.let { area ->
                val radiusMeters = area.radiusKm * 1000.0
                val latDelta = radiusMeters / 111195.0
                val lonDelta = radiusMeters /
                    (111195.0 * kotlin.math.cos(Math.toRadians(area.latitude))
                        .coerceAtLeast(0.02))
                val ring = (0..48).map { i ->
                    val angle = i * 2.0 * Math.PI / 48.0
                    GeoPoint(
                        (area.latitude + latDelta * kotlin.math.cos(angle))
                            .coerceIn(-90.0, 90.0),
                        (area.longitude + lonDelta * kotlin.math.sin(angle))
                            .coerceIn(-180.0, 180.0)
                    )
                }
                mapView.overlays.add(Polyline().apply {
                    setPoints(ring)
                    outlinePaint.color = android.graphics.Color.rgb(46, 151, 225)
                    outlinePaint.strokeWidth = 3.5f
                })
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
            // Road DNA markers are kept separate from the route and real
            // waypoints. A marker tap opens a preview; it never edits a route.
            roadDnaPois.take(60).forEach { poi ->
                if (poi.latitude.isFinite() && poi.longitude.isFinite() &&
                    poi.latitude in -90.0..90.0 && poi.longitude in -180.0..180.0) {
                    if (geoPoints.size < 2)
                        viewPoints.add(GeoPoint(poi.latitude, poi.longitude))
                    mapView.overlays.add(Marker(mapView).apply {
                        position = GeoPoint(poi.latitude, poi.longitude)
                        title = poi.name + if (poi.exactLocation) ""
                            else " (posizione indicativa)"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            onRoadDnaPoiClick?.invoke(poi)
                            true
                        }
                    })
                }
            }
            roadsideProposal?.let { candidate ->
                mapView.overlays.add(Marker(mapView).apply {
                    position = GeoPoint(candidate.latitude, candidate.longitude)
                    title = "Punto stradale proposto · Road DNA"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
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
            // Access markers are inserted last, above nearby area pins.
            // osmdroid dispatches marker taps from the top of the stack.
            areaAccesses.take(20).forEach { poi ->
                if (poi.latitude in -90.0..90.0 &&
                    poi.longitude in -180.0..180.0) {
                    mapView.overlays.add(Marker(mapView).apply {
                        position = GeoPoint(poi.latitude, poi.longitude)
                        title = poi.name + " · " + poi.category
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            onAreaAccessClick?.invoke(poi)
                            true
                        }
                    })
                }
            }
            // Online tourist suggestions are rendered as selectable pins.
            // Keep this layer separate from offline areas and access candidates.
            onlineSuggestions.take(60).forEach { poi ->
                if (poi.latitude.isFinite() && poi.longitude.isFinite() &&
                    poi.latitude in -90.0..90.0 && poi.longitude in -180.0..180.0) {
                    if (geoPoints.size < 2)
                        viewPoints.add(GeoPoint(poi.latitude, poi.longitude))
                    mapView.overlays.add(Marker(mapView).apply {
                        position = GeoPoint(poi.latitude, poi.longitude)
                        title = poi.name + " · " + poi.source
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            onOnlineSuggestionClick?.invoke(poi)
                            true
                        }
                    })
                }
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
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MotoChatColors.Outline),
        colors = CardDefaults.cardColors(
            containerColor = MotoChatColors.SurfaceRaised
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
    roadDnaInstalledBytes: Long = 0L,
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
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MotoChatColors.Surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text("MotoPack · Mappe offline", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MotoChatColors.SurfaceRaised
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("MOTOPACK V2 · ITALIA", style = MaterialTheme.typography.labelSmall,
                            color = MotoChatColors.Green)
                        Text("MotoPack v2: MotoMap, Valhalla, Photon e Road DNA.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("MotoMap: " + if (savedMaps.isNotEmpty()) "installata" else "assente",
                            style = MaterialTheme.typography.labelMedium)
                        Text("Valhalla: " + if (valhallaInstalledBytes > 0L) "installato" else "assente",
                            style = MaterialTheme.typography.labelMedium)
                        Text("Photon: " + if (photonInstalledBytes > 0L) "installato" else "assente",
                            style = MaterialTheme.typography.labelMedium)
                        Text("Road DNA: " + if (roadDnaInstalledBytes > 0L)
                            "installato (" + String.format("%.1f MiB",
                                roadDnaInstalledBytes / 1048576.0) + ")" else "assente",
                            style = MaterialTheme.typography.labelMedium)
                        Button(
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MotoChatColors.Outline),
                    colors = CardDefaults.cardColors(containerColor = MotoChatColors.SurfaceRaised)
                ) {
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
    roadDnaOnline: RoadDnaOnlineResult? = null,
    roadDnaOnlineLoading: Boolean = false,
    roadDnaOnlineError: String? = null,
    roadDnaDetails: String? = null,
    roadDnaDetailsLoading: Boolean = false,
    onRoadDnaOnlineSearch: (String) -> Unit = {},
    onRoadDnaDetails: (RoadDnaOnlinePoi) -> Unit = {},
    roadDnaNearbyCenter: Pair<Double, Double>? = null,
    roadDnaZoneFocus: RoadDnaZoneFocus? = null,
    roadDnaZone: RoadDnaOnlineResult? = null,
    roadDnaZoneOffline: List<RoadDnaPoi> = emptyList(),
    roadDnaZoneLoading: Boolean = false,
    roadDnaZoneError: String? = null,
    onRoadDnaZoneSearch: (Double, Double, Int, String) -> Unit = { _, _, _, _ -> },
    onRoadDnaZoneClear: () -> Unit = {},
    onRoadDnaNearbySearch: (Int, String) -> Unit = { _, _ -> },
    roadDnaAreaAccesses: List<RoadDnaOnlinePoi> = emptyList(),
    roadDnaAreaAccessLoading: Boolean = false,
    roadDnaAreaAccessError: String? = null,
    onRoadDnaAreaAccessSearch: (RoadDnaPoi) -> Unit = {},
    onRoadDnaAreaAccessClear: () -> Unit = {},
    geoapifyConfigured: Boolean = false,
    opentripmapConfigured: Boolean = false,
    roadDnaKeyStatus: String? = null,
    roadDnaKeyBusy: Boolean = false,
    onSaveRoadDnaKey: (String, String) -> Unit = { _, _ -> },
    onDeleteRoadDnaKey: (String) -> Unit = {},
    onTestRoadDnaKey: (String) -> Unit = {},
    routingSource: String? = null,
    routePreviewError: String? = null,
    routePreferences: RoutePreferences = RoutePreferences(),
    onRoutePreferencesChange: (RoutePreferences) -> Unit = {},
    isNavigating: Boolean = false,
    hasArrived: Boolean = false,
    currentLocation: Pair<Double, Double>? = null,
    savedMaps: List<SavedMapInfo> = emptyList(),
    photonInstalledBytes: Long = 0L,
    roadDnaInstalledBytes: Long = 0L,
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
    var showRoadDnaResults by remember { mutableStateOf(false) }
    var showAllRoadDnaPlaces by remember { mutableStateOf(false) }
    var selectedRoadDnaCategory by remember { mutableStateOf("consigliati") }
    var offlineRoadDnaCategory by remember { mutableStateOf("consigliati") }
    var showRoadDnaMap by remember { mutableStateOf(false) }
    var showOnlineSuggestionsMap by remember { mutableStateOf(false) }
    var showRoadDnaSearchParams by remember { mutableStateOf(false) }
    var showRoadDnaCenterPicker by remember { mutableStateOf(false) }
    var zonePickerMapRef by remember { mutableStateOf<MapView?>(null) }
    var zonePickerFitId by remember { mutableIntStateOf(0) }
    var zonePickedCenter by remember {
        mutableStateOf<Pair<Double, Double>?>(null)
    }
    var zoneMapMode by remember { mutableStateOf(false) }
    var nearbySearchMode by remember { mutableStateOf(false) }
    var zoneRadiusKm by remember { mutableIntStateOf(5) }
    var zoneCategoryId by remember { mutableStateOf("consigliati") }
    var zoneCategoryMenuExpanded by remember { mutableStateOf(false) }
    var zoneSearchStarted by remember { mutableStateOf(false) }
    // Increment ONLY when the user explicitly requests GPS recentering.
    // A map-centered search must keep the pan/zoom chosen by the user.
    var gpsMapRecenterId by remember { mutableIntStateOf(0) }
    var zoneMapRef by remember { mutableStateOf<MapView?>(null) }
    var selectedOnlineSuggestion by remember {
        mutableStateOf<RoadDnaOnlinePoi?>(null)
    }
    var selectedNearbyOffline by remember { mutableStateOf<RoadDnaPoi?>(null) }
    var onlineSuggestionsForMap by remember {
        mutableStateOf<List<RoadDnaOnlinePoi>>(emptyList())
    }
    var selectedOfflineRoadDnaPoi by remember { mutableStateOf<RoadDnaPoi?>(null) }
    var selectedOnlineRoadDnaAccess by remember {
        mutableStateOf<RoadDnaOnlinePoi?>(null)
    }
    var proposedRoadsideStop by remember {
        mutableStateOf<RoadDnaRoadsideCandidate?>(null)
    }
    var selectedRoadDnaPoi by remember { mutableStateOf<RoadDnaOnlinePoi?>(null) }
    val roadDnaListScroll = rememberScrollState()
    val roadDnaResultsScroll = rememberLazyListState()
    LaunchedEffect(roadDnaOnline) {
        if (roadDnaOnline != null) showAllRoadDnaPlaces = false
    }
    LaunchedEffect(roadDnaOnline, showAllRoadDnaPlaces) {
        roadDnaResultsScroll.scrollToItem(0)
    }
    if (showRoadDnaDialog && selectedRoadDnaPoi != null) {
        val poi = selectedRoadDnaPoi!!
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
            onDismissRequest = { selectedRoadDnaPoi = null },
            title = { Text("Scheda ${poi.source}") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(poi.name, style = MaterialTheme.typography.titleMedium)
                    Text("Risultato da ${poi.source}",
                        style = MaterialTheme.typography.bodySmall)
                    when {
                        roadDnaDetailsLoading -> {
                            CircularProgressIndicator()
                            Text("Caricamento dettagli ${poi.source}…")
                        }
                        roadDnaDetails != null -> Text(roadDnaDetails)
                        else -> Text("Preparazione della scheda…")
                    }
                }
            },
            // Closing a place detail never dismisses or clears the parent
            // results list. Back/outside tap has the same behavior.
            confirmButton = {
                TextButton(onClick = { selectedRoadDnaPoi = null }) {
                    Text("Torna ai risultati")
                }
            }
        )
    }
    // Match the large, isolated Road DNA nearby-map presentation.
    if (showRoadDnaMap && roadDnaSummary != null && routePreview != null &&
        !isNavigating) {
        val selectedOption = routeOptions.getOrNull(selectedRouteIndex)
        val places = RoadDnaOfflineFilter.select(
            roadDnaSummary.places, offlineRoadDnaCategory)
        val previewGeometry = selectedOption?.let {
            com.motogps.android.viewmodel.RoadDnaAnalysis.geometry(it.rawRouteJson)
        } ?: emptyList()
        Dialog(
            onDismissRequest = {
                showRoadDnaMap = false
                selectedOfflineRoadDnaPoi = null
                selectedOnlineRoadDnaAccess = null
                proposedRoadsideStop = null
                onRoadDnaAreaAccessClear()
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.88f),
                shape = RoundedCornerShape(24.dp),
                color = MotoChatColors.Surface,
                border = BorderStroke(1.dp, MotoChatColors.Outline),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Road DNA · percorso",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Text("${places.size} luoghi · " +
                        RoadDnaCategories.get(offlineRoadDnaCategory).title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .padding(horizontal = 10.dp),
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 2.dp
                    ) {
                        Box(modifier = Modifier.padding(7.dp)
                            .clip(RoundedCornerShape(10.dp)).clipToBounds()) {
                    RouteMapView(
                        geoPoints = extractGeoPoints(selectedOption,
                            currentLocation, routePreview),
                        routeFitKey = "dna:" + (selectedOption?.id ?: ""),
                        nightMode = true,
                        waypoints = routeWaypoints,
                        // While viewing online access candidates, hide
                        // overlapping offline area pins: an area pin must never
                        // intercept an access tap and clear the Geoapify list.
                        roadDnaPois = if (roadDnaAreaAccesses.isNotEmpty())
                            emptyList() else places,
                        roadsideProposal = proposedRoadsideStop,
                        areaAccesses = roadDnaAreaAccesses,
                        onAreaAccessClick = {
                            // Do not dismiss the area or clear Geoapify results
                            // when selecting one of its online access pins.
                            selectedOnlineRoadDnaAccess = it
                            proposedRoadsideStop = null
                        },
                        onRoadDnaPoiClick = {
                            selectedOfflineRoadDnaPoi = it
                            selectedOnlineRoadDnaAccess = null
                            proposedRoadsideStop = null
                            onRoadDnaAreaAccessClear()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 210.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selectedOfflineRoadDnaPoi == null &&
                            selectedOnlineRoadDnaAccess == null) {
                            Text("Tocca un segnalino per vedere il luogo. " +
                                "Le aree OSM non sono ingressi verificati.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (selectedOnlineRoadDnaAccess != null &&
                            selectedOfflineRoadDnaPoi != null) {
                            TextButton(onClick = {
                                selectedOnlineRoadDnaAccess = null
                            }) { Text("Torna all'area selezionata") }
                        }
                    selectedOnlineRoadDnaAccess?.let { access ->
                        HorizontalDivider()
                        Text("Punto online selezionato: " + access.name +
                            " · " + access.category)
                        Text("Geoapify: posizione indicativa del servizio. " +
                            "Non garantisce ingresso, parcheggio moto né " +
                            "percorso pedonale fino alla destinazione.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            onAddWaypoint(PlaceSearchResult(
                                name = access.name,
                                address = access.category +
                                    " · accesso al luogo non verificato",
                                displayArea = "Road DNA Geoapify",
                                latitude = access.latitude,
                                longitude = access.longitude
                            ))
                            showRoadDnaMap = false
                            selectedOnlineRoadDnaAccess = null
                            onRoadDnaAreaAccessClear()
                        }) { Text("Aggiungi punto online come tappa") }
                    }
                    selectedOfflineRoadDnaPoi?.takeIf {
                        selectedOnlineRoadDnaAccess == null
                    }?.let { poi ->
                        Text(poi.name + " · " + poi.category)
                        if (poi.exactLocation) {
                            Text("Posizione OSM puntuale. Valhalla deve poter " +
                                "calcolare una rotta raggiungibile.",
                                style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                onAddWaypoint(PlaceSearchResult(
                                    name = poi.name,
                                    address = poi.category,
                                    displayArea = "Road DNA offline",
                                    latitude = poi.latitude,
                                    longitude = poi.longitude
                                ))
                                showRoadDnaMap = false
                            }) { Text("Aggiungi come tappa") }
                        } else {
                            OutlinedButton(
                                enabled = geoapifyConfigured &&
                                    !roadDnaAreaAccessLoading,
                                onClick = {
                                    selectedOnlineRoadDnaAccess = null
                                    onRoadDnaAreaAccessSearch(poi)
                                }
                            ) { Text("Cerca accessi online") }
                            if (!geoapifyConfigured) {
                                Text("Per cercare parcheggi e punti informazioni " +
                                    "configura Geoapify in API Road DNA.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (roadDnaAreaAccessLoading) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth())
                            }
                            if (roadDnaAreaAccessError != null)
                                Text(roadDnaAreaAccessError,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            if (roadDnaAreaAccesses.isNotEmpty()) {
                                TextButton(onClick = {
                                    selectedOnlineRoadDnaAccess = null
                                    onRoadDnaAreaAccessClear()
                                }) { Text("Torna ai luoghi Road DNA") }
                                Text("Risultati Geoapify entro 2,5 km dal " +
                                    "punto rappresentativo dell'area: parcheggi " +
                                    "e informazioni, non ingressi verificati.",
                                    style = MaterialTheme.typography.bodySmall)
                                roadDnaAreaAccesses.take(6).forEach { access ->
                                    TextButton(onClick = {
                                        selectedOnlineRoadDnaAccess = access
                                    }) {
                                        Text(access.name + " · " + access.category +
                                            " · " + access.distanceMeters + " m")
                                    }
                                }
                            }
                            val candidate = RoadDnaAreaAccess.candidate(
                                poi, previewGeometry)
                            if (candidate == null) {
                                Text("Nessun punto sulla rotta sufficientemente " +
                                    "vicino all'area. Per raggiungerla, scegli " +
                                    "manualmente una strada o un ingresso sulla mappa.",
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("Possibile sosta su strada motociclistica, " +
                                    "esterna all'area OSM e a circa " +
                                    "${candidate.distanceToAreaMeters} m dal suo " +
                                    "rettangolo geografico. NON è un parcheggio " +
                                    "né un ingresso verificato: l'eventuale " +
                                    "percorso a piedi non è calcolato.",
                                    style = MaterialTheme.typography.bodySmall)
                                if (proposedRoadsideStop != candidate) {
                                    OutlinedButton(onClick = {
                                        proposedRoadsideStop = candidate
                                    }) { Text("Mostra punto stradale proposto") }
                                } else {
                                    Text("Sosta stradale evidenziata sulla mappa. " +
                                        "Controlla accessibilità, sicurezza della fermata " +
                                        "e percorso pedonale prima di confermare.",
                                        style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = {
                                        onAddWaypoint(PlaceSearchResult(
                                            name = poi.name + " · sosta stradale",
                                            address = "Sosta stradale; accesso pedonale non verificato",
                                            displayArea = "Road DNA · punto sulla rotta",
                                            latitude = candidate.latitude,
                                            longitude = candidate.longitude
                                        ))
                                        showRoadDnaMap = false
                                        proposedRoadsideStop = null
                                    }) { Text("Conferma tappa stradale") }
                                }
                            }
                        }
                    }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            showRoadDnaMap = false
                            showRoadDnaDialog = true
                            selectedOfflineRoadDnaPoi = null
                            selectedOnlineRoadDnaAccess = null
                            proposedRoadsideStop = null
                            onRoadDnaAreaAccessClear()
                        }) { Text("Torna a Road DNA") }
                    }
                }
            }
        }
    }

    // 1) Compact search panel: no vertical scrolling. The results map
    // action is pinned in AlertDialog's bottom bar.
    if (showRoadDnaSearchParams && !showRoadDnaCenterPicker && !isNavigating) {
        val zoneResultsReady = zoneSearchStarted && !roadDnaZoneLoading &&
            roadDnaZoneFocus != null &&
            roadDnaZoneFocus.radiusKm == zoneRadiusKm &&
            roadDnaZoneFocus.categoryId == zoneCategoryId &&
            (roadDnaZoneOffline.isNotEmpty() ||
                !roadDnaZone?.places.isNullOrEmpty())
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
            onDismissRequest = { showRoadDnaSearchParams = false },
            title = { Text("Road DNA", maxLines = 1) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Raggio: ${zoneRadiusKm} km",
                        style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = zoneRadiusKm.toFloat(),
                        onValueChange = { zoneRadiusKm = it.roundToInt().coerceIn(1, 20) },
                        valueRange = 1f..20f,
                        steps = 18
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { zoneCategoryMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Categoria: " +
                                RoadDnaCategories.get(zoneCategoryId).title + " ▾",
                                maxLines = 1)
                        }
                        DropdownMenu(
                            expanded = zoneCategoryMenuExpanded,
                            onDismissRequest = { zoneCategoryMenuExpanded = false }
                        ) {
                            RoadDnaCategories.options.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.title) },
                                    onClick = {
                                        zoneCategoryId = category.id
                                        zoneCategoryMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            enabled = !roadDnaZoneLoading,
                            onClick = {
                                nearbySearchMode = true
                                zoneMapMode = true
                                zoneSearchStarted = true
                                selectedNearbyOffline = null
                                selectedOnlineSuggestion = null
                                selectedOnlineRoadDnaAccess = null
                                zonePickedCenter = null
                                gpsMapRecenterId++
                                onRoadDnaNearbySearch(zoneRadiusKm, zoneCategoryId)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Intorno a me", maxLines = 1) }
                        OutlinedButton(
                            enabled = !roadDnaZoneLoading,
                            onClick = {
                                zonePickerMapRef = null
                                zonePickerFitId++
                                showRoadDnaCenterPicker = true
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Scegli zona", maxLines = 1) }
                    }
                    if (zonePickedCenter != null) {
                        Text(
                            "Zona: " + String.format(java.util.Locale.ITALY,
                                "%.4f, %.4f",
                                zonePickedCenter!!.first, zonePickedCenter!!.second),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        TextButton(
                            enabled = !roadDnaZoneLoading,
                            onClick = {
                                val center = zonePickedCenter
                                if (center != null) {
                                    nearbySearchMode = false
                                    zoneMapMode = true
                                    zoneSearchStarted = true
                                    selectedNearbyOffline = null
                                    selectedOnlineSuggestion = null
                                    selectedOnlineRoadDnaAccess = null
                                    onRoadDnaZoneSearch(center.first, center.second,
                                        zoneRadiusKm, zoneCategoryId)
                                }
                            }
                        ) { Text("Riesegui nella zona scelta") }
                    }
                    if (roadDnaZoneLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Ricerca in corso…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (roadDnaZoneError != null) {
                        Text(roadDnaZoneError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2)
                    }
                    if (zoneSearchStarted && !roadDnaZoneLoading) {
                        Text("${roadDnaZoneOffline.size} offline · " +
                            "${roadDnaZone?.places?.size ?: 0} online",
                            style = MaterialTheme.typography.bodySmall)
                        if (roadDnaZoneOffline.isEmpty() &&
                            roadDnaZone?.places.isNullOrEmpty() &&
                            roadDnaZoneError == null) {
                            Text("Nessun luogo trovato: cambia categoria o raggio.",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2)
                        }
                    }
                    if (!geoapifyConfigured) {
                        Text("Geoapify non configurato · MotoPack offline",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1)
                    }
                    val focus = roadDnaZoneFocus
                    if (focus != null &&
                        (focus.radiusKm != zoneRadiusKm ||
                            focus.categoryId != zoneCategoryId)) {
                        Text("Parametri cambiati: avvia una nuova ricerca.",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1)
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showRoadDnaSearchParams = false }) {
                        Text("Chiudi")
                    }
                    Button(
                        enabled = zoneResultsReady,
                        onClick = {
                            selectedOnlineSuggestion = null
                            selectedNearbyOffline = null
                            selectedOnlineRoadDnaAccess = null
                            zoneMapMode = true
                            zoneMapRef = null
                            showRoadDnaSearchParams = false
                            showOnlineSuggestionsMap = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Mostra sulla mappa", maxLines = 1) }
                }
            }
        )
    }

    // 2) Picker map — no tourist suggestions or search parameter controls.
    // The map's actual center at confirmation time becomes the query center.
    if (showRoadDnaCenterPicker && !isNavigating) {
        val selectedOption = routeOptions.getOrNull(selectedRouteIndex)
        val pickerCenter = zonePickedCenter ?:
            roadDnaZoneFocus?.let { it.latitude to it.longitude } ?:
            roadDnaNearbyCenter
        Dialog(
            onDismissRequest = {
                showRoadDnaCenterPicker = false
                zonePickerMapRef = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.84f),
                shape = RoundedCornerShape(24.dp),
                color = MotoChatColors.Surface,
                border = BorderStroke(1.dp, MotoChatColors.Outline),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Scegli il centro della ricerca",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp))
                    Text("Sposta e ingrandisci la mappa. La ricerca partirà " +
                        "dal punto al centro dello schermo.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 18.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .padding(horizontal = 10.dp),
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 2.dp
                    ) {
                        Box(modifier = Modifier.padding(6.dp)
                            .clip(RoundedCornerShape(10.dp)).clipToBounds()) {
                            RouteMapView(
                                geoPoints = if (routePreview == null ||
                                    pickerCenter != null) emptyList()
                                    else extractGeoPoints(selectedOption,
                                        currentLocation, routePreview),
                                routeFitKey = "dna-center-picker:" +
                                    (selectedOption?.id ?: "gps") +
                                    ":session:" + zonePickerFitId,
                                nightMode = true,
                                currentLocation = pickerCenter,
                                onMapReady = { zonePickerMapRef = it },
                                modifier = Modifier.fillMaxSize()
                            )
                            Text("+", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.Center))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            showRoadDnaCenterPicker = false
                            zonePickerMapRef = null
                        }) { Text("Indietro") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = zonePickerMapRef != null &&
                                !roadDnaZoneLoading,
                            onClick = {
                                val center = zonePickerMapRef?.mapCenter
                                if (center != null) {
                                    zonePickedCenter = center.latitude to center.longitude
                                    nearbySearchMode = false
                                    zoneMapMode = true
                                    zoneSearchStarted = true
                                    selectedNearbyOffline = null
                                    selectedOnlineSuggestion = null
                                    selectedOnlineRoadDnaAccess = null
                                    showRoadDnaCenterPicker = false
                                    zonePickerMapRef = null
                                    onRoadDnaZoneSearch(center.latitude, center.longitude,
                                        zoneRadiusKm, zoneCategoryId)
                                }
                            }
                        ) { Text("Cerca qui") }
                    }
                }
            }
        }
    }

    // 3) Results map — large independent map, POI information and waypoint action only.
    if (showOnlineSuggestionsMap && !isNavigating) {
        val selectedOption = routeOptions.getOrNull(selectedRouteIndex)
        val shownSuggestions = if (zoneMapMode) roadDnaZone?.places.orEmpty()
            else onlineSuggestionsForMap
        val shownOffline = if (zoneMapMode) roadDnaZoneOffline else emptyList()
        LaunchedEffect(roadDnaZoneFocus, zoneMapRef, zoneMapMode) {
            val focus = roadDnaZoneFocus
            val map = zoneMapRef
            if (zoneMapMode && focus != null && map != null) {
                val deltaLat = focus.radiusKm * 1000.0 / 111195.0
                val deltaLon = focus.radiusKm * 1000.0 /
                    (111195.0 * kotlin.math.cos(Math.toRadians(focus.latitude))
                        .coerceAtLeast(0.02))
                val bounds = BoundingBox.fromGeoPoints(listOf(
                    GeoPoint((focus.latitude + deltaLat).coerceAtMost(90.0),
                        (focus.longitude - deltaLon).coerceAtLeast(-180.0)),
                    GeoPoint((focus.latitude - deltaLat).coerceAtLeast(-90.0),
                        (focus.longitude + deltaLon).coerceAtMost(180.0))
                ))
                map.post {
                    if (zoneMapRef === map) map.zoomToBoundingBox(bounds, true, 28)
                }
            }
        }
        Dialog(
            onDismissRequest = {
                zoneMapRef = null
                showOnlineSuggestionsMap = false
                if (zoneMapMode) showRoadDnaSearchParams = true
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.88f),
                shape = RoundedCornerShape(24.dp),
                color = MotoChatColors.Surface,
                border = BorderStroke(1.dp, MotoChatColors.Outline),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (zoneMapMode) "Road DNA · luoghi nella zona"
                        else "Road DNA · suggerimenti lungo il percorso",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Text(if (zoneMapMode)
                        "${shownOffline.size} offline · ${shownSuggestions.size} online"
                    else "${shownSuggestions.size} luoghi suggeriti",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (routePreview != null || roadDnaNearbyCenter != null ||
                        (zoneMapMode && roadDnaZoneFocus != null)) {
                        Surface(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            shape = RoundedCornerShape(14.dp),
                            tonalElevation = 2.dp
                        ) {
                            Box(modifier = Modifier.padding(7.dp)
                                .clip(RoundedCornerShape(10.dp)).clipToBounds()) {
                        RouteMapView(
                        geoPoints = if (nearbySearchMode || routePreview == null)
                            emptyList()
                        else extractGeoPoints(selectedOption,
                            currentLocation, routePreview),
                        // Constant across map searches and provider responses.
                        // Only a new GPS-centered request may recenter the map.
                        routeFitKey = "dna-explore:" +
                            (selectedOption?.id ?: "no-route") +
                            ":gps-request:" + gpsMapRecenterId,
                        nightMode = true,
                        currentLocation = if (nearbySearchMode || routePreview == null)
                            roadDnaNearbyCenter else null,
                        waypoints = routeWaypoints,
                        roadDnaPois = if (roadDnaAreaAccesses.isNotEmpty())
                            emptyList() else shownOffline,
                        onRoadDnaPoiClick = {
                            selectedNearbyOffline = it
                            selectedOnlineSuggestion = null
                        },
                        areaAccesses = roadDnaAreaAccesses,
                        onAreaAccessClick = {
                            selectedOnlineRoadDnaAccess = it
                            selectedNearbyOffline = null
                            selectedOnlineSuggestion = null
                        },
                        onlineSuggestions = shownSuggestions,
                        onOnlineSuggestionClick = {
                            selectedOnlineSuggestion = it
                            selectedNearbyOffline = null
                        },
                        onMapReady = { zoneMapRef = it },
                        zoneFocus = if (zoneMapMode) roadDnaZoneFocus else null,
                        modifier = Modifier.fillMaxSize()
                        )
                            }
                        }
                    }

                    }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 176.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selectedOnlineRoadDnaAccess == null &&
                            selectedNearbyOffline == null &&
                            selectedOnlineSuggestion == null) {
                            Text("Tocca un segnalino per vedere il luogo " +
                                "e scegliere una destinazione o una tappa.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    selectedOnlineRoadDnaAccess?.let { access ->
                        HorizontalDivider()
                        Text(access.name + " · " + access.category)
                        Button(onClick = {
                            val place = PlaceSearchResult(
                                name = access.name,
                                address = access.category + " · Geoapify",
                                displayArea = "Road DNA · punto d'accesso indicativo",
                                latitude = access.latitude,
                                longitude = access.longitude
                            )
                            if (routePreview == null) onRouteSelected(place)
                            else onAddWaypoint(place)
                            showOnlineSuggestionsMap = false
                            selectedOnlineRoadDnaAccess = null
                            onRoadDnaAreaAccessClear()
                            onRoadDnaZoneClear()
                        }) { Text(if (routePreview == null)
                            "Imposta come destinazione" else "Aggiungi come tappa") }
                    }
                    if (roadDnaAreaAccessLoading)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (roadDnaAreaAccessError != null)
                        Text(roadDnaAreaAccessError,
                            style = MaterialTheme.typography.bodySmall)
                    selectedNearbyOffline?.let { poi ->
                        HorizontalDivider()
                        Text(poi.name + " · " + poi.category,
                            style = MaterialTheme.typography.titleSmall)
                        Text("MotoPack offline · circa ${poi.distanceMeters} m " +
                            "dal centro della ricerca.",
                            style = MaterialTheme.typography.bodySmall)
                        if (poi.exactLocation) {
                            Text("Il punto OSM non garantisce un accesso stradale; " +
                                "Valhalla verificherà il percorso.",
                                style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                val place = PlaceSearchResult(
                                    name = poi.name,
                                    address = poi.category + " · MotoPack",
                                    displayArea = "Road DNA offline",
                                    latitude = poi.latitude,
                                    longitude = poi.longitude
                                )
                                if (routePreview == null) onRouteSelected(place)
                                else onAddWaypoint(place)
                                showOnlineSuggestionsMap = false
                                selectedNearbyOffline = null
                                showRoadDnaDialog = false
                                showRoadDnaResults = false
                                onRoadDnaZoneClear()
                            }) { Text(if (routePreview == null)
                                "Imposta come destinazione" else "Aggiungi come tappa") }
                        } else {
                            Text("Area OSM: il punto è rappresentativo, NON " +
                                "un ingresso o un parcheggio verificato. " +
                                "Non si può usare direttamente come destinazione.",
                                style = MaterialTheme.typography.bodySmall)
                            if (geoapifyConfigured) {
                                OutlinedButton(onClick = {
                                    onRoadDnaAreaAccessSearch(poi)
                                }) { Text("Cerca parcheggi e punti informativi online") }
                            }
                        }
                    }
                    selectedOnlineSuggestion?.let { poi ->
                        HorizontalDivider()
                        Text(poi.name, style = MaterialTheme.typography.titleSmall)
                        Text("${poi.source} · ${poi.category} · circa " +
                            "${poi.distanceMeters} m " +
                            if (zoneMapMode) "dal centro della zona"
                            else "dalla rotta",
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (routePreview == null)
                            "Posizione online indicativa: verifica l'accesso " +
                                "stradale. Il punto scelto diventerà una destinazione " +
                                "e Valhalla calcolerà il percorso."
                            else "Posizione online indicativa: verifica " +
                                "l'accesso motociclistico prima di aggiungere la tappa.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            val place = PlaceSearchResult(
                                name = poi.name,
                                address = poi.category + " · " + poi.source,
                                displayArea = "Road DNA online",
                                latitude = poi.latitude,
                                longitude = poi.longitude
                            )
                            if (routePreview == null) onRouteSelected(place)
                            else onAddWaypoint(place)
                            showOnlineSuggestionsMap = false
                            selectedOnlineSuggestion = null
                            showRoadDnaResults = false
                            showRoadDnaDialog = false
                            onRoadDnaZoneClear()
                        }) { Text(if (routePreview == null)
                            "Imposta come destinazione" else "Aggiungi come tappa") }
                    }

                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (zoneMapMode) {
                            TextButton(onClick = {
                                zoneMapRef = null
                                showOnlineSuggestionsMap = false
                                showRoadDnaSearchParams = true
                            }) { Text("Nuova ricerca") }
                        } else {
                            TextButton(onClick = {
                                zoneMapRef = null
                                showOnlineSuggestionsMap = false
                            }) { Text("Torna ai risultati") }
                        }
                    }
                }
            }
        }
    }

    // One dialog at a time: overview -> results -> selected-place details.
    if (showRoadDnaDialog && showRoadDnaResults && selectedRoadDnaPoi == null &&
        !showOnlineSuggestionsMap) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
            onDismissRequest = { showRoadDnaResults = false },
            title = { Text("Luoghi lungo il percorso") },
            text = {
                when {
                    roadDnaOnlineLoading -> Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Ricerca turistica in corso…")
                    }
                    roadDnaOnlineError != null ->
                        Text(roadDnaOnlineError, color = MaterialTheme.colorScheme.error)
                    roadDnaOnline == null -> Text("Preparazione dei risultati…")
                    else -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val places = roadDnaOnline.places
                        val recommended = RoadDnaOnline.recommendedPlaces(places)
                        val scopedCategory = roadDnaOnline.categoryId != "consigliati"
                        val visiblePlaces = if (scopedCategory || showAllRoadDnaPlaces)
                            places else recommended.take(15)
                        val categoryTitle = RoadDnaCategories.get(roadDnaOnline.categoryId).title
                        Text("${places.size} luoghi · ${categoryTitle}",
                            style = MaterialTheme.typography.titleSmall)
                        if (!scopedCategory) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !showAllRoadDnaPlaces,
                                    onClick = { showAllRoadDnaPlaces = false },
                                    label = { Text("Consigliati") }
                                )
                                FilterChip(
                                    selected = showAllRoadDnaPlaces,
                                    onClick = { showAllRoadDnaPlaces = true },
                                    label = { Text("Tutti") }
                                )
                            }
                            Text(
                                if (showAllRoadDnaPlaces)
                                    "Elenco completo, inclusi monumenti minori."
                                else "${recommended.size} di interesse per la moto; massimo 15.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text("Sono mostrati solo i luoghi della categoria scelta.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Geoapify ${places.count { it.source == "Geoapify" }}" +
                                " · OpenTripMap ${places.count { it.source == "OpenTripMap" }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(roadDnaOnline.message,
                            style = MaterialTheme.typography.bodySmall)
                        if (visiblePlaces.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    onlineSuggestionsForMap = visiblePlaces.take(60)
                                    selectedOnlineSuggestion = null
                                    selectedNearbyOffline = null
                                    selectedOnlineRoadDnaAccess = null
                                    onRoadDnaAreaAccessClear()
                                    zoneMapMode = false
                                    nearbySearchMode = false
                                    zoneSearchStarted = false
                                    zoneCategoryId = roadDnaOnline.categoryId
                                    zoneMapRef = null
                                    showRoadDnaSearchParams = false
                                    showRoadDnaCenterPicker = false
                                    showOnlineSuggestionsMap = true
                                },
                                enabled = !isNavigating
                            ) { Text("Mostra suggerimenti sulla mappa") }
                        }
                        if (places.isEmpty()) {
                            Text("Nessun luogo turistico trovato lungo il percorso.")
                        } else if (visiblePlaces.isEmpty()) {
                            Text(if (scopedCategory)
                                "Nessun luogo di questa categoria nel tratto esaminato."
                            else "Nessuna tappa particolarmente interessante. " +
                                "Tocca Tutti per consultare anche i luoghi minori.")
                        } else {
                            if (!showAllRoadDnaPlaces && recommended.size > 15) {
                                Text("Mostrati i 15 consigliati più rilevanti.",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            LazyColumn(
                                state = roadDnaResultsScroll,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(visiblePlaces) { index, poi ->
                                    Column {
                                        Text("${index + 1}. ${poi.name}",
                                            style = MaterialTheme.typography.titleSmall)
                                        Text("${poi.source} · ${poi.category} · " +
                                            "circa ${poi.distanceMeters} m dalla rotta",
                                            style = MaterialTheme.typography.bodySmall)
                                        val detailsAvailable = when (poi.source) {
                                            "Geoapify" ->
                                                geoapifyConfigured && poi.geoPlaceId != null
                                            "OpenTripMap" ->
                                                opentripmapConfigured && poi.xid != null
                                            else -> false
                                        }
                                        if (detailsAvailable) {
                                            TextButton(onClick = {
                                                selectedRoadDnaPoi = poi
                                                onRoadDnaDetails(poi)
                                            }) { Text("Dettagli ${poi.source}") }
                                        } else {
                                            Text("Scheda dettagli non disponibile",
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRoadDnaResults = false }) {
                    Text("Torna a Road DNA")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRoadDnaResults = false
                    showRoadDnaDialog = false
                }) { Text("Chiudi") }
            }
        )
    }
    if (showRoadDnaDialog && !showRoadDnaResults &&
        selectedRoadDnaPoi == null && !showOnlineSuggestionsMap) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
            onDismissRequest = { showRoadDnaDialog = false },
            title = { Text("Road DNA") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 430.dp).verticalScroll(roadDnaListScroll),
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
                        Text("Categoria offline", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RoadDnaCategories.options.forEach { category ->
                                FilterChip(
                                    selected = offlineRoadDnaCategory == category.id,
                                    onClick = { offlineRoadDnaCategory = category.id },
                                    label = { Text(category.title) }
                                )
                            }
                        }
                        if (!roadDnaSummary.indexAvailable) {
                            Text("Indice turistico Road DNA non installato. " +
                                "Importa il nuovo MotoPack v2.")
                        } else {
                            // Counts remain available for diagnostics, but the
                            // user-facing dialog opens directly on curated POIs.
                            val offlinePlaces = RoadDnaOfflineFilter.select(
                                roadDnaSummary.places, offlineRoadDnaCategory)
                            Text(
                                if (offlineRoadDnaCategory == "consigliati")
                                    "Consigliati lungo il percorso"
                                else "Luoghi della categoria selezionata",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text("${offlinePlaces.size} luoghi selezionati",
                                style = MaterialTheme.typography.bodySmall)
                            if (offlineRoadDnaCategory in listOf(
                                    "ristoranti", "hotel", "divertimenti")) {
                                Text("Questa categoria non è nel database turistico offline. " +
                                    "Usa Cerca online; ristoranti e hotel sono " +
                                    "nell'indice separato Photon.")
                            } else if (offlinePlaces.isEmpty()) {
                                Text("Nessun luogo della categoria individuato lungo questa rotta.")
                            } else {
                                // Keep the overview compact, just like nearby search:
                                // inspect individual POIs on the dedicated map.
                                OutlinedButton(onClick = {
                                    selectedOfflineRoadDnaPoi = null
                                    selectedOnlineRoadDnaAccess = null
                                    proposedRoadsideStop = null
                                    onRoadDnaAreaAccessClear()
                                    showRoadDnaMap = true
                                    showRoadDnaDialog = false
                                }, enabled = !isNavigating) {
                                    Text("Mostra luoghi sulla mappa")
                                }
                            }
                            Text("Le aree OSM hanno posizioni indicative: " +
                                "il loro accesso stradale non è verificato.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Dati © OpenStreetMap contributors · ODbL 1.0",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text("Seleziona un itinerario Valhalla da analizzare.")
                    }
                    HorizontalDivider()
                    Text("Ricerca turistica online", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        enabled = !isNavigating && !isLoading,
                        onClick = {
                            showRoadDnaDialog = false
                            showRoadDnaResults = false
                            zoneMapMode = true
                            nearbySearchMode = true
                            zoneSearchStarted = true
                            onlineSuggestionsForMap = emptyList()
                            selectedOnlineSuggestion = null
                            selectedNearbyOffline = null
                            selectedOnlineRoadDnaAccess = null
                            zoneMapRef = null
                            onRoadDnaZoneClear()
                            showOnlineSuggestionsMap = false
                            showRoadDnaCenterPicker = false
                            showRoadDnaSearchParams = true
                            zonePickedCenter = null
                            gpsMapRecenterId++
                            onRoadDnaNearbySearch(zoneRadiusKm, zoneCategoryId)
                        }
                    ) { Text("Cerca intorno a me · GPS") }
                    OutlinedButton(
                        enabled = !isNavigating,
                        onClick = {
                            showRoadDnaDialog = false
                            showRoadDnaResults = false
                            zoneMapMode = true
                            nearbySearchMode = false
                            zoneSearchStarted = false
                            zonePickedCenter = null
                            onlineSuggestionsForMap = emptyList()
                            selectedOnlineSuggestion = null
                            zoneMapRef = null
                            onRoadDnaZoneClear()
                            showOnlineSuggestionsMap = false
                            showRoadDnaSearchParams = true
                            zonePickerMapRef = null
                            zonePickerFitId++
                            showRoadDnaCenterPicker = true
                        }
                    ) { Text("Cerca in questa zona · mappa") }
                    Text("Solo premendo Cerca online: il percorso viene inviato a Geoapify " +
                        "per cercare luoghi entro 2 km. OpenTripMap riceve fino a 5 punti " +
                        "campione lungo la rotta. Distanze in linea d'aria, non deviazioni " +
                        "stradali; risultati non esaustivi.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Che cosa vuoi scoprire?",
                        style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoadDnaCategories.options.forEach { category ->
                            FilterChip(
                                selected = selectedRoadDnaCategory == category.id,
                                onClick = { selectedRoadDnaCategory = category.id },
                                label = { Text(category.title) }
                            )
                        }
                    }
                    val selectedCategoryRequiresGeo =
                        !RoadDnaCategories.usesOpenTripMap(selectedRoadDnaCategory)
                    if (selectedCategoryRequiresGeo && !geoapifyConfigured) {
                        Text("Questa categoria richiede la chiave Geoapify. " +
                            "Configurarla in ⋮ → API Road DNA.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!geoapifyConfigured && !opentripmapConfigured) {
                        Text("Nessuna chiave configurata. Apri ⋮ → API Road DNA.")
                    } else {
                        Button(
                            onClick = {
                                showAllRoadDnaPlaces = false
                                showRoadDnaResults = true
                                onRoadDnaOnlineSearch(selectedRoadDnaCategory)
                            },
                            enabled = !roadDnaOnlineLoading && !isNavigating &&
                                (!selectedCategoryRequiresGeo || geoapifyConfigured) &&
                                routeOptions.getOrNull(selectedRouteIndex) != null
                        ) { Text("Cerca online (su richiesta)") }
                    }
                    if (roadDnaOnlineLoading) {
                        CircularProgressIndicator()
                        Text("Ricerca online in corso…")
                    }
                    if (roadDnaOnlineError != null)
                        Text(roadDnaOnlineError, color = MaterialTheme.colorScheme.error)
                    if (roadDnaOnline != null) {
                        val count = roadDnaOnline.places.size
                        val previousCategory = RoadDnaCategories.get(roadDnaOnline.categoryId)
                        Text("${count} luoghi della ricerca ${previousCategory.title}.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { showRoadDnaResults = true }) {
                            Text("Apri risultati (${count})")
                        }
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
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
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
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
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
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
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
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
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
    var showRoadDnaKeysDialog by remember { mutableStateOf(false) }
    if (showRoadDnaKeysDialog) {
        var geoDraft by remember(showRoadDnaKeysDialog) { mutableStateOf("") }
        var tripDraft by remember(showRoadDnaKeysDialog) { mutableStateOf("") }
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
            onDismissRequest = { showRoadDnaKeysDialog = false },
            title = { Text("API Road DNA") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 490.dp)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chiavi personali, conservate cifrate sul dispositivo " +
                        "ed escluse dal backup. Non vengono incluse nei log.")
                    HorizontalDivider()
                    Text(if (geoapifyConfigured) "Geoapify · configurata"
                        else "Geoapify · non configurata")
                    OutlinedTextField(
                        value = geoDraft, onValueChange = { geoDraft = it },
                        label = { Text("Chiave Geoapify") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            onSaveRoadDnaKey("geoapify", geoDraft); geoDraft = ""
                        }, enabled = geoDraft.isNotBlank() && !roadDnaKeyBusy) {
                            Text("Salva")
                        }
                        TextButton(onClick = { onTestRoadDnaKey("geoapify") },
                            enabled = geoapifyConfigured && !roadDnaKeyBusy) { Text("Verifica") }
                        TextButton(onClick = { onDeleteRoadDnaKey("geoapify") },
                            enabled = geoapifyConfigured && !roadDnaKeyBusy) { Text("Elimina") }
                    }
                    HorizontalDivider()
                    Text(if (opentripmapConfigured) "OpenTripMap · configurata"
                        else "OpenTripMap · non configurata")
                    OutlinedTextField(
                        value = tripDraft, onValueChange = { tripDraft = it },
                        label = { Text("Chiave OpenTripMap") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            onSaveRoadDnaKey("opentripmap", tripDraft); tripDraft = ""
                        }, enabled = tripDraft.isNotBlank() && !roadDnaKeyBusy) {
                            Text("Salva")
                        }
                        TextButton(onClick = { onTestRoadDnaKey("opentripmap") },
                            enabled = opentripmapConfigured && !roadDnaKeyBusy) { Text("Verifica") }
                        TextButton(onClick = { onDeleteRoadDnaKey("opentripmap") },
                            enabled = opentripmapConfigured && !roadDnaKeyBusy) { Text("Elimina") }
                    }
                    if (roadDnaKeyBusy) CircularProgressIndicator()
                    if (roadDnaKeyStatus != null) Text(roadDnaKeyStatus)
                    Text("OpenTripMap richiede la chiave come parametro HTTPS; " +
                        "non viene mai stampata nell'app. Limita la quota delle chiavi " +
                        "nei rispettivi pannelli provider.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showRoadDnaKeysDialog = false }) {
                    Text("Chiudi")
                }
            }
        )
    }
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
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
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
                    Text("SCELTA RAPIDA CON GUANTI", style = MaterialTheme.typography.labelSmall,
                        color = MotoChatColors.Green)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(5, 10, 15, 30).forEach { minutes ->
                            FilterChip(
                                selected = draftMinutes.roundToInt() == minutes,
                                onClick = { draftMinutes = minutes.toFloat() },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                label = { Text("${minutes}m", maxLines = 1) }
                            )
                        }
                    }
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
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = MotoChatColors.Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
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
            roadDnaInstalledBytes = roadDnaInstalledBytes,
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
        containerColor = MotoChatColors.Background,
        contentColor = MotoChatColors.Text,
        topBar = {
            if (routePreview != null) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Percorso", style = MaterialTheme.typography.titleLarge)
                            Surface(
                                color = MotoChatColors.GreenSoft,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, MotoChatColors.Green.copy(alpha = 0.35f))
                            ) {
                                Text("VALHALLA", modifier = Modifier.padding(
                                    horizontal = 7.dp, vertical = 4.dp),
                                    color = MotoChatColors.Green,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
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
                                DropdownMenuItem(text = { Text("API Road DNA") },
                                    onClick = { showMoreMenu = false; showRoadDnaKeysDialog = true })
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
                                DropdownMenuItem(text = { Text("API Road DNA") },
                                    onClick = { showMoreMenu = false; showRoadDnaKeysDialog = true })
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
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MotoChatColors.Outline),
                    colors = CardDefaults.cardColors(containerColor = MotoChatColors.SurfaceRaised),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
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

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(MotoChatColors.Surface)
                        .padding(top = 8.dp)
                ) {
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

                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showRoutePreferencesDialog = true },
                            enabled = !isNavigating,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MotoChatColors.SurfaceRaised,
                                contentColor = MotoChatColors.Text
                            ),
                            border = BorderStroke(1.dp, MotoChatColors.Outline)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Opzioni", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { showWaypointsSheet = true },
                            enabled = !isNavigating,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MotoChatColors.SurfaceRaised,
                                contentColor = MotoChatColors.Text
                            ),
                            border = BorderStroke(1.dp, MotoChatColors.Outline)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Tappe (${routeWaypoints.size})",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            showRoadDnaDialog = true
                            // Preserve existing Road DNA results until the ViewModel
                            // invalidates them for a changed route.
                            if (roadDnaOnline == null && !roadDnaOnlineLoading) {
                                onRoadDnaAnalyze()
                            }
                        },
                        enabled = !isNavigating && !isLoading &&
                            routeOptions.getOrNull(selectedRouteIndex) != null,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .heightIn(min = 52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MotoChatColors.SurfaceRaised,
                            contentColor = MotoChatColors.Green
                        ),
                        border = BorderStroke(1.dp, MotoChatColors.Outline)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Road DNA · Guida e luoghi", style = MaterialTheme.typography.labelMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("VARIANTI DI PERCORSO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MotoChatColors.TextMuted)
                        Text(
                            routingSource ?: if (isLoading) "Calcolo Valhalla"
                                else "Valhalla non disponibile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MotoChatColors.Green,
                            maxLines = 1
                        )
                    }

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
                                Surface(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 5.dp)
                                        .clickable { onRouteOptionSelect(index) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) MotoChatColors.GreenSoft
                                        else MotoChatColors.SurfaceRaised,
                                    border = BorderStroke(
                                        if (isSelected) 1.5.dp else 1.dp,
                                        if (isSelected) MotoChatColors.Green else MotoChatColors.Outline
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(option.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MotoChatColors.Text)
                                            if (isSelected) {
                                                Icon(Icons.Default.Check,
                                                    contentDescription = "Selezionato",
                                                    tint = MotoChatColors.Green)
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("$mins min",
                                                style = MaterialTheme.typography.headlineSmall)
                                            Text("$km km",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MotoChatColors.TextMuted)
                                        }
                                    }
                                }
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
                                    modifier = Modifier.fillMaxWidth(0.94f),
                                    properties = DialogProperties(usePlatformDefaultWidth = false),
                                    containerColor = MotoChatColors.Surface,
                                    shape = RoundedCornerShape(24.dp),
                                    tonalElevation = 0.dp,
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
                            Text("AVVIA NAVIGAZIONE", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = {
                        showRoadDnaDialog = false
                        showRoadDnaResults = false
                        zoneMapMode = true
                        nearbySearchMode = true
                        zoneSearchStarted = true
                        onlineSuggestionsForMap = emptyList()
                        selectedOnlineSuggestion = null
                        selectedNearbyOffline = null
                        selectedOnlineRoadDnaAccess = null
                        zoneMapRef = null
                        onRoadDnaZoneClear()
                        showOnlineSuggestionsMap = false
                        showRoadDnaCenterPicker = false
                        showRoadDnaSearchParams = true
                        zonePickedCenter = null
                        gpsMapRecenterId++
                        onRoadDnaNearbySearch(zoneRadiusKm, zoneCategoryId)
                    },
                    enabled = !isNavigating && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Place, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cerca intorno a me · GPS")
                }
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
