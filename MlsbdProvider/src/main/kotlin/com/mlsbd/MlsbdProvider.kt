package com.mlsbd

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// ─────────────────────────────────────────────────────────────
// MLSBD (mlsbd.co) - every data source comes from the site's
// WordPress REST API (no HTML page scraping):
//   list   : /wp-json/wp/v2/posts?categories=<id>&page=N
//   search : /wp-json/wp/v2/posts?search=<q>
//   details: /wp-json/wp/v2/posts?slug=<slug>&_embed
// Series vs movie is decided by the content's "Download Now"
// sections (Epi/Part header -> series, otherwise movie) and
// never by the slug.
// ─────────────────────────────────────────────────────────────

// --- TMDB API Constants (backdrop + logo only, posters come from the site) ---
const val TMDB_API = "https://api.themoviedb.org/3"
const val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

// --- TMDB Data Classes ---
data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null
)
data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)
data class TmdbFind(
    @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,
    @JsonProperty("tv_results") val tvShows: List<TmdbResult>? = null
)
data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)
data class TmdbSearch(
    @JsonProperty("results") val results: List<TmdbResult>? = null
)
data class TmdbAssets(val logo: String?, val backdrop: String?)

// --- WordPress REST API Data Classes ---
data class WpPost(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("title") val title: WpRendered? = null,
    @JsonProperty("content") val content: WpRendered? = null,
    @JsonProperty("_embedded") val embedded: WpEmbedded? = null
)
data class WpRendered(@JsonProperty("rendered") val rendered: String? = null)
data class WpEmbedded(@JsonProperty("wp:term") val terms: List<List<WpTerm>>? = null)
data class WpTerm(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Standard browser User-Agent (the site sits behind Cloudflare)
    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Own kotlin-module mapper: the shared parsedSafe path loses the generic element
    // type for plugin data classes (List<WpPost> came back as List<LinkedHashMap> and
    // crashed on cast). mapper.readValue<T>() materializes the full reified type - the
    // pattern proven by FourKHDHubProvider in this repo.
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private suspend inline fun <reified T : Any> getJson(url: String): T? = try {
        mapper.readValue<T>(app.get(url, headers = ua, timeout = 60).text)
    } catch (_: Exception) {
        null
    }

    private val apiBase get() = "$mainUrl/wp-json/wp/v2/posts"

    // --- Main Page Categories (WordPress category ids, verified live) ---
    override val mainPage = mainPageOf(
        "" to "Latest",
        "13" to "Bollywood",
        "14" to "Hollywood",
        "12" to "Bengali",
        "70" to "Dual Audio",
        "69" to "Hindi Dubbed",
        "53" to "Bangla Dubbed",
        "45" to "Korean",
        "40" to "TV Series",
        "192" to "Anime",
        "15" to "Animation",
        "35" to "South Indian"
    )

    // ─────────────────────────────────────────
    // Title / metadata helpers
    // ─────────────────────────────────────────

    private fun getYearFromTitle(rawTitle: String): Int? {
        Regex("""\((\d{4})-(\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun normalizeTitle(s: String?): String {
        return s?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase() ?: ""
    }

    private fun getResultYear(result: TmdbResult): Int? {
        return (result.releaseDate ?: result.firstAirDate)?.substringBefore("-")?.toIntOrNull()
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return Math.abs(tmdbYear - siteYear) <= 1
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear == null || candidates.size == 1) return candidates.first()
        return candidates.firstOrNull { yearMatches(getResultYear(it), siteYear) }
            ?: candidates.first()
    }

    /** base title for TMDB search: no [tag] groups, no (year) */
    private fun tmdbSearchTitle(base: String): String {
        return base.replace(Regex("""\[.*?]"""), "")
            .replace(Regex("""\(\d{4}(?:-\d{4})?\)"""), "")
            .trim()
    }

    private fun encodeUri(text: String): String {
        return text.replace("%", "%25").replace(" ", "%20").replace("#", "%23")
            .replace("&", "%26").replace("?", "%3F").replace("=", "%3D")
            .replace(":", "%3A").replace("/", "%2F").replace("'", "%27")
            .replace("\"", "%22").replace(",", "%2C")
    }

    /** Fetches metadata (logo, backdrop) from the TMDB API; posters come from the site */
    private suspend fun fetchTmdbAssets(title: String, isSeries: Boolean, year: Int?): TmdbAssets {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = if (isSeries) "tv" else "movie"

            val safeTitle = encodeUri(title)
            val searchRes = getJson<TmdbSearch>("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }

            val normTitle = normalizeTitle(title)

            val exactCandidates = validResults?.filter {
                normalizeTitle(it.title) == normTitle || normalizeTitle(it.name) == normTitle
            } ?: emptyList()
            val exactMatch = pickBestResult(exactCandidates, year)

            if (exactMatch != null) {
                tmdbId = exactMatch.id
                actualMediaType = exactMatch.mediaType ?: actualMediaType
            } else {
                val startsWithCandidates = if (normTitle.length >= 5) {
                    validResults?.filter { normalizeTitle(it.title ?: it.name).startsWith(normTitle) }
                        ?: emptyList()
                } else emptyList()

                val startsWithMatch = pickBestResult(startsWithCandidates, year)
                if (startsWithMatch != null) {
                    tmdbId = startsWithMatch.id
                    actualMediaType = startsWithMatch.mediaType ?: actualMediaType
                }
            }

            if (tmdbId == null) return TmdbAssets(null, null)

            val images = getJson<TmdbImages>("$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY")

            val logo = images?.logos?.firstOrNull { it.lang == "en" }
                ?: images?.logos?.firstOrNull { it.lang == null }
                ?: images?.logos?.firstOrNull { it.lang == "bn" }
                ?: images?.logos?.firstOrNull { it.lang == "hi" }
                ?: images?.logos?.firstOrNull()

            val backdrop = images?.backdrops?.firstOrNull { it.lang == null }
                ?: images?.backdrops?.firstOrNull { it.lang == "en" }
                ?: images?.backdrops?.firstOrNull { it.lang == "bn" }
                ?: images?.backdrops?.firstOrNull { it.lang == "hi" }
                ?: images?.backdrops?.firstOrNull()

            TmdbAssets(
                logo?.filePath?.let { "$TMDB_IMG$it" },
                backdrop?.filePath?.let { "$TMDB_IMG$it" }
            )
        } catch (e: Exception) {
            TmdbAssets(null, null)
        }
    }

    // ─────────────────────────────────────────
    // Content parsing helpers
    // ─────────────────────────────────────────

    /** first <img src> of the post content - the site's own poster */
    private fun firstImage(contentHtml: String?): String? {
        if (contentHtml.isNullOrBlank()) return null
        return Regex("""<img[^>]+src=["']([^"']+)["']""").find(contentHtml)?.groupValues?.get(1)
    }

    /** "Storyline : ..." paragraph from the content */
    private fun storyline(contentHtml: String): String? {
        val m = Regex("""(?i)Storyline\s*:?\s*</strong>\s*([^<]+)""").find(contentHtml)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** "1h 42min" from the movie-info misc line -> minutes */
    private fun durationMinutes(doc: Document): Int? {
        val misc = doc.selectFirst("p.misc")?.text() ?: return null
        val m = Regex("""(\d+)\s*h\s*(\d+)?\s*min""").find(misc) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        return h * 60 + min
    }

    /** card title: cleaned up post title */
    private fun cleanDisplayTitle(rawTitle: String): String {
        var t = rawTitle.replace(
            Regex("""\s*[-\u2013]?\s*Download\s*(&|&#038;)?\s*Watch\s*Online.*$""", RegexOption.IGNORE_CASE),
            ""
        )
        // SxxExx-yy -> Sxx Epi-xx-yy
        t = Regex("""(?i)\bS(\d{1,2})E(\d{1,4})(?:-(\d{1,4}))?\b""").replace(t) { m ->
            val end = m.groupValues[3]
            "S${m.groupValues[1]} Epi-${m.groupValues[2]}" + if (end.isNotBlank()) "-$end" else ""
        }
        for (marker in listOf(" \u2013 ", " WEB-DL", " WEB-Rip", " BluRay", " HDRip", " HDTV", " Dual Audio")) {
            val idx = t.indexOf(marker, ignoreCase = true)
            if (idx > 0) t = t.substring(0, idx)
        }
        return t.trim()
    }

    /**
     * Full display title of the details page:
     *   The Court (2026) S01 Epi-45-48        (batch episodes)
     *   Thukra Kar Pyaar Mera Part 2 (2026)   (parts)
     *   Kauriburir Mandir (2026)              (movie)
     */
    private fun displayTitle(base: String, rawTitle: String, isSeries: Boolean): String {
        if (!isSeries) return base

        val s = Regex("""(?i)\bS(\d{1,2})E(\d{1,4})(?:-(\d{1,4}))?\b""").find(rawTitle)
        if (s != null) {
            val end = s.groupValues[3]
            val range = "Epi-${s.groupValues[2]}" + if (end.isNotBlank()) "-$end" else ""
            return "$base S${s.groupValues[1]} $range"
        }

        val part = Regex("""(?i)Part[- ]?(\d+)""").find(rawTitle)
        if (part != null) {
            val partStr = "Part ${part.groupValues[1]}"
            return if (Regex("""\(\d{4}\)""").containsMatchIn(base)) {
                base.replace(Regex("""\s*\((\d{4})\)"""), " $partStr ($1)")
            } else "$base $partStr"
        }

        return base
    }

    private fun looksLikeSeries(t: String): Boolean {
        return Regex("""(?i)S\d{1,2}\s*E\d+""").containsMatchIn(t) ||
            Regex("""(?i)Epi[- ]?\d+""").containsMatchIn(t) ||
            Regex("""(?i)Part[- ]?\d+""").containsMatchIn(t) ||
            t.contains("Season", true)
    }

    /** the source tag of a "Download Now" section header (e.g. WEB-Rip / WEB-DL / "") */
    private fun sourceTag(header: String): String {
        return header
            .replace(Regex("""(?i)Download\s*Now"""), "")
            .replace(Regex("""(?i)Epi[- ]?\d+(?:-\d+)?"""), "")
            .replace(Regex("""(?i)Part[- ]?\d+"""), "")
            .trim()
    }

    private fun qualityOfLinkText(text: String): String {
        return when {
            Regex("""(?i)4K""").containsMatchIn(text) -> "4K"
            Regex("""(?i)1080p""").containsMatchIn(text) -> "1080p"
            Regex("""(?i)720p""").containsMatchIn(text) -> "720p"
            Regex("""(?i)480p""").containsMatchIn(text) -> "480p"
            else -> "Unknown"
        }
    }

    /**
     * Parses the "Download Now" sections of a post content.
     * Returns a list of (header, links) where links are (href, quality, sourceTag) triples.
     */
    private fun parseSections(doc: Document): List<Pair<String, List<Triple<String, String, String>>>> {
        val sections = mutableListOf<Pair<String, MutableList<Triple<String, String, String>>>>()
        var current: MutableList<Triple<String, String, String>>? = null
        var currentHeader = ""

        for (el in doc.body().children()) {
            if (el.hasClass("post-section-title")) {
                currentHeader = el.text().trim()
                current = mutableListOf()
                sections.add(currentHeader to current)
            } else if (current != null) {
                for (a in el.select("a[href]")) {
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }
                    if (!href.startsWith("http")) continue
                    val quality = qualityOfLinkText(a.text().trim())
                    current.add(Triple(href, quality, sourceTag(currentHeader)))
                }
            }
        }
        return sections.map { (header, links) -> header to links.toList() }
    }

    private fun WpPost.toSearchResponse(): SearchResponse? {
        val link = this.link ?: return null
        val rawTitle = Jsoup.parse(this.title?.rendered ?: "").text().trim()
        if (rawTitle.isBlank()) return null
        val display = cleanDisplayTitle(rawTitle)
        val poster = firstImage(this.content?.rendered)
        return if (looksLikeSeries(rawTitle)) {
            newTvSeriesSearchResponse(display, link, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(display, link, TvType.Movie) { this.posterUrl = poster }
        }
    }

    /** related posts from the first tag of the current post */
    private suspend fun fetchRecommendations(post: WpPost): List<SearchResponse> {
        val tagId = post.embedded?.terms?.firstOrNull()?.firstOrNull { it.id != null }?.id
            ?: return emptyList()
        val res = getJson<List<WpPost>>("$apiBase?tags=$tagId&per_page=9&_fields=id,slug,title,link,content,date")
            ?: return emptyList()
        return res.filter { it.slug != post.slug }.take(8).mapNotNull { it.toSearchResponse() }
    }

    private suspend fun fetchPosts(api: String): List<WpPost> {
        return getJson<List<WpPost>>(api) ?: emptyList()
    }

    // ─────────────────────────────────────────
    // MainAPI overrides
    // ─────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val api = buildString {
            append("$apiBase?per_page=20&page=$page&_fields=id,slug,title,link,content,date")
            if (request.data.isNotBlank()) append("&categories=${request.data}")
        }
        val items = fetchPosts(api).mapNotNull { it.toSearchResponse() }
        // portrait posters: mlsbd uses vertical cover art, not landscape cards
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val api = "$apiBase?search=${java.net.URLEncoder.encode(query, "UTF-8")}&per_page=20&_fields=id,slug,title,link,content,date"
        return fetchPosts(api).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val post = fetchPosts("$apiBase?slug=$slug&_embed").firstOrNull()
            ?: throw Exception("MLSBD: no post found for slug '$slug'")

        val contentHtml = post.content?.rendered ?: ""
        val doc = Jsoup.parse(contentHtml, mainUrl)

        val rawTitle = Jsoup.parse(post.title?.rendered ?: "").text().trim()

        // clean base name from the content header (the slug is unreliable)
        var baseName = doc.selectFirst("h2.name")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: cleanDisplayTitle(rawTitle)
        // "Thukra Kar Pyaar Mera Part 1 (2026)" carries a part in the header - the
        // series base title must stay separate from the per-part episodes
        if (Regex("""(?i)Part[- ]?\d+""").containsMatchIn(rawTitle)) {
            baseName = baseName.replace(Regex("""(?i)\s*Part[- ]?\d+"""), "").trim()
        }

        val poster = firstImage(contentHtml)
        val plot = storyline(contentHtml)
        val duration = durationMinutes(doc)
        val year = getYearFromTitle(rawTitle) ?: post.date?.substringBefore("-")?.toIntOrNull()

        // sections decide series vs movie (never the slug, never the title alone)
        val sections = parseSections(doc)
        val isSeries = sections.any { (header, _) ->
            Regex("""(?i)Epi[- ]?\d+""").containsMatchIn(header) ||
                Regex("""(?i)Part[- ]?\d+""").containsMatchIn(header)
        }
        val season = Regex("""(?i)Season[- ]?(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)S(\d{1,2})E""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        val recommendations = fetchRecommendations(post)
        val tmdbAssets = fetchTmdbAssets(tmdbSearchTitle(baseName), isSeries, year)
        val display = displayTitle(baseName, rawTitle, isSeries)

        if (isSeries) {
            // group sections by episode: the same "Epi-XX-YY" range from WEB-Rip and
            // WEB-DL is ONE episode holding the links of both sources
            val groups = LinkedHashMap<String, MutableList<Triple<String, String, String>>>()
            val meta = HashMap<String, Pair<String, Int>>()
            for ((header, links) in sections) {
                val ep = Regex("""(?i)Epi[- ]?(\d+)(?:-(\d+))?""").find(header)
                val part = Regex("""(?i)Part[- ]?(\d+)""").find(header)
                val key: String
                val name: String
                val num: Int
                if (ep != null) {
                    val end = ep.groupValues[2]
                    key = "Epi-${ep.groupValues[1]}" + if (end.isNotBlank()) "-$end" else ""
                    name = key
                    num = ep.groupValues[1].toIntOrNull() ?: 1
                } else if (part != null) {
                    key = "Part ${part.groupValues[1]}"
                    name = key
                    num = part.groupValues[1].toIntOrNull() ?: 1
                } else continue
                groups.getOrPut(key) { mutableListOf() }.addAll(links)
                meta[key] = name to num
            }

            val episodes = groups.map { (key, links) ->
                val (name, num) = meta[key] ?: (key to 1)
                val dataStr = links.distinctBy { it.first }.joinToString(",") { (href, q, src) ->
                    "$href|$q|$src"
                }
                newEpisode(dataStr) {
                    this.name = name
                    this.episode = num
                    this.season = season
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(display, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdbAssets.backdrop ?: poster
                this.logoUrl = tmdbAssets.logo
                this.plot = plot
                this.year = year
                this.duration = duration
                this.recommendations = recommendations
            }
        } else {
            // movie: every section's links go into the movie data (WEB-Rip + WEB-DL alike)
            val entries = sections.flatMap { it.second }
                .map { (href, q, src) -> "$href|$q|$src" }
                .toMutableList()
            // embedded players (if any) go through the extractor pool as well
            doc.select("iframe[src]").forEach {
                val src = it.attr("abs:href").ifBlank { it.attr("src") }
                if (src.startsWith("http")) entries.add("$src|Unknown|iframe")
            }

            val dataStr = entries.distinct().joinToString(",")

            return newMovieLoadResponse(display, url, TvType.Movie, dataStr) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdbAssets.backdrop ?: poster
                this.logoUrl = tmdbAssets.logo
                this.plot = plot
                this.year = year
                this.duration = duration
                this.recommendations = recommendations
            }
        }
    }

    // ─────────────────────────────────────────
    // Link loading
    // ─────────────────────────────────────────

    private fun qualityScore(q: String?): Int = when {
        q == null -> 9
        q.contains("720p", true) -> 1
        q.contains("1080p", true) -> 2
        q.contains("480p", true) -> 3
        q.contains("4K", true) -> 4
        else -> 9
    }

    // file-page path hint: only shape-route urls that look like file pages - direct
    // media links (pixeldrain /u/, googleusercontent, ...) go straight to the pool
    private val filePagePathRegex = Regex("""/(file|view|video|watch|share|dl)/""", RegexOption.IGNORE_CASE)

    /**
     * One uniform routing path:
     * 1. our own file-host families first, routed by PAGE SHAPE straight to this
     *    plugin's extractors. Sending them through the shared loadExtractor pool lets
     *    extractors from OTHER installed plugins grab these links first (all plugins
     *    join one pool and the last registered one is checked first) and emit their
     *    own names with the hub url prefixed, e.g.
     *    "https://savelinks.me/view/... GDFlix [FAST CLOUD] HDRIP [3.45GB] 1080p".
     * 2. everything else goes through the global pool - our registered mainUrls and,
     *    for unknown hosts, the built-in cloudstream extractors.
     */
    private suspend fun routeLink(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (filePagePathRegex.containsMatchIn(targetUrl) &&
            routeKnownFamily(targetUrl, subtitleCallback, callback)
        ) return

        runCatching {
            loadExtractor(targetUrl, referer = referer, subtitleCallback = subtitleCallback, callback = callback)
        }
    }

    /**
     * Detects the file-host family by page shape and routes the link. Renamed
     * deployments are covered automatically because the shape is the site structure,
     * not the domain name. Returns true when a known family matched.
     */
    private suspend fun routeKnownFamily(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url).document
            when {
                // GDFlix: bootstrap file list "Name : ..."
                isGdflixPage(doc) -> {
                    extractGdflixLinks(url, doc, subtitleCallback, callback)
                    true
                }
                // GdxFiles: every route carries a ?hash= token
                isGdxFilesPage(doc) -> {
                    extractGdxFilesLinks(url, doc, subtitleCallback, callback)
                    true
                }
                // MultiCloudLinks mirror hub
                doc.selectFirst("a.premium-btn") != null -> {
                    MulticloudlinksHandle().getUrl(url, null, subtitleCallback, callback)
                    true
                }
                // HubCloud download page
                url.contains("/video/", true) && doc.selectFirst("div.vd") != null -> {
                    HubCloud().getUrl(url, null, subtitleCallback, callback)
                    true
                }
                // FilePress is an SPA - detect it through its public API instead of the HTML
                else -> {
                    val id = Regex("""([0-9a-fA-F]{24})""").find(url)?.groupValues?.get(1)
                    if (id == null) {
                        false
                    } else {
                        val base = getBaseUrl(url)
                        val shaped = runCatching {
                            app.get(
                                "$base/api/file/get/$id",
                                headers = mapOf(
                                    "Accept" to "application/json, text/plain, */*",
                                    "Origin" to base,
                                    "Referer" to "$base/file/$id"
                                )
                            ).parsedSafe<Map<String, Any?>>()?.containsKey("status") == true
                        }.getOrDefault(false)
                        if (shaped) {
                            FilepressHandle().getUrl(url, null, subtitleCallback, callback)
                            true
                        } else false
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * savelinks.me is the site's own link-hub: its view pages simply list the current
     * file-host links. Only the hub domain itself is known here (site infrastructure,
     * not a file host) - everything inside is routed through the generic extractor pool.
     */
    private suspend fun resolveSavelinksPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val doc = app.get(url, headers = ua, timeout = 60).document
            val ownHost = getBaseUrl(url)
            val blocked = listOf(
                "savelinks.me", "mlsbd.co", "cinedoze.tv", "t.me", "telegram.me",
                "facebook.com", "twitter.com", "x.com", "instagram.com", "youtube.com",
                "whatsapp.com", "login", "register", "/blog", "imdb.com"
            )
            fun List<String>.filtered() = filter { link -> blocked.none { link.contains(it, true) } }

            val links = doc.select("ul li a[href]")
                .mapNotNull { it.attr("abs:href").takeIf { h -> h.startsWith("http") } }
                .filtered()
                .filter { getBaseUrl(it) != ownHost }
                .distinct()
                .ifEmpty {
                    doc.select("a[href]")
                        .mapNotNull { it.attr("abs:href").takeIf { h -> h.startsWith("http") } }
                        .filtered()
                        .filter { getBaseUrl(it) != ownHost }
                        .distinct()
                }

            links.amap { link -> routeLink(link, url, subtitleCallback, callback) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val entries = data.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val sorted = entries.sortedBy { qualityScore(it.split("|").getOrNull(1)) }

        sorted.amap { entry ->
            val link = entry.split("|").firstOrNull()?.trim() ?: return@amap
            if (!link.startsWith("http")) return@amap

            if (link.contains("savelinks", true)) {
                resolveSavelinksPage(link, subtitleCallback, callback)
            } else {
                routeLink(link, link, subtitleCallback, callback)
            }
        }

        return true
    }
}
