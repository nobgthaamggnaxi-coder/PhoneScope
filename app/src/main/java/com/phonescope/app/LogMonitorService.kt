package com.phonescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shizuku mode: follows Roblox's log output (logcat + log files) instead of recording the screen.
 * Biomes come from Sol's RNG's "[BloxstrapRPC]" status lines; merchants from "<name> has arrived" lines.
 */
class LogMonitorService : Service() {

    companion object {
        private const val TAG = "PhoneScope"
        const val ACTION_STOP = "com.phonescope.app.STOP_LOG"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var merchants: Detector
    private val procs = CopyOnWriteArrayList<Process>()
    private val lock = Any()
    @Volatile private var current: String? = null
    @Volatile private var tailFile: String? = null
    @Volatile private var tailProc: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (MacroStatus.logRunning) return START_NOT_STICKY
        if (!ShizukuShell.hasPermission()) {
            MacroStatus.post("Shizuku is not running or permission was not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        prefs = Prefs(this)
        merchants = Detector(emptyList(), prefs.merchantList())
        MacroStatus.logRunning = true
        MacroStatus.post("Running (Shizuku) – waiting for a biome…")
        Alerts.status(prefs, "Macro Started")

        thread("initial") { readCurrentBiome() }
        thread("logcat") { logcatLoop() }
        thread("logfile") { logFileLoop() }
        return START_NOT_STICKY
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread({
            try { body() } catch (e: Exception) { Log.w(TAG, "$name stopped", e) }
        }, "phonescope-$name").apply { isDaemon = true; start() }

    /** Announces the biome you're in right now (the last status line Roblox wrote). */
    private fun readCurrentBiome() {
        val last = ShizukuShell.run(
            "{ logcat -d -v raw 2>/dev/null | grep BloxstrapRPC; " +
                "f=\$(find ${ShizukuShell.ROBLOX_DIR} -type f -name '*.log' -exec ls -t {} + 2>/dev/null | head -n 1); " +
                "[ -n \"\$f\" ] && grep BloxstrapRPC \"\$f\"; } | tail -n 1"
        )
        LogParser.biome(last)?.let { onBiome(it, "start") }
    }

    /** Follows every app's log output; restarts if logcat exits. */
    private fun logcatLoop() {
        while (MacroStatus.logRunning) {
            try {
                val p = ShizukuShell.start("logcat", "-v", "raw", "-T", "1")
                procs += p
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!MacroStatus.logRunning) break
                        onLine(line, "logcat")
                    }
                }
                procs -= p
            } catch (e: Exception) {
                Log.w(TAG, "logcat failed", e)
            }
            if (MacroStatus.logRunning) Thread.sleep(3000)
        }
    }

    /** Follows the newest Roblox log file (if Roblox writes one where shell can read it). */
    private fun logFileLoop() {
        while (MacroStatus.logRunning) {
            val newest = runCatching { ShizukuShell.newestRobloxLog() }.getOrNull()
            if (newest != null && newest != tailFile) {
                tailProc?.destroy()
                tailFile = newest
                thread("tail") {
                    val p = ShizukuShell.start("tail", "-n", "0", "-f", newest)
                    tailProc = p
                    procs += p
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!MacroStatus.logRunning || tailFile != newest) break
                            onLine(line, "log file")
                        }
                    }
                    procs -= p
                }
            }
            Thread.sleep(20_000)
        }
    }

    private fun onLine(line: String, source: String) {
        LogParser.biome(line)?.let { onBiome(it, source) }
        if (line.contains("arriv", ignoreCase = true)) {
            val found = synchronized(lock) { merchants.onChatText(listOf(line)) }
            for (m in found) {
                Alerts.merchant(prefs, m.name, m.line)
                MacroStatus.post("Merchant: ${m.name}! (from $source)")
            }
        }
    }

    private fun onBiome(biome: String, source: String) {
        val old = synchronized(lock) {
            if (biome == current) return
            current.also { current = biome }
        }
        old?.let { if (prefs.notifies(it)) Alerts.biomeEnded(prefs, it) }
        if (prefs.notifies(biome)) Alerts.biomeStarted(prefs, biome)
        val time = DateFormat.getTimeInstance().format(Date())
        MacroStatus.post("Biome: $biome  •  since $time (from $source)")
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification("Biome: $biome"))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Macro running", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, LogMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PhoneScope is watching Sol's RNG")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        val wasRunning = MacroStatus.logRunning
        MacroStatus.logRunning = false
        tailFile = null
        procs.forEach { runCatching { it.destroy() } }
        procs.clear()
        if (wasRunning) {
            Alerts.status(prefs, "Macro Stopped")
            MacroStatus.post("Stopped")
        }
        super.onDestroy()
    }
}

fun Context.startLogMonitor() {
    androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, LogMonitorService::class.java))
}
