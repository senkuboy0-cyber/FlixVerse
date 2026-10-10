package com.mlsbd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import kotlinx.coroutines.delay

class Minochinos : VidhideExtractor() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/file/", "/v/")
        super.getUrl(embedUrl, referer, subtitleCallback, callback)
    }
}

class Luluvid : StreamWishExtractor() {
    override var name = "Luluvid"
    override var mainUrl = "https://luluvid.com"
}

class Dsvplay : StreamWishExtractor() {
    override var name = "Dsvplay"
    override var mainUrl = "https://dsvplay.com"
}

class FilePress : ExtractorApi() {
    override var name = "FilePress"
    override var mainUrl = "https://filepress.wiki"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = url.trimEnd('/').split("/").last()
            val host = java.net.URI(url).host
            val interceptor = CloudflareKiller()
            val apiPost = "https://$host/api/file/downlaod/"
            val apiPost2 = "https://$host/api/file/downlaod2/"
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Origin" to "https://$host",
                "Referer" to url
            )

            val methodsToTry = listOf("indexDownlaod", "dotFlixDownlaod", "telegramDownload", "TelegramDirectDownlaod", "cloudR2Downlaod", "cloudDownlaod")
            var extracted = false
            for (method in methodsToTry) {
                if (extracted) break
                try {
                    val initialPost = app.post(apiPost, headers = headers, json = mapOf("id" to fileId, "method" to method, "captchaValue" to ""), interceptor = interceptor).parsedSafe<Map<String, Any?>>()
                    val dataNode = initialPost?.get("data")
                    
                    var downloadId: String? = null
                    var finalUrl: String? = null

                    if (dataNode is String) {
                        if (dataNode.startsWith("http")) finalUrl = dataNode
                        else downloadId = dataNode
                    } else if (dataNode is Map<*, *>) {
                        var status = dataNode["status"]?.toString() ?: continue
                        downloadId = dataNode["downloadId"]?.toString()
                        
                        var attempts = 0
                        while (status != "completed" && status != "failed" && attempts < 15) {
                            delay(3000)
                            val poll = app.post(apiPost, headers = headers, json = mapOf("id" to fileId, "method" to method, "captchaValue" to ""), interceptor = interceptor).parsedSafe<Map<String, Any?>>()
                            val pollData = poll?.get("data") as? Map<String, Any?>
                            status = pollData?.get("status")?.toString() ?: "failed"
                            downloadId = pollData?.get("downloadId")?.toString()
                            attempts++
                        }
                    }

                    if (downloadId != null && finalUrl == null) {
                        val res2Json = app.post(apiPost2, headers = headers, json = mapOf("id" to downloadId, "method" to method, "captchaValue" to ""), interceptor = interceptor).parsedSafe<Map<String, Any?>>()
                        val data2Node = res2Json?.get("data")
                        if (data2Node is String) {
                            finalUrl = data2Node
                        } else if (data2Node is List<*>) {
                            finalUrl = data2Node.firstOrNull()?.toString()
                        }
                    }
                    
                    if (finalUrl != null && finalUrl.startsWith("http")) {
                        callback.invoke(newExtractorLink("FilePress", "FilePress Fast", finalUrl, ExtractorLinkType.VIDEO) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        })
                        extracted = true
                    }
                } catch (e: Exception) { continue }
            }

            // WebViewResolver Fallback if API fails
            if (!extracted) {
                try {
                    val doc = app.get(url, interceptor = WebViewResolver(Regex("worker|r2\\.dev|cloudflare|drive"))).document
                    doc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }.forEach { link ->
                        if (link.contains("worker") || link.contains("r2.dev")) {
                            callback.invoke(newExtractorLink(name, "FilePress Web", link, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}


// ─────────────────────────────────────────────
// Helper: Helper: Extracts base URL (scheme + host)
// ─────────────────────────────────────────────
fun getBaseUrl(url: String): String {
    return try {
        java.net.URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
}

// ─────────────────────────────────────────────
// Helper: Helper: Fetches latest GDFlix domain from GitHub JSON
// ─────────────────────────────────────────────
suspend fun getLatestGDFlixUrl(fallback: String): String {
    return fallback
}

// ─────────────────────────────────────────────
// Helper: Helper: Follows redirect chain to get final URL
// ─────────────────────────────────────────────
suspend fun resolveFinalUrl(startUrl: String, referer: String? = null): String? {
    var currentUrl = startUrl
    repeat(7) {
        val res = runCatching {
            app.head(currentUrl, allowRedirects = false, timeout = 2500L, referer = referer)
        }.getOrNull() ?: return null

        val location = res.headers["Location"] ?: res.headers["location"]
        if (location.isNullOrEmpty()) return currentUrl
        currentUrl = location
    }
    return currentUrl
}

// ─────────────────────────────────────────────
// Helper: Helper: Extracts quality from filename
// ─────────────────────────────────────────────
fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return when {
        str.lowercase().contains("4k") -> 2160
        str.lowercase().contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

open class GDFlix : ExtractorApi() {
    override val name            = "GDFlix"
    override val mainUrl         = "https://gdflix.*"
    override val requiresReferer = false

    private suspend fun cfBackupLinks(url: String): List<String> {
        val results = mutableListOf<String>()
        listOf("1", "2").forEach { t ->
            runCatching {
                val doc = app.get("$url?type=$t").document
                doc.select("a.btn-success").mapNotNullTo(results) { it.attr("href").takeIf { h -> h.isNotBlank() } }
            }
        }
        return results
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl   = getBaseUrl(url)
        val latestBase = getLatestGDFlixUrl(baseUrl)
        val newUrl     = if (baseUrl != latestBase) {
            baseUrl = latestBase
            url.replace(getBaseUrl(url), latestBase)
        } else url

        val document = app.get(newUrl).document

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").trim()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()
        val quality  = getIndexQuality(fileName)

        suspend fun emit(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    source  = "$name$server",
                    name    = "$name$server $fileName [$fileSize]",
                    url     = link,
                    type    = ExtractorLinkType.VIDEO
                ) { 
                    this.quality = quality 
                    this.headers = mapOf("Referer" to baseUrl)
                }
            )
        }

        for (anchor in document.select("div.text-center a")) {
            val text = anchor.text().trim()
            val link = anchor.attr("href")
            if (link.isBlank()) continue

            when {
                text.contains("Instant DL", ignoreCase = true) -> {
                    // The Instant DL extraction logic is kept here but disabled as requested.
                    // To re-enable, simply remove the /* and */ block comments below.
                    /*
                    runCatching {
                        val location = app.get(link, allowRedirects = false).headers["location"] ?: app.get(link, allowRedirects = false).headers["Location"].orEmpty()
                        var videoUrl = if (location.contains("?url=")) location.substringAfter("?url=") else location
                        if (videoUrl.isNotBlank()) {
                            if (!videoUrl.contains(".mkv", ignoreCase = true) && !videoUrl.contains(".mp4", ignoreCase = true)) {
                                videoUrl = "$videoUrl#.mkv"
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source  = "$name",
                                    name    = "$name $fileName [$fileSize] [Instant DL]",
                                    url     = videoUrl,
                                    type    = ExtractorLinkType.VIDEO
                                ) { 
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                                        "Referer" to ""
                                    )
                                }
                            )
                        }
                    }
                    */
                }
                text.contains("CLOUD DOWNLOAD", ignoreCase = true) -> {
                    val finalLink = if (link.startsWith("http")) link else "$baseUrl$link"
                    emit(finalLink, "[Cloud R2]")
                }
                text.contains("DIRECT", ignoreCase = true) -> {
                    val finalLink = if (link.startsWith("http")) link else "$baseUrl$link"
                    emit(finalLink, "[Direct]")
                }
                text.contains("FSL", ignoreCase = true) -> {
                    val finalLink = if (link.startsWith("http")) link else "$baseUrl$link"
                    emit(finalLink, "[FSL]")
                }
                text.contains("FAST CLOUD", ignoreCase = true) -> {
                    runCatching {
                        val targetUrl = if (link.startsWith("http")) link else "$baseUrl$link"
                        val doc = app.get(targetUrl).document
                        for (a in doc.select("div.card-body a")) {
                            var dlink = a.attr("href")
                            if (dlink.endsWith(".zip", ignoreCase = true)) {
                                dlink = dlink.dropLast(4)
                            }
                            val btnText = a.text().trim()
                            if (dlink.isNotBlank()) emit(dlink, "[Fast Cloud - $btnText]")
                        }
                    }
                }
                link.contains("pixeldra", ignoreCase = true) -> {
                    val pid      = link.substringAfterLast("/")
                    val dlBase   = getBaseUrl(link)
                    val finalURL = if (link.contains("download", ignoreCase = true)) link else "$dlBase/api/file/$pid?download"
                    emit(finalURL, "[Pixeldrain]")
                }
                text.contains("GoFile", ignoreCase = true) -> {
                    runCatching {
                        val gofileLinks = app.get(link).document.select(".row .row a").filter { it.attr("href").contains("gofile") }
                        for (it in gofileLinks) {
                            loadExtractor(it.attr("href"), "", subtitleCallback, callback)
                        }
                    }
                }
            }
        }

        runCatching {
            val wfileUrl = newUrl.replace("/file/", "/wfile/")
            for (source in cfBackupLinks(wfileUrl)) {
                val resolved = resolveFinalUrl(source) ?: continue
                emit(resolved, "[CF Backup]")
            }
        }
    }
}

class GDFlixApp : GDFlix() {
    override var mainUrl = "https://app.gdflix.*"
}

class GDFlixNet : GDFlix() {
    override var mainUrl = "https://gdflix.net"
}

class GDLink : GDFlix() {
    override val name    = "GDLink"
    override var mainUrl = "https://gdlink.*"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let { String(android.util.Base64.decode(String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)), android.util.Base64.DEFAULT)) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = getBaseUrl(url)

        val latestBaseUrl = if(url.contains("hubcloud")) {
            baseUrl
        } else {
            baseUrl
        }

        var newUrl = url

        if(baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val doc = app.get(newUrl).document

        var link = if(newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
        }
        else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""

            if(newUrl.contains("vcloud")) {
                extractDoubleAtob(scriptTag) ?: ""
            } else {
                Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
            }
        }

        if(!link.startsWith("https://")) link = baseUrl + link

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        suspend fun myCallback( link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    "${name}${server}",
                    "${name}${server} ${header}[${size}]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.headers = mapOf("Referer" to baseUrl)
                }
            )
        }

        for (it in document.select("h2 a.btn")) {
            val link = it.attr("href")
            val text = it.text()

            if (text.contains("FSL Server")) myCallback(link, "[FSL Server]")
            else if (text.contains("FSLv2")) myCallback(link, "[FSLv2 Server]")
            else if (text.contains("Mega Server")) myCallback(link, "[Mega Server]")
            else if (text.contains("Download File")) myCallback(link)
            else if (text.contains("BuzzServer")) {
                val dlink = app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"] ?: ""
                val baseUrl = getBaseUrl(link)
                if(dlink != "") myCallback( baseUrl + dlink, "[BuzzServer]")
            }
            else if (link.contains("pixeldra")) {
                val pixelLink = link
                val baseUrlLink = getBaseUrl(pixelLink)
                val finalURL = if (pixelLink.contains("download", true)) pixelLink
                else "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                myCallback(finalURL, "[Pixeldrain]")
            }
            else if (text.contains("Server : 10Gbps")) {
                var redirectUrl = resolveFinalUrl(link, baseUrl) ?: link
                if(redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                myCallback(redirectUrl, "[Download]")
            }
            else if (text.contains("Gofile")) loadExtractor(link, "", subtitleCallback, callback)
            else { println("No Server matched") }
        }
    }
}



class GoflixSbs : ExtractorApi() {
    override var name = "GoflixSbs"
    override var mainUrl = "https://goflix.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val interceptor = CloudflareKiller()
            val res = app.get(url, interceptor = interceptor).document
            val validLinks = res.select("a").mapNotNull { it.attr("abs:href") }
                .filter { 
                    it.contains("gofile") || 
                    it.contains("1fichier") || 
                    it.contains("megaup") || 
                    it.contains("pixeldrain") ||
                    it.contains("filemoon") ||
                    it.contains("streamwish")
                }
            
            validLinks.forEach { link ->
                loadExtractor(link.replace("&amp;", "&"), subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
