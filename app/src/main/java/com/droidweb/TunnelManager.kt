package com.droidweb

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloudflare Tunnel (cloudflared) manager.
 *
 * The cloudflared binary is ~30 MB, so it is NOT bundled in the APK.
 * On first use it is downloaded from the official Cloudflare GitHub
 * releases (arm64 Linux — a static Go binary, runs fine on Android) into
 * the app's private files dir, made executable, and launched as a
 * subprocess: `cloudflared tunnel run --token <TOKEN>`.
 *
 * The token is the user's own Cloudflare Tunnel token, pasted in the app
 * UI. It is stored in SharedPreferences (private to the app) and never
 * logged, never baked into the build, and never sent anywhere except to
 * the cloudflared subprocess. The tunnel's public URL is parsed from
 * cloudflared's log output and reported through [onUrl].
 *
 * With no token the app still works on local Wi-Fi / Tailscale — the
 * tunnel is purely the "localhost ONLINE" step.
 */
class TunnelManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onUrl: (String?) -> Unit,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    var publicUrl: String? = null
        private set

    val isRunning: Boolean get() = process?.isAlive == true

    /** Start the tunnel (downloads cloudflared first if needed). */
    fun start(token: String) {
        if (isRunning) return
        Thread({
            try {
                onStatus("Downloading cloudflared…")
                val bin = ensureBinary() ?: run {
                    onStatus("cloudflared download failed")
                    return@Thread
                }
                onStatus("Starting tunnel…")
                val pb = ProcessBuilder(
                    bin.absolutePath, "tunnel", "run", "--token", token
                )
                // cloudflared needs a writable HOME; point it at private storage.
                pb.environment()["HOME"] = context.filesDir.absolutePath
                pb.environment()["XDG_CONFIG_HOME"] = context.filesDir.absolutePath
                pb.redirectErrorStream(true)
                val p = pb.start()
                process = p
                // Read output in the background: find the public URL and
                // keep the pipe drained so the child never blocks.
                val logFile = File(context.filesDir, "cloudflared.log")
                FileOutputStream(logFile).use { out ->
                    p.inputStream.use { input ->
                        val buf = ByteArray(4096)
                        val line = StringBuilder()
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            for (i in 0 until n) {
                                val c = buf[i].toInt().toChar()
                                if (c == '\n') {
                                    handleLogLine(line.toString())
                                    line.clear()
                                } else {
                                    line.append(c)
                                }
                            }
                        }
                    }
                }
                p.waitFor()
                process = null
                if (publicUrl == null) onStatus("Tunnel exited — check the token")
            } catch (e: Exception) {
                Log.w(TAG, "tunnel failed: ${e.message}")
                onStatus("Tunnel error: ${e.message}")
                process = null
            }
        }, "droidweb-tunnel").start()
    }

    fun stop() {
        try { process?.destroy() } catch (_: Exception) { }
        process = null
        publicUrl = null
        onUrl(null)
        onStatus("Tunnel stopped")
    }

    /** Parse cloudflared log lines for the public hostname/URL. */
    private fun handleLogLine(line: String) {
        // cloudflared prints e.g. "https://<id>.trycloudflare.com" for
        // quick tunnels; for a named tunnel the URL is the configured
        // hostname. Look for any https:// host in the line.
        val m = Regex("https://[A-Za-z0-9.-]+").find(line) ?: return
        val url = m.value
        // Ignore Cloudflare API / update-check hosts.
        if (url.contains("cloudflare.com") && !url.contains("trycloudflare.com")) return
        if (publicUrl != url) {
            publicUrl = url
            onUrl(url)
            onStatus("Tunnel live: $url")
            Log.i(TAG, "tunnel URL: $url")
        }
    }

    /** Download the arm64 cloudflared binary if not already present. */
    private fun ensureBinary(): File? {
        val bin = File(context.filesDir, "cloudflared")
        if (bin.exists() && bin.canExecute() && bin.length() > 1_000_000) return bin
        return try {
            val url = URL(CLOUDFLARED_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "cloudflared download HTTP ${conn.responseCode}")
                return null
            }
            val tmp = File(context.filesDir, "cloudflared.tmp")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            tmp.renameTo(bin)
            bin.setExecutable(true, true)
            if (!bin.canExecute()) {
                Log.w(TAG, "cloudflared not executable after download")
                return null
            }
            Log.i(TAG, "cloudflared downloaded (${bin.length()} bytes)")
            bin
        } catch (e: Exception) {
            Log.w(TAG, "cloudflared download failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DroidWeb/Tunnel"
        // Official Cloudflare release asset (static Go binary, arm64 Linux).
        private const val CLOUDFLARED_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
    }
}
