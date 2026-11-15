package pl.btmap.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pl.btmap.core.BtmapCore
import pl.btmap.core.PowerMode
import pl.btmap.mobile.BtmapApp
import pl.btmap.mobile.MainActivity
import pl.btmap.mobile.R

/**
 * Keeps the core running while the app is not on screen and shows the current bands and station
 * in a silent, ongoing notification.
 *
 * Battery: the service only picks the power mode. With the screen off the core drops to
 * [PowerMode.IDLE] (no own GPS, sparse polling, no wake lock); the notification is rebuilt only
 * when its text changes.
 */
class MonitorService : Service() {

    private val core: BtmapCore get() = (application as BtmapApp).core
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: NotificationManager
    private var lastContent: NotificationContent? = null
    private val collecting = java.util.concurrent.atomic.AtomicBoolean(false)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateMode()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        ensureChannel()
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stops until the app is opened again; opening it starts monitoring in the background again.
            stopSelf()
            if (!uiVisible) core.stop()
            return START_NOT_STICKY
        }
        val initial = NotificationContent("BTMap", "Uruchamiam monitor sieci…", emptyList())
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(initial), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // Android 14+ refuses a location service started while the app is in the background
            // (e.g. a sticky restart). It starts again the next time the app is opened.
            stopSelf()
            return START_NOT_STICKY
        }
        lastContent = initial
        if (!core.hasPermissions()) {
            stopSelf()
            return START_NOT_STICKY
        }
        core.start()
        updateMode()
        if (collecting.compareAndSet(false, true)) {
            scope.launch {
                core.samples
                    .map(NotificationText::from)
                    .distinctUntilChanged()
                    .collect { content ->
                        if (content != lastContent) {
                            lastContent = content
                            notifications.notify(NOTIFICATION_ID, build(content))
                        }
                    }
            }
        }
        // Restarted by the system after being killed for memory.
        return START_STICKY
    }

    private fun updateMode() {
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        core.setMode(
            when {
                uiVisible -> PowerMode.LIVE
                screenOn -> PowerMode.BACKGROUND
                else -> PowerMode.IDLE
            },
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL_ID) != null) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Monitor sieci", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aktualne pasma i stacja, gdy BTMap działa w tle"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    private fun build(content: NotificationContent): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_btmap)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.header)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(0xFF176F5B.toInt())
            .addAction(0, "Zatrzymaj", stop)
        if (content.lines.size > 1) {
            builder.setStyle(
                NotificationCompat.InboxStyle().also { style -> content.lines.forEach(style::addLine) },
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "pl.btmap.mobile.STOP"

        @Volatile private var instance: MonitorService? = null

        /** Set by the activity; LIVE mode while the app is on screen. */
        @Volatile var uiVisible: Boolean = false
            set(value) {
                field = value
                instance?.updateMode()
            }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    init {
        instance = this
    }
}
