package io.github.freeyoung.tether

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

// A clip that went through Tether. source is the peer it came from, "" for a
// peer whose name is not known, or null for this phone.
data class ClipEntry(val id: Long, val text: String?, val image: String?, val source: String?, val time: Long)

data class FileEntry(
    val id: Long,
    val name: String,
    val peer: String,
    val incoming: Boolean,
    val ok: Boolean,
    val reason: String,
    val size: Long,
    // Where a received file is now, in Downloads.
    val uri: Uri?,
    val time: Long,
)

// History is what the Clipboard and Files screens list. It lasts as long as
// the process, which the service keeps alive.
object History {
    private const val MAX = 50
    private val ids = AtomicLong()

    private val mutableClips = MutableStateFlow(emptyList<ClipEntry>())
    val clips: StateFlow<List<ClipEntry>> = mutableClips.asStateFlow()

    private val mutableFiles = MutableStateFlow(emptyList<FileEntry>())
    val files: StateFlow<List<FileEntry>> = mutableFiles.asStateFlow()

    fun clip(text: String?, image: String?, source: String?) = mutableClips.update { list ->
        val top = list.firstOrNull()
        // The same text again, as when a clip comes back from another peer.
        if (text != null && top?.text == text) return@update list
        (listOf(ClipEntry(ids.incrementAndGet(), text, image, source, System.currentTimeMillis())) + list).take(MAX)
    }

    fun file(name: String, peer: String, incoming: Boolean, ok: Boolean, reason: String, size: Long, uri: Uri?) =
        mutableFiles.update { list ->
            (listOf(FileEntry(ids.incrementAndGet(), name, peer, incoming, ok, reason, size, uri, System.currentTimeMillis())) + list).take(MAX)
        }
}
