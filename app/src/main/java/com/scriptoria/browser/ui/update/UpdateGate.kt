package com.scriptoria.browser.ui.update

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.scriptoria.browser.data.config.AppConfig
import com.scriptoria.browser.data.config.UpdateStatus

/**
 * Shown when the hosted config outranks this build.
 *
 * A required update is not dismissible and swallows the back press, since the point is that the
 * build must not keep running. An optional one is an ordinary dialog the user can wave away.
 */
@Composable
fun UpdateGate(
    status: UpdateStatus,
    config: AppConfig?,
    onDismiss: () -> Unit
) {
    if (status == UpdateStatus.NONE || config == null) return
    val context = LocalContext.current
    val required = status == UpdateStatus.REQUIRED

    if (required) {
        BackHandler(enabled = true) { /* cannot be dismissed */ }
    }

    val openUpdatePage = {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(config.updateUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // No browser to handle it; leave the dialog up rather than failing silently.
        }
    }

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !required,
            dismissOnClickOutside = !required
        ),
        title = { Text(if (required) "Update required" else "Update available") },
        text = {
            Text(
                config.message ?: if (required) {
                    "This version of Scriptoria is no longer supported. " +
                        "Update to ${config.latestVersionName} to carry on."
                } else {
                    "Scriptoria ${config.latestVersionName} is available."
                }
            )
        },
        confirmButton = { TextButton(onClick = openUpdatePage) { Text("Update") } },
        dismissButton = if (required) null else {
            { TextButton(onClick = onDismiss) { Text("Later") } }
        }
    )
}
