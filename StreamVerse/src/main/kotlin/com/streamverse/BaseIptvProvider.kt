package com.streamverse

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Shared live-TV logic for StreamVerse and for every user-added playlist provider.
 *
 * Each channel card encodes a [ChannelPayload] (playlist source + stream url + metadata)
 * as URL-safe Base64 JSON, so load() and loadLinks() can restore the full context
 * without any server-side state.
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

    // ===================== Payload encoding =====================

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

    // ===================== Home page =====================

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

    // ===================== Search =====================

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

    // ===================== Details + links =====================

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
