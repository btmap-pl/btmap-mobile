package pl.btmap.mobile.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Connection to Shizuku (https://shizuku.rikka.app): a user-installed service that lets an app run
 * shell-level commands after the user authorises it. BTMap uses it to grant itself
 * `android.permission.DUMP` — the same one-time `pm grant` that otherwise needs a computer — so
 * carrier aggregation is read from the modem.
 */
class ShizukuBridge(context: Context) {

    enum class Status {
        /** Shizuku app not installed. */
        NOT_INSTALLED,
        /** Installed, but its service is not started (needs wireless debugging, adb or root once). */
        NOT_RUNNING,
        /** Running; BTMap is not authorised in Shizuku yet. */
        NO_PERMISSION,
        /** Authorised: Shizuku can be used. */
        READY,
    }

    private val appContext = context.applicationContext
    private val _status = MutableStateFlow(compute())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun refresh() {
        _status.value = compute()
    }

    private fun compute(): Status {
        if (!isInstalled()) return Status.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return Status.NOT_RUNNING
        val granted = runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Status.READY else Status.NO_PERMISSION
    }

    private fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shows Shizuku's own "allow BTMap?" dialog. */
    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
        refresh()
    }

    /** Opens the Shizuku app (to start its service). */
    fun openShizuku(context: Context) {
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let { context.startActivity(it) }
    }

    val hasDump: Boolean
        get() = appContext.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** Grants BTMap `android.permission.DUMP` through Shizuku. Returns true on success. */
    suspend fun grantDump(): Boolean = withContext(Dispatchers.IO) {
        if (hasDump) return@withContext true
        if (_status.value != Status.READY) return@withContext false
        run("pm", "grant", appContext.packageName, Manifest.permission.DUMP) == 0 && hasDump
    }

    /**
     * Runs a command with Shizuku's (shell) identity. `Shizuku.newProcess` is not public API in
     * Shizuku 13 but is still the supported way for short commands; it is called reflectively.
     */
    private fun run(vararg command: String): Int = exec(*command).first

    /** Runs a shell command through Shizuku; returns exit code and combined output (-1 on failure). */
    fun exec(vararg command: String): Pair<Int, String> = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf(*command), null, null) as Process
        val out = process.inputStream.bufferedReader().readText() + process.errorStream.bufferedReader().readText()
        process.waitFor() to out
    }.getOrDefault(-1 to "")

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4_2_1
    }
}
