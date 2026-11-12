package com.castletv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.DeserializationFeature
import java.time.Instant
import java.time.ZoneId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Home categories: see CastleSections (user can tick, reorder and rename them in the plugin settings).

class CastleTvProvider : MainAPI() {
    companion object {
        var context: Context? = null
    }

    override var mainUrl = "https://api.hlowb.com"
    override var name = "Castle TV"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val keySupFixx = BuildConfig.CASTLE_SUFFIX

    // Configure Jackson to ignore unknown properties
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val linkHeaders get() = mapOf("Referer" to mainUrl, "User-Agent" to "okhttp/4.9.0")

    private suspend fun getSecurityKey(): String? {
        return try {
            val url = "$mainUrl/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US"
            val securityResponse = mapper.readValue<SecurityKeyResponse>(app.get(url).text)
            if (securityResponse.code == 200) securityResponse.data else null
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptData(encryptedB64: String, apiKeyB64: String): String? =
        CastleCrypto.decrypt(encryptedB64, apiKeyB64, keySupFixx)

    /** Fetch and decrypt the details of a movie, a series or one season of it. */
    private suspend fun fetchDetails(movieId: String, securityKey: String): MovieDetails? {
        val url = "$mainUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&lang=en-US&movieId=$movieId&packageName=com.external.castle"
        val encrypted = app.get(url).text
        if (encrypted.isBlank()) return null
        val json = decryptData(encrypted, securityKey) ?: return null
        return mapper.readValue<MovieDetailsResponse>(json).data
    }

    /** Castle's own recommendations for one title (12 items of the same kind). */
    private suspend fun fetchRecommendations(movieId: String, securityKey: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/film-api/v0.1/movie/getRecommend?channel=IndiaA&clientType=1&lang=en-US&movieId=$movieId&packageName=com.external.castle"
            val encrypted = app.get(url).text
            if (encrypted.isBlank()) return emptyList()
            val json = decryptData(encrypted, securityKey) ?: return emptyList()
            mapper.readValue<RecommendResponse>(json).data.orEmpty().mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val id = item.id?.toString() ?: return@mapNotNull null
                if (id == movieId) return@mapNotNull null
                newMovieSearchResponse(name = title, url = id, type = tvTypeOf(item.movieType)) {
                    this.posterUrl = item.coverVerticalImage ?: item.coverHorizontalImage
                    this.year = yearOf(item.publishTime)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // movieType: 1=TvSeries, 2=Movie, 3=Reality Shows, 5=Anime
    private fun tvTypeOf(movieType: Int?) = when (movieType) {
        1, 3, 5 -> TvType.TvSeries
        else -> TvType.Movie
    }

    private fun yearOf(timestamp: Long?) =
        timestamp?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year }

    // key "locationId|rowId", in the order and with the names chosen in Manage Sections
    override val mainPage = mainPageOf(*CastleSections.mainPage().toTypedArray())

    /** Fetch one page of a Castle tab (home API with locationId) and decrypt it. */
    private suspend fun fetchTabPage(locationId: Int, page: Int, securityKey: String): HomePageData? {
        val url = "$mainUrl/film-api/v0.1/category/home?channel=IndiaA&clientType=1&lang=en-US&locationId=$locationId&mode=1&packageName=com.external.castle&page=$page&size=17"
        val response = app.get(url)
        val encryptedData = try {
            mapper.readValue<CastleApiResponse>(response.text).data
        } catch (e: Exception) {
            response.text
        }
        if (encryptedData.isNullOrBlank()) return null
        val decryptedJson = decryptData(encryptedData, securityKey) ?: return null
        return mapper.readValue<DecryptedResponse>(decryptedJson).data
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            // Each category is a whole row, so there is only one page
            if (page > 1) return newHomePageResponse(emptyList(), false)
            val securityKey = getSecurityKey() ?: return newHomePageResponse(emptyList())
            val locationId = request.data.substringBefore("|").toInt()
            val rowId = request.data.substringAfter("|").toLong()

            // The row can sit on any page of its tab
            var row: HomePageRow? = null
            var tabPage = 1
            while (row == null && tabPage <= 5) {
                val data = fetchTabPage(locationId, tabPage, securityKey) ?: break
                row = data.rows?.firstOrNull { it.id == rowId }
                if (tabPage >= (data.pages ?: 1)) break
                tabPage++
            }

            val contents = row?.contents?.mapNotNull { content ->
                val title = content.title ?: return@mapNotNull null
                val id = content.redirectId?.toString() ?: return@mapNotNull null
                newMovieSearchResponse(name = title, url = id, type = tvTypeOf(content.movieType)) {
                    posterUrl = content.coverImage
                }
            }.orEmpty()

            newHomePageResponse(HomePageList(request.name, contents), false)
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items.orEmpty()

    /** Paginated search: the API returns page/pages, 30 results per page. */
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return try {
            if (query.isBlank()) return newSearchResponseList(emptyList(), false)
            val securityKey = getSecurityKey() ?: return newSearchResponseList(emptyList(), false)
            val searchUrl = "$mainUrl/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1&keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&lang=en-US&mode=1&packageName=com.external.castle&page=$page&size=30"

            val encryptedData = app.get(searchUrl).text
            if (encryptedData.isBlank()) return newSearchResponseList(emptyList(), false)
            val decryptedJson = decryptData(encryptedData, securityKey) ?: return newSearchResponseList(emptyList(), false)
            val data = mapper.readValue<SearchApiResponse>(decryptedJson).data

            val results = data.rows?.mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val id = item.id?.toString() ?: return@mapNotNull null
                newMovieSearchResponse(name = title, url = id, type = tvTypeOf(item.movieType)) {
                    this.posterUrl = item.coverVerticalImage ?: item.coverHorizontalImage
                    this.year = yearOf(item.publishTime)
                }
            }.orEmpty()

            val hasNext = results.isNotEmpty() && page < (data.pages ?: page)
            newSearchResponseList(results, hasNext)
        } catch (e: Exception) {
            newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val movieId = url.substringAfterLast('/')
            val securityKey = getSecurityKey() ?: return null
            val details = fetchDetails(movieId, securityKey) ?: return null

            val title = details.title ?: "Unknown Title"
            val posterUrl = details.coverVerticalImage ?: details.coverHorizontalImage
            val backgroundPosterUrl = details.coverHorizontalImage ?: details.coverVerticalImage
            val plot = details.briefIntroduction
            val year = yearOf(details.publishTime)
            val rating = details.score?.times(1000)?.toInt() // CloudStream score format

            // Series-like content (has multiple episodes) or a movie
            val isSeriesLike = tvTypeOf(details.movieType) == TvType.TvSeries ||
                (details.episodes?.size ?: 0) > 1

            // TMDB backdrop and title logo; Castle's horizontal cover stays as the fallback background
            val tmdbInfo = CastleTmdb.artwork(title, year, !isSeriesLike)
            val tmdbBackdrop = tmdbInfo.backdrop
            val tmdbLogo = tmdbInfo.logo
            val background = tmdbBackdrop ?: backgroundPosterUrl
            val recs = fetchRecommendations(movieId, securityKey)

            if (isSeriesLike) {
                val allEpisodes = mutableListOf<Episode>()
                val tmdbEpisodes = tmdbInfo.tmdbId?.let { CastleTmdb.getAllEpisodes(it) }.orEmpty()

                val rawSiteEpisodes = mutableListOf<Triple<Int, ApiEpisode, String>>() // seasonNumber, episode, seasonId

                if (details.seasons != null && details.seasons.size > 1) {
                    for (season in details.seasons) {
                        val seasonId = season.movieId?.toString() ?: continue
                        val seasonNumber = season.number ?: continue
                        try {
                            val seasonDetails = fetchDetails(seasonId, securityKey) ?: continue
                            seasonDetails.episodes?.forEach { episode ->
                                rawSiteEpisodes.add(Triple(seasonNumber, episode, seasonId))
                            }
                        } catch (e: Exception) {
                            // Continue with other seasons even if one fails
                        }
                    }
                } else {
                    val sNum = details.seasonNumber ?: 1
                    details.episodes?.forEach { episode ->
                        rawSiteEpisodes.add(Triple(sNum, episode, details.id.toString()))
                    }
                }

                var overallIndex = 0
                rawSiteEpisodes.groupBy { it.first }.forEach { (sNum, epsInSeason) ->
                    epsInSeason.forEachIndexed { seasonEpIndex, (_, episode, sId) ->
                        val calculatedEpNum = seasonEpIndex + 1 // Forces episode 1 for each new season

                        // 1. Match using the newly calculated 1-based episode number
                        var tmdbEp = tmdbEpisodes.find { it.seasonNumber == sNum && it.episodeNumber == calculatedEpNum }
                        
                        // 2. Fallback to API's raw episode number if different
                        if (tmdbEp == null && episode.number != null) {
                            tmdbEp = tmdbEpisodes.find { it.seasonNumber == sNum && it.episodeNumber == episode.number }
                        }
                        
                        // 3. Fallback to chronological overall index (handles anime with 1 flat TMDB season)
                        if (tmdbEp == null) {
                            tmdbEp = tmdbEpisodes.getOrNull(overallIndex)
                        }

                        val epName = tmdbEp?.name?.takeIf { it.isNotBlank() } ?: episode.title ?: "Episode $calculatedEpNum"
                        val epPoster = tmdbEp?.stillPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: episode.coverImage
                        val epDesc = tmdbEp?.overview?.takeIf { it.isNotBlank() }

                        allEpisodes.add(
                            newEpisode("${sId}_${episode.id}") {
                                this.name = epName
                                this.season = sNum
                                this.episode = calculatedEpNum
                                this.posterUrl = epPoster
                                this.description = epDesc
                            }
                        )
                        overallIndex++
                    }
                }

                newTvSeriesLoadResponse(name = title, url = url, type = TvType.TvSeries, episodes = allEpisodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = background
                    this.logoUrl = tmdbLogo
                    this.recommendations = recs
                    this.plot = plot
                    this.year = year
                    this.score = Score.from(rating, 1000)
                    this.duration = details.episodes?.firstOrNull()?.duration?.div(60) // seconds to minutes
                    this.showStatus = if (details.seasonDescription?.contains("season", true) == true) {
                        ShowStatus.Ongoing
                    } else {
                        ShowStatus.Completed
                    }
                }
            } else {
                val episode = details.episodes?.firstOrNull()
                newMovieLoadResponse(
                    name = title,
                    url = url,
                    type = TvType.Movie,
                    dataUrl = "${details.id}_${episode?.id}" // movieId_episodeId
                ) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = background
                    this.logoUrl = tmdbLogo
                    this.recommendations = recs
                    this.plot = plot
                    this.year = year
                    this.score = Score.from(rating, 1000)
                    this.duration = episode?.duration?.div(60) // seconds to minutes
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ===================== Links =====================

    /**
     * False for 403/404, so a quality missing on the CDN is never added.
     * Only the first bytes are requested; the CDN answers a missing file with an XML error.
     * Brotli is handled explicitly: the CDN forces "Content-Encoding: br" on some cached
     * playlists and ignores Accept-Encoding, and its garbage must not break the check
     * (a truncated Brotli body cannot be decoded, which still never looks like an XML error).
     */
    private suspend fun isPlayableM3u8(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url).apply {
                linkHeaders.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=0-511")
            }.build()
            app.baseClient.newCall(request).execute().use { res ->
                if (res.code != 200 && res.code != 206) return false
                val raw = res.body?.bytes() ?: return false
                val text = CastleAudioProxy.decodeIfBrotli(raw, res.header("Content-Encoding"))
                    .toString(Charsets.UTF_8)
                !text.trimStart().startsWith("<")
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun requestVideo(
        movieId: String,
        episodeId: String,
        resolution: Int,
        languageId: Int?,
        securityKey: String,
    ): VideoData? {
        val text = app.post(
            url = "$mainUrl/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=com.external.castle&channel=IndiaA&lang=en-US",
            requestBody = mapper.writeValueAsString(CastleVideoRequest.body(movieId, episodeId, resolution, languageId))
                .toRequestBody("application/json; charset=utf-8".toMediaType()),
        ).text
        if (text.isBlank()) return null
        val json = decryptData(text, securityKey) ?: return null
        return mapper.readValue<VideoResponse>(json).data
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = "movieId_episodeId" from load()
            val movieId = data.substringBefore("_").substringAfterLast('/')
            val episodeId = data.substringAfter("_", "")
            if (movieId.isBlank() || episodeId.isBlank()) return false

            val securityKey = getSecurityKey() ?: return false
            val details = fetchDetails(movieId, securityKey) ?: return false
            val episode = details.episodes?.find { it.id?.toString() == episodeId } ?: return false
            val tracks = episode.tracks.orEmpty()

            // existIndividualVideo=false: every language is an audio track of the same video (same hash),
            // so one call is enough and the label lists all languages
            val requests: List<Pair<Int?, String>> =
                if (tracks.any { it.existIndividualVideo == true }) {
                    tracks.map { it.languageId to (it.languageName ?: it.abbreviate ?: "Default") }
                } else {
                    listOf(
                        tracks.firstOrNull()?.languageId to
                            tracks.mapNotNull { it.languageName ?: it.abbreviate }
                                .joinToString(", ").ifBlank { "Default" }
                    )
                }

            val seen = HashSet<String>() // "hash|quality": same video on another domain is not added twice
            var subsDone = false
            var any = false

            for ((languageId, label) in requests) {
                var apiUrl: String? = null
                var apiRes = 2
                // Ask 720 first: the API returns a usable URL (even a preview) to get hash and index
                for (res in listOf(2, 1)) {
                    val v = try {
                        requestVideo(movieId, episodeId, res, languageId, securityKey)
                    } catch (_: Exception) { null } ?: continue
                    val u = v.videoUrl
                    if (!u.isNullOrBlank() && v.permissionDenied != true) {
                        apiUrl = u
                        apiRes = res
                        if (!subsDone) {
                            v.subtitles?.forEach { sub ->
                                val su = sub.url
                                if (!su.isNullOrBlank()) {
                                    subtitleCallback.invoke(
                                        newSubtitleFile(lang = sub.title ?: sub.abbreviate ?: label, url = su)
                                    )
                                }
                            }
                            subsDone = true
                        }
                        break
                    }
                }
                if (apiUrl.isNullOrBlank()) continue

                val parts = CastleHls.parse(apiUrl)
                if (parts == null) {
                    // Unknown format: keep the signed URL as it is (stripping the query gives 403).
                    // When the CDN forces Brotli on it, the player cannot read it either; serve it
                    // as a plain passthrough (audioPid 0) through the local proxy instead.
                    val q = if (apiRes == 2) 720 else 480
                    if (seen.add("raw|$apiUrl")) {
                        val passthrough = CastleAudioProxy.contentEncodingOf(apiUrl)?.contains("br") == true
                        val linkUrl = if (passthrough) CastleAudioProxy.playlistUrl(apiUrl, 0) else apiUrl
                        callback.invoke(
                            newExtractorLink(source = name, name = "$name $label", url = linkUrl, type = ExtractorLinkType.M3U8) {
                                if (!passthrough) this.headers = linkHeaders
                                this.quality = q
                            }
                        )
                        any = true
                    }
                    continue
                }

                // Expand to full 1080/720/480, only the ones that exist on the CDN.
                // A quality the episode lists (resolution 1/2/3 = 480/720/1080) is kept even when
                // its probe fails (slow or flaky network), on the path the API gave.
                val listed = episode.videos.orEmpty().mapNotNull { v ->
                    when (v.resolution) { 1 -> 480; 2 -> 720; 3 -> 1080; else -> null }
                }.toSet()
                val qualities = mutableListOf<Pair<Int, String>>()
                for (q in listOf(1080, 720, 480)) {
                    if (!seen.add("${parts.hash}|$q")) continue
                    val candidates = CastleHls.qualityUrls(parts, q)
                    val url = candidates.firstOrNull { isPlayableM3u8(it) }
                        ?: candidates.firstOrNull()?.takeIf { q in listed }
                        ?: continue
                    qualities.add(q to url)
                }
                if (qualities.isEmpty()) continue

                // The proxy fetches playlists and segments on behalf of the player, so it
                // always needs the upstream headers (set them before probing).
                CastleAudioProxy.upstreamHeaders = linkHeaders

                // All languages are muxed into the same segments; the internal player reads only one.
                // With more than one audio stream, serve one link per language through the local proxy.
                val probe = CastleAudioProxy.probeFull(qualities.last().second)
                val audio = probe.audio

                if (audio.size <= 1) {
                    // Single audio stream. Some cached playlists come Brotli-compressed from the
                    // CDN (it ignores Accept-Encoding), which the player cannot decode: those are
                    // served as a plain passthrough (audioPid 0 = nothing is filtered) through
                    // the local proxy.
                    val passthrough = probe.wasBrotli
                    for ((q, url) in qualities) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name $label",
                                url = if (passthrough) CastleAudioProxy.playlistUrl(url, 0) else url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                if (!passthrough) this.headers = linkHeaders
                                this.quality = q
                            }
                        )
                        any = true
                    }
                } else {
                    audio.forEachIndexed { index, stream ->
                        val language = CastleAudioProxy.languageName(stream.language, index)
                        for ((q, url) in qualities) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name $language",
                                    url = CastleAudioProxy.playlistUrl(url, stream.pid),
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = q
                                }
                            )
                            any = true
                        }
                    }
                }
            }
            any
        } catch (e: Exception) {
            false
        }
    }
}
