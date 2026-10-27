package com.msrcevirici.msr.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.msrcevirici.msr.MainActivity
import com.msrcevirici.msr.R
import com.msrcevirici.msr.pipeline.Box
import com.msrcevirici.msr.pipeline.BoxTracker
import com.msrcevirici.msr.pipeline.Detection
import com.msrcevirici.msr.pipeline.FrameSignature
import com.msrcevirici.msr.pipeline.OverlayItem
import com.msrcevirici.msr.pipeline.TranslationCache
import java.util.concurrent.TimeUnit

/**
 * Faz 0 prototipi: MediaProjection ile ekranı yakalar, ML Kit ile OCR + EN→TR çeviri
 * yapar ve sonuçları dokunmayı geçiren bir overlay'de gösterir.
 *
 * Android 14+ sırası zorunludur: kullanıcı onayı → startForeground(mediaProjection)
 * → getMediaProjection() → registerCallback() → createVirtualDisplay().
 */
class ScreenTranslateService : Service() {

    companion object {
        private const val TAG = "MSR"
        const val ACTION_START = "com.msrcevirici.msr.START"
        const val ACTION_STOP = "com.msrcevirici.msr.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_SECURE_OVERLAY = "secureOverlay"
        const val EXTRA_INTERVAL_MS = "intervalMs"
        const val EXTRA_CAPTURE_SCALE = "captureScale"

        private const val CHANNEL_ID = "msr_capture"
        private const val NOTIFICATION_ID = 1

        private const val GRID_COLS = 16
        private const val GRID_ROWS = 32
        /** Bu orandan fazla karo değişirse kaydırma/sayfa geçişi sayılır, kutular temizlenir. */
        private const val SCROLL_FRACTION = 0.25f
        /** Kutular temizlendikten sonra eski karelerin işlenmemesi için bekleme. */
        private const val SETTLE_MS = 250L

        @Volatile
        var isRunning = false
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: OverlayView? = null

    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator
    private val cache = TranslationCache()
    @Volatile private var modelReady = false
    @Volatile private var modelStatus = "Model indiriliyor"

    // Aşağıdakiler yalnızca worker iş parçacığında kullanılır.
    private var intervalMs = 700L
    private var captureScale = 0.5f
    private var rawBitmap: Bitmap? = null
    private var frameBitmap: Bitmap? = null
    private var frameDirty = false
    private var settleUntil = 0L
    private var prevSignature: IntArray? = null
    private var items: List<OverlayItem> = emptyList()
    private lateinit var hudBox: Box
    private var lastHud = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null || !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Başlatılamadı: onay veya overlay izni yok")
            stopSelf()
            return START_NOT_STICKY
        }

        intervalMs = intent!!.getIntExtra(EXTRA_INTERVAL_MS, 700).toLong().coerceIn(200, 5000)
        captureScale = intent.getFloatExtra(EXTRA_CAPTURE_SCALE, 0.5f).coerceIn(0.25f, 1f)
        val secureOverlay = intent.getBooleanExtra(EXTRA_SECURE_OVERLAY, true)

        // getMediaProjection()'dan ÖNCE ön plan servisi başlamış olmalı.
        startInForeground()

        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        isRunning = true

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { modelReady = true; modelStatus = "" }
            .addOnFailureListener { e -> modelStatus = "Model indirilemedi"; Log.e(TAG, "Model", e) }

        workerThread = HandlerThread("msr-pipeline").also { it.start() }
        worker = Handler(workerThread.looper)

