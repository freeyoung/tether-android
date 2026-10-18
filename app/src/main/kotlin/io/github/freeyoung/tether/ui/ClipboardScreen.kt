package io.github.freeyoung.tether.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.freeyoung.tether.ClipEntry
import io.github.freeyoung.tether.ClipboardTileService
import io.github.freeyoung.tether.History
import io.github.freeyoung.tether.PhoneClipboard
import io.github.freeyoung.tether.R
import io.github.freeyoung.tether.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ClipboardScreen(s: Snapshot?, actions: AppActions) {
    val clips by History.clips.collectAsStateWithLifecycle()
    Page(title = stringResource(R.string.tab_clipboard)) {
        val connected = s != null && s.connected.isNotEmpty()
        if (!connected && clips.isEmpty()) {
            EmptyState(R.drawable.sym_content_paste, stringResource(R.string.clipboard_not_connected), iconSize = 48.dp, top = 120.dp)
            return@Page
        }
        if (!connected) {
            // The history stays useful without a device.
            Panel {
                PanelRow(R.drawable.sym_link, Tint.colors.secondary, stringResource(R.string.clipboard_not_connected))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connected) {
                BigTile(
                    stringResource(R.string.send_to_devices), stringResource(R.string.send_to_devices_text),
                    R.drawable.sym_arrow_circle_up_fill1, Tint.colors.teal, Modifier.weight(1f),
                ) { sendClipboard(actions) }
            }
            BigTile(
                stringResource(R.string.add_tile), stringResource(R.string.add_tile_text),
                R.drawable.sym_dashboard_customize_fill1, Tint.colors.indigo, Modifier.weight(1f),
            ) { addTile(actions) }
        }
        Section(stringResource(R.string.history)) {
            if (clips.isEmpty()) {
                EmptyState(R.drawable.sym_history, stringResource(R.string.history_empty), iconSize = 32.dp, top = 40.dp)
            }
            clips.forEach { entry ->
                ClipRow(entry) {
                    val ok = PhoneClipboard.copyAgain(actions.context, entry)
                    actions.show(actions.context.getString(if (ok) R.string.copied_again else R.string.image_gone))
                }
            }
        }
        Text(stringResource(R.string.send_clipboard_help), fontSize = 13.sp, color = Tint.colors.secondary)
    }
}

// The tiles "Send to Desktop" and "Get from Desktop" of the iPhone app.
@Composable
private fun BigTile(title: String, subtitle: String, @DrawableRes icon: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Tint.colors.card).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, color, 32.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tint.colors.label)
            Text(subtitle, fontSize = 11.sp, color = Tint.colors.secondary)
        }
    }
}

@Composable
private fun ClipRow(entry: ClipEntry, onClick: () -> Unit) {
    val c = Tint.colors
    val res = LocalResources.current
    val remote = entry.source != null
    val color = if (remote) c.indigo else c.teal
    Panel(radius = 12.dp, onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Sym(if (remote) R.drawable.sym_computer else R.drawable.sym_smartphone, color, 20.dp, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.image != null) {
                    Thumbnail(entry.image)
                } else {
                    Text(entry.text.orEmpty(), fontSize = 15.sp, color = c.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val source = when {
                        !remote -> res.getString(R.string.this_phone)
                        entry.source.isEmpty() -> res.getString(R.string.paired_device)
                        else -> entry.source
                    }
                    Text(source, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
                    Text("·", fontSize = 11.sp, color = c.secondary)
                    Text(relativeTime(entry.time), fontSize = 11.sp, color = c.secondary)
                }
            }
            Sym(R.drawable.sym_content_copy, c.tertiary, 16.dp)
        }
    }
}

@Composable
private fun Thumbnail(path: String) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeSmall(path) }
    }
    val b = bitmap
    if (b == null) {
        Text(stringResource(R.string.image), fontSize = 15.sp, color = Tint.colors.secondary)
        return
    }
    Image(
        b.asImageBitmap(), contentDescription = stringResource(R.string.image),
        Modifier.heightIn(max = 120.dp).clip(RoundedCornerShape(8.dp)),
        alignment = Alignment.CenterStart,
        contentScale = ContentScale.Fit,
    )
}

// An image at about 480 px, for a thumbnail.
private fun decodeSmall(path: String): Bitmap? {
    if (!File(path).exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

// Asks Android to add the Send Clipboard tile to Quick Settings.
fun addTile(actions: AppActions) {
    val context = actions.context
    if (Build.VERSION.SDK_INT < 33) {
        actions.show(context.getString(R.string.add_tile_by_hand))
        return
    }
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, ClipboardTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_notification),
        context.mainExecutor,
    ) { result ->
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) actions.show(context.getString(R.string.tile_already_added))
    }
}
