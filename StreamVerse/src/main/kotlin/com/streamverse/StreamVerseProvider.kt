package com.streamverse

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.streamverse.setting.PlaylistStore
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

// =====================================================================================
// Curated categories
// =====================================================================================

/**
 * The curated IPTV playlists that become the StreamVerse home page categories.
 * PlayZ TV itself is excluded from this list.
 */
object StreamVerseCategories {
    val ALL: List<Pair<String, String>> = listOf(
        "https://raw.githubusercontent.com/doms9/iptv/refs/heads/default/M3U8/events.m3u8" to "Live Sports HD",
        "https://raw.githubusercontent.com/abusaeeidx/BDxTV/refs/heads/main/playlist_s.m3u" to "Live Sports SD",
        "https://m3u-tvb.pages.dev/pk.m3ux" to "Pakistan",
        "https://iptv-org.github.io/iptv/index.country.m3u" to "World Country",
        "https://iptv-org.github.io/iptv/index.language.m3u" to "World Languages",
        "https://raw.githubusercontent.com/abusaeeidx/IPTV-Scraper-Zilla/refs/heads/main/Plex-All.m3u" to "Plex",
        "https://raw.githubusercontent.com/abusaeeidx/Mrgify-BDIX-IPTV/refs/heads/main/playlist.m3u" to "BDIX TV",
        "https://playlists-by-playztv.pages.dev/snxt" to "Sun Nxt",
        "https://raw.githubusercontent.com/abusaeeidx/IPTV-Scraper-Zilla/refs/heads/main/distrotv.m3u" to "Distro Tv",
        "https://playlists-by-playztv.pages.dev/disttv-playztv.m3u" to "Dish Tv",
        "https://playlists-by-playztv.pages.dev/zeesd.m3u" to "Z5 SD",
        "https://raw.githubusercontent.com/abusaeeidx/Yupptv-Playlist/refs/heads/main/playlist.m3u" to "YuppTv",
        "https://raw.githubusercontent.com/iptv-org/iptv/refs/heads/master/streams/uk_samsung.m3u" to "Samsung",
        "https://pastebin.com/raw/1ErgTspB" to "LGTV IN",
        "https://tinyurl.com/freeiptvtv" to "Global",
        "https://raw.githubusercontent.com/abusaeeidx/Netfluix/refs/heads/main/netflixplaylist.m3u" to "Netflix",
        "https://premiumplugx.com/htt/prime.php?playlist=1|user-agent=mozila" to "Prime IN",
        "https://raw.githubusercontent.com/abusaeeidx/Prime-Playlist/refs/heads/main/Prime.m3u|user-agent=mozila" to "Prime IN 2",
        "https://thaparxBD.github.io/jio1.m3u" to "JIO",
        "https://thaparxBD.github.io/jio2.m3u" to "JIO 2",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/jio.m3u" to "JIO 3",
        "https://jji0-tt.p-szsk.workers.dev/jstar.m3u[jtvf]" to "JIO 4",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/airtel.m3u" to "Airtel",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/tataplay.m3u" to "Tata Play",
        "https://m3u-tvb.pages.dev/tata.m3ux" to "Tata Play 2",
        "https://raw.githubusercontent.com/abusaeeidx/Tataplay-Playlist/refs/heads/main/tataplay.m3u" to "Tata Play 3",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/dth.m3u" to "DTH",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/dth2.m3u" to "DTH 2",
        "https://raw.githubusercontent.com/RanbirOp/CS3/refs/heads/main/playlist/full.m3u" to "MX Play",
        "https://bit.ly/ForYouLiVe" to "World Live",
        "https://raw.githubusercontent.com/habib41/m3u1/refs/heads/main/vod1.m3u" to "World VOD",
        "https://shamserpulami.blogspot.com/p/iptv.html" to "Nepal",
        "https://raw.githubusercontent.com/nijanthan06/ott-playlist/refs/heads/main/Live.m3u" to "Tamil",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/sonyliv.m3u" to "SonyLiv",
        "https://sony-liv-playlist.vercel.app/playlist.m3u" to "SonyLiv 2",
        "https://sonylivplaylist.pages.dev/byp.m3u" to "SonyLiv 3",
        "https://raw.githubusercontent.com/Sanjay-969/CS3/refs/heads/main/Sony%20Liv%20All%20Channels.txt" to "SonyLiv 4",
        "https://raw.githubusercontent.com/rabdonx/Howler/refs/heads/main/playlist.m3u" to "Howler",
        "https://ott-playlist.glitch.me/ptv.txt" to "PTV",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/zee5.m3u" to "ZEE5",
        "https://zee5-playlist.vercel.app/playlist.m3u" to "ZEE5 2",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/zee5ww.m3u" to "ZEE5 3 WW",
        "https://zee5wwplaylist.pages.dev/ww.m3u" to "ZEE5 4 WW",
        "https://zee5wwplaylist.pages.dev/ww2.m3u" to "ZEE5 5 WW",
        "https://raw.githubusercontent.com/RanbirOp/CS3/refs/heads/main/playlist/zee.m3u" to "ZEE5 6",
        "https://m3u-tvb.pages.dev/zee.m3ux" to "ZEE5 SD",
        "https://raw.githubusercontent.com/abusaeeidx/Aynadtv/refs/heads/main/playlist.m3u" to "Ayna",
        "https://raw.githubusercontent.com/abhijeet2023/CS3/refs/heads/main/Tv-Channel/India.m3u" to "India",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/india.m3u" to "India 2",
        "https://indialist.pages.dev/ind.m3u" to "India 3",
        "https://indialist.pages.dev/ind2.m3u" to "India 4",
        "https://iptv-org.github.io/iptv/countries/in.m3u" to "India 5",
        "https://iptv-org.github.io/iptv/countries/bd.m3u" to "Bangladesh",
        "https://iptv-org.github.io/iptv/countries/pk.m3u" to "Pakistan 2",
        "https://iptv-org.github.io/iptv/countries/np.m3u" to "Nepal 2",
        "https://m3u-tvb.pages.dev/ind2.m3ux" to "India 6",
        "https://m3u-tvb.pages.dev/bd2.m3ux" to "Bangladesh 2",
        "https://raw.githubusercontent.com/mitthu456/APP-M3U/refs/heads/main/bd.m3u" to "Bangladesh 3",
        "https://aynalist.pages.dev/ayna.m3u" to "Ayna 2",
        "https://raw.githubusercontent.com/abusaeeidx/dhuutv/refs/heads/main/playlist.m3u" to "Dhuu TV",
        "https://raw.githubusercontent.com/Shirazi-0/DFE/refs/heads/main/Afghanistan.m3u" to "Afghanistan",
        "https://raw.githubusercontent.com/Shirazi-0/DFE/refs/heads/main/Afghanistan%202.m3u" to "Afghanistan 2",
        "https://raw.githubusercontent.com/Shirazi-0/DFE/refs/heads/main/iran.m3u" to "Iran",
        "https://iptv-org.github.io/iptv/countries/af.m3u" to "Afghanistan 3",
        "https://raw.githubusercontent.com/free-iptv/iptv/refs/heads/main/playlist.m3u" to "Free IPTV",
        "https://iptv-org.github.io/iptv/index.m3u" to "World All",
        "https://iptv-org.github.io/iptv/categories/news.m3u" to "News",
        "https://iptv-org.github.io/iptv/categories/sports.m3u" to "Sports",
        "https://iptv-org.github.io/iptv/categories/music.m3u" to "Music",
        "https://iptv-org.github.io/iptv/categories/kids.m3u" to "Kids",
        "https://iptv-org.github.io/iptv/categories/movies.m3u" to "Movies",
        "https://iptv-org.github.io/iptv/categories/documentary.m3u" to "Documentary",
        "https://iptv-org.github.io/iptv/categories/entertainment.m3u" to "Entertainment",
        "https://iptv-org.github.io/iptv/categories/general.m3u" to "General",
        "https://iptv-org.github.io/iptv/categories/religious.m3u" to "Religious",
        "https://iptv-org.github.io/iptv/categories/shop.m3u" to "Shopping",
        "https://iptv-org.github.io/iptv/categories/science.m3u" to "Science",
        "https://iptv-org.github.io/iptv/categories/lifestyle.m3u" to "Lifestyle",
        "https://iptv-org.github.io/iptv/categories/animation.m3u" to "Animation",
        "https://iptv-org.github.io/iptv/categories/business.m3u" to "Business",
        "https://iptv-org.github.io/iptv/categories/cooking.m3u" to "Cooking",
        "https://iptv-org.github.io/iptv/categories/education.m3u" to "Education",
        "https://iptv-org.github.io/iptv/categories/family.m3u" to "Family",
        "https://iptv-org.github.io/iptv/categories/comedy.m3u" to "Comedy",
        "https://iptv-org.github.io/iptv/categories/legislative.m3u" to "Legislative",
        "https://iptv-org.github.io/iptv/categories/outdoor.m3u" to "Outdoor",
        "https://iptv-org.github.io/iptv/categories/auto.m3u" to "Auto",
        "https://iptv-org.github.io/iptv/categories/weather.m3u" to "Weather",
        "https://iptv-org.github.io/iptv/categories/travel.m3u" to "Travel",
        "https://iptv-org.github.io/iptv/categories/series.m3u" to "Series",
        "https://iptv-org.github.io/iptv/categories/classic.m3u" to "Classic",
        "https://raw.githubusercontent.com/abusaeeidx/Daddy-Live-HD/refs/heads/main/Daddylive.m3u" to "Daddy Live HD",
        "https://raw.githubusercontent.com/abusaeeidx/UK-Tv/refs/heads/main/playlist.m3u" to "UK",
        "https://iptv-org.github.io/iptv/countries/us.m3u" to "USA",
        "https://iptv-org.github.io/iptv/countries/gb.m3u" to "UK 2",
        "https://iptv-org.github.io/iptv/countries/us@Movies.m3u" to "USA Movies",
        "https://iptv-org.github.io/iptv/countries/us@Sports.m3u" to "USA Sports",
        "https://m3u-tvb2.pages.dev/output (7).txt" to "iscreen",
        "https://raw.githubusercontent.com/bamaghzb/PROVIDER/refs/heads/main/paidchannels.txt" to "Paid Channels",
        "https://hg-ht.pages.dev/WORLD TV4xx.m3u" to "WorldTV 4.0",
        "https://m3u-tvb2.pages.dev/mmmyccc.txt" to "MYCO",
        "https://hg-ht.pages.dev/master-20261102-122246.m3u" to "INDONESIA",
        "https://pvp-pl-2.pages.dev/output/outputpiiiishow (10).txt" to "Pishow",
        "https://still-bird-0810.mota-bhsi.workers.dev/|user-agent=PlayZTV" to "JIO IND 2",
    )
}

