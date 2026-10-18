package io.github.freeyoung.tether

import android.Manifest
import android.app.ActivityOptions
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

// ClipboardWatcher sends what the user copies while Tether is in the
// background. It is an experimental option, off by default. KDE Connect does
// the same.
//
// Android tells a background app nothing when the clipboard changes, but it
// logs that it denied the app the change. Tether reads the log for that line,
// and opens SendClipboardActivity for a moment to read the clipboard.
//
// It needs READ_LOGS, which only adb grants, and the permission to display
// over other apps, to open an activity from the background. And on Android 13
// and later, the user must allow each new log reader in a dialog, which
// Android shows only while Tether is on the screen. So the watcher starts
// from MainActivity, and stops working when the process ends.
object ClipboardWatcher {
    enum class State { Disabled, NeedsPermission, NeedsApproval, Active }

    // A log access request that Android answered is answered the same way
    // for 60 seconds (LogcatManagerService).
    private const val ANSWER_KEPT_MILLIS = 60_000L

    // Tether's own write of a clip from a peer changes the clipboard too.
    private const val OWN_WRITE_MILLIS = 2000L

    // One copy can log the denial twice, up to about 0.6 s apart.
    private const val REPEAT_MILLIS = 1500L

    private val mutableState = MutableStateFlow(State.Disabled)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())

    // It is never called in the background, but Android logs the denial only
    // for an app that listens.
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {}

    private var process: Process? = null
    private var startedAt = 0L
    @Volatile private var probeAt = 0L
    @Volatile private var lastOpen = 0L

    fun hasLogAccess(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun canOpenFromBackground(context: Context) = Settings.canDrawOverlays(context)

    // Call it while Tether is on the screen: only then does Android ask the
    // user for log access.
    @Synchronized
    fun onAppVisible(context: Context) {
        val app = context.applicationContext
        if (!Prefs.autoSend(app) || !Prefs.enabled(app)) return stopWith(app, State.Disabled)
        if (!hasLogAccess(app) || !canOpenFromBackground(app)) return stopWith(app, State.NeedsPermission)
        if (mutableState.value == State.Active && process != null) return
        // Asking again within the minute gets the same answer, without a dialog.
        if (process != null && SystemClock.elapsedRealtime() - startedAt < ANSWER_KEPT_MILLIS) return
        restart(app)
    }

    // Call it when Tether leaves the screen: a clipboard check from the
    // background proves that Tether sees the denials.
    @Synchronized
    fun onAppHidden(context: Context) {
        if (mutableState.value != State.NeedsApproval || process == null) return
        val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
        main.postDelayed({
            probeAt = SystemClock.elapsedRealtime()
            runCatching { clipboard.primaryClipDescription }
        }, 1000)
    }

    @Synchronized
    fun stop(context: Context) = stopWith(context.applicationContext, if (Prefs.autoSend(context)) State.NeedsApproval else State.Disabled)

    private fun stopWith(context: Context, state: State) {
        process?.let {
            it.destroy()
            context.getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(listener)
        }
        process = null
        mutableState.value = state
    }

    private fun restart(context: Context) {
        stopWith(context, State.NeedsApproval)
        val p = try {
            // -T 1: only lines from now on.
            ProcessBuilder("logcat", "-T", "1", "-s", "ClipboardService").redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.w(TAG, "could not read the log", e)
            return
        }
        context.getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(listener)
        process = p
        startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "watching the clipboard")
        val needle = "Denying clipboard access to ${context.packageName}"
        thread(name = "tether-clipboard-watch") {
            try {
                p.inputStream.bufferedReader().forEachLine { if (needle in it) denied(context) }
            } catch (_: Exception) {
            }
            // The log reader ended: Android stopped it, or stop() did.
            synchronized(this) {
                if (process === p) {
                    process = null
                    mutableState.value = State.NeedsApproval
                }
            }
        }
    }

    private fun denied(context: Context) {
        // Only an approved reader sees the lines of other apps.
        mutableState.value = State.Active
        val now = SystemClock.elapsedRealtime()
        if (now - probeAt < REPEAT_MILLIS || now - PhoneClipboard.lastWrite < OWN_WRITE_MILLIS || now - lastOpen < REPEAT_MILLIS) return
        lastOpen = now
        context.startActivity(
            Intent(context, SendClipboardActivity::class.java)
                .putExtra(SendClipboardActivity.AUTOMATIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle(),
        )
    }
}
