package com.droidweb

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main UI: set the password, paste an optional Cloudflare Tunnel token,
 * start/stop the screen-sharing service, and see the URLs.
 *
 * The service refuses to start without a password — that is deliberate.
 * The password is the only thing standing between the open internet and
 * full control of this device, so it must be set first.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var passwords: PasswordStore
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var passwordField: EditText
    private lateinit var tokenField: EditText
    private lateinit var portField: EditText
    private lateinit var startStopButton: Button

    private val input by lazy { InputInjector() }
    private var serviceRunning = false

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
                log("Service started — open the URL below in a browser")
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        passwords = PasswordStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            text = "DroidWeb"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "This device hosts its own browser remote control."
            gravity = Gravity.CENTER_HORIZONTAL
        })

        root.addView(label("Password (required — the server will not start without one)"))
        passwordField = EditText(this).apply {
            hint = "Set / change password (min 4 chars)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passwordField)
        root.addView(button("Save password") {
            val pw = passwordField.text.toString()
            if (passwords.setPassword(pw)) {
                Toast.makeText(this, "Password saved", Toast.LENGTH_SHORT).show()
                passwordField.text.clear()
                refreshUi()
            } else {
                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(label("Cloudflare Tunnel token (optional — makes it work ONLINE)"))
        tokenField = EditText(this).apply {
            hint = "Paste tunnel token here (leave empty for local / Tailscale only)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(tokenField)
        root.addView(button("Save tunnel token") {
            prefs().edit()
                .putString(ScreenStreamService.KEY_TUNNEL_TOKEN, tokenField.text.toString().trim())
                .apply()
            Toast.makeText(this, "Tunnel token saved (applies on next start)", Toast.LENGTH_SHORT).show()
            tokenField.text.clear()
            refreshUi()
        })

        root.addView(label("Port (default 8080)"))
        portField = EditText(this).apply {
            hint = "8080"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(portField)

        startStopButton = button("Start sharing") { toggleService() }
        root.addView(startStopButton)

        root.addView(label("Status"))
        statusView = TextView(this).apply { textSize = 15f }
        root.addView(statusView)

        root.addView(label("Log"))
        logView = TextView(this).apply { textSize = 13f }
        root.addView(logView)

        root.addView(button("Check root") {
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
        })

        setContentView(ScrollView(this).apply { addView(root) })

        // Pre-fill saved values (token is never shown back).
        portField.setText(prefs().getInt(ScreenStreamService.KEY_PORT, 8080).toString())
        requestNotificationPermission()
        refreshUi()
    }

    private fun toggleService() {
        if (serviceRunning) {
            startService(Intent(this, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_STOP
            })
            serviceRunning = false
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
        val hasToken = !prefs().getString(ScreenStreamService.KEY_TUNNEL_TOKEN, null).isNullOrBlank()
        statusView.text = buildString {
            append("Password set: ${if (passwords.hasPassword()) "yes" else "NO — set one above"}\n")
            append("Service: ${if (serviceRunning) "RUNNING" else "stopped"}\n")
            if (serviceRunning && localIp != null) {
                append("Local URL: http://$localIp:$port\n")
                append("(open it in any browser, then log in)\n")
            }
            append("Tunnel token: ${if (hasToken) "saved" else "not set (local / Tailscale only)"}\n")
            if (serviceRunning && hasToken) {
                append("Tunnel URL: appears in the log once cloudflared connects\n")
            }
            append("Root input: ${if (input.isRootAvailable()) "available" else "not detected yet (tap Check root)"}")
        }
        startStopButton.text = if (serviceRunning) "Stop sharing" else "Start sharing"
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
