package io.github.freeyoung.tether

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Files moves received files to Downloads, and stages files the user sends.
object Files {
    // Moves a file that the engine received into the system's Downloads.
    // Returns its new address, or null.
    fun moveToDownloads(context: Context, path: String): Uri? {
        val file = File(path)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(file.name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        file.delete()
        return uri
    }

    fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    // The engine reads files by path, so a shared file is copied to the cache
    // first, with its own name. Returns the paths.
    fun stage(context: Context, uris: List<Uri>): List<String> = uris.mapNotNull { uri ->
        runCatching {
            val dir = File(outgoing(context), UUID.randomUUID().toString()).apply { mkdirs() }
            val file = File(dir, displayName(context, uri))
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            file.path
        }.getOrNull()
    }

    // Stages the files, then has the engine send them to the peer.
    suspend fun send(context: Context, fingerprint: String, uris: List<Uri>) {
        val paths = withContext(Dispatchers.IO) { stage(context, uris) }
        Tether.sendFiles(fingerprint, paths)
    }

    // Deletes a staged file once it is sent, or could not be. Returns its size.
    fun sent(context: Context, name: String): Long {
        val dir = outgoing(context).listFiles()?.firstOrNull { File(it, name).exists() } ?: return 0
        val size = File(dir, name).length()
        dir.deleteRecursively()
        return size
    }

    fun clearStaged(context: Context) {
        outgoing(context).deleteRecursively()
    }

    private fun outgoing(context: Context) = File(context.cacheDir, "outgoing")

    private fun displayName(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
        // Keep only the plain name.
        return name?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"
    }
}