// =====================================================================================
// M3U parsing
// =====================================================================================

/**
 * One playable entry of an M3U / M3U8 playlist.
 */
data class M3uChannel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
)

/**
 * Minimal M3U / M3U8 playlist parser for IPTV lists.
 *
 * Supported input features:
 *  - "#EXTINF:<duration> attr=\"value\" ...,Channel Name" entries
 *  - tvg-logo, group-title and tvg-name attributes
 *  - "#EXTVLCOPT:http-user-agent=..." and "#EXTVLCOPT:http-referrer=..."
 *  - "#EXTHTTP:{\"User-Agent\":\"...\"}" JSON options
 *  - per-entry "|user-agent=..." / "|referer=..." URL suffixes used by common IPTV apps
 *
 * Only http(s) stream URLs are kept; rtmp / plugin / unknown schemes are skipped
 * because the in-app player cannot open them.
 */
object M3uParser {

    private val attrRegex = Regex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")
    private val vlcOptRegex = Regex("#EXTVLCOPT:\\s*([\\w-]+)\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
    private val extHttpUaRegex = Regex("\"User-Agent\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val extHttpRefRegex = Regex("\"Referer\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val urlSuffixRegex = Regex("\\|(user-agent|referer|referrer)\\s*=\\s*([^|]*)$", RegexOption.IGNORE_CASE)

    /** Splits "|user-agent=..." / "|referer=..." off the end of a stream URL. */
    fun splitUrlSuffix(url: String): Triple<String, String?, String?> {
        var u = url
        var ua: String? = null
        var ref: String? = null
        while (true) {
            val m = urlSuffixRegex.find(u) ?: break
            when (m.groupValues[1].lowercase()) {
                "user-agent" -> ua = m.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: ua
                else -> ref = m.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: ref
            }
            u = u.substring(0, m.range.first)
        }
        return Triple(u, ua, ref)
    }

    /** First comma outside of quotes: attribute section before it, display name after it. */
    private fun splitExtinf(line: String): Pair<String, String> {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return line.substring(0, i) to line.substring(i + 1).trim()
            }
        }
        return line to ""
    }

