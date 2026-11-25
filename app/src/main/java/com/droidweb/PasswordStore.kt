package com.droidweb

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Password storage + login sessions.
 *
 * The password is stored as SHA-256(salt + password) with a random 16-byte
 * salt — never plaintext. A login creates a random session token (UUID)
 * valid for 24 h; the token is checked on every protected request, either
 * as a `session` cookie or as a `?token=` query parameter (the <img> tag
 * for the MJPEG stream cannot set headers, so the query parameter matters).
 */
class PasswordStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // token -> expiry epoch millis
    private val sessions = ConcurrentHashMap<String, Long>()

    fun hasPassword(): Boolean = prefs.contains(KEY_HASH)

    /** Set (or change) the password. Returns false if the password is too short. */
    fun setPassword(password: String): Boolean {
        if (password.length < 4) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(password, salt)
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash.toHex())
            .apply()
        // Changing the password invalidates all sessions.
        sessions.clear()
        return true
    }

    fun checkPassword(password: String): Boolean {
        val saltHex = prefs.getString(KEY_SALT, null) ?: return false
        val wantHex = prefs.getString(KEY_HASH, null) ?: return false
        val got = hash(password, saltHex.fromHex())
        return MessageDigest.isEqual(got, wantHex.fromHex())
    }

    /** Create a new session token (valid 24 h). */
    fun newSession(): String {
        val token = UUID.randomUUID().toString()
        sessions[token] = System.currentTimeMillis() + SESSION_TTL_MS
        return token
    }

    fun isValidSession(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val exp = sessions[token] ?: return false
        if (System.currentTimeMillis() > exp) {
            sessions.remove(token)
            return false
        }
        return true
    }

    fun invalidateSession(token: String?) {
        if (!token.isNullOrEmpty()) sessions.remove(token)
    }

    private fun hash(password: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(password.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    companion object {
        private const val PREFS = "droidweb_auth"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val SESSION_TTL_MS = 24L * 60 * 60 * 1000
    }
}
