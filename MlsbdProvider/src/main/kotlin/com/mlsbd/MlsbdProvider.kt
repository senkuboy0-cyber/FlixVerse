package com.mlsbd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// --- TMDB API Constants ---
const val TMDB_API = "https://api.themoviedb.org/3"
const val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

// --- TMDB Data Classes ---
data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null
    // Removed posters from TMDB as we strictly use the website's poster
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
// Removed poster from TmdbAssets
data class TmdbAssets(val logo: String?, val backdrop: String?)
// -------------------------

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Standard User-Agent for network requests
    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // --- Helper Functions for Title & TMDB Logic ---
    
    // Extracts year from title formatted as (YYYY) or (YYYY-YYYY)
    private fun getYearFromTitle(rawTitle: String): Int? {
        val doubleMatch = Regex("\\((\\d{4})-\\d{4}\\)").find(rawTitle)
        if (doubleMatch != null) {
            return doubleMatch.groupValues[1].toIntOrNull()
        }
        val singleMatch = Regex("\\((\\d{4})\\)").find(rawTitle)
        if (singleMatch != null) {
            return singleMatch.groupValues[1].toIntOrNull()
        }
        return null
    }

    // Formats title for display on the UI
    private fun getDisplayTitle(rawTitle: String): String {
        val doubleMatch = Regex("\\(\\d{4}-\\d{4}\\)").find(rawTitle)
        if (doubleMatch != null) {
            return rawTitle.substringBefore(doubleMatch.value).trim() + " " + doubleMatch.value
        }
        val singleMatch = Regex("\\(\\d{4}\\)").find(rawTitle)
        if (singleMatch != null) {
            return rawTitle.substringBefore(singleMatch.value).trim() + " " + singleMatch.value
        }
        return rawTitle.trim()
    }

    // Cleans title for better TMDB search accuracy
    private fun cleanTitleForTmdb(rawTitle: String): String {
        var clean = rawTitle.replace(Regex("^\\[.*?]\\s*"), "")
        
        val doubleMatch = Regex("\\(\\d{4}-\\d{4}\\)").find(clean)
        if (doubleMatch != null) {
            return clean.substringBefore(doubleMatch.value).trim()
        }
        val singleMatch = Regex("\\(\\d{4}\\)").find(clean)
        if (singleMatch != null) {
            return clean.substringBefore(singleMatch.value).trim()
        }
        return clean.trim()
    }

    private fun encodeUri(text: String): String {
        return text.replace("%", "%25").replace(" ", "%20").replace("#", "%23")
            .replace("&", "%26").replace("?", "%3F").replace("=", "%3D")
            .replace(":", "%3A").replace("/", "%2F").replace("'", "%27")
            .replace("\"", "%22").replace(",", "%2C")
    }

    private fun normalizeTitle(s: String?): String {
        return s?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase() ?: ""
    }

    private fun getResultYear(result: TmdbResult): Int? {
        return (result.releaseDate ?: result.firstAirDate)?.substringBefore("-")?.toIntOrNull()
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return Math.abs(tmdbYear - siteYear) <= 1 // Allow 1 year difference
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear == null || candidates.size == 1) return candidates.first()
        return candidates.firstOrNull { yearMatches(getResultYear(it), siteYear) }
            ?: candidates.first()
    }

    // Fetches metadata (Logo, Backdrop) from TMDB API
    // Posters are intentionally NOT fetched here to force usage of website's posters
    private suspend fun fetchTmdbAssets(title: String, isSeries: Boolean, year: Int?, imdbId: String? = null): TmdbAssets {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = if (isSeries) "tv" else "movie"

            // Attempt to find by IMDB ID first
            if (imdbId != null && imdbId.startsWith("tt")) {
                val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id").parsedSafe<TmdbFind>()
                val tvId = findRes?.tvShows?.firstOrNull()?.id
                val movieId = findRes?.movies?.firstOrNull()?.id

                if (isSeries && tvId != null) { tmdbId = tvId; actualMediaType = "tv" }
                else if (!isSeries && movieId != null) { tmdbId = movieId; actualMediaType = "movie" }
                else if (movieId != null) { tmdbId = movieId; actualMediaType = "movie" }
                else if (tvId != null) { tmdbId = tvId; actualMediaType = "tv" }
            }

            // Fallback to text search if IMDB ID fails or is not provided
            if (tmdbId == null) {
                val safeTitle = encodeUri(title)
                val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle").parsedSafe<TmdbSearch>()
                val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                
                val normTitle = normalizeTitle(title)
                
                val exactCandidates = validResults?.filter { normalizeTitle(it.title) == normTitle || normalizeTitle(it.name) == normTitle } ?: emptyList()
                val exactMatch = pickBestResult(exactCandidates, year)

                if (exactMatch != null) {
                    tmdbId = exactMatch.id
                    actualMediaType = exactMatch.mediaType ?: actualMediaType
                } else {
                    val startsWithCandidates = if (normTitle.length >= 5) {
                        validResults?.filter { normalizeTitle(it.title ?: it.name).startsWith(normTitle) } ?: emptyList()
                    } else emptyList()
                    
                    val startsWithMatch = pickBestResult(startsWithCandidates, year)
                    if (startsWithMatch != null) {
                        tmdbId = startsWithMatch.id
                        actualMediaType = startsWithMatch.mediaType ?: actualMediaType
                    }
                }
            }

            if (tmdbId == null) return TmdbAssets(null, null)

            // Fetch specific images for the matched TMDB ID
            val images = app.get("$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()

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

            val logoUrl = logo?.filePath?.let { "$TMDB_IMG$it" }
            val backdropUrl = backdrop?.filePath?.let { "$TMDB_IMG$it" }

            TmdbAssets(logoUrl, backdropUrl)

        } catch (e: Exception) {
            TmdbAssets(null, null)
        }
    }

    // --- Main Page Categories ---
    // Removed categories: Klikk, Unrated, Ullu, Horror, Japanese, MX Player, Chorki
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/category/bollywood-movies/" to "Bollywood",
        "$mainUrl/category/hollywood-movies/" to "Hollywood",
        "$mainUrl/category/bangla-movies/" to "Bengali",
        "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/bangla-dubbed/" to "Bangla Dubbed",
        "$mainUrl/category/korean-movies/" to "Korean",
        "$mainUrl/category/tv-series/" to "TV Series",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/animation-movies/" to "Animation",
        "$mainUrl/category/south-indian-movies/" to "South Indian"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = ua, timeout = 60).document
        
        val elements = doc.select("div.single-post, article.main-post-area div.single-post").toList()
        
        val items = elements.amap { el ->
            val a = el.selectFirst(".post-desc a, .thumb a") ?: return@amap null
            val href = a.attr("abs:href").ifBlank { return@amap null }
            
            val rawTitle = el.selectFirst("h2.post-title")?.text()?.trim() ?: return@amap null
            
            val displayTitle = getDisplayTitle(rawTitle)
            val isSeries = rawTitle.contains("Season", true) || rawTitle.contains("Episode", true) || href.contains("series", true) || href.contains("season", true) || href.contains("episode", true)
            
            // Strictly using website's poster. Removed TMDB call to speed up main page loading.
            val finalPoster = el.selectFirst("div.thumb img")?.attr("src")
            
            if (isSeries) newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) { this.posterUrl = finalPoster }
            else newMovieSearchResponse(displayTitle, href, TvType.Movie) { this.posterUrl = finalPoster }
        }.filterNotNull()
        
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = ua, timeout = 60).document
        
        val elements = doc.select("div.single-post").toList()
        val items = elements.amap { el ->
            val a = el.selectFirst(".post-desc a, .thumb a") ?: return@amap null
            val href = a.attr("abs:href").ifBlank { return@amap null }
            
            val rawTitle = el.selectFirst("h2.post-title")?.text()?.trim() ?: return@amap null
            
            val displayTitle = getDisplayTitle(rawTitle)
            val isSeries = rawTitle.contains("Season", true) || rawTitle.contains("Episode", true) || href.contains("series", true) || href.contains("season", true) || href.contains("episode", true)
            
            // Strictly using website's poster here as well.
            val finalPoster = el.selectFirst("div.thumb img")?.attr("src")
            
            if (isSeries) newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) { this.posterUrl = finalPoster }
            else newMovieSearchResponse(displayTitle, href, TvType.Movie) { this.posterUrl = finalPoster }
        }.filterNotNull()
        
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua, timeout = 60).document
        
        val rawTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: doc.title().trim()
        
        val displayTitle = getDisplayTitle(rawTitle)
        val cleanTitle = cleanTitleForTmdb(rawTitle)
        val year = getYearFromTitle(rawTitle)

        // Find poster on load page natively from website
        var originalPoster = doc.selectFirst("div.entry-content img.aligncenter, div.post-content img, div.content img")?.attr("src")
        if (originalPoster == null || originalPoster.contains("mlsbdshop")) {
            originalPoster = doc.select("img").firstOrNull { it.attr("src").contains("uploads/images") }?.attr("src") ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        }

        // Fetch description/storyline
        var description = doc.selectFirst("div.storyline")?.text()
            ?.replace(Regex("(?i)Storyline\\s*:"), "")?.trim()

        if (description.isNullOrBlank()) {
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            if (description == null || description.contains("Storyline :")) {
                description = doc.select("div.entry-content p, div.post-content p").firstOrNull { it.text().contains("Storyline") || it.text().contains("Director") }?.text()?.trim() ?: doc.title().trim()
            }
        }

        val imdbId = doc.selectFirst("a[href*='imdb.com/title']")?.attr("href")
            ?.substringAfter("title/")?.substringBefore("/")?.takeIf { it.startsWith("tt") }

        val contentArea = doc.selectFirst("div.entry-content, div.post-content, div.content")
        val isSeries = rawTitle.contains("Episode", true) || rawTitle.contains("Season", true) || 
                       url.contains("episode", true) || url.contains("season", true) || 
                       (contentArea?.text()?.contains(Regex("(?i)(Download Now Epi|Download Episode|Episode \\d+)")) == true)

        // Only fetch TMDB for Backdrop and Logo
        val tmdbAssets = fetchTmdbAssets(cleanTitle, isSeries, year, imdbId)
        
        // Finalized specific assets
        val finalPoster = originalPoster // Forcing usage of website's poster only
        val finalBackdrop = tmdbAssets.backdrop ?: finalPoster
        val finalLogo = tmdbAssets.logo

        // Parse episodes if it's a TV series
        if (isSeries && contentArea != null) {
            val episodes = mutableListOf<Episode>()
            var currentEpNum = 1
            val episodeMap = mutableMapOf<Int, MutableList<String>>()

            val seasonMatch = Regex("(?i)Season[- ]?(\\d+)").find(rawTitle)
            val parsedSeason = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            for (tag in contentArea.children()) {
                val text = tag.text().trim()
                
                // Track current episode number based on headers/paragraphs
                val headerEpMatch = Regex("(?i)(?:Epi|Ep|Episode)[- ]?(\\d+)").find(text)
                if (headerEpMatch != null && tag.tagName() in listOf("h2", "h3", "h4", "p", "div", "strong", "b")) {
                    currentEpNum = headerEpMatch.groupValues[1].toInt()
                }

                val links = if (tag.tagName() == "a" && tag.hasAttr("href")) listOf(tag) else tag.select("a[href]")
                
                for (a in links) {
                    val aText = a.text().trim()
                    val href = a.attr("abs:href")
                    
                    val isValid = href.contains("savelinks", true) || href.contains("gdflix", true) || 
                                  href.contains("hubcloud", true) || href.contains("drive", true) || 
                                  href.contains("filepress", true) || href.contains("mega", true) || 
                                  href.contains("vimeo", true) || aText.contains("Download in", true) || 
                                  aText.contains("Watch Online", true) || aText.contains("Episode", true) || 
                                  aText.contains("Epi", true)
                    
                    if (isValid) {
                        val linkEpMatch = Regex("(?i)(?:Epi|Ep|Episode)[- ]?(\\d+)").find(aText)
                        val epNumForLink = if (linkEpMatch != null) {
                            linkEpMatch.groupValues[1].toInt()
                        } else {
                            currentEpNum
                        }
                        
                        var quality = "Unknown"
                        if (aText.contains("720p", true)) quality = "720p"
                        else if (aText.contains("1080p", true)) quality = "1080p"
                        else if (aText.contains("480p", true)) quality = "480p"
                        else if (aText.contains("4K", true)) quality = "4K"
                        else {
                            val parentText = tag.text()
                            if (parentText.contains("720p", true)) quality = "720p"
                            else if (parentText.contains("1080p", true)) quality = "1080p"
                            else if (parentText.contains("480p", true)) quality = "480p"
                            else if (parentText.contains("4K", true)) quality = "4K"
                        }
                        
                        episodeMap.getOrPut(epNumForLink) { mutableListOf() }.add("$href|$quality")
                        currentEpNum = epNumForLink
                    }
                }
            }

            episodeMap.forEach { (epNum, links) ->
                episodes.add(newEpisode(links.joinToString(",")) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = parsedSeason
                    this.posterUrl = finalPoster
                })
            }
            
            val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.logoUrl = finalLogo
                this.plot = description
                this.year = year
            }
        } else {
            // Logic for Movie Load Response
            val iframes = doc.select("iframe").mapNotNull { it.attr("src") }.filter { it.startsWith("http") }.map { "$it|Unknown" }
            val links = doc.select("a").mapNotNull { a -> 
                val href = a.attr("abs:href")
                val text = a.text()
                if (href.contains("savelinks", true) || href.contains("filepress", true) || href.contains("gdflix", true) || href.contains("hubcloud", true) || href.contains("drive", true) || href.contains("mega", true) || href.contains("vimeo", true)) {
                    var quality = "Unknown"
                    if (text.contains("720p", true)) quality = "720p"
                    else if (text.contains("1080p", true)) quality = "1080p"
                    else if (text.contains("480p", true)) quality = "480p"
                    else if (text.contains("4K", true)) quality = "4K"
                    "$href|$quality"
                } else null
            }
            val dataStr = (iframes + links).distinct().joinToString(",")
            
            return newMovieLoadResponse(displayTitle, url, TvType.Movie, dataStr) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.logoUrl = finalLogo
                this.plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val urls = data.split(",")

        fun getQualityScore(q: String): Int {
            return when {
                q.contains("720p", true) -> 1
                q.contains("1080p", true) -> 2
                q.contains("480p", true) -> 3
                q.contains("4K", true) -> 4
                else -> 5
            }
        }

        val sortedUrls = urls.sortedBy { getQualityScore(it.substringAfterLast("|", "Unknown")) }

        // Cleans extractor link names dynamically
        val customCallback: (ExtractorLink) -> Unit = { link ->
            var newName = link.name
            
            if (newName.contains("] ")) {
                newName = newName.substringBefore("] ") + "]"
            }
            
            try {
                val field = link::class.java.getDeclaredField("name")
                field.isAccessible = true
                field.set(link, newName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            callback.invoke(link)
        }

        // Resolves extractors for different streaming hosts
        suspend fun invokeExtractor(targetUrl: String, referer: String?) {
            try {
                if (targetUrl.contains("gdflix", true)) GDFlix().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                else if (targetUrl.contains("hubcloud", true)) {
                    HubCloud().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("filepress", true) || targetUrl.contains("filebee", true)) {
                    FilePress().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("minochinos", true)) {
                    Minochinos().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("luluvid", true)) {
                    Luluvid().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("dsvplay", true) || targetUrl.contains("playmogo", true)) {
                    loadExtractor(targetUrl.replace("dsvplay.com", "dood.to").replace("playmogo.com", "dood.to"), subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("morencius", true)) {
                    loadExtractor(targetUrl.replace("morencius.com", "filemoon.sx"), subtitleCallback, customCallback)
                }
                else loadExtractor(targetUrl, subtitleCallback, customCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        sortedUrls.amap { item ->
            if (item.isNotBlank()) {
                val parts = item.split("|")
                val url = parts[0].trim()

                if (url.startsWith("http")) {
                    if (url.contains("savelinks", true)) {
                        // Extract specific streaming servers from savelinks redirect pages
                        try {
                            val slHtml = app.get(url, headers = ua, timeout = 60).text
                            val urlRegex = Regex("(?i)https?://[^\\s\"'<]+")
                            val validHosts = listOf("gdflix", "hubcloud", "filepress", "minochinos", "luluvid", "dsvplay", "vimeo", "drive", "pixeldrain", "filemoon", "vidmoly", "streamwish", "streamtape", "doodstream", "gofile", "gdtot")
                            
                            val doc = org.jsoup.Jsoup.parse(slHtml)
                            val aLinks = doc.select("a").mapNotNull { it.attr("abs:href") }
                            
                            val allLinks = (urlRegex.findAll(slHtml).map { it.value }.toList() + aLinks).distinct()
                            
                            allLinks.amap { slUrl ->
                                if (validHosts.any { slUrl.contains(it, true) }) {
                                    invokeExtractor(slUrl, url)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        invokeExtractor(url, url)
                    }
                }
            }
        }
        
        return true
    }
}