    fun parse(text: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var ua: String? = null
        var ref: String? = null

        fun flush(url: String) {
            val (clean, urlUa, urlRef) = splitUrlSuffix(url)
            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                channels.add(
                    M3uChannel(
                        name = name?.takeIf { it.isNotBlank() } ?: clean.substringAfterLast('/'),
                        url = clean,
                        group = group?.takeIf { it.isNotBlank() },
                        logo = logo?.takeIf { it.isNotBlank() },
                        userAgent = ua ?: urlUa,
                        referrer = ref ?: urlRef,
                    )
                )
            }
            name = null; logo = null; group = null; ua = null; ref = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF") -> {
                    val (attrs, title) = splitExtinf(line)
                    name = title.ifBlank { null }
                    for (m in attrRegex.findAll(attrs)) {
                        when (m.groupValues[1].lowercase()) {
                            "tvg-logo", "logo" -> logo = m.groupValues[2]
                            "group-title", "group" -> group = m.groupValues[2]
                            "tvg-name" -> if (name.isNullOrBlank()) name = m.groupValues[2]
                        }
                    }
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val m = vlcOptRegex.find(line) ?: return@forEach
                    when (m.groupValues[1].lowercase()) {
                        "http-user-agent" -> ua = m.groupValues[2].trim()
                        "http-referrer", "http-referer" -> ref = m.groupValues[2].trim()
                    }
                }
                line.startsWith("#EXTHTTP:") -> {
                    extHttpUaRegex.find(line)?.let { ua = it.groupValues[1] }
                    extHttpRefRegex.find(line)?.let { ref = it.groupValues[1] }
                }
                line.startsWith("#") -> {}
                else -> flush(line)
            }
        }
        return channels
    }
}

