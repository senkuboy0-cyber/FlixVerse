package com.castletv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.base64DecodeArray
import okhttp3.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.DeserializationFeature
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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

    private suspend fun getSecurityKey(): String? {
        return try {
            val url = "$mainUrl/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US"
            val response = app.get(url)
            val securityResponse = mapper.readValue<SecurityKeyResponse>(response.text)
            
            if (securityResponse.code == 200) {
                securityResponse.data
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveKey(apiKeyB64: String): ByteArray {
        val apiKeyBytes = base64DecodeArray(apiKeyB64)
        val keyMaterial = apiKeyBytes + keySupFixx.toByteArray(StandardCharsets.US_ASCII)
        
        return when {
            keyMaterial.size < 16 -> keyMaterial + ByteArray(16 - keyMaterial.size)
            keyMaterial.size > 16 -> keyMaterial.copyOfRange(0, 16)
            else -> keyMaterial
        }
    }

    private fun decryptData(encryptedB64: String, apiKeyB64: String): String? {
        return try {
            val aesKey = deriveKey(apiKeyB64)
            val iv = aesKey // Use the same key as IV as confirmed by analysis
            
            val encryptedData = base64DecodeArray(encryptedB64)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(encryptedData)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        *CastleCategories.list.map { CastleCategories.key(it) to it.name }.toTypedArray()
    )

    /** Fetch one page of a Castle tab (home API with locationId) and decrypt it. */
    private suspend fun fetchTabPage(locationId: Int, page: Int, securityKey: String): HomePageData? {
        val url = "$mainUrl/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=$locationId&mode=1&packageName=com.external.castle&page=$page&size=17"
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
                // movieType: 1=TvSeries, 2=Movie, 3=Reality Shows, 5=Anime
                val type = when (content.movieType) {
                    1, 3, 5 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                newMovieSearchResponse(name = title, url = id, type = type) {
                    posterUrl = content.coverImage
                }
            }.orEmpty()

            newHomePageResponse(HomePageList(request.name, contents), false)
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<com.lagradost.cloudstream3.SearchResponse> {
        return try {
            if (query.isBlank()) return emptyList()           
            val securityKey = getSecurityKey() ?: return emptyList()          
            val searchUrl = "$mainUrl/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1&clientType=1&keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&lang=en-US&mode=1&packageName=com.external.castle&page=1&size=30"
            
            val response = app.get(searchUrl)
            val encryptedData = response.text
            
            if (encryptedData.isNullOrBlank()) {
                return emptyList()
            }
            
            val decryptedJson = decryptData(encryptedData, securityKey)
            if (decryptedJson == null) {
                return emptyList()
            }
            
            val searchResponse = mapper.readValue<SearchApiResponse>(decryptedJson)
            val searchData = searchResponse.data
            
            searchData.rows?.mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val id = item.id?.toString() ?: return@mapNotNull null
                val posterUrl = item.coverVerticalImage ?: item.coverHorizontalImage
                // movieType: 1=TvSeries, 2=Movie, 3=Reality Shows, 5=Anime
                val type = when (item.movieType) {
                    1, 3, 5 -> TvType.TvSeries // Series, Reality Shows, Anime
                    2 -> TvType.Movie
                    else -> TvType.Movie
                }
                
                newMovieSearchResponse(
                    name = title,
                    url = id,
                    type = type
                ) {
                    this.posterUrl = posterUrl
                    this.year = item.publishTime?.let { timestamp ->
                        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).year
                    }
                }
            } ?: emptyList()
            
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val movieId = url.substringAfterLast('/')
            
            val securityKey = getSecurityKey() ?: return null
            val detailsUrl = "$mainUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&clientType=1&lang=en-US&movieId=$movieId&packageName=com.external.castle"
            
            val response = app.get(detailsUrl)
            val encryptedData = response.text
            
            if (encryptedData.isNullOrBlank()) {
                return null
            }
            
            val decryptedJson = decryptData(encryptedData, securityKey)
            if (decryptedJson == null) {
                return null
            }
            
            val detailsResponse = mapper.readValue<MovieDetailsResponse>(decryptedJson)
            val details = detailsResponse.data
            
            val title = details.title ?: "Unknown Title"
            val posterUrl = details.coverVerticalImage ?: details.coverHorizontalImage
            val backgroundPosterUrl = details.coverHorizontalImage ?: details.coverVerticalImage
            val plot = details.briefIntroduction
            val year = details.publishTime?.let { timestamp ->
                Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).year
            }
            val rating = details.score?.times(1000)?.toInt() // Convert to CloudStream format
            val tags = details.tags
            val actors = details.actors?.map { 
                ActorData(
                    Actor(it.name ?: "", it.avatar)
                )
            }
            val recommendations = emptyList<SearchResponse>() // Can be populated later if needed
            
            // Determine if this is series-like content (has multiple episodes) or a movie
            // movieType: 1=TvSeries, 2=Movie, 3=Reality Shows, 5=Anime
            val isSeriesLike = details.movieType == 1 || details.movieType == 3 || details.movieType == 5 || 
                               (details.episodes?.size ?: 0) > 1

            // TMDB backdrop and title logo; Castle's horizontal cover stays as the fallback background
            val (tmdbBackdrop, tmdbLogo) = CastleTmdb.artwork(title, year, !isSeriesLike)
            val background = tmdbBackdrop ?: backgroundPosterUrl
            
            when {
                isSeriesLike -> { // TV Series, Reality Shows, Anime (anything with episodes)
                    val allEpisodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                    
                    // If there are multiple seasons, fetch episodes for each season
                    if (details.seasons != null && details.seasons.size > 1) {
                        
                        for (season in details.seasons) {
                            val seasonId = season.movieId?.toString() ?: continue
                            val seasonNumber = season.number ?: continue
                            
                            try {
                                // Fetch episodes for this season
                                val seasonUrl = "$mainUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&clientType=1&lang=en-US&movieId=$seasonId&packageName=com.external.castle"
                                val seasonResponse = app.get(seasonUrl)
                                val seasonEncryptedData = seasonResponse.text
                                
                                if (!seasonEncryptedData.isNullOrBlank()) {
                                    val seasonDecryptedJson = decryptData(seasonEncryptedData, securityKey)
                                    if (seasonDecryptedJson != null) {
                                        val seasonDetailsResponse = mapper.readValue<MovieDetailsResponse>(seasonDecryptedJson)
                                        val seasonDetails = seasonDetailsResponse.data
                                                          seasonDetails.episodes?.forEach { episode ->
                            allEpisodes.add(
                                newEpisode("${seasonId}_${episode.id}") {
                                    this.name = episode.title ?: "Episode ${episode.number ?: allEpisodes.size + 1}"
                                    this.season = seasonNumber
                                    this.episode = episode.number ?: allEpisodes.size + 1
                                    this.posterUrl = episode.coverImage
                                }
                            )
                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Continue with other seasons even if one fails
                            }
                        }
                    } else {
                        // Single season or no season info, use current episodes
                        details.episodes?.forEachIndexed { index, episode ->
                            allEpisodes.add(
                                newEpisode("${details.id}_${episode.id}") {
                                    this.name = episode.title ?: "Episode ${episode.number ?: index + 1}"
                                    this.season = details.seasonNumber
                                    this.episode = episode.number ?: index + 1
                                    this.posterUrl = episode.coverImage
                                }
                            )
                        }
                    }
                    
                    newTvSeriesLoadResponse(
                        name = title,
                        url = url,
                        type = TvType.TvSeries,
                        episodes = allEpisodes
                    ) {
                        this.posterUrl = posterUrl
                        this.backgroundPosterUrl = background
                        this.logoUrl = tmdbLogo
                        this.plot = plot
                        this.year = year
                        this.score = Score.from(rating, 1000)
                        this.tags = tags
                        this.actors = actors
                        this.recommendations = recommendations
                        this.duration = details.episodes?.firstOrNull()?.duration?.div(60) // Convert seconds to minutes
                        this.showStatus = if (details.seasonDescription?.contains("season", true) == true) {
                            ShowStatus.Ongoing
                        } else {
                            ShowStatus.Completed
                        }
                    }
                }
                else -> { // Movie (movieType 2 or single episode content)
                    val episode = details.episodes?.firstOrNull()
                    newMovieLoadResponse(
                        name = title,
                        url = url,
                        type = TvType.Movie,
                        dataUrl = "${details.id}_${episode?.id}" // Combine movie ID and episode ID
                    ) {
                        this.posterUrl = posterUrl
                        this.backgroundPosterUrl = background
                        this.logoUrl = tmdbLogo
                        this.plot = plot
                        this.year = year
                        this.score = Score.from(rating, 1000)
                        this.tags = tags
                        this.actors = actors
                        this.recommendations = recommendations
                        this.duration = episode?.duration?.div(60) // Convert seconds to minutes
                    }
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Data format: "movieId_episodeId"
            val parts = data.split("_")
            if (parts.size != 2) {
                return false
            }
            
            val movieId = if (parts[0].contains("/")) parts[0].substringAfterLast('/') else parts[0]
            val episodeId = parts[1]            
            
            // Get available languages/tracks first to determine languageId
            val securityKey = getSecurityKey() ?: return false
            val detailsUrl = "$mainUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&clientType=1&lang=en-US&movieId=$movieId&packageName=com.external.castle"
            val detailsResponse = app.get(detailsUrl)
            val detailsDecrypted = decryptData(detailsResponse.text, securityKey) ?: return false
            val details = mapper.readValue<MovieDetailsResponse>(detailsDecrypted).data
            
            // Find the episode to get available tracks
            val episode = details.episodes?.find { it.id?.toString() == episodeId }
            if (episode == null) {
                return false
            }
            
            // Get all available languages/tracks
            val availableTracks = episode.tracks ?: emptyList()
            
            // Available resolutions to try (from highest to lowest quality)
            val resolutions = listOf(3, 2, 1) // FHD 1080P, HD 720P, SD 480P
            
            var videoLoaded = false
            
            // Loop through all available languages
            // If existIndividualVideo is false for all tracks, only fetch for the first language and collect all language names
            val hasIndividualVideo = availableTracks.any { it.existIndividualVideo == true }
            if (!hasIndividualVideo && availableTracks.isNotEmpty()) {
                val firstTrack = availableTracks.first()
                val languageId = firstTrack.languageId ?: return false
                val allLanguageNames = availableTracks.mapNotNull { it.languageName ?: it.abbreviate }.joinToString(", ")

                for (resolution in resolutions) {
                    try {
                        val videoUrl = "$mainUrl/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=com.external.castle&channel=IndiaA&lang=en-US"
                        val postBody = """
                            {
                              "mode": "1",
                              "appMarket": "GuanWang",
                              "clientType": "1",
                              "woolUser": "false",
                              "apkSignKey": "ED0955EB04E67A1D9F3305B95454FED485261475",
                              "androidVersion": "13",
                              "movieId": "$movieId",
                              "episodeId": "$episodeId",
                              "isNewUser": "true",
                              "resolution": "$resolution",
                              "packageName": "com.external.castle"
                            }
                        """.trimIndent()

                        val videoResponse = app.post(
                            url = videoUrl,
                            requestBody = postBody.toRequestBody("application/json; charset=utf-8".toMediaType()),
                        )

                        val encryptedData = videoResponse.text
                        if (encryptedData.isNullOrBlank()) {
                            continue
                        }

                        val decryptedJson = decryptData(encryptedData, securityKey)
                        if (decryptedJson == null) {
                            continue
                        }
                        val videoData = mapper.readValue<VideoResponse>(decryptedJson).data

                        if (videoData.videoUrl != null && videoData.permissionDenied != true) {
                            val qualityName = when (resolution) {
                                3 -> "1080p"
                                2 -> "720p"
                                1 -> "480p"
                                else -> "${resolution}p"
                            }

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = if (videoData.videoUrl.contains("preview", ignoreCase = true)) {
                                        "$name - $allLanguageNames (preview) Requires Castle TV Premium"
                                    } else {
                                        "$name - $allLanguageNames"
                                    },
                                    url = videoData.videoUrl,
                                    type = ExtractorLinkType.M3U8
                                )
                                {
                                    this.headers = mapOf("Referer" to mainUrl)
                                    this.quality = when (resolution) {
                                        3 -> 1080
                                        2 -> 720
                                        1 -> 480
                                        else -> resolution * 240
                                    }
                                }
                            )

                            if (!videoLoaded) {
                                videoData.subtitles?.forEach { subtitle ->
                                    if (!subtitle.url.isNullOrBlank()) {
                                        subtitleCallback.invoke(
                                            newSubtitleFile(
                                                lang = subtitle.title ?: subtitle.abbreviate ?: "Unknown",
                                                url = subtitle.url
                                            )
                                        )
                                    }
                                }
                            }

                            videoLoaded = true
                        } else {
                        }
                    } catch (e: Exception) {
                    }
                }
            } else {
                for (track in availableTracks) {
                    val languageId = track.languageId ?: continue
                    val languageName = track.languageName ?: track.abbreviate ?: "Unknown"

                    for (resolution in resolutions) {
                        try {
                            val videoUrl = "$mainUrl/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=com.external.castle&channel=IndiaA&lang=en-US"
                            val postBody = """
                                {
                                "mode": "1",
                                "appMarket": "GuanWang",
                                "clientType": "1",
                                "woolUser": "false",
                                "apkSignKey": "ED0955EB04E67A1D9F3305B95454FED485261475",
                                "androidVersion": "13",
                                "languageId": "$languageId",
                                "movieId": "$movieId",
                                "episodeId": "$episodeId",
                                "isNewUser": "true",
                                "resolution": "$resolution",
                                "packageName": "com.external.castle"
                                }
                            """.trimIndent()

                            val videoResponse = app.post(
                                url = videoUrl,
                                requestBody = postBody.toRequestBody("application/json; charset=utf-8".toMediaType()),
                            )

                            val encryptedData = videoResponse.text

                            if (encryptedData.isNullOrBlank()) {
                                continue
                            }

                            val decryptedJson = decryptData(encryptedData, securityKey)
                            if (decryptedJson == null) {
                                continue
                            }
                            val videoData = mapper.readValue<VideoResponse>(decryptedJson).data

                            if (videoData.videoUrl != null && videoData.permissionDenied != true) {
                                val qualityName = when (resolution) {
                                    3 -> "1080p"
                                    2 -> "720p"
                                    1 -> "480p"
                                    else -> "${resolution}p"
                                }

                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = if (videoData.videoUrl.contains("preview", ignoreCase = true)) {
                                            "$name - $languageName (preview) Requires Castle TV Premium"
                                        } else {
                                            "$name - $languageName"
                                        },
                                        url = videoData.videoUrl,
                                        type = ExtractorLinkType.M3U8
                                    )
                                    {
                                        this.headers = mapOf("Referer" to mainUrl)
                                        this.quality = when (resolution) {
                                            3 -> 1080
                                            2 -> 720
                                            1 -> 480
                                            else -> resolution * 240
                                        }
                                    }
                                )

                                if (!videoLoaded) {
                                    videoData.subtitles?.forEach { subtitle ->
                                        if (!subtitle.url.isNullOrBlank()) {
                                            subtitleCallback.invoke(
                                                newSubtitleFile(
                                                    lang = subtitle.title ?: subtitle.abbreviate ?: "Unknown",
                                                    url = subtitle.url
                                                )
                                            )
                                        }
                                    }
                                }

                                videoLoaded = true
                            } else {
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            
            videoLoaded
            
        } catch (e: Exception) {
            false
        }
    }
}
