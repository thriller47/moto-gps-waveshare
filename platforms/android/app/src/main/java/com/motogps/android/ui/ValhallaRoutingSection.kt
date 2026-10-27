package com.motogps.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motogps.android.routing.ValhallaRegion

/** Compact, touch-friendly controls for the routing graph inside the map dialog. */
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
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = regions.find { it.id == selectedId } ?: regions.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Grafi Valhalla", style = MaterialTheme.typography.titleMedium)
            Text(
                "Servono per calcolare percorsi e svolte. Sono separati dalle mappe stradali.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (installedBytes > 0L)
                    "Grafo installato · ${installedBytes / 1_000_000} MB"
                else "Nessun grafo installato",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedButton(
                onClick = onRefreshCatalog,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !isDownloading && !loadingCatalog
            ) {
                Text(if (loadingCatalog) "Caricamento regioni..." else "Aggiorna elenco regioni")
            }

            if (regions.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = !isDownloading && !isNavigating && !loadingCatalog,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    ) {
                        Column {
                            Text("Regione selezionata", style = MaterialTheme.typography.labelSmall)
                            Text(
                                selected?.label ?: "Seleziona una regione",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        regions.forEach { region ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(region.label, maxLines = 2)
                                        Text(
                                            "${region.bytes / 1_000_000} MB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    selectedId = region.id
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = { selected?.let { onDownload(it.id) } },
                    enabled = !isDownloading && !isNavigating && !loadingCatalog && selected != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text("Scarica grafo · ${selected?.bytes?.div(1_000_000) ?: 0} MB")
                }
            } else if (!loadingCatalog) {
                Text(
                    "Aggiorna l'elenco per scegliere il grafo da scaricare.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = onImport,
                enabled = !isDownloading && !isNavigating,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text("Importa grafo TAR / ZST")
            }

            if (isDownloading) {
                if (status?.startsWith("Importazione grafo") == true) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Download grafo · ${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Annulla download")
                }
            }

            if (status != null) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            if (installedBytes > 0L) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !isDownloading && !isNavigating,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Rimuovi grafo installato")
                }
            }
            if (isNavigating) {
                Text(
                    "Termina la navigazione prima di sostituire il grafo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
