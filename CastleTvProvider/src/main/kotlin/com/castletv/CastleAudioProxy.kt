package com.castletv

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Castle TS segments carry every language as its own AAC stream inside one file.
 * Media3 reads HLS TS by stream type, so it keeps only one AAC stream and the player
 * never shows the other languages (androidx/media#2634, judged "bad media").
 *
 * This local proxy serves the playlist and its segments with the PMT rewritten to list
 * only one chosen audio PID (other audio packets are dropped), so every language gets
 * its own playable link in the internal player. No external app is needed.
 */
object CastleAudioProxy {

    data class AudioStream(val pid: Int, val language: String?)

    private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)
    private const val PACKET = 188

    @Volatile
    var upstreamHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var server: ServerSocket? = null

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "CastleAudioProxy").apply { isDaemon = true }
    }

    // ===================== Public API =====================

    /** Local playlist URL that plays [playlistUrl] with only [audioPid] as its audio. */
    fun playlistUrl(playlistUrl: String, audioPid: Int): String =
        "http://127.0.0.1:${port()}/p/$audioPid/${encode(playlistUrl)}/index.m3u8"

    /** Audio streams listed in the PMT of the first segment of [playlistUrl]. */
    fun probe(playlistUrl: String): List<AudioStream> {
        return try {
            val playlist = fetch(playlistUrl).toString(Charsets.UTF_8)
            val first = playlist.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return emptyList()
            audioStreams(fetch(URL(URL(playlistUrl), first).toString()))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===================== Server =====================

    @Synchronized
    private fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (_: Exception) {
                }
            }
        }
        return s.localPort
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 30_000
                val path = readRequestPath(socket.getInputStream()) ?: return
                val out = socket.getOutputStream()
                // /p/{pid}/{url}/index.m3u8  or  /s/{pid}/{url}/seg.ts
                val parts = path.trimStart('/').split('/')
                if (parts.size < 3) return respond(out, 404, "text/plain", "Not found".toByteArray())
                val pid = parts[1].toIntOrNull() ?: return respond(out, 400, "text/plain", "Bad pid".toByteArray())
                val target = decode(parts[2])
                when (parts[0]) {
                    "p" -> respond(out, 200, "application/vnd.apple.mpegurl", rewritePlaylist(target, pid))
                    "s" -> respond(out, 200, "video/mp2t", filterSegment(fetch(target), pid))
                    else -> respond(out, 404, "text/plain", "Not found".toByteArray())
                }
            } catch (e: Exception) {
                try {
                    respond(socket.getOutputStream(), 502, "text/plain", (e.message ?: "error").toByteArray())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readRequestPath(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val firstLine = buf.toString("UTF-8").lineSequence().firstOrNull() ?: return null
        // GET /path HTTP/1.1
        return firstLine.split(' ').getOrNull(1)?.substringBefore('?')
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Bad Gateway"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    // ===================== Upstream =====================

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).apply {
            upstreamHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        app.baseClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            return res.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun rewritePlaylist(playlistUrl: String, pid: Int): ByteArray {
        val port = port()
        val text = fetch(playlistUrl).toString(Charsets.UTF_8)
        val base = URL(playlistUrl)
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) {
                line
            } else {
                val abs = URL(base, t).toString()
                "http://127.0.0.1:$port/s/$pid/${encode(abs)}/seg.ts"
            }
        }.toByteArray(Charsets.UTF_8)
    }

    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    private fun encode(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), B64_FLAGS)

    private fun decode(s: String): String =
        String(Base64.decode(s, B64_FLAGS), Charsets.UTF_8)

    // ===================== MPEG-TS =====================

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun pidOf(b: ByteArray, i: Int) = ((u(b, i + 1) and 0x1F) shl 8) or u(b, i + 2)

    private fun isUnitStart(b: ByteArray, i: Int) = (u(b, i + 1) and 0x40) != 0

    /** Offset of the payload of the packet at [i], or -1 when it has none. */
    private fun payloadStart(b: ByteArray, i: Int): Int {
        val afc = (u(b, i + 3) shr 4) and 3
        if (afc == 0 || afc == 2) return -1
        val off = i + 4 + if (afc == 3) 1 + u(b, i + 4) else 0
        return if (off < i + PACKET) off else -1
    }

    /** Offset of the PSI section in the packet at [i] (after the pointer field), or -1. */
    private fun sectionStart(b: ByteArray, i: Int): Int {
        if (!isUnitStart(b, i)) return -1
        val o = payloadStart(b, i)
        if (o < 0) return -1
        val s = o + 1 + u(b, o)
        return if (s + 12 < i + PACKET) s else -1
    }

    private fun findPmtPid(b: ByteArray): Int? {
        var i = 0
        while (i + PACKET <= b.size) {
            if (u(b, i) == 0x47 && pidOf(b, i) == 0) {
                val s = sectionStart(b, i)
                if (s >= 0) {
                    val len = ((u(b, s + 1) and 0x0F) shl 8) or u(b, s + 2)
                    val end = minOf(s + 3 + len - 4, i + PACKET)
                    var j = s + 8
                    while (j + 4 <= end) {
                        val program = (u(b, j) shl 8) or u(b, j + 1)
                        if (program != 0) return ((u(b, j + 2) and 0x1F) shl 8) or u(b, j + 3)
                        j += 4
                    }
                }
            }
            i += PACKET
        }
        return null
    }

    private fun audioStreams(b: ByteArray): List<AudioStream> {
        val pmt = findPmtPid(b) ?: return emptyList()
        var i = 0
        while (i + PACKET <= b.size) {
            if (u(b, i) == 0x47 && pidOf(b, i) == pmt) {
                val s = sectionStart(b, i)
                if (s >= 0) {
                    val out = mutableListOf<AudioStream>()
                    val len = ((u(b, s + 1) and 0x0F) shl 8) or u(b, s + 2)
                    val pil = ((u(b, s + 10) and 0x0F) shl 8) or u(b, s + 11)
                    val end = minOf(s + 3 + len - 4, i + PACKET)
                    var j = s + 12 + pil
                    while (j + 5 <= end) {
                        val type = u(b, j)
                        val pid = ((u(b, j + 1) and 0x1F) shl 8) or u(b, j + 2)
                        val esLen = ((u(b, j + 3) and 0x0F) shl 8) or u(b, j + 4)
                        var lang: String? = null
                        var k = j + 5
                        while (k + 2 <= j + 5 + esLen && k + 2 <= end) {
                            val tag = u(b, k)
                            val dl = u(b, k + 1)
                            if (tag == 0x0A && dl >= 3 && k + 5 <= end) {
                                lang = String(b, k + 2, 3, Charsets.US_ASCII)
                            }
                            k += 2 + dl
                        }
                        if (type in AUDIO_TYPES) out.add(AudioStream(pid, lang))
                        j += 5 + esLen
                    }
                    return out
                }
            }
            i += PACKET
        }
        return emptyList()
    }

    /** Keep video and [keepPid] only: rewrite every PMT and drop packets of the other audio streams. */
    private fun filterSegment(b: ByteArray, keepPid: Int): ByteArray {
        val pmt = findPmtPid(b) ?: return b
        val audio = audioStreams(b)
        if (audio.none { it.pid == keepPid }) return b
        val drop = audio.map { it.pid }.filter { it != keepPid }.toSet()

        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i + PACKET <= b.size) {
            if (u(b, i) != 0x47) {
                out.write(b, i, PACKET)
                i += PACKET
                continue
            }
            val pid = pidOf(b, i)
            when {
                pid in drop -> Unit
                pid == pmt && isUnitStart(b, i) -> out.write(rewritePmtPacket(b, i, drop))
                else -> out.write(b, i, PACKET)
            }
            i += PACKET
        }
        return out.toByteArray()
    }

    private fun rewritePmtPacket(b: ByteArray, i: Int, drop: Set<Int>): ByteArray {
        val packet = b.copyOfRange(i, i + PACKET)
        val s = sectionStart(packet, 0)
        if (s < 0) return packet
        val len = ((u(packet, s + 1) and 0x0F) shl 8) or u(packet, s + 2)
        if (s + 3 + len > PACKET) return packet // section spans packets: leave untouched
        val pil = ((u(packet, s + 10) and 0x0F) shl 8) or u(packet, s + 11)
        val end = s + 3 + len - 4

        val section = ByteArrayOutputStream()
        section.write(packet, s, 12 + pil)
        var j = s + 12 + pil
        while (j + 5 <= end) {
            val pid = ((u(packet, j + 1) and 0x1F) shl 8) or u(packet, j + 2)
            val esLen = ((u(packet, j + 3) and 0x0F) shl 8) or u(packet, j + 4)
            if (pid !in drop) section.write(packet, j, 5 + esLen)
            j += 5 + esLen
        }
        val sec = section.toByteArray()
        val newLen = sec.size - 3 + 4
        sec[1] = ((u(sec, 1) and 0xF0) or ((newLen shr 8) and 0x0F)).toByte()
        sec[2] = (newLen and 0xFF).toByte()
        val crc = crc32Mpeg(sec)

        val result = ByteArray(PACKET) { 0xFF.toByte() }
        System.arraycopy(packet, 0, result, 0, s)
        System.arraycopy(sec, 0, result, s, sec.size)
        val c = s + sec.size
        result[c] = (crc ushr 24).toByte()
        result[c + 1] = (crc ushr 16).toByte()
        result[c + 2] = (crc ushr 8).toByte()
        result[c + 3] = crc.toByte()
        return result
    }

    private fun crc32Mpeg(d: ByteArray): Int {
        var crc = -1
        for (byte in d) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if ((crc and Int.MIN_VALUE) != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }

    // ===================== Language names =====================

    private val LANGUAGE_NAMES = mapOf(
        "hin" to "Hindi", "tam" to "Tamil", "tel" to "Telugu", "kan" to "Kannada",
        "mal" to "Malayalam", "ben" to "Bengali", "mar" to "Marathi", "pan" to "Punjabi",
        "guj" to "Gujarati", "ori" to "Odia", "urd" to "Urdu", "eng" to "English",
        "kor" to "Korean", "jpn" to "Japanese", "chi" to "Chinese", "zho" to "Chinese",
        "spa" to "Spanish", "fre" to "French", "fra" to "French", "ger" to "German",
        "deu" to "German", "rus" to "Russian", "por" to "Portuguese", "tur" to "Turkish",
        "ara" to "Arabic", "tha" to "Thai", "ind" to "Indonesian", "ita" to "Italian",
    )

    fun languageName(code: String?, index: Int): String =
        code?.lowercase()?.let { LANGUAGE_NAMES[it] ?: it.uppercase() } ?: "Audio ${index + 1}"
}
