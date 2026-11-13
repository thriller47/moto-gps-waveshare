package com.motogps.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** Visible attribution and license references for the core navigation data and engines. */
// Branding validation is consolidated with the ESP32 boot-screen changes.
@Composable
fun MotoChatInfoDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Moto Chat GPS · Informazioni") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Navigazione motociclistica con mappe e tecnologie aperte.")
                Text("Ringraziamenti e licenze", style = MaterialTheme.typography.titleMedium)

                Text("OpenStreetMap", style = MaterialTheme.typography.titleSmall)
                Text("Dati cartografici © collaboratori OpenStreetMap. Dati disponibili secondo la licenza Open Database License (ODbL).")
                Text(
                    "© OpenStreetMap contributors · openstreetmap.org/copyright",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://www.openstreetmap.org/copyright")
                    }
                )

                Text("Valhalla", style = MaterialTheme.typography.titleSmall)
                Text("Motore di calcolo dei percorsi open source. Grazie ai suoi sviluppatori e contributori.")
                Text(
                    "Codice e licenza MIT · github.com/valhalla/valhalla",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/valhalla/valhalla/blob/master/LICENSE")
                    }
                )

                Text("Photon", style = MaterialTheme.typography.titleSmall)
                Text("Motore di ricerca geografica basato su OpenStreetMap. Grazie agli sviluppatori e contributori.")
                Text(
                    "Codice e licenza · github.com/komoot/photon",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/komoot/photon/blob/master/LICENSE")
                    }
                )

                Text(
                    "Le attribuzioni dei dati e le licenze dei componenti di terze parti rimangono applicabili. Questa schermata non sostituisce i testi di licenza e gli avvisi richiesti nelle distribuzioni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        }
    )
}
