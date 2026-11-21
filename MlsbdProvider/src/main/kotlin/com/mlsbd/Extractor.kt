package com.mlsbd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document

// ─────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────

/** scheme + host of a url, falls back to the input on parse errors */
fun getBaseUrl(url: String): String {
    return try {
        java.net.URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
}

/** numeric quality (720 / 1080 / 2160) from a file name, or Qualities.Unknown */
fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return when {
        str.lowercase().contains("4k") -> 2160
        str.lowercase().contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

/** "[1080p]" / "[Unknown]" suffix used in every emitted link name */
fun qualityTag(str: String?): String {
    val q = getIndexQuality(str)
    return if (q == Qualities.Unknown.value) "Unknown" else "${q}p"
}

/** Follows a redirect chain (max 7 hops) to the final url */
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

// ─────────────────────────────────────────────────────────────
// Page-shape detection: GDFlix and GdxFiles have very similar
// names, so the fuzzy mainUrl match can swap them; the page
// itself is the truth and the wrong extractor hands the link
// over to the right one.
// ─────────────────────────────────────────────────────────────

/** GDFlix file pages: bootstrap list "Name : ...", "Size : ..." */
private fun isGdflixPage(doc: Document): Boolean {
    return doc.selectFirst("ul > li.list-group-item:containsOwn(Name)") != null
}

/** GdxFiles (Laravel multi-host) pages: every route carries a ?hash= token */
private fun isGdxFilesPage(doc: Document): Boolean {
    return doc.selectFirst("a[href*=hash=]") != null
}

// ─────────────────────────────────────────────────────────────
// GDFlix  (new10.gdflix.io, gdflix.vip, gdflix.net, ...)
// Buttons: DIRECT SERVER [MGT], FAST CLOUD / ZIPDISK (+ .zip trim),
// CLOUD DOWNLOAD (R2), PixelDrain, GoFile [Multiup -> goflix.sbs], FSL
// Link name format: "Gdflix [source] [quality]"
// ─────────────────────────────────────────────────────────────

open class GdflixBase : ExtractorApi() {
    override var name = "Gdflix"
    override var mainUrl = "https://*.gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            if (isGdxFilesPage(doc)) {
                // fuzzy match sent a GdxFiles page here - hand it over (page shape is the truth)
                extractGdxFilesLinks(url, doc, subtitleCallback, callback)
                return
            }
            extractGdflixLinks(url, doc, subtitleCallback, callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class Gdflix : GdflixBase()

class GdflixApex : GdflixBase() {
    override var mainUrl = "https://gdflix.*"
}

// robust handle: matches any subdomain and any TLD the brand rotates onto
class GdflixHandle : GdflixBase() {
    override var mainUrl = "gdflix.*"
}

private suspend fun extractGdflixLinks(
    url: String,
    doc: Document,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val base = getBaseUrl(url)
    val fileName = doc.select("ul > li.list-group-item:contains(Name)").text()
        .substringAfter("Name : ").trim()
    val quality = getIndexQuality(fileName)
    val tag = qualityTag(fileName)

    suspend fun emit(link: String, server: String) {
        callback.invoke(
            newExtractorLink("Gdflix", "Gdflix [$server] [$tag]", link, ExtractorLinkType.VIDEO) {
                this.quality = quality
                this.headers = mapOf("Referer" to base)
            }
        )
    }

    for (anchor in doc.select("a[href]")) {
        val text = anchor.text().trim()
        val link = anchor.attr("href").trim()
        if (link.isBlank() || text.isBlank()) continue
        val abs = if (link.startsWith("http")) link else "$base$link"

        when {
            // disabled on request - remove the comment markers to re-enable the 10Gbps CDN button
            text.contains("Instant DL", ignoreCase = true) -> {}

            // direct file link (the file name with extension is part of the path)
            text.contains("DIRECT SERVER", ignoreCase = true) -> emit(abs, "mgt")

            // signed R2 redirect - resolve it to the real storage url
            text.contains("CLOUD DOWNLOAD", ignoreCase = true) -> {
                emit(resolveFinalUrl(abs) ?: abs, "r2")
            }

            // FAST CLOUD / ZIPDISK landing page -> the real link ends in ".zip"
            // (optionally followed by "?bytes=<size>"); stripping ".zip" yields the stream
            text.contains("FAST CLOUD", ignoreCase = true) ||
                text.contains("ZIPDISK", ignoreCase = true) ||
                abs.contains("/cflare/", ignoreCase = true) -> {
                runCatching {
                    val page = app.get(abs).document
                    for (a in page.select("a.btn-success, div.card-body a")) {
                        val dlink = a.attr("href").trim()
                            .replace(Regex("""\.zip(?=\?|$)""", RegexOption.IGNORE_CASE), "")
                        if (dlink.startsWith("http")) emit(dlink.replace(" ", "%20"), "fast cloud")
                    }
                }
            }

            text.contains("FSL", ignoreCase = true) -> emit(abs, "fsl")

            // pixeldrain.com / pixeldrain.dev /u/<id> -> the download API lives on the same host
            link.contains("pixeldra", ignoreCase = true) -> {
                val pid = link.trimEnd('/').substringAfterLast('/')
                val host = getBaseUrl(link)
                if (pid.isNotBlank() && host.startsWith("http")) {
                    emit("$host/api/file/$pid?download", "pixeldrain")
                }
            }

            // GoFile mirrors (goflix.sbs/en/mirror/... or gofile.io/d/...) -> registered extractors
            text.contains("GoFile", ignoreCase = true) || link.contains("gofile", ignoreCase = true) -> {
                runCatching { loadExtractor(abs, url, subtitleCallback, callback) }
            }
        }
    }

    // legacy CF backup routes (only exist when the server keeps the file there)
    runCatching {
        val wfileUrl = url.replace("/file/", "/wfile/")
        for (source in gdflixCfBackupLinks(wfileUrl)) {
            val resolved = resolveFinalUrl(source) ?: continue
            emit(resolved, "cf backup")
        }
    }
}

private suspend fun gdflixCfBackupLinks(url: String): List<String> {
    val results = mutableListOf<String>()
    listOf("1", "2").forEach { t ->
        runCatching {
            val doc = app.get("$url?type=$t").document
            doc.select("a.btn-success").mapNotNullTo(results) { it.attr("href").takeIf { h -> h.isNotBlank() } }
        }
    }
    return results
}

// ─────────────────────────────────────────────────────────────
// GdxFiles  (gdxfiles.com/file/<id> - Laravel based multi-host page)
// Routes (all server-gated, nothing is constructed):
//   /file/r2/<id>?hash=         -> 302 to a signed Cloudflare R2 url (24h)
//   /file/<id>/watch?hash=      -> jwplayer page, SRC = the same signed R2 url
//   /file/pixeldrain/<id>?hash= -> status API -> download_url (account filesystem copy)
//   /file/gofile/<id>?hash=     -> status API -> download_url -> GoFile extractor
//   /file/multiup/<id>?hash=    -> mirror list only, no direct link - skipped
// Link name format: "Gdxfiles [source] [quality]"
// ─────────────────────────────────────────────────────────────

open class GdxFilesBase : ExtractorApi() {
    override var name = "Gdxfiles"
    override var mainUrl = "https://*.gdxfiles.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            if (isGdflixPage(doc)) {
                // fuzzy match sent a GDFlix page here - hand it over (page shape is the truth)
                extractGdflixLinks(url, doc, subtitleCallback, callback)
                return
            }
            extractGdxFilesLinks(url, doc, subtitleCallback, callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class Gdxfiles : GdxFilesBase()

class GdxfilesApex : GdxFilesBase() {
    override var mainUrl = "https://gdxfiles.*"
}

// robust handle: matches any subdomain and any TLD the brand rotates onto
class GdxfilesHandle : GdxFilesBase() {
    override var mainUrl = "gdxfiles.*"
}

private suspend fun extractGdxFilesLinks(
    url: String,
    doc: Document,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val base = getBaseUrl(url)
    val fileId = Regex("""/file/([A-Za-z0-9]+)/""").find(url)?.groupValues?.get(1)
        ?: url.trimEnd('/').substringAfterLast('/')
    val fileName = doc.title().substringBefore(" |").trim().ifBlank { fileId }
    val quality = getIndexQuality(fileName)
    val tag = qualityTag(fileName)

    suspend fun emit(link: String, server: String) {
        callback.invoke(
            newExtractorLink("Gdxfiles", "Gdxfiles [$server] [$tag]", link, ExtractorLinkType.VIDEO) {
                this.quality = quality
            }
        )
    }

    val hrefs = doc.select("a[href]").mapNotNull { a ->
        a.attr("abs:href").ifBlank { a.attr("href") }.takeIf { it.isNotBlank() }
    }

    fun absolute(link: String) = if (link.startsWith("http")) link else "$base$link"

    val r2Route = hrefs.firstOrNull { it.contains("/file/r2/$fileId?hash=") }
    val watchRoute = hrefs.firstOrNull { it.contains("/file/$fileId/watch?hash=") }
    val pixeldrainRoute = hrefs.firstOrNull { it.contains("/file/pixeldrain/$fileId?hash=") }
    val gofileRoute = hrefs.firstOrNull { it.contains("/file/gofile/$fileId?hash=") }

    // 1) R2 - 24h signed url straight from the 302 Location header
    if (r2Route != null) runCatching {
        val routeHtml = app.get(absolute(r2Route)).text
        val dlHash = Regex("""download\?hash=([a-f0-9]{32})""").find(routeHtml)?.groupValues?.get(1)
        if (dlHash != null) {
            val res = app.get("$base/file/r2/$fileId/download?hash=$dlHash", allowRedirects = false)
            val location = res.headers["location"] ?: res.headers["Location"]
            if (!location.isNullOrBlank() && location.startsWith("http")) {
                emit(location, "r2")
            } else {
                resolveFinalUrl("$base/file/r2/$fileId/download?hash=$dlHash")
                    ?.takeIf { it.contains("r2.cloudflarestorage.com") }
                    ?.let { emit(it, "r2") }
            }
        }
    }

    // 2) Watch - jwplayer SRC, only when the file page itself links the watch route
    if (watchRoute != null) runCatching {
        val html = app.get(absolute(watchRoute)).text
            .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val src = Regex("""(?:const|var|let)?\s*SRC\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""https://[a-z0-9]+\.r2\.cloudflarestorage\.com/[^"'\\\s]+""").find(html)?.value
        src?.takeIf { it.startsWith("http") }?.let { emit(it, "watch") }
    }

    // 3) Pixeldrain - completed uploads only; the copy lives in the uploader's
    //    account filesystem, so the playable endpoint is /api/filesystem/<id>
    if (pixeldrainRoute != null) runCatching {
        val host = Regex("""/file/pixeldrain/([^/?#']+)""").find(pixeldrainRoute)?.groupValues?.get(1) ?: fileId
        var direct: String? = null
        repeat(3) {
            val body = app.get("$base/file/pixeldrain/$host/status").parsedSafe<Map<String, Any?>>()
            when (body?.get("status")) {
                "completed" -> {
                    val dlUrl = (body["download_url"] as? String)?.takeIf { it.contains("pixeldrain.com") }
                    if (dlUrl != null) {
                        val pid = dlUrl.trimEnd('/').substringAfterLast('/')
                        direct = "https://pixeldrain.com/api/filesystem/$pid"
                    }
                    return@repeat
                }
                "processing" -> delay(5000L)
                else -> return@repeat
            }
        }
        direct?.let { emit(it, "pixeldrain") }
    }

    // 4) Gofile - same async pattern, hand the finished link to the GoFile extractor
    if (gofileRoute != null) runCatching {
        val host = Regex("""/file/gofile/([^/?#']+)""").find(gofileRoute)?.groupValues?.get(1) ?: fileId
        val body = app.get("$base/file/gofile/$host/status").parsedSafe<Map<String, Any?>>()
        if (body?.get("status") == "completed") {
            (body["download_url"] as? String)
                ?.takeIf { it.startsWith("http") }
                ?.let { loadExtractor(it, subtitleCallback = subtitleCallback, callback = callback) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Filepress  (filepress.lat, filepress.link, filebee-like renames)
//
// "Watch Online in High Quality" (/video/<id> player flow):
//   GET /file/video/<id>/        -> {status, data:{thirdPartyDetails:[...], name}}
//   GET /file/video/<index>/<id> -> {status, data:{url}}     (per-source stream)
//   GET /file/video/fastCDN/<id> -> {status, data:{fileCode}} -> https://bhdrive.xyz/<code>
//
// Download buttons - the API gates every method; status:false or a missing
// answer means unavailable and NOTHING is emitted (no url is ever guessed).
// Link name format: "Filepress [quality]" / "Filepress [Online]"
// ─────────────────────────────────────────────────────────────

open class FilepressBase : ExtractorApi() {
    override var name = "Filepress"
    override var mainUrl = "https://*.filepress.*"
    override val requiresReferer = false

    companion object {
        // the platform's own SPA refuses anything that is not a 24 char id
        private val FILE_ID_REGEX = Regex("""([0-9a-fA-F]{24})""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = FILE_ID_REGEX.find(url)?.groupValues?.get(1) ?: return
            val base = getBaseUrl(url)
            val apiHeaders = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Origin" to base,
                "Referer" to "$base/file/$fileId"
            )

            // file name for the quality tag (public file-details API)
            var fileName: String? = null
            runCatching {
                val details = app.get("$base/api/file/get/$fileId", headers = apiHeaders)
                    .parsedSafe<Map<String, Any?>>()
                val data = details?.get("data") as? Map<*, *>
                fileName = ((data?.get("name") ?: data?.get("fileName")) as? String)
                    ?.takeIf { it.isNotBlank() }
            }

            // 1) "Watch Online in High Quality" - strictly server-gated: sources only
            //    exist when the server prepared the watch stream, otherwise nothing.
            runCatching {
                val details = app.get("$base/file/video/$fileId/", headers = apiHeaders)
                    .parsedSafe<Map<String, Any?>>()
                if (details?.get("status") == true) {
                    val data = details["data"] as? Map<*, *>
                    val name = (data?.get("name") as? String)?.takeIf { it.isNotBlank() } ?: fileName
                    val sources = data?.get("thirdPartyDetails") as? List<*> ?: emptyList<Any>()
                    val emitted = mutableSetOf<String>()
                    for (i in 0 until minOf(3, sources.size)) {
                        val source = sources[i] as? Map<*, *> ?: continue
                        val link = resolveWatchSource(base, fileId, i, source, apiHeaders) ?: continue
                        if (emitted.add(link)) {
                            callback.invoke(
                                newExtractorLink("Filepress", "Filepress [Online]", link, ExtractorLinkType.VIDEO) {
                                    this.quality = getIndexQuality(name ?: link)
                                }
                            )
                        }
                    }
                }
            }

            // 2) "Fast Download" (gpixelFastDownload) - two-stage, strictly server-gated:
            //    stage 1: POST /api/file/downlaod/  {id, method:"gpixelFastDownload"} -> 24-hex copy-job id
            //    stage 2: POST /api/file/downlaod2/ {id:<job id>}                     -> direct video link
            //    Every other answer (status:false / 404 / non-hex data) means the button
            //    does not exist on this file -> emit nothing.
            runCatching {
                val init = app.post(
                    "$base/api/file/downlaod/",
                    headers = apiHeaders,
                    json = mapOf("id" to fileId, "method" to "gpixelFastDownload", "captchaValue" to "")
                ).parsedSafe<Map<String, Any?>>()

                val jobId = init?.takeIf { it["status"] == true }
                    ?.let { it["data"] as? String }
                    ?.takeIf { it.matches(Regex("^[0-9a-f]{24}$")) }

                if (jobId != null) {
                    val result = app.post(
                        "$base/api/file/downlaod2/",
                        headers = apiHeaders,
                        json = mapOf("id" to jobId, "method" to "gpixelFastDownload", "captchaValue" to "")
                    ).parsedSafe<Map<String, Any?>>()

                    val link = result?.takeIf { it["status"] == true }
                        ?.let { it["data"] as? String }
                        ?.takeIf { it.startsWith("http") }

                    if (link != null) {
                        emitDownload(link, fileName, callback)
                        return
                    }
                }
            }

            // 3) remaining download methods - the server answers "Method Not Found" /
            //    status:false when the file does not have them
            val methodsToTry = listOf("dotFlixDownlaod", "telegramDownload")
            for (method in methodsToTry) {
                val response = runCatching {
                    app.post(
                        "$base/api/file/downlaod/",
                        headers = apiHeaders,
                        json = mapOf("id" to fileId, "method" to method, "captchaValue" to "")
                    ).parsedSafe<Map<String, Any?>>()
                }.getOrNull() ?: continue

                if (response["status"] != true) continue
                val link = (response["data"] as? String)?.takeIf { it.startsWith("http") } ?: continue

                when {
                    // dotFlixDownlaod -> https://new3.dotflix.shop/share/<code> (needs Turnstile verification)
                    link.contains("dotflix", true) -> {
                        val resolved = resolveDotflix(link)
                        if (resolved != null) {
                            emitDownload(resolved.first, resolved.second ?: fileName, callback)
                            return
                        }
                    }
                    // telegramDownload -> https://tgfiles.space?start=<token> (cloudflare protected)
                    link.contains("tgfiles", true) -> {
                        val resolved = resolveTgfiles(link)
                        if (resolved != null) {
                            emitDownload(resolved, fileName, callback)
                            return
                        }
                    }
                    // any other method that hands out a direct link
                    else -> {
                        emitDownload(link, fileName, callback)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun emitDownload(
        link: String,
        fileName: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink("Filepress", "Filepress [${qualityTag(fileName)}]", link, ExtractorLinkType.VIDEO) {
                this.quality = getIndexQuality(fileName)
            }
        )
    }

    /**
     * One watch source. FASTCDN / RCLOUD entries with a file code stream through the
     * telegram-backed CDN; every other type goes through the per-index stream API.
     * Returns null whenever the server does not hand out a real url.
     */
    private suspend fun resolveWatchSource(
        base: String,
        fileId: String,
        index: Int,
        source: Map<*, *>,
        apiHeaders: Map<String, String>
    ): String? {
        val type = source["type"] as? String
        val filecode = (source["filecode"] ?: source["fileCode"]) as? String

        if ((type == "FASTCDN" || type == "RCLOUD") && !filecode.isNullOrBlank() && filecode.length > 5) {
            if (filecode.startsWith("http")) return filecode
        }

        // per-index stream endpoint (server-gated)
        runCatching {
            val res = app.get("$base/file/video/$index/$fileId", headers = apiHeaders)
                .parsedSafe<Map<String, Any?>>()
            if (res?.get("status") == true) {
                val link = (res["data"] as? Map<*, *>)?.get("url") as? String
                if (link != null && link.startsWith("http")) return link
            }
        }

        // fastCDN fallback (telegram-backed stream)
        runCatching {
            val res = app.get("$base/file/video/fastCDN/$fileId", headers = apiHeaders)
                .parsedSafe<Map<String, Any?>>()
            val code = (res?.get("data") as? Map<*, *>)?.get("fileCode") as? String
            if (!code.isNullOrBlank() && code.length > 5) return "https://bhdrive.xyz/$code"
        }

        return null
    }

    /**
     * dotFlixDownlaod returns https://new3.dotflix.shop/share/<code>.
     * The share page needs a Cloudflare Turnstile verification before
     * POST /api/extract-download returns the real downloadUrl, so the verification
     * page is run in a WebView and its session cookie is reused for the API call.
     */
    private suspend fun resolveDotflix(shareUrl: String): Pair<String, String?>? {
        return try {
            val sharingCode = shareUrl.trimEnd('/').substringAfterLast('/')

            val shareHtml = app.get(shareUrl, interceptor = CloudflareKiller()).text
            val verifyUrl = Regex("""https://[a-z0-9.-]*dotflix\.[a-z.]+/share/[A-Za-z0-9]{4,16}\b""")
                .findAll(shareHtml).map { it.value }.firstOrNull { it != shareUrl } ?: return null

            var fileName: String? = null
            runCatching {
                fileName = Regex("""<h1[^>]*>([^<]{5,200})</h1>""")
                    .find(app.get(verifyUrl).text)?.groupValues?.get(1)?.trim()
            }

            WebViewResolver(
                interceptUrl = Regex(".^"), // never match: closing happens via the request callback below
                additionalUrls = listOf(Regex("api/extract-download")),
                useOkhttp = false, // keep the challenge in the real WebView network stack
                timeout = 30_000L
            ).resolveUsingWebView(verifyUrl, referer = shareUrl) { request ->
                request.url.toString().contains("api/extract-download")
            }

            val verifyHost = java.net.URI(verifyUrl).host
            var cookie: String? = null
            var attempts = 0
            while (cookie.isNullOrBlank() && attempts < 12) {
                delay(500)
                cookie = runCatching {
                    android.webkit.CookieManager.getInstance().getCookie("https://$verifyHost/")
                }.getOrNull()
                attempts++
            }

            val headers = mutableMapOf(
                "Accept" to "application/json, text/plain, */*",
                "Origin" to "https://$verifyHost",
                "Referer" to shareUrl
            )
            cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
            WebViewResolver.webViewUserAgent?.let { headers["User-Agent"] = it }

            val result = app.post(
                "https://$verifyHost/api/extract-download",
                headers = headers,
                json = mapOf("sharingCode" to sharingCode)
            ).parsedSafe<Map<String, Any?>>()

            if (result?.get("success") == true) {
                (result["downloadUrl"] as? String)?.takeIf { it.startsWith("http") }?.let { it to fileName }
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * telegramDownload returns https://tgfiles.space?start=<token> which is cloudflare
     * protected. Best effort: let the WebView pass cloudflare and capture the first
     * video stream request. Returns null when no media request is seen (nothing emitted).
     */
    private suspend fun resolveTgfiles(tgUrl: String): String? {
        return try {
            val pattern = Regex("""\.(mkv|mp4|avi|webm|ts|m3u8)(\?|$)""")
            val (fixed, extras) = WebViewResolver(
                interceptUrl = pattern,
                useOkhttp = false,
                timeout = 45_000L
            ).resolveUsingWebView(tgUrl, referer = "https://new4.filepress.lat/")
            (fixed?.url?.toString() ?: extras.firstOrNull()?.url?.toString())
                ?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }
}

class Filepress : FilepressBase()

class FilepressApex : FilepressBase() {
    override var mainUrl = "https://filepress.*"
}

// robust handle: matches any subdomain and any TLD the brand rotates onto
class FilepressHandle : FilepressBase() {
    override var mainUrl = "filepress.*"
}

// ─────────────────────────────────────────────────────────────
// MultiCloudLinks  (new3.multicloudlinks.com/view/<code>)
// Every mirror button on the view page is resolved separately:
//   Watch Online        -> /watch/<code> page embeds streamSrc
//   Instant Download    -> reqinstant...&api=1 -> {"finalUrl"} -> googleusercontent link
//   Turbo Download (R2) -> multidownload.click signed url, 302s to itself +action=download
//   GDbazar Mirror      -> /video/<code> once the server cached the file
//   VikingFile Mirror   -> Turnstile-protected, WebView captures the media request
//   Pixeldrain Mirror   -> 302 to pixeldrain.com/u/<id> -> /api/file/<id>?download
//   FilePress Mirror    -> filebee 301-hops to the current deployment -> Filepress extractor
//   GDFlix Mirror       -> intentionally skipped (requested)
// Link name format: "Multicloudlinks [source] [quality]"
// ─────────────────────────────────────────────────────────────

open class MultiCloudLinksBase : ExtractorApi() {
    override var name = "Multicloudlinks"
    override var mainUrl = "https://*.multicloudlinks.*"
    override val requiresReferer = false

    companion object {
        // Watch page: var/const streamSrc = "https://dr1.multidownload.click/d/<id>?...&action=watch"
        private val STREAM_SRC_REGEX = Regex("""streamSrc\s*=\s*["']([^"']+)["']""")

        // Instant download page: <a href="https://video-downloads.googleusercontent.com/..." class="btn-download">
        private val DRIVE_SERVE_REGEX =
            Regex("""href=["'](https://[a-z0-9.-]*googleusercontent\.com/[^"']+)["']""", RegexOption.IGNORE_CASE)

        private val FILE_EXT_REGEX = Regex("""\.(mkv|mp4|avi|webm|ogg|ts)(\?|$)""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Directly opened /watch/<code> page (no mirror buttons) -> just grab streamSrc
            if (url.contains("/watch/")) {
                val doc = app.get(url).document
                val fname = doc.title().substringBefore(" - ").trim()
                STREAM_SRC_REGEX.find(doc.outerHtml())?.groupValues?.get(1)?.let { link ->
                    callback.invoke(
                        newExtractorLink(
                            "Multicloudlinks",
                            "Multicloudlinks [watch] [${qualityTag(fname)}]",
                            link,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getIndexQuality(fname)
                        }
                    )
                }
                return
            }

            val doc = app.get(url).document

            val fileName = doc.selectFirst("div.file-icon-name")?.attr("title")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.title().substringBefore(" -").trim()
            val quality = getIndexQuality(fileName)
            val tag = qualityTag(fileName)

            suspend fun emit(link: String, server: String) {
                callback.invoke(
                    newExtractorLink(
                        "Multicloudlinks",
                        "Multicloudlinks [$server] [$tag]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                    }
                )
            }

            val buttons = doc.select("a.premium-btn").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                val text = a.selectFirst(".btn-text-wrapper")?.text()?.trim().orEmpty()
                if (href.isBlank()) null else text to href
            }

            for ((text, link) in buttons) {
                when {
                    // explicitly excluded by request
                    text.contains("GDFlix", ignoreCase = true) -> continue

                    text.contains("Watch Online", ignoreCase = true) || link.contains("/watch/") ->
                        resolveWatch(link) { emit(it, "watch") }

                    text.contains("Instant", ignoreCase = true) || link.contains("reqinstant.") ->
                        resolveInstant(link) { emit(it, "instant") }

                    // signed url that 302-redirects to itself with &action=download (direct file)
                    text.contains("Turbo", ignoreCase = true) || link.contains("multidownload.") ->
                        emit(link, "turbo r2")

                    text.contains("GDbazar", ignoreCase = true) || link.contains("gdbazar.") ->
                        resolveGdbazar(link) { emit(it, "gdbazar") }

                    text.contains("Viking", ignoreCase = true) || link.contains("viking.") ->
                        resolveViking(link) { emit(it, "viking") }

                    text.contains("Pixeldrain", ignoreCase = true) || link.contains("pixeldra.") ->
                        resolvePixeldrain(link) { emit(it, "pixeldrain") }

                    text.contains("FilePress", ignoreCase = true) ||
                        link.contains("filebee.") || link.contains("filepress.") ->
                        resolveFilePress(link, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun resolveWatch(link: String, emit: suspend (String) -> Unit) {
        runCatching {
            STREAM_SRC_REGEX.find(app.get(link).text)?.groupValues?.get(1)?.let { emit(it) }
        }
    }

    /**
     * reqinstant.../?eid=..&exp=..&token=.. - the page's own JS adds api=1 and receives
     * {"success":true,"finalUrl":...}; the finalUrl page embeds the (presigned) Google
     * Drive serve link in the btn-download anchor.
     */
    private suspend fun resolveInstant(link: String, emit: suspend (String) -> Unit) {
        runCatching {
            val apiUrl = link + if (link.contains("?")) "&api=1" else "?api=1"
            val json = app.get(apiUrl).parsedSafe<Map<String, Any?>>() ?: return
            if (json["success"] != true) return
            val finalUrl = (json["finalUrl"] as? String)?.takeIf { it.startsWith("http") } ?: return

            val dlHtml = app.get(finalUrl).text
            DRIVE_SERVE_REGEX.find(dlHtml)?.groupValues?.get(1)
                ?.replace("&amp;", "&")?.let { emit(it) }
        }
    }

    /**
     * gdbazar.xyz/file/<code> - /video/<code> answers 202 text/html while the server is
     * still caching the file, and 200 video streams once ready. /wn/<code>?status=1 reports
     * progress; the same warm-up request also starts the cache job.
     */
    private suspend fun resolveGdbazar(link: String, emit: suspend (String) -> Unit) {
        runCatching {
            val base = getBaseUrl(link)
            val code = link.trimEnd('/').substringAfterLast('/')

            // warm the session (cookies) and trigger the server-side cache job
            app.get("$base/file/$code")

            runCatching {
                val res = app.head("$base/video/$code", allowRedirects = false)
                val type = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty()
                if (type.startsWith("video/") || type.contains("matroska")) {
                    emit("$base/video/$code")
                    return
                }
            }

            // cold cache: poll the same status endpoint the /wn/ page uses (~56s budget)
            repeat(7) {
                delay(8000)
                val body = runCatching { app.get("$base/wn/$code?status=1").text }.getOrNull()
                    ?: return@repeat
                if (body.contains("\"ready\":true") || body.contains("\"ready\": true")) {
                    emit("$base/video/$code")
                    return
                }
            }
        }
    }

    /**
     * viking.multicloudlinks.com 302-hops to vikingfile.com/f/<id>. The file page renders
     * a Turnstile widget; once solved its JS assigns the returned direct link to
     * <video>.src - loading the page in a WebView and catching the media request yields
     * the direct url. Nothing is emitted when the WebView sees no media request.
     */
    private suspend fun resolveViking(link: String, emit: suspend (String) -> Unit) {
        runCatching {
            val filePage = resolveFinalUrl(link) ?: return

            val (fixed, extras) = WebViewResolver(
                interceptUrl = FILE_EXT_REGEX,
                useOkhttp = false, // media requests must be seen by the WebView network stack
                timeout = 60_000L
            ).resolveUsingWebView(filePage, referer = filePage)

            (fixed?.url?.toString() ?: extras.firstOrNull()?.url?.toString())
                ?.takeIf { it.startsWith("http") }
                ?.let { emit(it) }
        }
    }

    /** pixeldra.multicloudlinks.com 302-hops to pixeldrain.com/u/<id>. */
    private suspend fun resolvePixeldrain(link: String, emit: suspend (String) -> Unit) {
        runCatching {
            val final = resolveFinalUrl(link) ?: return
            if (!final.contains("pixeldrain")) return
            val id = final.trimEnd('/').substringAfterLast('/')
            emit("https://pixeldrain.com/api/file/$id?download")
        }
    }

    /** filebee.live 301-hops to the current FilePress deployment; reuse the Filepress extractor. */
    private suspend fun resolveFilePress(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val final = resolveFinalUrl(link) ?: link
            Filepress().getUrl(final, null, subtitleCallback, callback)
        }
    }
}

class Multicloudlinks : MultiCloudLinksBase()

class MulticloudlinksApex : MultiCloudLinksBase() {
    override var mainUrl = "https://multicloudlinks.*"
}

// robust handle: matches any subdomain and any TLD the brand rotates onto
class MulticloudlinksHandle : MultiCloudLinksBase() {
    override var mainUrl = "multicloudlinks.*"
}

// ─────────────────────────────────────────────────────────────
// HubCloud - kept as-is (hubcloud.* rotations)
// ─────────────────────────────────────────────────────────────

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let {
            String(
                android.util.Base64.decode(
                    String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)),
                    android.util.Base64.DEFAULT
                )
            )
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = getBaseUrl(url)

        val doc = app.get(url).document

        var link = if (url.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        } else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            if (url.contains("vcloud")) {
                extractDoubleAtob(scriptTag) ?: ""
            } else {
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            }
        }

        if (!link.startsWith("https://")) link = baseUrl + link

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        suspend fun myCallback(link: String, server: String = "") {
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

        for (anchor in document.select("h2 a.btn")) {
            val link = anchor.attr("href")
            val text = anchor.text()

            if (text.contains("FSL Server")) myCallback(link, "[FSL Server]")
            else if (text.contains("FSLv2")) myCallback(link, "[FSLv2 Server]")
            else if (text.contains("Mega Server")) myCallback(link, "[Mega Server]")
            else if (text.contains("Download File")) myCallback(link)
            else if (text.contains("BuzzServer")) {
                val dlink = app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"] ?: ""
                val linkBase = getBaseUrl(link)
                if (dlink != "") myCallback(linkBase + dlink, "[BuzzServer]")
            } else if (link.contains("pixeldra")) {
                val linkBase = getBaseUrl(link)
                val finalURL = if (link.contains("download", true)) link
                else "$linkBase/api/file/${link.substringAfterLast("/")}?download"
                myCallback(finalURL, "[Pixeldrain]")
            } else if (text.contains("Server : 10Gbps")) {
                var redirectUrl = resolveFinalUrl(link, baseUrl) ?: link
                if (redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                myCallback(redirectUrl, "[Download]")
            } else if (text.contains("Gofile")) loadExtractor(link, "", subtitleCallback, callback)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// GoflixSbs - the "GoFile [Multiup]" mirror host used by GDFlix
// ─────────────────────────────────────────────────────────────

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
