package ai.soulvai.accuraprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.PowerManager

/**
 * Keeps the bridge alive as a foreground service.
 *
 * This is the whole reason for building an app rather than living in Termux:
 * a foreground service with a visible notification is the arrangement Android
 * actually promises to keep running. A background thread, or a terminal app the
 * user has swiped away, is killed at the OS's discretion — which on a till
 * means receipts silently stop halfway through a shift.
 */
class PrintBridgeService : Service() {

    private var server: BridgeServer? = null
    private var watchdog: Handler? = null
    private val watchdogTick = object : Runnable {
        override fun run() {
            checkStillListening()
            watchdog?.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    /**
     * Set before teardown so nothing re-posts the notification on the way out.
     *
     * Stopping ran server.stop(), which logs "Stopped", which called back into
     * updateNotification() — posting a fresh ongoing notification just as the
     * service was being destroyed. The system removed the foreground one it
     * knew about, and that new copy stayed in the shade: undismissable, with a
     * Stop button for a service that no longer existed.
     */
    @Volatile private var shuttingDown = false
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Keeps the CPU and Wi-Fi up for the duration of a print, and only then.
     *
     * The service used to hold a partial wake lock permanently, which is pure
     * drain on a device that is ever off the charger. In loopback mode it is
     * also unnecessary: the request comes from a browser on this same device,
     * so the CPU is by definition already awake when one arrives. What does
     * need protecting is the hop out to the printer — an aggressive Wi-Fi
     * sleep between accepting the request and reaching the printer is exactly
     * how a print goes missing.
     */
    private val powerGate = object : PowerGate {
        override fun acquire() = acquireLocks()
        override fun release() = releaseLocks()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BridgeLog.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shuttingDown = true
            stopSelf()
            return START_NOT_STICKY
        }
        shuttingDown = false

        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))

        // Always rebuild rather than only starting when idle: this is also the
        // path a settings change takes, and a server left running would keep
        // the old port and printer.
        server?.stop()
        run {
            val s = BridgeServer(
                context = applicationContext,
                printerIp = Prefs.printerIp(this),
                printerPort = Prefs.printerPort(this),
                bridgePort = Prefs.bridgePort(this),
                loopbackOnly = Prefs.loopbackOnly(this),
                onLog = { line -> BridgeLog.append(line); updateNotification(BridgeState.summary()) },
                power = powerGate,
            )
            s.start()
            server = s
            // The bind happens on a worker thread; give it a moment so the
            // notification reflects the real outcome instead of "Starting…".
            android.os.Handler(mainLooper).postDelayed({ updateNotification(BridgeState.summary()) }, 400)
        }

        startWatchdog()
        warnIfBatteryRestricted()
        checkForUpdate()

        // In LAN mode the request arrives from another device, so the radio has
        // to stay reachable even when this one is idle. In loopback mode the
        // locks are taken per print instead.
        if (!Prefs.loopbackOnly(this)) acquireLocks()