// =====================================================================================
// Playlist fetching + cache
// =====================================================================================

/** Where a playlist lives: a remote URL or an m3u file imported from the device. */
sealed interface PlaylistSource {
    data class Remote(val url: String) : PlaylistSource
    data class Local(val path: String) : PlaylistSource
}

/**
 * Fetches playlists and caches the parsed channels in memory.
 *
 * PlayZ-specific URL markers are handled transparently:
 *  - "[jtvf]" at the end is a PlayZ format flag and is stripped before the request
 *  - "|user-agent=..." at the end selects the request User-Agent
 */
object PlaylistFetcher {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    private val cache = HashMap<String, Pair<Long, List<M3uChannel>>>()
    private val jtvfRegex = Regex("\\[jtvf]$", RegexOption.IGNORE_CASE)
    private val uaSuffixRegex = Regex("\\|user-agent=([^|]*)$", RegexOption.IGNORE_CASE)

    fun key(source: PlaylistSource): String = when (source) {
        is PlaylistSource.Remote -> "remote|${source.url}"
        is PlaylistSource.Local -> "local|${source.path}"
    }

    fun sourceOfKey(key: String): PlaylistSource =
        if (key.startsWith("local|")) PlaylistSource.Local(key.removePrefix("local|"))
        else PlaylistSource.Remote(key.removePrefix("remote|"))

