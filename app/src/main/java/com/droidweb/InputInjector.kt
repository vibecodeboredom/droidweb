package com.droidweb

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Injects touch / key input by running the `input` command as root.
 *
 * The `input` command must NOT run as raw UID 0 on some Android versions —
 * it talks to SurfaceFlinger/InputManager and fails when it is root. The
 * reliable form is to run it as the shell user (UID 2000), which both
 * Magisk and KernelSU `su` support (`su 2000 -c ...`). This class probes
 * both variants once and caches whichever works.
 *
 * Coordinates are real display pixels. The browser scales its viewer
 * coordinates to the device display size before sending them.
 */
class InputInjector {

    @Volatile
    private var variant: Variant? = null

    private enum class Variant { SU_ROOT, SU_SHELL }

    /** Tap at display pixel (x, y). */
    fun tap(x: Int, y: Int): Boolean = run("input tap $x $y")

    /** Swipe from (x1, y1) to (x2, y2) over [durationMs]. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        run("input swipe $x1 $y1 $x2 $y2 $durationMs")

    /** Send a key event by Android keycode name, e.g. KEYCODE_BACK. */
    fun key(keyCode: String): Boolean {
        if (!keyCode.startsWith("KEYCODE_")) return false
        return run("input keyevent $keyCode")
    }

    /** Type text (uses `input text`, escaping spaces). */
    fun text(text: String): Boolean {
        val safe = text.replace(" ", "%s").replace(Regex("[^A-Za-z0-9%._-]"), "")
        if (safe.isEmpty()) return false
        return run("input text $safe")
    }

    /** True once a working root variant has been found. */
    fun isRootAvailable(): Boolean = variant != null || probe()

    private fun probe(): Boolean {
        // KEYCODE_WAKEUP is a real keycode and a harmless no-op when the
        // screen is already on — it just exercises the `input` binary.
        return run("input keyevent KEYCODE_WAKEUP").also {
            Log.i(TAG, "root probe: ${if (it) "OK ($variant)" else "FAILED"}")
        }
    }

    private fun run(cmd: String): Boolean {
        val v = variant ?: run {
            // Try shell user first (most reliable), then plain root.
            if (exec("su", "2000", "-c", cmd)) {
                Variant.SU_SHELL
            } else if (exec("su", "-c", cmd)) {
                Variant.SU_ROOT
            } else {
                Log.w(TAG, "no working su variant for: $cmd")
                return false
            }.also { variant = it }
        }
        return when (v) {
            Variant.SU_SHELL -> exec("su", "2000", "-c", cmd)
            Variant.SU_ROOT -> exec("su", "-c", cmd)
        }
    }

    /**
     * Run a command and drain its output (never let stdout/stderr fill up
     * and deadlock the process). Returns true on exit code 0.
     */
    private fun exec(vararg args: String): Boolean {
        return try {
            val pb = ProcessBuilder(*args).redirectErrorStream(true)
            val p = pb.start()
            // Drain output so the child can never block on a full pipe.
            BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${args.joinToString(" ")}: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "DroidWeb/Input"
    }
}
