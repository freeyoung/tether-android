package io.github.freeyoung.tether

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

// PhoneClipboard is the system clipboard of the phone. Android lets an app
// write it at any time, but read it only while the app has the focus.
object PhoneClipboard {
    // Marks what Tether wrote, so that it never goes back to the peers.
    private const val FROM_PEER = "io.github.freeyoung.tether.FROM_PEER"

    // Android's own extra, which keyboards and password managers set on secrets.
    private const val IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    // The iPhone app and tetherd reject clipboard images above 32 MiB.
    private const val MAX_IMAGE_BYTES = 32L shl 20

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    // The timestamp of the clip that was read last, so that the app reads
    // each clip once, and Android shows its "pasted" message once.
    @Volatile private var lastRead = 0L

    // When Tether last wrote the clipboard, in SystemClock.elapsedRealtime.
    @Volatile var lastWrite = 0L
        private set

    sealed interface Result {
        data object Sent : Result
        data object Nothing : Result
        data object Secret : Result
        data object NotRunning : Result
    }

    // Writes content from a peer. Call it on the main thread.
    fun write(context: Context, event: Event) {
        put(context, event.text, event.png)
    }

    // Puts text, or the PNG file at png, on the clipboard, marked so that the
    // app never sends it on focus. Returns false when the file is gone.
    private fun put(context: Context, text: String?, png: String?): Boolean {
        val label = context.getString(R.string.app_name)
        val clip = when {
            text != null -> ClipData.newPlainText(label, text)
            png != null && File(png).exists() ->
                ClipData.newUri(context.contentResolver, label, FileProvider.getUriForFile(context, "${context.packageName}.files", File(png)))
            else -> return false
        }
        clip.description.extras = PersistableBundle().apply { putBoolean(FROM_PEER, true) }
        lastWrite = SystemClock.elapsedRealtime()
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        return true
    }

    // Reads the clipboard and sends it to the peers. Call it while an activity
    // of the app has the focus. With force, it sends a clip that was read before.
    fun send(context: Context, force: Boolean): Result {
        if (!Tether.running) return Result.NotRunning
        val manager = context.getSystemService(ClipboardManager::class.java)
        val description = manager.primaryClipDescription ?: return Result.Nothing
        val extras = description.extras
        if (extras?.getBoolean(IS_SENSITIVE) == true) return Result.Secret
        if (!force && (extras?.getBoolean(FROM_PEER) == true || description.timestamp == lastRead)) {
            return Result.Nothing
        }
        lastRead = description.timestamp
        val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return Result.Nothing
        val uri = item.uri
        val type = uri?.let { context.contentResolver.getType(it) }
        if (uri != null && (type?.startsWith("image/") == true || description.hasMimeType("image/*"))) {
            val png = imageAsPng(context, uri) ?: return Result.Nothing
            val file = saveSent(context, png)
            Tether.copyPng(file.path)
            History.clip(null, file.path, null)
            return Result.Sent
        }
        val text = item.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return Result.Nothing
        Tether.copyText(text)
        History.clip(text, null, null)
        return Result.Sent
    }

    // Puts a clip of the history on the phone's clipboard and sends it to the
    // peers again. Returns false when its image is gone.
    fun copyAgain(context: Context, entry: ClipEntry): Boolean {
        if (!put(context, entry.text, entry.image)) return false
        if (entry.text != null) Tether.copyText(entry.text) else Tether.copyPng(entry.image!!)
        return true
    }

    // What to tell the user after a send that the user asked for.
    fun message(context: Context, result: Result): String = when (result) {
        Result.Sent -> {
            val count = Tether.state.value?.connected?.size ?: 0
            if (count == 0) context.getString(R.string.sent_nobody)
            else context.resources.getQuantityString(R.plurals.sent_to, count, count)
        }
        Result.Nothing -> context.getString(R.string.clipboard_empty)
        Result.Secret -> context.getString(R.string.clipboard_secret)
        Result.NotRunning -> context.getString(R.string.not_running)
    }

    // Images this phone sent stay a while for the history, as images from peers do.
    private fun saveSent(context: Context, png: ByteArray): File {
        val dir = File(context.cacheDir, "sent").apply { mkdirs() }
        val file = File(dir, "sent-${System.currentTimeMillis()}.png")
        file.writeBytes(png)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        return file
    }

    // The image at uri as PNG: as it is when it is a PNG, else converted.
    private fun imageAsPng(context: Context, uri: Uri): ByteArray? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            // At most 1 byte more than the limit, to tell that it is too large.
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= MAX_IMAGE_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray().takeIf { it.size <= MAX_IMAGE_BYTES }
        }
        when {
            bytes == null -> null
            bytes.size >= 8 && bytes.copyOf(8).contentEquals(PNG_SIGNATURE) -> bytes
            else -> {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray().takeIf { it.size <= MAX_IMAGE_BYTES }
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the copied image", e)
        null
    }
}
