package com.fourkhdhub

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 4K-HDHub link chain:
 * greenmotors (?id=) -> s('o','...') -> b64 -> b64 -> rot13 -> b64 -> JSON {o: b64(target)}
 * -> hubcloud.ist/drive/ID  (or hubdrive.bar/file/N which links to the same hubcloud drive)
 * -> gamerxyt.com/hubcloud.php -> server buttons.
 *
 * Kept: FSL / FSLv2 (signed R2, plays with no headers), Buzz, PixelServer (when alive), Watch Online (only if not a duplicate).
 * Dropped: Server : 10Gbps (Google / googleusercontent, ExoPlayer can't play), Telegram.
 */
object HubResolver {
    val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    private const val DEAD_PIXEL = "negn6f"

    /** "PSYREN.S01E01...1080p...H.265..." -> "1080p H265" */
    fun label(fileName: String): String {
        val q = Regex("(2160p|1440p|1080p|720p|576p|480p|360p)", RegexOption.IGNORE_CASE).find(fileName)?.value?.lowercase()
            ?: if (Regex("(?i)\\b4K\\b|UHD").containsMatchIn(fileName)) "2160p" else null
        val codec = when {
            Regex("(?i)H\\.?265|x265|HEVC").containsMatchIn(fileName) -> "H265"
            Regex("(?i)H\\.?264|x264|AVC").containsMatchIn(fileName) -> "H264"
            Regex("(?i)AV1").containsMatchIn(fileName) -> "AV1"
            else -> null
        }
        return listOfNotNull(q, codec).joinToString(" ").ifBlank { "Video" }
    }

    private fun b64(s: String): String {
        val clean = s.trim().replace("-", "+").replace("_", "/")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun rot13(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        )
    }

    /** greenmotors redirect page -> hubcloud / hubdrive url */
    private suspend fun decodeGreen(url: String, mainUrl: String): String? {
        val body = app.get(url, headers = ua, referer = "$mainUrl/").text
        val o = Regex("s\\('o','([^']+)'").find(body)?.groupValues?.get(1) ?: return null
        val json = b64(rot13(b64(b64(o))))
        val target = JSONObject(json).optString("o").ifBlank { return null }
        return b64(target)
    }

    private fun driveId(url: String): String =
        Regex("/(?:drive|file)/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1) ?: url

    suspend fun resolve(
        link: String,
        label: String,
        mainUrl: String,
        seenDrives: MutableSet<String>,
        seenUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        var target = when {
            link.contains("hubcloud") || link.contains("hubdrive") -> link
            else -> decodeGreen(link, mainUrl) ?: return
        }
        
        // HubDrive page only points to the same HubCloud drive
        if (target.contains("hubdrive")) {
            val doc = app.get(target, headers = ua).document
            target = doc.select("a[href*=hubcloud]").map { it.attr("href") }
                .firstOrNull { it.contains("/drive/") } ?: return
        }
        if (!target.contains("hubcloud")) return
        if (!seenDrives.add(driveId(target))) return

        val drivePage = app.get(target, headers = ua)
        val driveUrl = drivePage.url
        val html = drivePage.text
        val gamer = drivePage.document.selectFirst("a#download")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: Regex("var url\\s*=\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            ?: return
        val doc = app.get(gamer, headers = ua, referer = driveUrl).document

        val buttons = doc.select("a.btn[href], a[href]").distinctBy { it.attr("href") }
        for (btn in buttons) {
            val text = btn.text().trim()
            val href = btn.attr("href").trim()
            if (!href.startsWith("http")) continue
            if (!text.startsWith("Download", true) && !text.startsWith("Watch", true)) continue
            if (text.contains("Telegram", true)) continue
            // ZipDisk = .mkv.zip archive, not playable
            if (text.contains("ZipDisk", true) || href.substringBefore("?").endsWith(".zip", true)) continue
            // Never Google: 10Gbps server -> googleusercontent
            if (text.contains("10Gbps", true) || href.contains("googleusercontent") ||
                href.contains("gpdl.") || href.contains("pixel.hubcloud")
            ) continue
            runCatching {
                when {
                    text.contains("FSLv2", true) -> emit("FSLv2", label, href, null, seenUrls, callback)
                    text.contains("FSL", true) -> emit("FSL", label, href, null, seenUrls, callback)
                    text.contains("Buzz", true) -> buzz(href, label, seenUrls, callback)
                    // Older uploads: "Download File [1.07 GB]" = direct workers.dev mkv (206, no headers needed)
                    text.contains("Download File", true) -> emit("HubCloud", label, href, null, seenUrls, callback)
                    text.contains("Pixel", true) || href.contains("pixeldrain") -> pixel(href, label, seenUrls, callback)
                    text.startsWith("Watch", true) -> watch(href, label, seenUrls, callback)
                }
            }
        }
    }

    private suspend fun emit(
        server: String,
        label: String,
        url: String,
        referer: String?,
        seenUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!seenUrls.add(url)) return
        callback(
            newExtractorLink(server, "$server · $label", url, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(label)
                if (referer != null) this.referer = referer
            }
        )
    }

    /** Watch Online = hbplay.pages.dev/?u=b64(url). Usually the same FSL url -> skipped as duplicate. */
    private suspend fun watch(href: String, label: String, seenUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit) {
        val u = Regex("[?&]u=([^&]+)").find(href)?.groupValues?.get(1) ?: return
        val real = runCatching { b64(URLDecoder.decode(u, "UTF-8")) }.getOrNull() ?: return
        if (!real.startsWith("http") || real.contains("googleusercontent")) return
        emit("Watch", label, real, null, seenUrls, callback)
    }

    /** pixeldrain.dev/u/ID -> api/file/ID (skip dead placeholder negn6f) */
    private suspend fun pixel(
        href: String,
        label: String,
        seenUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        if (href.contains("pixel.hubcloud", ignoreCase = true)) return

        val id = Regex("""/(?:u|file)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(href)?.groupValues?.get(1)
            ?: return

        if (id.equals(DEAD_PIXEL, ignoreCase = true)) return  // negn6f

        val host = when {
            href.contains("pixeldrain.com", ignoreCase = true) -> "https://pixeldrain.com"
            else -> "https://pixeldrain.dev"
        }

        val infoRes = try {
            app.get(
                "$host/api/file/$id/info",
                headers = ua + mapOf("Accept" to "application/json")
            )
        } catch (_: Exception) {
            return
        }
        if (!infoRes.isSuccessful) return

        val obj = try {
            JSONObject(infoRes.text)
        } catch (_: Exception) {
            return
        }
        if (obj.has("can_download") && !obj.optBoolean("can_download", true)) return
        if (obj.optLong("size", 1L) <= 0L) return

        // Actual video URL (verified)
        val streamUrl = "$host/api/file/$id"

        emit(
            server = "Pixel",
            label = label,
            url = streamUrl,
            referer = "$host/",
            seenUrls = seenUrls,
            callback = callback
        )
    }

    /** Buzz (fuckingfast.net/{slug}): page holds the signed ts.fuckingfast.net/d/... link (may be Cloudflare-blocked) */
    private suspend fun buzz(href: String, label: String, seenUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit) {
        val res = app.get(href, headers = ua)
        if (!res.isSuccessful) return
        val body = res.text
        val direct = Regex("https?://[a-z0-9.]*fuckingfast\\.[a-z]+/d/[^\"'\\s<>]+").find(body)?.value
            ?: Regex("window\\.open\\([\"'](https?://[^\"']+)[\"']").find(body)?.groupValues?.get(1)
            ?: return
        emit("Buzz", label, direct.replace("&amp;", "&"), href, seenUrls, callback)
    }
}
