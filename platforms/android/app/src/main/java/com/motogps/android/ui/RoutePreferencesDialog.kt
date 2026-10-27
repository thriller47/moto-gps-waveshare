package com.motogps.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle

@Composable
fun RoutePreferencesDialog(
    preferences: RoutePreferences,
    onChange: (RoutePreferences) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opzioni percorso") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Tipo di percorso", style = MaterialTheme.typography.titleSmall)
                listOf(
                    RouteStyle.FASTEST to "Rapido",
                    RouteStyle.SHORTEST to "Breve",
                    RouteStyle.SECONDARY to "Strade secondarie"
                ).forEach { (style, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onChange(preferences.copy(style = style)) }
                            .heightIn(min = 46.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = preferences.style == style,
                            onClick = { onChange(preferences.copy(style = style)) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Preferenze strade", style = MaterialTheme.typography.titleSmall)
                val enabled = preferences.style != RouteStyle.SHORTEST
                if (!enabled) {
                    Text(
                        "Breve privilegia la distanza e ignora le preferenze di evitamento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) {
                            onChange(preferences.copy(avoidHighways = !preferences.avoidHighways))
                        }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = preferences.avoidHighways, enabled = enabled,
                        onCheckedChange = { onChange(preferences.copy(avoidHighways = it)) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Evita autostrade")
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) {
                            onChange(preferences.copy(avoidTolls = !preferences.avoidTolls))
                        }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = preferences.avoidTolls, enabled = enabled,
                        onCheckedChange = { onChange(preferences.copy(avoidTolls = it)) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Evita pedaggi")
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) {
                            onChange(preferences.copy(avoidFerries = !preferences.avoidFerries))
                        }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = preferences.avoidFerries, enabled = enabled,
                        onCheckedChange = { onChange(preferences.copy(avoidFerries = it)) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Evita traghetti")
                }
                Text(
                    "Le esclusioni non sono divieti assoluti. Ogni modifica ricalcola il percorso.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}
