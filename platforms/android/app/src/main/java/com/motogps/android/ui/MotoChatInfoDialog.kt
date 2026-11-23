package com.motogps.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Stitch Dark Touring attribution sheet. License texts and outgoing links remain
 * explicit and usable even when the screen is small or in landscape orientation.
 */
@Composable
fun MotoChatInfoDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(28.dp),
            color = MotoChatColors.Surface,
            border = BorderStroke(1.dp, MotoChatColors.Outline)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MotoChatColors.Green
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Moto Chat GPS · Info", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Navigazione libera con tecnologie aperte",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MotoChatColors.TextMuted
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Chiudi informazioni")
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Surface(
                        color = MotoChatColors.SurfaceRaised,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MotoChatColors.Outline)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "STACK OPEN SOURCE & PRIVACY",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "100% LIBERO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MotoChatColors.Green
                            )
                        }
                    }

                    @Composable
                    fun LicenseEntry(
                        name: String,
                        tag: String,
                        description: String,
                        linkLabel: String,
                        link: String
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MotoChatColors.TextMuted
                                )
                            }
                            Text(description, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "$linkLabel ↗",
                                modifier = Modifier.clickable { uriHandler.openUri(link) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MotoChatColors.Green
                            )
                        }
                    }

                    LicenseEntry(
                        name = "OpenStreetMap",
                        tag = "DATI ODbL",
                        description = "Dati cartografici © collaboratori OpenStreetMap. " +
                            "Dati disponibili secondo la licenza Open Database License (ODbL).",
                        linkLabel = "openstreetmap.org/copyright",
                        link = "https://www.openstreetmap.org/copyright"
                    )
                    HorizontalDivider(color = MotoChatColors.Outline)
                    LicenseEntry(
                        name = "Valhalla",
                        tag = "MIT LICENSE",
                        description = "Motore di calcolo dei percorsi open source. " +
                            "Grazie ai suoi sviluppatori e contributori.",
                        linkLabel = "github.com/valhalla/valhalla",
                        link = "https://github.com/valhalla/valhalla/blob/master/LICENSE"
                    )
                    HorizontalDivider(color = MotoChatColors.Outline)
                    LicenseEntry(
                        name = "Photon",
                        tag = "LICENZA",
                        description = "Motore di ricerca geografica basato su OpenStreetMap. " +
                            "Grazie agli sviluppatori e contributori.",
                        linkLabel = "github.com/komoot/photon",
                        link = "https://github.com/komoot/photon/blob/master/LICENSE"
                    )
                    Text(
                        "Le attribuzioni dei dati e le licenze dei componenti di terze parti " +
                            "rimangono applicabili. Questa schermata non sostituisce i testi " +
                            "di licenza e gli avvisi richiesti nelle distribuzioni.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MotoChatColors.TextMuted
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, MotoChatColors.Outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MotoChatColors.Text)
                ) {
                    Text("✓  CHIUDI", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
