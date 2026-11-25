package com.droidweb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Foreground service that captures the screen and serves it on the web.
 *
 * Start sequence (Android 14+ rules — get this order right):
 *   1. MainActivity gets the screen-capture permission via
 *      MediaProjectionManager.createScreenCaptureIntent().
 *   2. It starts this service (startForegroundService) with the
 *      resultCode + result Intent.
 *   3. onStartCommand calls startForeground() FIRST, with
 *      FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, and only then calls
 *      getMediaProjection(). Doing it the other way round throws a
 *      SecurityException on API 34+.
 *
 * Capture: VirtualDisplay (at half resolution to save bandwidth) ->
 * ImageReader -> acquireLatestImage -> Bitmap -> JPEG (quality 70) ->
 * FrameBroadcaster. Throttled to ~12 fps so a slow browser never
 * drowns the device.
 */
class ScreenStreamService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webServer: WebServer? = null

    private val frames = FrameBroadcaster()
    private val passwords by lazy { PasswordStore(this) }
    private val input by lazy { InputInjector() }
    private var tunnel: TunnelManager? = null

    private var displayWidth = 0
    private var displayHeight = 0
    private var lastFrameMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "started without capture permission — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        // 1. Foreground FIRST (Android 14+ requires this before
        //    getMediaProjection, and within seconds of service start).
        //    The 3-arg startForeground (with the service type) only
        //    exists from API 29; older devices use the 2-arg form and
        //    the manifest's foregroundServiceType.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, buildNotification(), foregroundServiceType()
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // 2. Then the projection.
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stopSelf()
                }
            }, null)
            startCapture()
            startWebServer()
        } catch (e: Exception) {
            Log.e(TAG, "failed to start capture: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0

    private fun startCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        // Half resolution: far less data to copy + encode, still readable.
        val capW = (displayWidth * SCALE).toInt().coerceAtLeast(320)
        val capH = (displayHeight * SCALE).toInt().coerceAtLeast(320)
        Log.i(TAG, "display ${displayWidth}x$displayHeight -> capture ${capW}x$capH")

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < MIN_FRAME_INTERVAL_MS) {
                // Throttle: drop this frame, keep ~12 fps.
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameMs = now
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // STRIDE FIX: the ImageReader plane's rows are rowStride
                // bytes apart, NOT width*pixelStride. copyPixelsFromBuffer
                // assumes tightly-packed rows, so feeding it the raw
                // buffer when rowStride > width*pixelStride skews every
                // row and the picture smears diagonally. Copy into a
                // bitmap wide enough for a full stride row, then crop
                // to the real image size.
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val paddedWidth = (rowStride / pixelStride).coerceAtLeast(image.width)
                val full = Bitmap.createBitmap(
                    paddedWidth, image.height, Bitmap.Config.ARGB_8888
                )
                full.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (paddedWidth == image.width) {
                    full
                } else {
                    Bitmap.createBitmap(full, 0, 0, image.width, image.height).also {
                        full.recycle()
                    }
                }
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                bitmap.recycle()
                frames.publish(out.toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "frame encode failed: ${e.message}")
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DroidWeb",
            capW, capH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.i(TAG, "capture started")
    }

    private fun startWebServer() {
        if (!passwords.hasPassword()) {
            Log.w(TAG, "no password set — server will not start")
            stopSelf()
            return
        }
        val port = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT)
        webServer = WebServer(
            context = this,
            port = port,
            passwords = passwords,
            frames = frames,
            input = input,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            onStatus = {
                JSONObject().apply {
                    put("streaming", true)
                    put("clients", frames.clientCount())
                    put("displayWidth", displayWidth)
                    put("displayHeight", displayHeight)
                    put("root", input.isRootAvailable())
                    put("tunnel", tunnel?.publicUrl)
                }
            }
        )
        try {
            webServer?.start()
            Log.i(TAG, "web server on port $port")
            // Tunnel (optional): only if the user pasted a token.
            val token = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_TUNNEL_TOKEN, null)
            if (!token.isNullOrBlank()) {
                tunnel = TunnelManager(this,
                    onStatus = { s -> Log.i(TAG, "tunnel: $s") },
                    onUrl = { url -> Log.i(TAG, "tunnel URL: $url") })
                tunnel?.start(token)
            }
            // Refresh the notification with the URL.
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "web server failed: ${e.message}", e)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "droidweb"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "DroidWeb", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "DroidWeb screen sharing" }
            )
        }
        val port = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT)
        return Notification.Builder(this, channelId)
            .setContentTitle("DroidWeb is sharing your screen")
            .setContentText("Open http://<this device>:$port in a browser (login required)")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try { webServer?.stop() } catch (_: Exception) { }
        tunnel?.stop()
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { mediaProjection?.stop() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.droidweb.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val PREFS = "droidweb"
        const val KEY_PORT = "port"
        const val KEY_TUNNEL_TOKEN = "tunnelToken"
        const val DEFAULT_PORT = 8080
        private const val TAG = "DroidWeb/Service"
        private const val NOTIFICATION_ID = 1
        private const val SCALE = 0.5
        private const val JPEG_QUALITY = 70
        private const val MIN_FRAME_INTERVAL_MS = 80L // ~12 fps
    }
}
