package com.droidweb

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Main UI (v3): a dark card-based layout — status card with tap-to-copy
 * URLs, a prominent Start/Stop button, and the password / tunnel token /
 * port settings in their own cards.
 *
 * The service refuses to start without a password — that is deliberate.
 * The password is the only thing standing between the open internet and
 * full control of this device, so it must be set first.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var passwords: PasswordStore

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var localUrlView: TextView
    private lateinit var tunnelUrlView: TextView
    private lateinit var logView: TextView
    private lateinit var passwordField: EditText
    private lateinit var tokenField: EditText
    private lateinit var portField: EditText
    private lateinit var startStopButton: MaterialButton

    private val input by lazy { InputInjector() }
    private var serviceRunning = false

    // While the service runs, re-read the status every 2s so the tunnel
    // URL appears in the card as soon as cloudflared connects.
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            if (serviceRunning) {
                refreshUi()
                refreshHandler.postDelayed(this, 2000)
            }
        }
    }

    private val captureLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenStreamService::class.java).apply {
                    putExtra(ScreenStreamService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenStreamService.EXTRA_RESULT_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                serviceRunning = true
                refreshUi()
                refreshHandler.post(refreshTick)
                log("Service started — open the URL below in a browser")
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        passwords = PasswordStore(this)

        statusDot = findViewById(R.id.status_dot)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        localUrlView = findViewById(R.id.status_local_url)
        tunnelUrlView = findViewById(R.id.status_tunnel_url)
        logView = findViewById(R.id.log_view)
        passwordField = findViewById(R.id.password_field)
        tokenField = findViewById(R.id.token_field)
        portField = findViewById(R.id.port_field)
        startStopButton = findViewById(R.id.start_stop_button)

        findViewById<MaterialButton>(R.id.save_password_button).setOnClickListener {
            val pw = passwordField.text.toString()
            if (passwords.setPassword(pw)) {
                Toast.makeText(this, "Password saved", Toast.LENGTH_SHORT).show()
                passwordField.text.clear()
                refreshUi()
            } else {
                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.save_token_button).setOnClickListener {
            prefs().edit()
                .putString(ScreenStreamService.KEY_TUNNEL_TOKEN, tokenField.text.toString().trim())
                .apply()
            Toast.makeText(this, "Tunnel token saved (applies on next start)", Toast.LENGTH_SHORT).show()
            tokenField.text.clear()
            refreshUi()
        }

        startStopButton.setOnClickListener { toggleService() }

        findViewById<MaterialButton>(R.id.check_root_button).setOnClickListener {
            Thread {
                val ok = input.isRootAvailable()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) "Root input works" else "Root NOT available — input will fail",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshUi()
                }
            }.start()
        }

        // Tap a URL to copy it.
        localUrlView.setOnClickListener { copyUrl(localUrlView.text.toString(), "Local URL") }
        tunnelUrlView.setOnClickListener { copyUrl(tunnelUrlView.text.toString(), "Tunnel URL") }

        // Pre-fill saved values (token is never shown back).
        portField.setText(prefs().getInt(ScreenStreamService.KEY_PORT, 8080).toString())
        requestNotificationPermission()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshTick)
        super.onDestroy()
    }

    private fun copyUrl(url: String, label: String) {
        if (!url.startsWith("http")) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, url))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun toggleService() {
        if (serviceRunning) {
            startService(Intent(this, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_STOP
            })
            serviceRunning = false
            refreshHandler.removeCallbacks(refreshTick)
            refreshUi()
            log("Service stopped")
            return
        }
        if (!passwords.hasPassword()) {
            Toast.makeText(this, "Set a password first — the server will not start without one", Toast.LENGTH_LONG).show()
            return
        }
        prefs().edit()
            .putInt(
                ScreenStreamService.KEY_PORT,
                portField.text.toString().toIntOrNull() ?: 8080
            )
            .apply()
        // Ask Android for screen-capture permission; the result starts
        // the service (see captureLauncher).
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun refreshUi() {
        val port = prefs().getInt(ScreenStreamService.KEY_PORT, 8080)
        val localIp = localIpAddress()
        val hasPassword = passwords.hasPassword()
        val hasToken = !prefs().getString(ScreenStreamService.KEY_TUNNEL_TOKEN, null).isNullOrBlank()
        val tunnelUrl = prefs().getString(ScreenStreamService.KEY_TUNNEL_URL, null)
        val rootOk = input.isRootAvailable()

        // Status card.
        val dotColor = when {
            serviceRunning -> R.color.dw_good
            else -> R.color.dw_muted
        }
        statusDot.background.mutate().setColorFilter(
            ContextCompat.getColor(this, dotColor), PorterDuff.Mode.SRC_IN
        )
        statusTitle.text = if (serviceRunning) "Sharing" else "Stopped"
        statusDetail.text = buildString {
            append("Password: ${if (hasPassword) "set" else "NOT SET — set one below"}")
            append("\nRoot input: ${if (rootOk) "available" else "not detected (tap Check root)"}")
        }
        if (serviceRunning && localIp != null) {
            localUrlView.text = "http://$localIp:$port"
            localUrlView.setTextColor(ContextCompat.getColor(this, R.color.dw_accent))
        } else {
            localUrlView.text = "Local URL — start sharing to see it"
            localUrlView.setTextColor(ContextCompat.getColor(this, R.color.dw_muted))
        }
        when {
            !tunnelUrl.isNullOrBlank() -> {
                tunnelUrlView.text = tunnelUrl
                tunnelUrlView.setTextColor(ContextCompat.getColor(this, R.color.dw_accent))
            }
            serviceRunning && hasToken -> {
                tunnelUrlView.text = "Tunnel — connecting…"
                tunnelUrlView.setTextColor(ContextCompat.getColor(this, R.color.dw_warn))
            }
            hasToken -> {
                tunnelUrlView.text = "Tunnel — token saved, starts with sharing"
                tunnelUrlView.setTextColor(ContextCompat.getColor(this, R.color.dw_muted))
            }
            else -> {
                tunnelUrlView.text = "Tunnel — no token saved"
                tunnelUrlView.setTextColor(ContextCompat.getColor(this, R.color.dw_muted))
            }
        }
        startStopButton.text = if (serviceRunning) "Stop sharing" else "Start sharing"
        startStopButton.backgroundTintList =
            ContextCompat.getColorStateList(
                this,
                if (serviceRunning) R.color.dw_bad else R.color.dw_accent
            )
    }

    private fun localIpAddress(): String? {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) return null
            "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
        } catch (e: Exception) {
            Log.w(TAG, "no wifi IP: ${e.message}")
            null
        }
    }

    private fun log(msg: String) {
        logView.append("$msg\n")
    }

    private fun prefs() = getSharedPreferences(ScreenStreamService.PREFS, Context.MODE_PRIVATE)

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    companion object {
        private const val TAG = "DroidWeb/Main"
    }
}
