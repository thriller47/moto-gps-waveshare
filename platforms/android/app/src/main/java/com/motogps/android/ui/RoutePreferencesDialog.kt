package com.motogps.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle

/**
 * Compact bottom sheet: a maximum 320dp panel stays below the map.
 * Scroll the panel instead of covering the route preview with a full dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePreferencesDialog(
    preferences: RoutePreferences,
    onChange: (RoutePreferences) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftCurves by remember(preferences.curveLevel) {
        mutableFloatStateOf(preferences.curveLevel.toFloat())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("Opzioni percorso", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    RouteStyle.FASTEST to "Rapido",
                    RouteStyle.SHORTEST to "Breve",
                    RouteStyle.SECONDARY to "Secondarie",
                    RouteStyle.TOURING to "Touring moto"
                ).forEach { (style, name) ->
                    FilterChip(
                        selected = preferences.style == style,
                        onClick = { onChange(preferences.copy(style = style)) },
                        label = { Text(name, maxLines = 1) }
                    )
                }
            }
            val shortest = preferences.style == RouteStyle.SHORTEST
            val enabled = !shortest
            if (preferences.style == RouteStyle.TOURING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Preferisci curve", modifier = Modifier.weight(1f))
                    Text(
                        listOf("No", "Leggero", "Medio", "Alto")
                            [draftCurves.toInt().coerceIn(0,3)],
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Slider(
                    value = draftCurves,
                    onValueChange = { draftCurves = it },
                    onValueChangeFinished = {
                        onChange(preferences.copy(curveLevel =
                            draftCurves.toInt().coerceIn(0, 3)))
                    },
                    valueRange = 0f..3f, steps = 2
                )
                Text(
                    "Valuta fino a due alternative con più curve, senza deviazioni eccessive. Non misura i panorami.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (shortest) {
                Text(
                    "Il profilo Breve ignora le penalità stradali.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            @Composable
            fun PreferenceItem(label: String, checked: Boolean, toggle: (Boolean)->Unit) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min=42.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = toggle
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            PreferenceItem("Evita autostrade", preferences.avoidHighways) {
                onChange(preferences.copy(avoidHighways = it))
            }
            PreferenceItem("Evita pedaggi", preferences.avoidTolls) {
                onChange(preferences.copy(avoidTolls = it))
            }
            PreferenceItem("Evita traghetti", preferences.avoidFerries) {
                onChange(preferences.copy(avoidFerries = it))
            }
            Text(
                "Le esclusioni non sono divieti assoluti. Le modifiche aggiornano il percorso.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier=Modifier.height(8.dp))
        }
    }
}
