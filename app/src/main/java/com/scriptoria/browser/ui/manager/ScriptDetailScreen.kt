package com.scriptoria.browser.ui.manager

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scriptoria.browser.engine.executor.ScriptUpdateStatus
import com.scriptoria.browser.engine.executor.UserscriptManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreen(
    scriptId: Long,
    userscriptManager: UserscriptManager,
    onNavigateBack: () -> Unit,
    onEditScript: (Long) -> Unit
) {
    val context = LocalContext.current
    val scripts by userscriptManager.scripts.collectAsState()
    val script = scripts.firstOrNull { it.id == scriptId }
    val scope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }

    if (script == null) {
        onNavigateBack()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Script Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onEditScript(script.id) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Code")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = script.metadata.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = script.enabled,
                            onCheckedChange = {
                                scope.launch { userscriptManager.toggleScript(script.id, it) }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (script.metadata.version.isNotEmpty()) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("v${script.metadata.version}", fontSize = 11.sp) }
                            )
                        }
                        if (script.metadata.author.isNotEmpty()) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text(script.metadata.author, fontSize = 11.sp) }
                            )
                        }
                        SuggestionChip(
                            onClick = {},
                            label = { Text(script.metadata.runAt.tagValue, fontSize = 11.sp) }
                        )
                    }

                    if (script.metadata.description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = script.metadata.description,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Actions row: Edit Code & Check Updates
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onEditScript(script.id) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Edit Code")
                }

                OutlinedButton(
                    onClick = {
                        isCheckingUpdate = true
                        scope.launch {
                            val result = userscriptManager.checkForUpdate(script.id)
                            isCheckingUpdate = false
                            val msg = when (result) {
                                is ScriptUpdateStatus.Updated -> "Updated from ${result.oldVersion} to ${result.newVersion}"
                                is ScriptUpdateStatus.UpToDate -> "Script is already up to date"
                                is ScriptUpdateStatus.NoSource -> "No @updateURL found in metadata"
                                is ScriptUpdateStatus.Failed -> "Update check failed: ${result.error}"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isCheckingUpdate
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (isCheckingUpdate) "Checking..." else "Check Update")
                }
            }

            // Matches Section
            SectionCard(title = "Matching URLs") {
                val rules = script.metadata.matches + script.metadata.includes
                if (rules.isEmpty()) {
                    Text("Runs on all websites (*://*/*)", fontSize = 13.sp)
                } else {
                    rules.forEach {
                        Text("• $it", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }

            // Excludes Section (if any)
            if (script.metadata.excludes.isNotEmpty()) {
                SectionCard(title = "Excluded URLs") {
                    script.metadata.excludes.forEach {
                        Text("• $it", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }

            // Grants Section
            SectionCard(title = "Requested Permissions & Grants") {
                if (script.metadata.grants.isEmpty()) {
                    Text("No special GM APIs requested (none)", fontSize = 13.sp)
                } else {
                    script.metadata.grants.forEach { grant ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(grant, fontSize = 13.sp)
                        }
                    }
                }
            }

            // External @require dependencies
            if (script.metadata.requires.isNotEmpty()) {
                SectionCard(title = "External Libraries (@require)") {
                    script.metadata.requires.forEach { req ->
                        Text("• $req", fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }

            // Timestamps
            SectionCard(title = "Script Information") {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val updatedStr = dateFormat.format(Date(script.lastUpdated))
                val executedStr = if (script.lastExecuted > 0) dateFormat.format(Date(script.lastExecuted)) else "Never"

                Text("Last updated: $updatedStr", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Last executed: $executedStr", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (script.installUrl != null) {
                    Text("Install source: ${script.installUrl}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Delete Script Button
            OutlinedButton(
                onClick = {
                    scope.launch {
                        userscriptManager.deleteScript(script.id)
                        onNavigateBack()
                    }
                },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Delete Userscript")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
