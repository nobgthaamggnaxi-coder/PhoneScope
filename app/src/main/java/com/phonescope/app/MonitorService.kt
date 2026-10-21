package com.phonescope.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service: captures the screen every few seconds, reads it with on-device OCR,
 * and sends Discord alerts for biome changes and merchants.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "PhoneScope"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.phonescope.app.STOP"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val MAX_SIDE = 1600 // downscale big screens to keep OCR fast

        val running get() = MacroStatus.screenRunning
        fun postStatus(s: String) = MacroStatus.post(s)
    }

    private lateinit var prefs: Prefs
    private lateinit var detector: Detector
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        worker = HandlerThread("phonescope-scan").also { it.start() }
        handler = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground BEFORE asking for the MediaProjection (Android 14+ rule).
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (code != Activity.RESULT_OK || data == null) {
            postStatus("Screen capture permission was not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) {
            postStatus("Could not start screen capture")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                postStatus("Screen capture stopped")
                stopSelf()
            }
        }, handler)

        detector = Detector(prefs.biomeList(), prefs.merchantList())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneScope::scan")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        setupDisplay()
        MacroStatus.screenRunning = true
        postStatus("Running – waiting for a biome…")
        Alerts.status(prefs, "Macro Started")
        handler.postDelayed(scanLoop, 1500)
        return START_NOT_STICKY
    }

    // ---------- screen capture ----------

    private fun captureSize(): Triple<Int, Int, Int> {
        val dm = getSystemService(DisplayManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(metrics.widthPixels, metrics.heightPixels))
        return Triple(
            (metrics.widthPixels * scale).roundToInt(),
            (metrics.heightPixels * scale).roundToInt(),
            metrics.densityDpi
        )
    }

    private fun setupDisplay() {
        val (w, h, dpi) = captureSize()
        width = w; height = h
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = display
        if (vd == null) {
            display = projection?.createVirtualDisplay(
                "PhoneScope", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, handler
            )
        } else {
            // Rotation: reuse the same virtual display (Android 14 allows only one per permission).
            vd.resize(w, h, dpi)
            vd.surface = newReader.surface
        }
        reader?.close()
        reader = newReader
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val (w, h, _) = captureSize()
            if (display != null && (w != width || h != height)) setupDisplay()
        }
    }

    private fun grabFrame(): Bitmap? {
        val image = try { reader?.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } finally {
            image.close()
        }
    }

    private fun crop(src: Bitmap, r: RectF): Bitmap {
        val l = (r.left * src.width).toInt().coerceIn(0, src.width - 1)
        val t = (r.top * src.height).toInt().coerceIn(0, src.height - 1)
        val rr = (r.right * src.width).toInt().coerceIn(l + 1, src.width)
        val b = (r.bottom * src.height).toInt().coerceIn(t + 1, src.height)
        return if (l == 0 && t == 0 && rr == src.width && b == src.height) src
        else Bitmap.createBitmap(src, l, t, rr - l, b - t)
    }

    private fun ocr(bmp: Bitmap): List<String> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS)
        return result.textBlocks.flatMap { block -> block.lines.map { it.text } }
    }

    // ---------- scan loop ----------

    private val scanLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                scanOnce()
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
                postStatus("Scan error: ${e.message}")
            }
            handler.postDelayed(this, prefs.scanIntervalSec * 1000L)
        }
    }

    private fun scanOnce() {
        val frame = grabFrame() ?: return
        val biomeRegion = Prefs.parseRegion(prefs.biomeRegion)
        val chatRegion = Prefs.parseRegion(prefs.chatRegion)

        val biomeLines = ocr(crop(frame, biomeRegion))
        val chatLines = if (chatRegion == biomeRegion) biomeLines else ocr(crop(frame, chatRegion))

        detector.onBiomeText(biomeLines)?.let { change ->
            change.old?.let { if (prefs.notifies(it)) Alerts.biomeEnded(prefs, it) }
            if (prefs.notifies(change.new)) Alerts.biomeStarted(prefs, change.new)
        }
        for (m in detector.onChatText(chatLines)) {
            Alerts.merchant(prefs, m.name, m.line)
            postStatus("Merchant: ${m.name}!")
        }

        val biome = detector.current ?: "unknown"
        val seen = biomeLines.take(4).joinToString(" | ").take(120)
        val time = java.text.DateFormat.getTimeInstance().format(java.util.Date())
        postStatus("Biome: $biome  •  last scan $time\nOCR sees: ${seen.ifBlank { "(no text)" }}")
        updateNotification("Biome: $biome")
        frame.recycle()
    }

    // ---------- notification ----------

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Macro running", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
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

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        val wasRunning = running
        MacroStatus.screenRunning = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            display?.release(); display = null
            reader?.close(); reader = null
            projection?.stop(); projection = null
            recognizer.close()
            worker.quitSafely()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        if (wasRunning) Alerts.status(prefs, "Macro Stopped")
        postStatus("Stopped")
        super.onDestroy()
    }
}

/** Helper so the activity can start / stop the service. */
fun Context.startMonitor(resultCode: Int, data: Intent) {
    val i = Intent(this, MonitorService::class.java)
        .putExtra(MonitorService.EXTRA_CODE, resultCode)
        .putExtra(MonitorService.EXTRA_DATA, data)
    androidx.core.content.ContextCompat.startForegroundService(this, i)
}

fun Context.stopMonitor() {
    stopService(Intent(this, MonitorService::class.java))
}
