package com.motogps.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Manage only the locally imported Valhalla TAR/Zstandard graph. */
@Composable
fun ValhallaRoutingSection(
    installedBytes: Long,
    isImporting: Boolean,
    status: String?,
    isNavigating: Boolean,
    onImport: () -> Unit,
    onCancelImport: () -> Unit,
    onDelete: () -> Unit
) {
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
            Text("Grafo Valhalla", style = MaterialTheme.typography.titleMedium)
            Text(
                "Importa un grafo di routing .tar o .tar.zst già scaricato sul telefono. " +
                    "È separato dalle mappe stradali .motomap.zip.",
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
                onClick = onImport,
                enabled = !isImporting && !isNavigating,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text("Importa grafo TAR / ZST")
            }
            if (isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = onCancelImport,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Annulla importazione")
                }
            }
            if (!status.isNullOrBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            if (installedBytes > 0L) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !isImporting && !isNavigating,
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
