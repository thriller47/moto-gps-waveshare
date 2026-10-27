package com.motogps.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motogps.android.routing.ValhallaRegion

@Composable
fun ValhallaRoutingSection(
    regions: List<ValhallaRegion>,
    loadingCatalog: Boolean,
    installedBytes: Long,
    isDownloading: Boolean,
    progress: Float,
    status: String?,
    isNavigating: Boolean,
    onRefreshCatalog: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = regions.find { it.id == selectedId } ?: regions.firstOrNull()

    Text("Routing Valhalla offline", style = MaterialTheme.typography.titleMedium)
    Text(
        if (installedBytes > 0)
            "Grafo installato: " + (installedBytes / 1_000_000) + " MB · OSRM disabilitato"
        else "Nessun grafo di routing. Installa una regione per attivare la navigazione.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefreshCatalog, enabled = !isDownloading && !loadingCatalog) {
            Text(if (loadingCatalog) "Caricamento..." else "Aggiorna regioni")
        }
        if (installedBytes > 0) {
            OutlinedButton(onClick = onDelete, enabled = !isDownloading && !isNavigating) {
                Text("Elimina routing")
            }
        }
    }
    if (regions.isNotEmpty()) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !isDownloading) {
                Text(selected?.label ?: "Seleziona regione")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                regions.forEach { region ->
                    DropdownMenuItem(
                        text = { Text(region.label + " · " + (region.bytes / 1_000_000) + " MB") },
                        onClick = { selectedId = region.id; expanded = false }
                    )
                }
            }
        }
        Button(
            onClick = { selected?.let { onDownload(it.id) } },
            enabled = !isDownloading && !isNavigating && selected != null
        ) {
            Text("Scarica routing " + (selected?.let { "(" + (it.bytes / 1_000_000) + " MB)" } ?: ""))
        }
    }
    if (isDownloading) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text((progress * 100).toInt().toString() + "%")
        OutlinedButton(onClick = onCancel) { Text("Annulla download") }
    }
    if (status != null) Text(status, style = MaterialTheme.typography.bodySmall)
    if (isNavigating) Text(
        "Termina la navigazione prima di sostituire le mappe.",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "I dati di routing sono distinti dalle mappe .motomap. " +
            "Il download richiede Internet; il calcolo dei percorsi usa poi solo Valhalla locale. " +
            "Ricerca indirizzi e cartografia possono ancora richiedere la rete.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
}
