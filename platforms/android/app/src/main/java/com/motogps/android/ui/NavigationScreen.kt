package com.motogps.android.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import com.motogps.android.map.SavedMapInfo
import com.motogps.android.viewmodel.PlaceSearchResult
import com.motogps.android.viewmodel.RouteOption
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.cachemanager.CacheManager
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
            if (geoPoints.isNotEmpty()) {
                val polyline = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = android.graphics.Color.BLUE
                    outlinePaint.strokeWidth = 12f
                }
                mapView.overlays.add(polyline)

                val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                mapView.post {
                    mapView.zoomToBoundingBox(boundingBox, true, 64)
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
                    title = "Destinazione"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(destMarker)
            }

            mapView.invalidate()
        },
        modifier = modifier
    )
}

fun triggerOfflineMapDownload(mapView: MapView?, context: Context) {
    if (mapView == null) {
        Toast.makeText(context, "Mappa non ancora pronta", Toast.LENGTH_SHORT).show()
        return
    }
    val boundingBox = mapView.boundingBox
    if (boundingBox != null) {
        val cacheManager = CacheManager(mapView)
        val zoomMin = 12
        val zoomMax = 16
        try {
            cacheManager.downloadAreaAsync(context, boundingBox, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
                override fun downloadStarted() {}
                override fun onTaskComplete() {
                    Toast.makeText(context, "Mappa offline salvata con successo", Toast.LENGTH_SHORT).show()
                }
                override fun onTaskFailed(errors: Int) {
                    Toast.makeText(context, "Errore download mappa: $errors", Toast.LENGTH_SHORT).show()
                }
                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                override fun setPossibleTilesInArea(total: Int) {}
            })
            Toast.makeText(context, "Download mappa offline avviato...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Impossibile salvare la mappa: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Area mappa non valida", Toast.LENGTH_SHORT).show()
    }
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

    if (geoPoints.isEmpty()) {
        if (currentLocation != null && (currentLocation.first != 0.0 || currentLocation.second != 0.0)) {
            geoPoints.add(GeoPoint(currentLocation.first, currentLocation.second))
        }
        if (destination != null) {
            geoPoints.add(GeoPoint(destination.latitude, destination.longitude))
        }
    }

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
    onDeleteMap: (String) -> Unit,
    onDownloadArea: () -> Unit,
    onDownloadRegion: (String) -> Unit,
    downloadStatus: String? = null,
    downloadProgress: Float = 0f,
    isDownloadingMap: Boolean = false,
    onCancelDownload: () -> Unit = {},
    onDismiss: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gestione Mappe Offline") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Mappe .motomap salvate:", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))

                if (savedMaps.isEmpty()) {
                    Text("Nessuna mappa offline salvata.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 160.dp).fillMaxWidth()) {
                        items(savedMaps) { mapInfo ->
                            val mbStr = String.format("%.2f MB", mapInfo.sizeBytes / (1024.0 * 1024.0))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mapInfo.fileName, style = MaterialTheme.typography.bodyMedium)
                                    Text(mbStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onDeleteMap(mapInfo.fileName) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Elimina")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                if (isDownloadingMap) {
                    MapDownloadProgressBox(
                        downloadStatus = downloadStatus,
                        downloadProgress = downloadProgress,
                        onCancelDownload = onCancelDownload
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = onDownloadArea,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDownloadingMap
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scarica strade vicine (~8 km)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Le mappe provengono dal gateway vettoriale e vengono salvate per l'uso offline. " +
                        "Il corridoio si scarica dalla schermata percorso. " +
                        "Le regioni complete richiedono archivi dedicati e non sono disponibili con questo metodo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    initialGateway: String,
    onGatewaySave: (String) -> Unit,
    onSearch: (String) -> Unit,
    onConnectRequest: () -> Unit,
    connectionState: String,
    isLoading: Boolean,
    searchResults: List<PlaceSearchResult>,
    routePreview: PlaceSearchResult?,
    routeOptions: List<RouteOption>,
    selectedRouteIndex: Int,
    isNavigating: Boolean = false,
    currentLocation: Pair<Double, Double>? = null,
    savedMaps: List<SavedMapInfo> = emptyList(),
    onRouteOptionSelect: (Int) -> Unit,
    onRouteSelected: (PlaceSearchResult) -> Unit,
    onStartNavigation: (PlaceSearchResult) -> Unit,
    onStopNavigation: () -> Unit = {},
    onBackToSearch: () -> Unit,
    onDownloadArea: () -> Unit = {},
    onDownloadCorridor: (List<Pair<Double, Double>>) -> Unit = {},
    onDownloadRegion: (String) -> Unit = {},
    onDeleteMap: (String) -> Unit = {},
    downloadStatus: String? = null,
    downloadProgress: Float = 0f,
    isDownloadingMap: Boolean = false,
    onCancelDownload: () -> Unit = {},
    onRefreshMaps: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var currentGateway by remember { mutableStateOf(initialGateway) }
    var showGatewayDialog by remember { mutableStateOf(initialGateway.isBlank()) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var tempGateway by remember { mutableStateOf(initialGateway) }

    var centerOnLocationTrigger by remember { mutableStateOf(0) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }
    var longPressDestination by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    if (showGatewayDialog) {
        AlertDialog(
            onDismissRequest = {
                if (currentGateway.isNotBlank()) showGatewayDialog = false
            },
            title = { Text("Configurazione Gateway") },
            text = {
                OutlinedTextField(
                    value = tempGateway,
                    onValueChange = { tempGateway = it },
                    label = { Text("Indirizzo Gateway") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempGateway.isNotBlank()) {
                            onGatewaySave(tempGateway)
                            currentGateway = tempGateway
                            showGatewayDialog = false
                        }
                    }
                ) {
                    Text("Salva")
                }
            }
        )
    }

    LaunchedEffect(showOfflineDialog) {
        if (showOfflineDialog) {
            onRefreshMaps()
        }
    }

    if (showOfflineDialog) {
        OfflineMapsDialog(
            savedMaps = savedMaps,
            onDeleteMap = onDeleteMap,
            onDownloadArea = onDownloadArea,
            onDownloadRegion = onDownloadRegion,
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
                        TextButton(onClick = onBackToSearch) {
                            Text("Cambia", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("MOTO GPS") },
                    actions = {
                        IconButton(onClick = { showOfflineDialog = true }) {
                            Icon(Icons.Default.Place, contentDescription = "Mappe Offline")
                        }
                        IconButton(onClick = { showGatewayDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Impostazioni Gateway")
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
                    RouteMapView(
                        geoPoints = geoPoints,
                        modifier = Modifier.fillMaxSize()
                    )
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

                    Text(
                        text = "Seleziona percorso (OSM)",
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
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Calcolo del percorso in corso...")
                        }
                    }

                    // Salva Mappa Corridoio Quick Action Button
                    OutlinedButton(
                        onClick = {
                            val pts = geoPoints.map { Pair(it.latitude, it.longitude) }
                            onDownloadCorridor(pts)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Salva Mappa Corridoio (10 km)")
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
                            Text("Termina Navigazione", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Button(
                            onClick = { onStartNavigation(routePreview) },
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
                    placeholder = { Text("Cerca destinazione") },
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
                        Text("Gestione Mappe Offline", style = MaterialTheme.typography.labelSmall)
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