        // START_STICKY: if Android reclaims us under memory pressure, come back.
        return START_STICKY
    }

    /**
     * Brings the bridge back on its own when it has stopped listening.
     *
     * START_STICKY covers the service being reclaimed, and the boot receiver
     * covers a reboot. Neither covers the case a counter actually hits: the
     * process survives but the listening socket does not - Wi-Fi drops, the
     * port is taken, a bind fails - and the app sits there in a FAILED or
     * STOPPED state until somebody notices bills are not printing and opens
     * it to press a button. That is a cashier discovering it mid-sale.
     *
     * A till cannot rely on being watched, so it checks itself.
     */
    private fun startWatchdog() {
        if (watchdog == null) watchdog = Handler(mainLooper)
        watchdog?.removeCallbacks(watchdogTick)
        watchdog?.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
    }

    private fun checkStillListening() {
        if (shuttingDown) return
        if (!Prefs.autoStart(this)) return  // a deliberate Stop stays stopped
        val s = server
        if (s != null && s.isRunning() && BridgeState.status == BridgeState.Status.RUNNING) return
        BridgeLog.append("Bridge was not listening — restarting it")
        try {
            s?.stop()
            val fresh = BridgeServer(
                context = applicationContext,
                printerIp = Prefs.printerIp(this),
                printerPort = Prefs.printerPort(this),
                bridgePort = Prefs.bridgePort(this),
                loopbackOnly = Prefs.loopbackOnly(this),
                onLog = { line -> BridgeLog.append(line); updateNotification(BridgeState.summary()) },
                power = powerGate,
            )
            fresh.start()
            server = fresh
        } catch (e: Exception) {
            BridgeLog.append("Could not restart the bridge: ${e.message}")
        }
    }

    /**
     * Says so, in the log and the notification, when Android is allowed to
     * stop us. Battery optimisation is the single most common reason a till
     * stops printing halfway through a shift, and it fails silently: the app
     * still looks installed, its settings are still there, and the only
     * symptom is bills not coming out.
     */
    private fun warnIfBatteryRestricted() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                BridgeLog.append("Battery optimisation is ON — Android may stop the bridge. Open Accura Print and tap Fix.")
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        shuttingDown = true
        watchdog?.removeCallbacks(watchdogTick)
        watchdog = null
        server?.stop()
        server = null
        releaseLocks(force = true)
        // Take the notification down explicitly. Relying on the service dying
        // to remove it is what let a re-posted copy outlive the service.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (_: Exception) {}
        super.onDestroy()
    }

    /**
     * Asks GitHub once a day whether there is a newer release.
     *
     * Result goes into the ongoing notification rather than a new one: this
     * thing is meant to sit quietly in the shade, and an app that invents its
     * own alert to tell you about itself is the kind you end up silencing.
     */
    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheck = now
        Thread {
            val current = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: return@Thread
            } catch (_: Exception) { return@Thread }
            val r = UpdateCheck.fetch(current) ?: return@Thread
            if (r.newer) {
                BridgeState.updateAvailable = r.latest
                BridgeLog.append("Update available: ${r.latest}")
                android.os.Handler(mainLooper).post { updateNotification(BridgeState.summary()) }
            } else {
                BridgeState.updateAvailable = null
            }
        }.start()
    }

    @Synchronized
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                // Partial: CPU stays available, screen is free to sleep.
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EscPosBridge::print")
                    .apply { setReferenceCounted(false) }
            }
            // Timeout is a backstop: if a print somehow never returns, the lock
            // still lets go rather than draining the battery until reboot.
            if (wakeLock?.isHeld != true) wakeLock?.acquire(LOCK_TIMEOUT_MS)

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EscPosBridge::wifi")
                    .apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (e: Exception) {
            BridgeLog.append("Could not take wake/wifi lock: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseLocks(force: Boolean = false) {
        // In LAN mode the locks are held for as long as the service runs.
        if (!force && !Prefs.loopbackOnly(this)) return
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_desc); setShowBadge(false) }
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PrintBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val failed = BridgeState.status == BridgeState.Status.FAILED
        return builder
            .setContentTitle(getString(if (failed) R.string.notif_title_failed else R.string.notif_title))
            .setContentText(text)
            // Pull the notification down and the whole picture is there —
            // listening address, printer, counts, and how the last receipt
            // went — without unlocking the tablet or opening the app.
            .setStyle(Notification.BigTextStyle().bigText(BridgeState.details()))
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        if (shuttingDown) return
        try {
            (getSystemService(NotificationManager::class.java))
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ID = "printbridge"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "ai.soulvai.accuraprint.STOP"
        /** Backstop so a hung print cannot hold the CPU awake indefinitely. */
        const val LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        /** Often enough that a dropped bridge is back before the next customer. */
        const val WATCHDOG_INTERVAL_MS = 30 * 1000L
        @Volatile private var lastUpdateCheck = 0L
    }
}
