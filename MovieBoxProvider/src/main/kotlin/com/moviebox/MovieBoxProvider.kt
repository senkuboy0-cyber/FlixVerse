package com.moviebox

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.moviebox.MovieBoxUtils.isPlaceholder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Collections

class MovieBoxProvider : MainAPI() {
    override var mainUrl = MovieBoxApi.preferredHost
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(*MovieBoxCategories.list.toTypedArray())

    // ---------------- Home ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (tabId, category) = request.data.split("|")
        val data = MovieBoxApi.rankingList(tabId, category, page)
        val items = (data?.get("subjects") ?: data?.get("items"))?.mapNotNull { it.toSearch() } ?: emptyList()
        val hasMore = data?.get("pager")?.get("hasMore")?.asBoolean() ?: false
        return newHomePageResponse(HomePageList(request.name, items), hasMore)
    }

    // ---------------- Search ----------------
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val data = MovieBoxApi.search(query, page)
        val list = data?.get("results")?.flatMap { r -> r["subjects"]?.mapNotNull { it.toSearch() } ?: emptyList() }
            ?.distinctBy { it.url } ?: emptyList()
        val hasMore = data?.get("pager")?.get("hasMore")?.asBoolean() ?: false
        return newSearchResponseList(list, hasMore)
    }

    private fun JsonNode.toSearch(): SearchResponse? {
        val id = this["subjectId"]?.asText() ?: return null
        val title = this["title"]?.asText() ?: return null
        return newMovieSearchResponse(title, id, tvType(this["subjectType"]?.asInt())) {
            posterUrl = this@toSearch["cover"]?.get("url")?.asText()
            score = Score.from10(this@toSearch["imdbRatingValue"]?.asText() ?: this@toSearch["imdbRate"]?.asText())
        }
    }

    private fun tvType(subjectType: Int?) = when (subjectType) {
        2, 7 -> TvType.TvSeries
        else -> TvType.Movie
    }

    // ---------------- Details ----------------
    override suspend fun load(url: String): LoadResponse {
        val id = Regex("""subjectId=([^&]+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')
        val data = MovieBoxApi.subject(id) ?: throw ErrorLoadingException("No data")

        val title = data["title"]?.asText() ?: throw ErrorLoadingException("No title")
        val poster = data["cover"]?.get("url")?.asText()
        val plot = data["description"]?.asText()
        val year = data["releaseDate"]?.asText()?.take(4)?.toIntOrNull()
        val rating = Score.from10(data["imdbRatingValue"]?.asText())
        val minutes = MovieBoxUtils.durationMinutes(data["duration"]?.asText(), data["durationSeconds"]?.asInt())
        val type = tvType(data["subjectType"]?.asInt())

        val related = mutableSetOf(id)
        data["dubs"]?.forEach { d -> d["subjectId"]?.asText()?.let { related.add(it) } }
        val recs = recommendations(id, related)

        if (type == TvType.TvSeries) {
            val episodes = buildEpisodes(id, title, year, poster)
            return newTvSeriesLoadResponse(title, id, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.score = rating
                this.duration = minutes
                this.recommendations = recs
            }
        }
        return newMovieLoadResponse(title, id, TvType.Movie, "$id|0|0") {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.score = rating
            this.duration = minutes
            this.recommendations = recs
        }
    }

    /** detail-rec + play-related-rec, without this title and its dubs. */
    private suspend fun recommendations(id: String, exclude: Set<String>): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        listOf("detail-rec", "play-related-rec").amap { kind -> kind to MovieBoxApi.recommendations(id, kind) }
            .forEach { (_, data) ->
                data?.get("items")?.forEach { item ->
                    val sid = item["subjectId"]?.asText() ?: return@forEach
                    if (sid in exclude || out.containsKey(sid)) return@forEach
                    item.toSearch()?.let { out[sid] = it }
                }
            }
        return out.values.toList()
    }

    private suspend fun buildEpisodes(id: String, title: String, year: Int?, poster: String?): List<Episode> {
        val seasons = MovieBoxApi.seasonInfo(id)?.get("seasons")?.toList() ?: emptyList()
        val total = seasons.sumOf { it["maxEp"]?.asInt() ?: 0 }

        // Episode titles from MovieBox resource list (all seasons in one ordered list)
        val mbTitles = Collections.synchronizedMap(mutableMapOf<Pair<Int, Int>, String>())
        val pages = ((total + 19) / 20).coerceIn(1, 15)
        (1..pages).toList().amap { page ->
            MovieBoxApi.resourceList(id, 1, 1, page, 20, 0)?.get("list")?.forEach { r ->
                val se = r["se"]?.asInt() ?: return@forEach
                val ep = r["ep"]?.asInt() ?: return@forEach
                r["title"]?.asText()?.takeIf { it.isNotBlank() }?.let { mbTitles.putIfAbsent(se to ep, it) }
            }
        }
        val meta = Cinemeta.episodes(MovieBoxUtils.cleanTitle(title), year)

        val episodes = mutableListOf<Episode>()
        seasons.forEach { s ->
            val se = s["se"]?.asInt() ?: 1
            for (ep in 1..(s["maxEp"]?.asInt() ?: 0)) {
                val m = meta[se to ep]
                episodes.add(newEpisode("$id|$se|$ep") {
                    this.name = mbTitles[se to ep] ?: m?.title ?: "Episode $ep"
                    this.season = se
                    this.episode = ep
                    this.description = m?.description
                    this.posterUrl = m?.thumbnail ?: poster
                    m?.aired?.let { addDate(it) }
                })
            }
        }
        if (episodes.isEmpty()) episodes.add(newEpisode("$id|1|1") { name = "Episode 1"; season = 1; episode = 1 })
        return episodes
    }

    // ---------------- Links ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val id = parts[0].let { p -> Regex("""subjectId=([^&]+)""").find(p)?.groupValues?.get(1) ?: p.substringAfterLast('/') }
        val season = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: 0

        val main = MovieBoxApi.subject(id)
        val rawTitle = main?.get("title")?.asText()
        fun label(d: JsonNode) = MovieBoxUtils.languageName(
            d["lanName"]?.asText(), d["lanCode"]?.asText(), d["original"]?.asBoolean() == true, rawTitle
        )

        // App sources: the subject itself + its dubs, names as the JSON has them
        val appSources = LinkedHashMap<String, String>()
        main?.get("dubs")?.forEach { d -> d["subjectId"]?.asText()?.let { appSources.putIfAbsent(it, label(d)) } }
        if (!appSources.containsKey(id)) {
            val own = MovieBoxUtils.bracketLanguage(rawTitle) ?: "Original"
            val ordered = LinkedHashMap<String, String>().apply { put(id, own); putAll(appSources) }
            appSources.clear(); appSources.putAll(ordered)
        }

        val seenLinks = Collections.synchronizedSet(mutableSetOf<String>())
        val seenSubs = Collections.synchronizedSet(mutableSetOf<String>())
        var found = false

        /** Same file can come from bcdn and bcdnxw: key on the path only. */
        fun fileKey(url: String) = url.substringBefore("?").substringAfter("hakunaymatata.com").substringAfter("aoneroom.com")

        suspend fun sendSubs(list: JsonNode?) {
            list?.forEach { c ->
                val url = c["url"]?.asText() ?: return@forEach
                if (!seenSubs.add(url.substringBefore("?"))) return@forEach
                val lang = c["lanName"]?.asText() ?: c["language"]?.asText() ?: c["lan"]?.asText() ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, url))
            }
        }

        val gotLinks = Collections.synchronizedSet(mutableSetOf<String>()) // subjectIds the app/TV API gave links for

        // ---------- Method 1: app API ----------
        suspend fun appLinks(sid: String, language: String) {
            val linkName = "$name $language"
            suspend fun send(url: String, quality: Int?, type: ExtractorLinkType?, cookie: String? = null) {
                if (!seenLinks.add("$language|${fileKey(url)}")) return
                found = true
                gotLinks.add(sid)
                callback.invoke(newExtractorLink(name, linkName, url, type) {
                    // CDN: no Referer (429) and a non-browser User-Agent (browser UA gives 428)
                    this.headers = buildMap {
                        put("User-Agent", APP_USER_AGENT)
                        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                    }
                    if (quality != null && quality > 0) this.quality = quality
                })
            }
            val resourceIds = Collections.synchronizedList(mutableListOf<String>())

            // 1) play-info streams (MP4 / HLS / DASH with signed cookie)
            val play = MovieBoxApi.playInfo(sid, season, episode)
            play?.get("streams")?.forEach { s ->
                val url = s["url"]?.asText()
                if (isPlaceholder(url, s["duration"]?.asInt())) return@forEach
                val q = s["resolutions"]?.asText()?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.maxOrNull()
                send(url!!, q, linkType(url, s["format"]?.asText()), s["signCookie"]?.asText())
                s["id"]?.asText()?.let { sendSubs(MovieBoxApi.streamCaptions(sid, it)?.get("extCaptions")) }
            }

            // 2) Movies: resourceDetectors in subject detail
            if (season == 0) {
                val detail = if (sid == id) main else MovieBoxApi.subject(sid)
                detail?.get("resourceDetectors")?.forEach { det ->
                    sendSubs(det["extCaptions"])
                    det["resolutionList"]?.toList()?.amap { r ->
                        val rid = r["resourceId"]?.asText()
                        rid?.let { resourceIds.add(it) }
                        val res = r["resolution"]?.asInt()
                        val link = r["resourceLink"]?.asText()
                        val real = if (!isPlaceholder(link)) link else rid?.let { realLink(sid, it) }
                        real?.let { send(it, res, ExtractorLinkType.VIDEO) }
                    }
                }
            }

            // 3) Series (and movies with no detector): resource list + resource-position
            if (season > 0 || resourceIds.isEmpty()) {
                findResources(sid, season, episode).amap { r ->
                    val rid = r["resourceId"]?.asText() ?: return@amap
                    resourceIds.add(rid)
                    sendSubs(r["extCaptions"])
                    val link = r["resourceLink"]?.asText()
                    val real = if (!isPlaceholder(link)) link else realLink(sid, rid)
                    real?.let { send(it, r["resolution"]?.asInt(), ExtractorLinkType.VIDEO) }
                }
            }

            // 4) External subtitles
            resourceIds.firstOrNull()?.let { rid ->
                sendSubs(MovieBoxApi.extCaptions(sid, rid, episode)?.get("extCaptions"))
            }
        }

        // ---------- Method 2: website API ----------
        suspend fun webLinks(sid: String, detailPath: String, language: String) {
            val linkName = "$name $language"
            MovieBoxWeb.media(sid, detailPath, season, episode).forEach { data ->
                sendSubs(data["captions"])
                val items = listOf("streams", "hls", "dash", "downloads").flatMap { data[it]?.toList() ?: emptyList() }
                items.forEach { s ->
                    val url = s["url"]?.asText()?.takeIf { it.startsWith("http") } ?: return@forEach
                    if (isPlaceholder(url, s["duration"]?.asInt())) return@forEach
                    if (!seenLinks.add("$language|${fileKey(url)}")) return@forEach
                    val q = s["resolution"]?.asInt()?.takeIf { it > 0 }
                        ?: s["resolutions"]?.asText()?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.maxOrNull()
                    found = true
                    callback.invoke(newExtractorLink(name, linkName, url, linkType(url, s["format"]?.asText())) {
                        // Website CDN (bcdnxw) plays only from the site: browser UA + site Referer
                        this.headers = buildMap {
                            put("User-Agent", MovieBoxWeb.BROWSER_UA)
                            put("Referer", "${MovieBoxWeb.SITE}/")
                            put("Origin", MovieBoxWeb.SITE)
                            s["signCookie"]?.asText()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                        }
                        if (q != null) this.quality = q
                    })
                }
            }
        }

        // Method 1 first: the app API for every language; record which languages gave links
        suspend fun tryApp(sid: String, language: String) = appLinks(sid, language)
        // Website detail: gives each language's detailPath (needed for DASH) and the full dubs list
        val webMain = MovieBoxWeb.subject(id)
        val webPaths = mutableMapOf<String, String>()
        webMain?.get("detailPath")?.asText()?.let { webPaths[id] = it }
        webMain?.get("dubs")?.forEach { d ->
            val sid = d["subjectId"]?.asText() ?: return@forEach
            d["detailPath"]?.asText()?.let { webPaths.putIfAbsent(sid, it) }
        }

        // DASH (.mpd, hevc) from the website for a language; MP4 stays from the app API
        suspend fun dashLinks(sid: String, language: String) {
            val path = webPaths[sid] ?: return
            MovieBoxWeb.dash(sid, path, season, episode).forEach { d ->
                val url = d["url"]?.asText()?.takeIf { it.startsWith("http") } ?: return@forEach
                if (!seenLinks.add("$language|${fileKey(url)}")) return@forEach
                val cookie = d["signCookie"]?.asText().orEmpty()
                val q = d["resolutions"]?.asText()?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.maxOrNull()
                found = true
                callback.invoke(newExtractorLink(name, "$name $language DASH", url, ExtractorLinkType.DASH) {
                    this.headers = buildMap {
                        put("User-Agent", MovieBoxWeb.BROWSER_UA)
                        // sbcdnw only accepts movie-box.co / movieboxhd.net as Referer (others give 429)
                        put("Referer", "https://movie-box.co/")
                        put("Accept", "*/*")
                        if (cookie.isNotBlank()) {
                            put("Cookie", cookie)
                            put("X-MB-Token", cookie)
                        }
                    }
                    if (q != null && q > 0) this.quality = q
                })
            }
        }

        // ---------- TV API: DASH, HLS, then its MP4 (another server, kept even if the same file) ----------
        /** Returns true when the TV API gave a DASH for this language. */
        suspend fun tvLinks(sid: String, language: String, tv: JsonNode?): Boolean {
            if (tv == null) return false
            var gotDash = false
            fun q(s: JsonNode) = s["resolution"]?.asText()?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it > 0 }
                ?: s["resolutions"]?.asText()?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.maxOrNull()
            suspend fun send(url: String, label: String, type: ExtractorLinkType, quality: Int?, cookie: String?) {
                if (!seenLinks.add("tv|$language|${fileKey(url)}")) return
                found = true
                gotLinks.add(sid)
                callback.invoke(newExtractorLink(name, label, url, type) {
                    this.headers = buildMap {
                        put("User-Agent", MovieBoxTv.UA)
                        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                    }
                    if (quality != null) this.quality = quality
                })
            }
            val streams = tv["streams"]?.toList() ?: emptyList()
            fun isHls(s: JsonNode) = s["format"]?.asText().equals("HLS", true) || s["url"]?.asText()?.contains(".m3u8") == true
            // 1) DASH
            streams.filter { !isHls(it) && (it["format"]?.asText().equals("DASH", true) || it["url"]?.asText()?.contains(".mpd") == true) }
                .forEach { s ->
                    val url = s["url"]?.asText() ?: return@forEach
                    gotDash = true
                    send(url, "$name $language DASH", ExtractorLinkType.DASH, q(s), s["signCookie"]?.asText())
                }
            // 2) HLS (highest first)
            streams.filter { isHls(it) }.sortedByDescending { q(it) ?: 0 }.forEach { s ->
                val url = s["url"]?.asText() ?: return@forEach
                send(url, "$name $language HLS", ExtractorLinkType.M3U8, q(s), s["signCookie"]?.asText())
            }
            // 3) MP4 from the TV server (bcdnt)
            tv["resources"]?.forEach { r ->
                val url = r["url"]?.asText()?.takeIf { it.startsWith("http") } ?: return@forEach
                if (isPlaceholder(url)) return@forEach
                send(url, "$name TV $language", ExtractorLinkType.VIDEO, q(r), null)
            }
            return gotDash
        }

        /** One language: app MP4 first, then TV DASH/HLS/MP4; website DASH only when TV gave none. */
        suspend fun languageLinks(sid: String, language: String): Unit = coroutineScope {
            val tvJob = async { runCatching { MovieBoxTv.playInfo(sid, season, episode) }.getOrNull() }
            tryApp(sid, language)
            val tvDash = tvLinks(sid, language, tvJob.await())
            if (!tvDash) dashLinks(sid, language)
            Unit
        }

        appSources.entries.toList().amap { (sid, language) -> languageLinks(sid, language) }

        // Website = backup only: used just when the app and TV APIs gave no link at all.
        // Then its dubs list is used (hidden dubs tried via the app API first), and its own links last.
        if (gotLinks.isNotEmpty()) return true
        val webSources = LinkedHashMap<String, Pair<String, String>>() // sid -> (detailPath, language)
        webMain?.get("dubs")?.forEach { d ->
            val sid = d["subjectId"]?.asText() ?: return@forEach
            val path = d["detailPath"]?.asText() ?: return@forEach
            webSources.putIfAbsent(sid, path to (appSources[sid] ?: label(d)))
        }
        if (!webSources.containsKey(id)) webMain?.get("detailPath")?.asText()?.let { path ->
            webSources[id] = path to (appSources[id] ?: MovieBoxUtils.bracketLanguage(rawTitle) ?: "Original")
        }
        webSources.entries.toList().amap { (sid, v) ->
            val (path, language) = v
            if (!appSources.containsKey(sid)) languageLinks(sid, language)
            if (sid !in gotLinks) {
                webLinks(sid, path, language)
                dashLinks(sid, language)
            }
        }
        return found
    }

    private fun linkType(url: String, format: String?): ExtractorLinkType? = when {
        url.contains(".mpd", true) || format.equals("DASH", true) -> ExtractorLinkType.DASH
        url.contains(".m3u8", true) || format.equals("HLS", true) -> ExtractorLinkType.M3U8
        url.contains(".mp4", true) || url.contains(".mkv", true) -> ExtractorLinkType.VIDEO
        else -> INFER_TYPE
    }

    /** Anti-piracy bypass: resource-position returns the real signed mp4. */
    private suspend fun realLink(sid: String, resourceId: String): String? =
        MovieBoxApi.resourcePosition(sid, resourceId)?.get("list")?.firstOrNull()?.get("resourceLink")?.asText()
            ?.takeIf { !isPlaceholder(it) }

    /** One resource per resolution for this episode. The list ignores "se" and orders every season together. */
    private suspend fun findResources(sid: String, season: Int, episode: Int): List<JsonNode> {
        val seasons = MovieBoxApi.seasonInfo(sid)?.get("seasons")?.toList() ?: emptyList()
        val resolutions = seasons.flatMap { s -> s["resolutions"]?.mapNotNull { it["resolution"]?.asInt() } ?: emptyList() }
            .toSet().ifEmpty { setOf(360, 480, 720, 1080) }
        fun matches(r: JsonNode, res: Int) = r["resolution"]?.asInt() == res &&
                (season == 0 || (r["se"]?.asInt() == season && r["ep"]?.asInt() == episode))

        return resolutions.toList().amap { res ->
            var index = 0
            seasons.forEach { s ->
                if ((s["se"]?.asInt() ?: 0) < season)
                    index += s["resolutions"]?.firstOrNull { it["resolution"]?.asInt() == res }?.get("epNum")?.asInt() ?: 0
            }
            index += episode.coerceAtLeast(1)
            MovieBoxApi.resourceList(sid, season, episode, index, 1, res)?.get("list")?.firstOrNull()
                ?.takeIf { matches(it, res) }?.let { return@amap it }
            for (page in 1..30) {
                val list = MovieBoxApi.resourceList(sid, season, episode, page, 20, res)?.get("list") ?: break
                list.firstOrNull { matches(it, res) }?.let { return@amap it }
                if (list.size() < 20) break
            }
            null
        }.filterNotNull()
    }
}
