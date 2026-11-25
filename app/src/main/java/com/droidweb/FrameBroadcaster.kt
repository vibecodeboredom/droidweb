package com.droidweb

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Distributes JPEG frames to MJPEG stream clients.
 *
 * Each connected browser gets its own bounded queue (capacity 2, oldest
 * frame dropped when full — a slow client must never stall the capture
 * loop). A stream client reads through [newStream], a custom InputStream
 * that serves one multipart frame after another:
 *
 *   --droidweb\r\n
 *   Content-Type: image/jpeg\r\n
 *   Content-Length: <n>\r\n
 *   \r\n
 *   <jpeg bytes>\r\n
 *
 * When the browser disconnects, NanoHTTPD closes the stream and the
 * client is unregistered.
 */
class FrameBroadcaster {

    private val clients = ConcurrentHashMap<String, LinkedBlockingQueue<ByteArray>>()

    /** Push a fresh JPEG frame to every connected client (non-blocking). */
    fun publish(jpeg: ByteArray) {
        for (q in clients.values) {
            // Bounded queue: drop the oldest frame rather than blocking.
            if (!q.offer(jpeg)) {
                q.poll()
                q.offer(jpeg)
            }
        }
    }

    /** Create a new MJPEG stream for one client. Call [closeStream] when done. */
    fun newStream(): InputStream {
        val id = java.util.UUID.randomUUID().toString()
        val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        clients[id] = queue
        return MjpegInputStream(id, queue) { closeStream(id) }
    }

    fun closeStream(id: String) {
        clients.remove(id)
    }

    fun clientCount(): Int = clients.size

    private class MjpegInputStream(
        private val id: String,
        private val queue: LinkedBlockingQueue<ByteArray>,
        private val onClose: () -> Unit,
    ) : InputStream() {

        @Volatile
        private var closed = false

        // Bytes of the frame currently being served (header + jpeg + CRLF).
        private var current: ByteArray? = null
        private var pos = 0

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            while (true) {
                val cur = current
                if (cur != null && pos < cur.size) {
                    val n = minOf(len, cur.size - pos)
                    System.arraycopy(cur, pos, b, off, n)
                    pos += n
                    if (pos >= cur.size) current = null
                    return n
                }
                // Need the next frame. Block briefly; a closed stream must
                // not hang NanoHTTPD's worker thread forever.
                val jpeg = try {
                    queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                if (jpeg == null) {
                    // No frame arrived (capture paused/stopped). Keep the
                    // connection open by waiting again instead of ending it.
                    if (closed) return -1
                    continue
                }
                val header = ("--$BOUNDARY\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
                val framed = ByteArray(header.size + jpeg.size + 2)
                System.arraycopy(header, 0, framed, 0, header.size)
                System.arraycopy(jpeg, 0, framed, header.size, jpeg.size)
                framed[framed.size - 2] = '\r'.code.toByte()
                framed[framed.size - 1] = '\n'.code.toByte()
                current = framed
                pos = 0
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
            super.close()
        }
    }

    companion object {
        const val BOUNDARY = "droidweb"
        private const val QUEUE_CAPACITY = 2
        private const val POLL_TIMEOUT_MS = 500L
    }
}