        val metrics = screenMetrics()
        hudBox = Box(
            (8 * metrics.density).toInt(),
            (36 * metrics.density).toInt(),
            (metrics.widthPixels * 0.75f).toInt(),
            (58 * metrics.density).toInt(),
        )
        addOverlay(secureOverlay)
        startCapture(mp, metrics)
        worker.post(tick)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ekran çevirisi", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MSR çeviri açık")
            .setContentText("Ekran metni çevriliyor")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Durdur", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun screenMetrics(): DisplayMetrics {
        val wm = getSystemService(WindowManager::class.java)!!
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.setTo(resources.displayMetrics)
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun addOverlay(secure: Boolean) {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // Deney: FLAG_SECURE ile overlay yakalamada siyah görünür ve kendi çevirimizi OCR'lamayız.
        if (secure) flags = flags or WindowManager.LayoutParams.FLAG_SECURE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+: başka uygulamanın penceresinden dokunma geçmesi için opaklık en fazla 0.8.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val view = OverlayView(this, hudBox)
        getSystemService(WindowManager::class.java)!!.addView(view, params)
        overlayView = view
    }

    private fun startCapture(mp: MediaProjection, metrics: DisplayMetrics) {
        val w = (metrics.widthPixels * captureScale).toInt()
        val h = (metrics.heightPixels * captureScale).toInt()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        // Android 14+: createVirtualDisplay'den önce callback kaydı zorunlu.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)
        virtualDisplay = mp.createVirtualDisplay(
            "msr-capture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null,
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            val started = SystemClock.elapsedRealtime()
            try {
                step()
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline hatası", e)
            }
            if (isRunning) {
                val elapsed = SystemClock.elapsedRealtime() - started
                worker.postDelayed(this, (intervalMs - elapsed).coerceAtLeast(0))
            }
        }
    }

    private fun step() {
        // Sanal ekran yalnızca ekran değişince yeni kare üretir; null = değişiklik yok.
        imageReader?.acquireLatestImage()?.use { image ->
            copyFrame(image)
            frameDirty = true
        }
        val now = SystemClock.elapsedRealtime()
        if (now < settleUntil || !frameDirty) {
            refreshHudIfNeeded()
            return
        }
        frameDirty = false
        val frame = frameBitmap ?: return

        val t0 = SystemClock.elapsedRealtime()
        val signature = signatureOf(frame)
        // Kendi kutularımız + HUD maskelenir.
        val occupied = items.map { it.box } + hudBox
        val mask = FrameSignature.maskTiles(
            occupied.map { it.scaled(captureScale) }, frame.width, frame.height, GRID_COLS, GRID_ROWS,
        )
        val prev = prevSignature
        prevSignature = signature
        val changed = if (prev == null) 1f else FrameSignature.changedFraction(prev, signature, mask)
        if (changed == 0f) return

        if (changed > SCROLL_FRACTION && items.isNotEmpty()) {
            // Kaydırma/sayfa değişimi: eski kutular artık yanlış yerde.
            items = emptyList()
            settleUntil = now + SETTLE_MS
            frameDirty = true
            publish("Değişim %${(changed * 100).toInt()} → temizlendi")
            return
        }

        val t1 = SystemClock.elapsedRealtime()
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0)), 5, TimeUnit.SECONDS)
        val detections = text.textBlocks.mapNotNull { block ->
            val r = block.boundingBox ?: return@mapNotNull null
            Detection(
                Box(r.left, r.top, r.right, r.bottom).scaled(1f / captureScale),
                block.text.replace('\n', ' '),
            )
        }
        val t2 = SystemClock.elapsedRealtime()

        val fresh = BoxTracker.newDetections(occupied, detections, minHeight = (6 / captureScale).toInt())
        val added = fresh.map { d -> OverlayItem(d.box, d.text, translate(d.text)) }
        val t3 = SystemClock.elapsedRealtime()

        if (added.isNotEmpty()) items = items + added
        val hud = "OCR ${t2 - t1}ms · Çeviri ${t3 - t2}ms · Toplam ${t3 - t0}ms · " +
            "${detections.size} blok, +${added.size}"
        Log.d(TAG, hud)
        publish(hud)
    }

    private fun translate(source: String): String {
        cache[source]?.let { return it }
        if (!modelReady) return source
        return try {
            Tasks.await(translator.translate(source), 3, TimeUnit.SECONDS).also { cache[source] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Çeviri başarısız", e)
            source
        }
    }

    private fun copyFrame(image: Image) {
        val plane = image.planes[0]
        val paddedWidth = plane.rowStride / plane.pixelStride
        var raw = rawBitmap
        if (raw == null || raw.width != paddedWidth || raw.height != image.height) {
            raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            rawBitmap = raw
        }
        plane.buffer.rewind()
        raw!!.copyPixelsFromBuffer(plane.buffer)
        // Satır dolgusu varsa gerçek genişliğe kırp.
        frameBitmap = if (paddedWidth == image.width) raw else Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
    }

    private fun signatureOf(frame: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(frame, GRID_COLS, GRID_ROWS, true)
        val pixels = IntArray(GRID_COLS * GRID_ROWS)
        small.getPixels(pixels, 0, GRID_COLS, 0, 0, GRID_COLS, GRID_ROWS)
        small.recycle()
        return FrameSignature.luminance(pixels)
    }

    private fun refreshHudIfNeeded() {
        if (modelStatus.isNotEmpty() && !lastHud.startsWith(modelStatus)) publish("")
    }

    private fun publish(hud: String) {
        val text = listOf(modelStatus, hud).filter { it.isNotEmpty() }.joinToString(" · ")
        lastHud = text
        val snapshot = items
        mainHandler.post { overlayView?.update(snapshot, text) }
    }

    override fun onDestroy() {
        isRunning = false
        if (::workerThread.isInitialized) {
            worker.removeCallbacksAndMessages(null)
            workerThread.quitSafely()
        }
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        overlayView?.let { getSystemService(WindowManager::class.java)!!.removeView(it) }
        overlayView = null
        if (::recognizer.isInitialized) recognizer.close()
        if (::translator.isInitialized) translator.close()
        super.onDestroy()
    }
}

/** Overlay ve servis dışından kontrol için kısa yollar. */
fun Context.stopScreenTranslate() {
    startService(Intent(this, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_STOP))
}