    /** Strips [jtvf] and the |user-agent= suffix from a playlist URL; encodes raw spaces. */
    fun cleanRemoteUrl(url: String): Pair<String, String?> {
        var u = jtvfRegex.replace(url, "")
        var ua: String? = null
        val m = uaSuffixRegex.find(u)
        if (m != null) {
            ua = m.groupValues[1].trim().takeIf { it.isNotEmpty() }
            u = u.substring(0, m.range.first)
        }
        // Some curated URLs contain raw spaces (e.g. "output (7).txt")
        u = u.replace(" ", "%20")
        return u to ua
    }

    fun readLocalText(path: String): String? = try {
        val f = File(path)
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    /** Raw playlist text for a source, or null when unreachable. */
    suspend fun text(source: PlaylistSource): String? = try {
        when (source) {
            is PlaylistSource.Remote -> {
                val (clean, ua) = cleanRemoteUrl(source.url)
                val headers = ua?.let { mapOf("User-Agent" to it) } ?: emptyMap()
                app.get(clean, headers = headers).text
            }
            is PlaylistSource.Local -> readLocalText(source.path)
        }
    } catch (_: Exception) {
        null
    }

    /** Parsed channels for a source, cached for CACHE_TTL_MS. */
    suspend fun channels(source: PlaylistSource, forceRefresh: Boolean = false): List<M3uChannel> {
        val k = key(source)
        if (!forceRefresh) {
            val hit = synchronized(cache) { cache[k] }
            if (hit != null && System.currentTimeMillis() - hit.first < CACHE_TTL_MS) {
                return hit.second
            }
        }
        val parsed = text(source)?.let { M3uParser.parse(it) }.orEmpty()
        synchronized(cache) { cache[k] = System.currentTimeMillis() to parsed }
        return parsed
    }

    fun clear(source: PlaylistSource) = synchronized(cache) { cache.remove(key(source)) }

    fun clearAll() = synchronized(cache) { cache.clear() }
}

// =====================================================================================
// Providers
// =====================================================================================

/**
 * Shared live-TV logic for StreamVerse and for every user-added playlist provider.
 *
 * Each channel card encodes a [BaseIptvProvider.ChannelPayload] (playlist source +
 * stream url + metadata) as URL-safe Base64 JSON, so load() and loadLinks() can
 * restore the full context without any server-side state.
 */
abstract class BaseIptvProvider : MainAPI() {

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "hi"

    private val mapper = jacksonObjectMapper()

    data class ChannelPayload(
        val p: String, // playlist source key ("remote|<url>" or "local|<path>")
        val u: String, // stream url
        val n: String, // channel name
        val g: String? = null, // group title
        val l: String? = null, // logo url
        val ua: String? = null, // per-channel User-Agent
        val ref: String? = null, // per-channel Referer
    )

    private val base64Flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encodePayload(payload: ChannelPayload): String =
        Base64.encodeToString(mapper.writeValueAsBytes(payload), base64Flags)

    fun decodePayload(encoded: String): ChannelPayload? = try {
        mapper.readValue<ChannelPayload>(Base64.decode(encoded, base64Flags))
    } catch (_: Exception) {
        null
    }

    fun payloadOf(channel: M3uChannel, source: PlaylistSource): ChannelPayload = ChannelPayload(
        p = PlaylistFetcher.key(source),
        u = channel.url,
        n = channel.name,
        g = channel.group,
        l = channel.logo,
        ua = channel.userAgent,
        ref = channel.referrer,
    )

    fun toItem(channel: M3uChannel, source: PlaylistSource): SearchResponse =
        newLiveSearchResponse(
            name = channel.name,
            url = encodePayload(payloadOf(channel, source)),
            type = TvType.Live,
            fix = false,
        ) {
            this.posterUrl = channel.logo
        }

