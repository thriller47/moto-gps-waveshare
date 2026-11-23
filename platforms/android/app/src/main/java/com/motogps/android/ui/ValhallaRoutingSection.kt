package com.motogps.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MotoChatColors.Outline),
        colors = CardDefaults.cardColors(
            containerColor = MotoChatColors.SurfaceRaised,
            contentColor = MotoChatColors.Text
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Grafo Valhalla", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (installedBytes > 0L) "INSTALLATO" else "NON INSTALLATO",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (installedBytes > 0L) MotoChatColors.Green
                        else MotoChatColors.Warning
                )
            }
            Text(
                "Importa un grafo di routing .tar o .tar.zst già scaricato sul telefono. " +
                    "È separato dalle mappe stradali .motomap.zip.",
                style = MaterialTheme.typography.bodySmall,
                color = MotoChatColors.TextMuted
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, MotoChatColors.Green),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MotoChatColors.Green
                )
            ) {
                Text("Importa grafo TAR / ZST", style = MaterialTheme.typography.labelMedium)
            }
            if (isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = onCancelImport,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(28.dp)
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MotoChatColors.Error
                    )
                ) {
                    Text("Rimuovi grafo installato")
                }
            }
            if (isNavigating) {
                Text(
                    "Termina la navigazione prima di sostituire il grafo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MotoChatColors.TextMuted
                )
            }
        }
    }
}
