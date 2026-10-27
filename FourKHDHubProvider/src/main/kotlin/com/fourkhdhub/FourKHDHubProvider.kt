package com.fourkhdhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

/** One downloadable file on a 4K-HDHub page (a movie variant or one episode in one quality). */
data class HubFile(
    val name: String = "",
    val size: String? = null,
    val links: List<String> = emptyList()
)

class FourKHDHubProvider : MainAPI() {
    override var mainUrl = "https://4khdhub.one"
    override var name = "4K-HDHub"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/movies/" to "Latest Movies",
        "category/hindi-movies/" to "Hindi Movies",
        "category/series/" to "Latest Series",
        "category/hindi-series/" to "Hindi Series",
        "category/korean-series/" to "Korean Series",
        "category/drama-series/" to "Drama Series",
        "category/anime/" to "Anime"
    )

    // ---------------- Home ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        val doc = app.get(url, headers = HubResolver.ua).document
        val items = doc.select("div.card-grid a.movie-card").mapNotNull { it.toSearch() }
            .ifEmpty { doc.select("a.movie-card").mapNotNull { it.toSearch() } }
        val hasMore = items.isNotEmpty() && doc.selectFirst("a.pagination-next") != null
        return newHomePageResponse(HomePageList(request.name, items), hasMore)
    }

    // ---------------- Search ----------------
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val doc = app.get(url, headers = HubResolver.ua).document
        val items = doc.select("a.movie-card").mapNotNull { it.toSearch() }.distinctBy { it.url }
        val hasMore = items.isNotEmpty() && doc.selectFirst("a.pagination-next") != null
        return newSearchResponseList(items, hasMore)
    }

    private fun Element.toSearch(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val img = selectFirst("img")
        val title = selectFirst(".movie-card-title")?.text()?.trim()
            ?.ifBlank { null } ?: img?.attr("alt")?.trim() ?: return null
        val poster = img?.attr("src")
        val year = selectFirst(".movie-card-meta")?.text()?.let { Regex("(\\d{4})").find(it)?.value?.toIntOrNull() }
        return if (href.contains("-series-")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // ---------------- Details ----------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = HubResolver.ua).document
        val h1 = doc.selectFirst("h1.page-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: ""
        val year = Regex("\\((\\d{4})\\)").find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val title = h1.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim().ifBlank { h1 }
        val poster = doc.selectFirst("div.poster-image img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val score = Score.from10(doc.selectFirst(".imdb-score")?.text()?.trim())
        val recs = doc.select("div.card-grid-small a.movie-card").mapNotNull { it.toSearch() }
            .filter { it.url != url }

        // Series: only "Single EP's" (#episodes). Zip/Pack (#complete-pack) is ignored on purpose.
        val episodeGroups = doc.select("#episodes .season-item.episode-item")
        if (url.contains("-series-") || episodeGroups.isNotEmpty()) {
            // key = season to episode
            val files = LinkedHashMap<Pair<Int, Int>, MutableList<HubFile>>()
            val titles = HashMap<Pair<Int, Int>, String>()
            episodeGroups.forEach { group ->
                val groupTitle = group.selectFirst(".episode-title")?.text().orEmpty()
                val season = Regex("S(\\d+)", RegexOption.IGNORE_CASE).find(groupTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: group.selectFirst(".episode-number")?.text()?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
                    ?: 1
                group.select(".episode-download-item").forEach { item ->
                    val fileName = item.selectFirst(".episode-file-title")?.text()?.trim().orEmpty()
                    // Filename season is unreliable (e.g. S03 files named S01E..), so episode comes from the badge first
                    val ep = item.selectFirst(".badge-psa")?.text()?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
                        ?: Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(fileName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@forEach
                    val links = item.select(".episode-links a[href]").map { it.attr("href") }.filter { it.startsWith("http") }.distinct()
                    if (links.isEmpty()) return@forEach
                    val key = season to ep
                    files.getOrPut(key) { mutableListOf() }
                        .add(HubFile(fileName, item.selectFirst(".badge-size")?.text()?.trim(), links))
                    if (titles[key] == null) episodeTitle(fileName)?.let { titles[key] = it }
                }
            }
            val episodes = files.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .map { (key, list) ->
                    newEpisode(list.distinctBy { it.name + it.links.first() }.toJson()) {
                        this.season = key.first
                        this.episode = key.second
                        this.name = titles[key]
                    }
                }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.score = score
                this.recommendations = recs
            }
        }

        // Movie: every download card (all qualities / formats)
        val movieFiles = doc.select(".download-item").mapNotNull { item ->
            val fileName = item.selectFirst(".file-title")?.text()?.trim()
                ?: item.selectFirst(".download-header")?.ownText()?.trim().orEmpty()
            val size = item.select(".download-header .badge").firstOrNull { it.text().contains(Regex("(?i)\\d\\s*(GB|MB)")) }?.text()
            val links = item.select("a[href]").map { it.attr("href") }
                .filter { it.startsWith("http") && !it.contains("t.me") }.distinct()
            if (links.isEmpty()) null else HubFile(fileName, size, links)
        }
        return newMovieLoadResponse(title, url, TvType.Movie, movieFiles.toJson()) {
            this.posterUrl = poster
            this.year = year
            this.score = score
            this.recommendations = recs
        }
    }

    /** "Grand.Blue.Dreaming.S01E09.Truth.or.Dare.720p.AMZN..." -> "Truth or Dare" */
    private fun episodeTitle(fileName: String): String? {
        val m = Regex("S\\d+E\\d+[._ ](.+?)[._ ](?:2160p|1080p|720p|480p|4K)", RegexOption.IGNORE_CASE).find(fileName) ?: return null
        val t = m.groupValues[1].replace('.', ' ').replace('_', ' ').trim()
        return t.ifBlank { null }
    }

    // ---------------- Links ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val files = parseJson<List<HubFile>>(data)
        // Short label per file: "1080p H265"; add the size only when two files would share a label
        val labels = files.map { HubResolver.label(it.name) }
        val dup = labels.groupingBy { it }.eachCount()
        val seenDrives = java.util.Collections.synchronizedSet(HashSet<String>())
        val seenUrls = java.util.Collections.synchronizedSet(HashSet<String>())
        files.withIndex().toList().amap { (i, f) ->
            val label = if ((dup[labels[i]] ?: 0) > 1 && !f.size.isNullOrBlank()) "${labels[i]} · ${f.size}" else labels[i]
            // Sequential per file: HubCloud and HubDrive buttons usually point to the same drive (deduped)
            f.links.forEach { link ->
                runCatching { HubResolver.resolve(link, label, mainUrl, seenDrives, seenUrls, callback) }
            }
        }
        return true
    }
}
