package com.msrcevirici.msr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.msrcevirici.msr.capture.ScreenTranslateService
import com.msrcevirici.msr.capture.stopScreenTranslate
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Flutter arayüzü ile native yakalama servisi arasındaki köprü ("msr/control" kanalı). */
class MainActivity : FlutterActivity() {

    private companion object {
        const val CHANNEL = "msr/control"
        const val REQUEST_CAPTURE = 1001
        const val REQUEST_NOTIFICATIONS = 1002
    }

    private var pendingStart: MethodChannel.Result? = null
    private var pendingArgs: MethodCall? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "status" -> result.success(status())
                "requestOverlayPermission" -> {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    result.success(null)
                }
                "requestNotificationPermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                    }
                    result.success(null)
                }
                "start" -> start(call, result)
                "stop" -> {
                    stopScreenTranslate()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun status(): Map<String, Boolean> = mapOf(
        "overlayPermission" to Settings.canDrawOverlays(this),
        "notificationPermission" to (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ),
        "running" to ScreenTranslateService.isRunning,
    )

    private fun start(call: MethodCall, result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(this)) {
            result.error("NO_OVERLAY", "Üstte gösterme izni verilmedi", null)
            return
        }
        if (pendingStart != null) {
            result.error("BUSY", "Onay ekranı zaten açık", null)
            return
        }
        pendingStart = result
        pendingArgs = call
        // Onay token'ı tek kullanımlıktır; her oturumda yeniden istenir.
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    // FlutterActivity bir ComponentActivity değil; Activity Result API yerine klasik geri çağrı.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val result = pendingStart ?: return
        val call = pendingArgs
        pendingStart = null
        pendingArgs = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            result.success(false)
            return
        }
        val intent = Intent(this, ScreenTranslateService::class.java)
            .setAction(ScreenTranslateService.ACTION_START)
            .putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenTranslateService.EXTRA_DATA, data)
            .putExtra(ScreenTranslateService.EXTRA_SECURE_OVERLAY, call?.argument<Boolean>("secureOverlay") ?: true)
            .putExtra(ScreenTranslateService.EXTRA_INTERVAL_MS, call?.argument<Int>("intervalMs") ?: 700)
            .putExtra(
                ScreenTranslateService.EXTRA_CAPTURE_SCALE,
                (call?.argument<Double>("captureScale") ?: 0.5).toFloat(),
            )
        startForegroundService(intent)
        result.success(true)
    }
}
