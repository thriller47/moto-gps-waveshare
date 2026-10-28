package com.motogps.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.motogps.android.routing.ValhallaRouteLocations
import com.motogps.android.viewmodel.PlaceSearchResult

/** Compact waypoint editor leaves the preview map visible above the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteWaypointsSheet(
    waypoints: List<PlaceSearchResult>,
    searchResults: List<PlaceSearchResult>,
    searching: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onAdd: (PlaceSearchResult) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 370.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Tappe intermedie (${waypoints.size}/${ValhallaRouteLocations.MAX_STOPS})",
                style = MaterialTheme.typography.titleMedium)
            Text("Le tappe sono visitate nell'ordine scelto.",
                style = MaterialTheme.typography.bodySmall)
            waypoints.forEachIndexed { index, stop ->
                Row(modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${stop.name}", maxLines = 2,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { onMove(index, -1) }, enabled = index > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Sposta su")
                    }
                    IconButton(onClick = { onMove(index, 1) }, enabled = index < waypoints.lastIndex) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Sposta giù")
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Elimina tappa")
                    }
                }
            }
            if (waypoints.size < ValhallaRouteLocations.MAX_STOPS) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cerca una tappa o inserisci coordinate") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { onSearch(query) },
                            enabled = query.isNotBlank()) {
                            Icon(Icons.Default.Search, contentDescription = "Cerca")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) })
                )
                if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                searchResults.take(6).forEach { place ->
                    ListItem(
                        headlineContent = { Text(place.name) },
                        supportingContent = { Text(place.displayArea.ifBlank { place.address }) },
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        modifier = Modifier.clickable {
                            onAdd(place)
                            query = ""
                        }
                    )
                }
            }
            Text("Tieni premuto anche sulla mappa per aggiungere una tappa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}
