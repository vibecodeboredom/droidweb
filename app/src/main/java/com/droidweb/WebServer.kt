package com.droidweb

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Embedded HTTP server (NanoHTTPD) on localhost:8080.
 *
 * Routes (all under the password gate unless noted):
 *   GET  /            viewer page (assets/viewer.html)
 *   GET  /login       login page (assets/login.html) — public
 *   POST /login       {password} -> sets session cookie, redirects to /
 *   GET  /logout      clears the session, redirects to /login
 *   GET  /stream      MJPEG multipart stream (auth: session cookie OR ?token=)
 *   POST /input       JSON input event (auth required)
 *   GET  /status      JSON status (auth required)
 *
 * Auth is checked centrally in [serve] before any protected route runs.
 * The stream and input endpoints never serve anything unauthenticated.
 */
class WebServer(
    private val context: Context,
    private val port: Int,
    private val passwords: PasswordStore,
    private val frames: FrameBroadcaster,
    private val input: InputInjector,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val onStatus: () -> JSONObject,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return try {
            when {
                uri == "/login" && session.method == Method.GET ->
                    serveAsset("login.html", MIME_HTML)
                uri == "/login" && session.method == Method.POST ->
                    handleLogin(session)
                uri == "/logout" -> handleLogout(session)
                uri.isEmpty() || uri == "/" -> {
                    if (!authed(session)) return redirectToLogin()
                    serveAsset("viewer.html", MIME_HTML)
                }
                uri == "/stream" -> {
                    if (!authed(session)) return forbidden()
                    serveStream()
                }
                uri == "/input" && session.method == Method.POST -> {
                    if (!authed(session)) return forbidden()
                    handleInput(session)
                }
                uri == "/status" -> {
                    if (!authed(session)) return forbidden()
                    json(200, onStatus().toString())
                }
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "serve $uri failed: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}"
            )
        }
    }

    // ---- auth -----------------------------------------------------------

    /** Session cookie OR ?token= query parameter. */
    private fun authed(session: IHTTPSession): Boolean {
        val cookie = session.cookies.read("session")
        if (passwords.isValidSession(cookie)) return true
        val token = session.parameters["token"]?.firstOrNull()
        return passwords.isValidSession(token)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val password = session.parameters["password"]?.firstOrNull().orEmpty()
        if (!passwords.checkPassword(password)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, MIME_HTML,
                "<html><body><h1>Wrong password</h1>" +
                    "<p><a href=\"/login\">Try again</a></p></body></html>"
            )
        }
        val token = passwords.newSession()
        val res = newFixedLengthResponse(
            Response.Status.REDIRECT, MIME_HTML, ""
        )
        res.addHeader("Location", "/?token=$token")
        // Cookie too, so later requests (and the <img> tag) are authed
        // even without the query parameter.
        res.addHeader(
            "Set-Cookie",
            "session=$token; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax"
        )
        return res
    }

    private fun handleLogout(session: IHTTPSession): Response {
        passwords.invalidateSession(session.cookies.read("session"))
        val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
        res.addHeader("Location", "/login")
        res.addHeader("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly")
        return res
    }

    private fun redirectToLogin(): Response {
        val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
        res.addHeader("Location", "/login")
        return res
    }

    private fun forbidden(): Response = newFixedLengthResponse(
        Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden"
    )

    // ---- stream + input -------------------------------------------------

    private fun serveStream(): Response {
        val res = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=--${FrameBroadcaster.BOUNDARY}",
            frames.newStream()
        )
        res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        res.addHeader("Pragma", "no-cache")
        res.addHeader("Connection", "close")
        return res
    }

    private fun handleInput(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"] ?: return json(400, """{"ok":false,"error":"empty body"}""")
        val ok = try {
            val j = JSONObject(raw)
            val w = j.optInt("w", 0)
            val h = j.optInt("h", 0)
            // Scale browser viewer coordinates to real display pixels.
            fun sx(x: Double): Int =
                if (w > 0) (x * displayWidth / w).toInt().coerceIn(0, displayWidth - 1) else 0
            fun sy(y: Double): Int =
                if (h > 0) (y * displayHeight / h).toInt().coerceIn(0, displayHeight - 1) else 0
            when (j.optString("type")) {
                "tap" -> input.tap(sx(j.getDouble("x")), sy(j.getDouble("y")))
                "swipe" -> input.swipe(
                    sx(j.getDouble("x1")), sy(j.getDouble("y1")),
                    sx(j.getDouble("x2")), sy(j.getDouble("y2")),
                    j.optInt("durationMs", 300)
                )
                "key" -> input.key(j.getString("key"))
                "text" -> input.text(j.optString("text", ""))
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad input: ${e.message}")
            false
        }
        return json(if (ok) 200 else 500, """{"ok":$ok}""")
    }

    // ---- helpers ----------------------------------------------------------

    private fun serveAsset(name: String, mime: String): Response {
        val bytes = context.assets.open(name).readBytes()
        return newFixedLengthResponse(
            Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()
        )
    }

    private fun json(code: Int, body: String): Response {
        val status = when (code) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json", body)
    }

    companion object {
        private const val TAG = "DroidWeb/Server"
    }
}
