package com.motogps.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.motogps.android.routing.ValhallaRouteLocations
import com.motogps.android.viewmodel.PlaceSearchResult

/**
 * Glove-friendly waypoint sheet from Stitch Dark Touring.
 * Query state, result ordering and all ViewModel callbacks are unchanged.
 */
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
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MotoChatColors.Surface,
        contentColor = MotoChatColors.Text,
        scrimColor = MotoChatColors.Scrim.copy(alpha = 0.85f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = MotoChatColors.Outline)
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("TAPPE INTERMEDIE", style = MaterialTheme.typography.labelSmall,
                color = MotoChatColors.Green)
            Text(
                "${waypoints.size}/${ValhallaRouteLocations.MAX_STOPS} tappe",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Le tappe sono visitate nell'ordine scelto.",
                style = MaterialTheme.typography.bodySmall,
                color = MotoChatColors.TextMuted
            )

            waypoints.forEachIndexed { index, stop ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MotoChatColors.SurfaceRaised,
                    border = BorderStroke(1.dp, MotoChatColors.Outline)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            color = MotoChatColors.Green,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stop.name,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 10.dp)
                        )
                        IconButton(
                            onClick = { onMove(index, -1) },
                            enabled = index > 0,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Sposta su")
                        }
                        IconButton(
                            onClick = { onMove(index, 1) },
                            enabled = index < waypoints.lastIndex,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Sposta giù")
                        }
                        IconButton(
                            onClick = { onRemove(index) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Elimina tappa",
                                tint = MotoChatColors.Error
                            )
                        }
                    }
                }
            }

            if (waypoints.size < ValhallaRouteLocations.MAX_STOPS) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    label = { Text("Cerca tappa o coordinate") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                onSearch(query)
                            },
                            enabled = query.isNotBlank()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Cerca")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        if (query.isNotBlank()) onSearch(query)
                    })
                )
                if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                searchResults.take(6).forEach { place ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onAdd(place)
                            query = ""
                        },
                        color = MotoChatColors.SurfaceRaised,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MotoChatColors.Outline)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MotoChatColors.Green
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(place.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOf(
                                        place.displayArea.ifBlank { place.address },
                                        place.distanceLabel
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MotoChatColors.TextMuted
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Tieni premuto anche sulla mappa per aggiungere una tappa.",
                style = MaterialTheme.typography.bodySmall,
                color = MotoChatColors.TextMuted
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