    /** Groups one playlist's channels by group-title into home page rows. */
    suspend fun groupedLists(source: PlaylistSource): List<HomePageList> {
        val channels = PlaylistFetcher.channels(source)
        if (channels.isEmpty()) return emptyList()
        return channels
            .groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "Live Channels" }
            .map { (group, chans) ->
                HomePageList(group, chans.sortedBy { it.name.lowercase() }.map { toItem(it, source) })
            }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        return try {
            val source = PlaylistFetcher.sourceOfKey(request.data)
            val lists = groupedLists(source)
            newHomePageResponse(lists, false)
        } catch (_: Exception) {
            newHomePageResponse(emptyList(), false)
        }
    }

    /** Search one playlist using the warm cache. */
    protected suspend fun searchSource(source: PlaylistSource, query: String, limit: Int = 100): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return try {
            PlaylistFetcher.channels(source)
                .filter { it.name.lowercase().contains(q) }
                .take(limit)
                .map { toItem(it, source) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Concurrent search over many sources; failures are skipped. */
    protected suspend fun searchSources(
        sources: List<PlaylistSource>,
        query: String,
        parallelism: Int = 12,
        perSourceLimit: Int = 60,
    ): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val semaphore = Semaphore(parallelism)
        return try {
            coroutineScope {
                val jobs = mutableListOf<Deferred<List<SearchResponse>>>()
                for (source in sources) {
                    jobs.add(
                        async {
                            semaphore.withPermit {
                                try {
                                    PlaylistFetcher.channels(source)
                                        .filter { it.name.lowercase().contains(q) }
                                        .take(perSourceLimit)
                                        .map { toItem(it, source) }
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }
                    )
                }
                jobs.awaitAll().flatten()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val payload = decodePayload(url) ?: return null
        if (payload.u.isBlank()) return null
        return newLiveStreamLoadResponse(
            name = payload.n.ifBlank { "Live Channel" },
            url = url,
            dataUrl = url,
        ) {
            this.posterUrl = payload.l
            this.plot = payload.g?.let { "Group: $it" }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = decodePayload(data) ?: return false
        val url = payload.u
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        val headers = buildMap {
            payload.ua?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            payload.ref?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        val path = url.lowercase().substringBefore('?')
        val isHls = path.endsWith(".m3u") || path.endsWith(".m3u8") || path.contains(".m3u/")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = payload.n.ifBlank { name },
                url = url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.headers = headers
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}

/**
 * The StreamVerse IPTV extension itself: every curated playlist is one home page
 * category (101 categories, PlayZ TV excluded). Search runs over all playlists
 * concurrently and reuses the warm cache.
 */
class StreamVerseProvider : BaseIptvProvider() {

    override var name = "StreamVerse"

    override val mainPage: List<MainPageData> =
        mainPageOf(*StreamVerseCategories.ALL.map { (url, category) ->
            "remote|$url" to category
        }.toTypedArray())

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items.orEmpty()

    /** Paginated global search: 60 results per page, cached playlists answered instantly. */
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val sources = StreamVerseCategories.ALL.map { (url, _) -> PlaylistSource.Remote(url) }
        val results = searchSources(sources, query)
        val perPage = 60
        val start = (page - 1) * perPage
        if (start >= results.size) return newSearchResponseList(emptyList(), false)
        val hasNext = start + perPage < results.size
        return newSearchResponseList(results.drop(start).take(perPage), hasNext)
    }
}

/**
 * One provider per user-added playlist (Settings > Add Playlist).
 * These providers are registered separately from StreamVerse, so their channels
 * never mix with the curated StreamVerse categories. The single "All Channels"
 * category expands into one home page row per group-title found in the playlist.
 */
class CustomPlaylistProvider(
    private val item: PlaylistStore.Item,
) : BaseIptvProvider() {

    override var name = item.name

    private val sourceKey: String = when (val src = item.source()) {
        is PlaylistSource.Local -> "local|${src.path}"
        is PlaylistSource.Remote -> "remote|${src.url}"
    }

    override val mainPage: List<MainPageData> = mainPageOf(sourceKey to "All Channels")

    override suspend fun search(query: String): List<SearchResponse> =
        searchSource(item.source(), query)
}
