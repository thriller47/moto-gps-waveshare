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
fun NavigationScreen() {
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MOTO GPS") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search Destination") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )

            // Mimic iOS device summary section
            ListItem(
                headlineContent = { Text("我的圆屏") },
                supportingContent = { Text("未连接") }, // idle state
                leadingContent = {
                    Icon(Icons.Default.Settings, contentDescription = null) // placeholder icon
                }
            )

            Divider()

            // Placeholder for iOS "Select Destination" view
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
                    Text("选择目的地", style = MaterialTheme.typography.titleMedium)
                    Text("搜索地点或地址，选好路线就出发。")
                }
            }
        }
    }
}
