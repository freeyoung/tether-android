package io.github.freeyoung.tether.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.freeyoung.tether.Prefs
import io.github.freeyoung.tether.R

// The apps that posted notifications, each with a switch: off keeps its
// notifications on the phone.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationAppsSheet(onDismiss: () -> Unit) {
    val c = Tint.colors
    val context = LocalContext.current
    val apps = remember { Prefs.seenApps(context).entries.sortedBy { it.value.lowercase() } }
    var muted by remember { mutableStateOf(Prefs.mutedApps(context)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.background) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ListSection(stringResource(R.string.notification_apps), footer = stringResource(R.string.notification_apps_footer)) {
                if (apps.isEmpty()) {
                    EmptyState(R.drawable.sym_notifications, stringResource(R.string.no_notification_apps), iconSize = 24.dp, top = 20.dp)
                }
                apps.forEachIndexed { i, (app, name) ->
                    if (i > 0) RowDivider()
                    val icon = remember(app) { appIcon(context.packageManager, app) }
                    PanelRow(
                        icon = R.drawable.sym_apps, iconTint = c.teal, title = name,
                        leading = icon?.let { { Image(it, null, Modifier.size(32.dp)) } },
                        onClick = { muted = toggle(context, app, muted) },
                        trailing = { TintSwitch(app !in muted) { muted = toggle(context, app, muted) } },
                    )
                }
            }
        }
    }
}

private fun toggle(context: Context, app: String, muted: Set<String>): Set<String> {
    Prefs.setAppMuted(context, app, app !in muted)
    return Prefs.mutedApps(context)
}

private fun appIcon(pm: PackageManager, app: String): ImageBitmap? = try {
    pm.getApplicationIcon(app).toBitmap(96, 96).asImageBitmap()
} catch (_: PackageManager.NameNotFoundException) {
    null
}
