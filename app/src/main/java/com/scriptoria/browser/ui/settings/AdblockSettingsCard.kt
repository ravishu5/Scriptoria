package com.scriptoria.browser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scriptoria.browser.engine.adblock.AdblockManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun AdblockSettingsCard(adblockManager: AdblockManager) {
    val scope = rememberCoroutineScope()
    val preferences = adblockManager.preferences

    var enabled by remember { mutableStateOf(preferences.isEnabled) }
    var lists by remember { mutableStateOf(preferences.getLists()) }
    var allowlistSize by remember { mutableStateOf(preferences.allowlist.size) }
    var isUpdating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Block ads and trackers", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(
                        text = when {
                            !enabled -> "Off"
                            !adblockManager.isReady -> "Preparing filter lists…"
                            else -> "${adblockManager.blockedCount} requests blocked this session"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        preferences.isEnabled = it
                    }
                )
            }

            HorizontalDivider()

            Text(
                text = "Filter lists",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            lists.forEach { entity ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entity.title ?: entity.url,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (entity.lastLocalUpdate > 0) {
                                "Updated " + DateFormat.getDateTimeInstance(
                                    DateFormat.MEDIUM, DateFormat.SHORT
                                ).format(Date(entity.lastLocalUpdate))
                            } else {
                                "Not downloaded yet"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = entity.enabled,
                        enabled = enabled && !isUpdating,
                        onCheckedChange = { checked ->
                            entity.enabled = checked
                            preferences.updateList(entity)
                            // AbpEntity is mutable, so hand Compose a new list to compare against.
                            lists = preferences.getLists()
                            scope.launch { adblockManager.refreshLists(force = false) }
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isUpdating) {
                        isUpdating = true
                        status = null
                        scope.launch {
                            try {
                                adblockManager.refreshLists(force = true)
                                lists = preferences.getLists()
                                status = "Filter lists up to date"
                            } catch (e: Exception) {
                                status = "Update failed: ${e.message}"
                            } finally {
                                isUpdating = false
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column {
                    Text("Update filter lists now", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(
                        text = status ?: "Lists refresh automatically once a day",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (allowlistSize > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            preferences.allowlist = emptySet()
                            allowlistSize = 0
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Clear allowed sites", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(
                            text = "$allowlistSize site${if (allowlistSize == 1) "" else "s"} " +
                                "currently have blocking turned off",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
