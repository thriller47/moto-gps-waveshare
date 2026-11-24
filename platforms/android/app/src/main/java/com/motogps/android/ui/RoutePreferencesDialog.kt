package com.motogps.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle

/**
 * Stitch Dark Touring route options, kept compact so the map remains visible.
 * The RoutePreferences value and every onChange/onDismiss callback are unchanged.
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
        containerColor = MotoChatColors.Surface,
        contentColor = MotoChatColors.Text,
        scrimColor = MotoChatColors.Scrim.copy(alpha = 0.85f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = MotoChatColors.Outline)
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("OPZIONI PERCORSO", style = MaterialTheme.typography.labelSmall,
                color = MotoChatColors.Green)
            Text("Scegli come viaggiare", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    RouteStyle.FASTEST to "Rapido",
                    RouteStyle.SHORTEST to "Breve",
                    RouteStyle.SECONDARY to "Secondarie",
                    RouteStyle.TOURING to "Touring moto",
                    RouteStyle.PANORAMIC to "Panoramico"
                ).forEach { (style, name) ->
                    FilterChip(
                        selected = preferences.style == style,
                        onClick = { onChange(preferences.copy(style = style)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        label = { Text(name, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MotoChatColors.SurfaceRaised,
                            labelColor = MotoChatColors.TextMuted,
                            selectedContainerColor = MotoChatColors.GreenSoft,
                            selectedLabelColor = MotoChatColors.Green
                        )
                    )
                }
            }

            val shortest = preferences.style == RouteStyle.SHORTEST
            val enabled = !shortest
            if (preferences.style == RouteStyle.TOURING) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MotoChatColors.SurfaceRaised,
                    border = BorderStroke(1.dp, MotoChatColors.Outline)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Preferisci curve", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOf("No", "Leggero", "Medio", "Alto")[
                                    draftCurves.roundToInt().coerceIn(0, 3)
                                ],
                                color = MotoChatColors.Green,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Slider(
                            value = draftCurves,
                            onValueChange = { draftCurves = it },
                            onValueChangeFinished = {
                                onChange(preferences.copy(
                                    curveLevel = draftCurves.roundToInt().coerceIn(0, 3)
                                ))
                            },
                            valueRange = 0f..3f,
                            steps = 2
                        )
                        Text(
                            "Valuta fino a due alternative con più curve, senza deviazioni " +
                                "eccessive. Non misura i panorami.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MotoChatColors.TextMuted
                        )
                    }
                }
            }
            if (preferences.style == RouteStyle.PANORAMIC) {
                Text(
                    "Favorisce strade secondarie e alternative con curve moderate. " +
                        "Non misura i panorami reali; con tappe Valhalla mantiene " +
                        "il profilo stradale ma non propone alternative.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MotoChatColors.TextMuted
                )
            }
            if (shortest) {
                Text("Il profilo Breve ignora le penalità stradali.",
                    color = MotoChatColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall)
            }

            @Composable
            fun PreferenceItem(label: String, checked: Boolean, toggle: (Boolean) -> Unit) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clickable(enabled = enabled) { toggle(!checked) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = toggle
                    )
                    Spacer(Modifier.width(8.dp))
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
                color = MotoChatColors.TextMuted
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
