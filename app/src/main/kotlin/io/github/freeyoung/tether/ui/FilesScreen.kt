package io.github.freeyoung.tether.ui

import android.app.DownloadManager
import android.content.Intent
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.freeyoung.tether.FileEntry
import io.github.freeyoung.tether.Files
import io.github.freeyoung.tether.History
import io.github.freeyoung.tether.R
import io.github.freeyoung.tether.Snapshot
import io.github.freeyoung.tether.Transfer

@Composable
fun FilesScreen(s: Snapshot?, actions: AppActions) {
    val files by History.files.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Page(
        title = stringResource(R.string.tab_files),
        actions = {
            ToolbarPill {
                ToolbarButton(R.drawable.sym_folder_open, stringResource(R.string.open_downloads)) {
                    context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        },
    ) {
        val connected = s != null && s.connected.isNotEmpty()
        if (connected) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SendRow(stringResource(R.string.send_a_file), stringResource(R.string.send_a_file_text), R.drawable.sym_upload_file_fill1, Tint.colors.teal) {
                    actions.pickFiles(null)
                }
                SendRow(stringResource(R.string.send_a_photo), stringResource(R.string.send_a_photo_text), R.drawable.sym_image_fill1, Tint.colors.indigo) {
                    actions.pickPhotos(null)
                }
            }
        } else {
            EmptyState(R.drawable.sym_folder, stringResource(R.string.files_not_connected), iconSize = 48.dp, top = if (files.isEmpty()) 120.dp else 24.dp)
        }
        if (s != null && s.sending.isNotEmpty()) {
            Section(stringResource(R.string.files_in_progress)) {
                s.sending.forEach { TransferRow(it) }
            }
        }
        if (files.isNotEmpty()) {
            Section(stringResource(R.string.completed)) {
                files.forEach { FileRow(it) }
            }
        } else if (connected && s.sending.isEmpty()) {
            EmptyState(R.drawable.sym_inbox, stringResource(R.string.no_transfers), iconSize = 32.dp, top = 40.dp)
        }
    }
}

@Composable
private fun SendRow(title: String, subtitle: String, @DrawableRes icon: Int, color: Color, onClick: () -> Unit) {
    Panel(radius = 16.dp, onClick = onClick) {
        PanelRow(
            icon = null,
            iconTint = color,
            title = title,
            subtitle = subtitle,
            leading = { GradientBadge(icon, color, 48.dp, 24.dp) },
            trailing = { Chevron() },
        )
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    val c = Tint.colors
    val res = LocalResources.current
    Panel {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Sym(iconFor(t.filename), c.teal, 24.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.filename, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.outgoing_to, t.peer), fontSize = 11.sp, color = c.secondary)
                LinearProgressIndicator(
                    progress = { t.fraction.toFloat() },
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = c.teal,
                    trackColor = c.fill,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                val percent = "${(t.fraction * 100).toInt()}%"
                Text(
                    if (t.queued > 0) percent + " · " + res.getQuantityString(R.plurals.queued, t.queued, t.queued) else percent,
                    fontSize = 11.sp, color = c.secondary,
                )
            }
        }
    }
}

@Composable
private fun FileRow(f: FileEntry) {
    val c = Tint.colors
    val context = LocalContext.current
    val open = f.uri?.let { uri ->
        {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Files.mimeType(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(view) }
            Unit
        }
    }
    Panel(onClick = open) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Sym(iconFor(f.name), if (!f.ok) c.red else if (f.incoming) c.blue else c.secondary, 24.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(f.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (f.incoming) R.string.incoming_from else R.string.outgoing_to, f.peer),
                    fontSize = 11.sp, color = c.secondary,
                )
                val size = Formatter.formatShortFileSize(context, f.size)
                val detail = when {
                    !f.ok -> stringResource(R.string.transfer_failed, f.reason)
                    f.incoming -> stringResource(R.string.tap_to_open, size)
                    else -> stringResource(R.string.delivered, size)
                }
                Text(detail, fontSize = 11.sp, color = if (f.ok) c.secondary else c.red, maxLines = 2)
            }
        }
    }
}

@DrawableRes
private fun iconFor(name: String): Int {
    val type = Files.mimeType(name)
    return if (type.startsWith("image/") || type.startsWith("video/")) R.drawable.sym_image else R.drawable.sym_description
}
