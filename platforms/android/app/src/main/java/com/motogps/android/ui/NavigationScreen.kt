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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.motogps.android.R
import androidx.core.content.ContextCompat
import com.motogps.android.map.SavedMapInfo
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.viewmodel.PlaceSearchResult
import com.motogps.android.viewmodel.RouteOption
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun RouteMapView(
    geoPoints: List<GeoPoint>,
    currentLocation: Pair<Double, Double>? = null,
    destinationLocation: Pair<Double, Double>? = null,
    waypoints: List<PlaceSearchResult> = emptyList(),
    onWaypointLongPress: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
            }
        },
        update = { mapView ->
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
                    outlinePaint.color = android.graphics.Color.BLUE
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
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
            if (viewPoints.size >= 2) {
                val bounds = BoundingBox.fromGeoPoints(viewPoints)
                mapView.post { mapView.zoomToBoundingBox(bounds, true, 64) }
            } else if (viewPoints.size == 1) {
                mapView.controller.setCenter(viewPoints.first())
                mapView.controller.setZoom(15.0)
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
    valhallaInstalledBytes: Long,
    valhallaImporting: Boolean,
    valhallaStatus: String?,
    isNavigating: Boolean,
    onImportValhalla: () -> Unit,
    onCancelValhalla: () -> Unit,
    onDeleteValhalla: () -> Unit,
    onDeleteMap: (String) -> Unit,
    onImportOsmPack: () -> Unit,
    downloadStatus: String? = null,
    downloadProgress: Float = 0f,
    isDownloadingMap: Boolean = false,
    onCancelDownload: () -> Unit = {},
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gestione mappe") },
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
                        Text("Mappe stradali", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Importa un pacchetto .motomap.zip preparato sul PC. Le strade saranno disponibili sul display ESP32.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = onImportOsmPack,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            enabled = !isDownloadingMap && !valhallaImporting && !isNavigating
                        ) {
                            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importa mappa ZIP")
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
    onConnectRequest: () -> Unit,
    connectionState: String,
    isLoading: Boolean,
    searchResults: List<PlaceSearchResult>,
    routePreview: PlaceSearchResult?,
    routeOptions: List<RouteOption>,
    routeWaypoints: List<PlaceSearchResult> = emptyList(),
    waypointSearchResults: List<PlaceSearchResult> = emptyList(),
    waypointSearching: Boolean = false,
    waypointSearchError: String? = null,
    onSearchWaypoint: (String) -> Unit = {},
    onAddWaypoint: (PlaceSearchResult) -> Unit = {},
    onRemoveWaypoint: (Int) -> Unit = {},
    onMoveWaypoint: (Int, Int) -> Unit = { _, _ -> },
    selectedRouteIndex: Int,
    routingSource: String? = null,
    routePreviewError: String? = null,
    routePreferences: RoutePreferences = RoutePreferences(),
    onRoutePreferencesChange: (RoutePreferences) -> Unit = {},
    isNavigating: Boolean = false,
    hasArrived: Boolean = false,
    currentLocation: Pair<Double, Double>? = null,
    savedMaps: List<SavedMapInfo> = emptyList(),
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
    onDeleteMap: (String) -> Unit = {},
    downloadStatus: String? = null,
    downloadProgress: Float = 0f,
    isDownloadingMap: Boolean = false,
    onCancelDownload: () -> Unit = {},
    onRefreshMaps: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
    diagnosticMode: Int = 0,
    onDiagnosticModeChange: (Int) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showRoutePreferencesDialog by remember { mutableStateOf(false) }
    var showWaypointsSheet by remember { mutableStateOf(false) }

    var centerOnLocationTrigger by remember { mutableStateOf(0) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }
    var longPressDestination by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current


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

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Diagnostica ESP32") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OFF per la navigazione ordinaria. BASE registra eventi critici, DEBUG include rendering e BLE. Solo PSRAM: nessuna scrittura flash. Spegnimento automatico dopo 10 minuti.")
                    for ((mode, label) in listOf(0 to "OFF", 1 to "BASE", 2 to "DEBUG")) {
                        OutlinedButton(
                            onClick = {
                                onDiagnosticModeChange(mode)
                                showDiagnosticsDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text((if (diagnosticMode == mode) "✓ " else "") + label)
                        }
                    }
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
            valhallaInstalledBytes = valhallaInstalledBytes,
            valhallaImporting = valhallaImporting,
            valhallaStatus = valhallaStatus,
            isNavigating = isNavigating,
            onImportValhalla = onImportValhalla,
            onCancelValhalla = onCancelValhalla,
            onDeleteValhalla = onDeleteValhalla,
            onDeleteMap = onDeleteMap,
            onImportOsmPack = onImportOsmPack,
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
                        TextButton(onClick = { showDiagnosticsDialog = true }) {
                            Text("LOG")
                        }
                        IconButton(onClick = onExportDiagnostics) {
                            Icon(Icons.Default.Share, contentDescription = "Esporta diagnostica")
                        }
                        TextButton(onClick = onBackToSearch) {
                            Text("Cambia", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("MOTO GPS") },
                    actions = {
                        TextButton(onClick = { showDiagnosticsDialog = true }) {
                            Text("LOG")
                        }
                        IconButton(onClick = onExportDiagnostics) {
                            Icon(Icons.Default.Share, contentDescription = "Esporta diagnostica")
                        }
                        IconButton(onClick = { showOfflineDialog = true }) {
                            Icon(Icons.Default.Place, contentDescription = "Gestione mappe")
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
                        geoPoints = geoPoints,
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
                            onClick = { onStartNavigation(routePreview) },
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

                Text(
                    "Cerca un indirizzo oppure tieni premuto sulla mappa. Puoi anche inserire coordinate GPS, ad esempio 45.4642, 9.1900.",
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
                        Text("Mostra Mappa / Mia Posizione", style = MaterialTheme.typography.labelSmall)
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
                        Text("Gestione mappe", style = MaterialTheme.typography.labelSmall)
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
                                    val subtitle = listOf(place.displayArea, place.address).filter { it.isNotBlank() }.joinToString(" · ")
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
