package com.motogps.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(initialGateway: String, onGatewaySave: (String) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var currentGateway by remember { mutableStateOf(initialGateway) }
    var showGatewayDialog by remember { mutableStateOf(initialGateway.isBlank()) }
    var tempGateway by remember { mutableStateOf(initialGateway) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MOTO GPS") },
                actions = {
                    IconButton(onClick = { showGatewayDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Impostazioni Gateway")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Cerca destinazione") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )

            ListItem(
                headlineContent = { Text("Il mio display") },
                supportingContent = { Text("Non connesso") },
                leadingContent = {
                    Icon(Icons.Default.Settings, contentDescription = null)
                }
            )

            HorizontalDivider()

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Seleziona destinazione", style = MaterialTheme.typography.titleMedium)
                    Text("Cerca un luogo o un indirizzo, scegli il percorso e parti.")
                }
            }
        }
    }
}
