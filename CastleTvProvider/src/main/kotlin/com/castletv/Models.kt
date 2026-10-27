package com.castletv

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ===================== Castle API responses =====================
data class CastleApiResponse(
    val code: Int,
    val msg: String,
    val data: String? = null
)

data class SecurityKeyResponse(
    val code: Int,
    val msg: String,
    val data: String
)

// Wrapper for the decrypted response
data class DecryptedResponse(
    val code: Int,
    val msg: String,
    val data: HomePageData
)

data class HomePageData(
    val page: Int? = null,
    val pages: Int? = null,
    val size: Int? = null,
    val total: Int? = null,
    val rows: List<HomePageRow>? = null
)

data class HomePageRow(
    val id: Long? = null,
    val name: String? = null,
    val coverImage: String? = null,
    val coverImageHeight: Int? = null,
    val coverImageWidth: Int? = null,
    val type: Int? = null,
    val redirectType: Int? = null,
    val briefIntroduction: String? = null,
    val contents: List<ContentItem>? = null
)

data class ContentItem(
    val title: String? = null,
    val coverImage: String? = null,
    val redirectType: Int? = null,
    val redirectId: Long? = null,
    val movieType: Int? = null,
    val score: Double? = null,
    val publishTime: Long? = null,
    val heat: Int? = null,
    val order: Int? = null,
    val unlockPlayback: Boolean? = null,
    val languages: List<String>? = null,
    val excludeChannelIds: List<String>? = null,
    val memberLevel: Int? = null,
    val standardExpireTime: Long? = null,
    val indiaResolutionLabel: String? = null,
    val standardNewExpireTime: Long? = null,
    val countdownHourNew: Int? = null,
    val countdownHour: Int? = null,
    val serverTime: Long? = null,
    val woolUser: Any? = null
)

// Data classes for movie/series details
data class MovieDetailsResponse(
    val code: Int,
    val msg: String,
    val data: MovieDetails
)

data class MovieDetails(
    val id: Long? = null,
    val title: String? = null,
    val score: Double? = null,
    val movieType: Int? = null,
    val movieTypeName: String? = null,
    val coverHorizontalImage: String? = null,
    val coverVerticalImage: String? = null,
    val unlockPlayback: Boolean? = null,
    val seasonDescription: String? = null,
    val languages: List<String>? = null,
    val lastEpisodeCount: Int? = null,
    val serverTime: Long? = null,
    val totalNumber: Int? = null,
    val woolUser: Boolean? = null,
    val briefIntroduction: String? = null,
    val publishTime: Long? = null,
    val tags: List<String>? = null,
    val countries: List<String>? = null,
    val isAuthorized: Boolean? = null,
    val originalTitle: String? = null,
    val directors: List<Person>? = null,
    val actors: List<Person>? = null,
    val episodes: List<ApiEpisode>? = null,
    val seasonNumber: Int? = null,
    val updateNumber: Int? = null,
    val watchCount: Long? = null,
    val commentTotal: Int? = null,
    val previewTime: Int? = null,
    val seasons: List<Season>? = null,
    val audioTags: List<String>? = null,
    val countryIds: List<Long>? = null,
    val tagIds: List<Long>? = null,
    val resolution: Int? = null,
    val indiaResolutionLabel: String? = null,
    val titbits: List<Titbit>? = null,
    val honorTag: Any? = null,
    val downloadTag: Any? = null
)

data class Person(
    val id: Long? = null,
    val name: String? = null,
    val avatar: String? = null
)

data class ApiEpisode(
    val id: Long? = null,
    val title: String? = null,
    val number: Int? = null,
    val coverImage: String? = null,
    val duration: Int? = null,
    val videos: List<VideoQuality>? = null,
    val playResolution: Int? = null,
    val mobileTrafficPlayResolution: Int? = null,
    val tracks: List<Track>? = null,
    val onlineTime: Long? = null
)

data class VideoQuality(
    val resolution: Int? = null,
    val resolutionDescription: String? = null,
    val size: Long? = null,
    val premiumProPermission: Boolean? = null
)

data class Track(
    val languageId: Int? = null,
    val languageName: String? = null,
    val abbreviate: String? = null,
    val isDefault: Boolean? = null,
    val existIndividualVideo: Boolean? = null,
    val subtitles: List<Any>? = null,
    val order: Int? = null,
    val index: Int? = null
)

data class Season(
    val movieId: Long? = null,
    val number: Int? = null,
    val description: String? = null,
    val isCurrent: Boolean? = null
)

data class Titbit(
    val id: String? = null,
    val name: String? = null,
    val videoCategory: Int? = null,
    val coverImage: String? = null
)

// Data classes for search response
data class SearchApiResponse(
    val code: Int,
    val msg: String,
    val data: SearchData
)

// getRecommend: data is a plain list of titles
data class RecommendResponse(
    val code: Int,
    val msg: String,
    val data: List<SearchResultItem>? = null
)

data class SearchData(
    val page: Int? = null,
    val pages: Int? = null,
    val size: Int? = null,
    val total: Int? = null,
    val rows: List<SearchResultItem>? = null
)

data class SearchResultItem(
    val id: Long? = null,
    val title: String? = null,
    val score: Double? = null,
    val movieType: Int? = null,
    val movieTypeName: String? = null,
    val coverHorizontalImage: String? = null,
    val coverVerticalImage: String? = null,
    val unlockPlayback: Boolean? = null,
    val seasonDescription: String? = null,
    val languages: List<String>? = null,
    val lastEpisodeCount: Int? = null,
    val serverTime: Long? = null,
    val woolUser: Boolean? = null,
    val briefIntroduction: String? = null,
    val publishTime: Long? = null,
    val tags: List<String>? = null,
    val countries: List<String>? = null
)

// Data classes for video/streaming response
data class VideoResponse(
    val code: Int,
    val msg: String,
    val data: VideoData
)

data class VideoData(
    val videoUrl: String? = null,
    val expireTime: Long? = null,
    val isPreview: Boolean? = null,
    val videos: List<VideoQuality>? = null,
    val subtitles: List<SubtitleData>? = null,
    val inBlacklist: Boolean? = null,
    val permissionDenied: Boolean? = null
)

data class SubtitleData(
    val languageId: Int? = null,
    val abbreviate: String? = null,
    val title: String? = null,
    val url: String? = null,
    val isDefault: Boolean? = null,
    val isAI: Int? = null
)

// ===================== TMDB =====================

data class TmdbSearchResponse(val results: List<TmdbSearchItem>? = null)

data class TmdbSearchItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
)

data class TmdbImagesResponse(
    val logos: List<TmdbImageItem>? = null,
    val backdrops: List<TmdbImageItem>? = null,
)

data class TmdbImageItem(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val iso6391: String? = null,
)

/**
 * Backdrop and title logo from TMDB.
 * Castle titles are already clean, so the title is searched as it is (name + year first, then name only):
 * series in TMDB tv, movies in TMDB movie.
 * Only a result whose name is exactly the Castle title (case ignored) is accepted.
 * Among those, the release year must match (exact, then one year either side);
 * if no year fits but there is just one exact-name result, that one is used.
 * No exact-name result means nothing is taken from TMDB.
 */
object CastleTmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private const val IMG = "https://image.tmdb.org/t/p/original"

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun norm(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase()

    private fun TmdbSearchItem.year(): Int? =
        (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

    private fun TmdbSearchItem.sameName(title: String): Boolean {
        val t = norm(title)
        return listOf(this.title, name, originalTitle, originalName).any { norm(it) == t }
    }

    /** Title for TMDB search only: (...) and [...] parts removed. The app keeps showing the original title. */
    private fun searchTitle(title: String): String =
        title.replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
            .ifEmpty { title }

    suspend fun artwork(originalTitle: String, year: Int?, isMovie: Boolean): Pair<String?, String?> {
        val kind = if (isMovie) "movie" else "tv"
        val title = searchTitle(originalTitle)
        return try {
            val query = java.net.URLEncoder.encode(title, "UTF-8")
            // Exact-name results of one search; name is always matched, with or without the year
            suspend fun exactResults(extra: String) = mapper.readValue<TmdbSearchResponse>(
                app.get("$API/search/$kind?api_key=$KEY&query=$query$extra").text
            ).results.orEmpty().filter { it.id != null && it.sameName(title) }
            // Name + year first, then name only
            val yearParam = if (isMovie) "primary_release_year" else "first_air_date_year"
            val exact = (year?.let { exactResults("&$yearParam=$it") }.orEmpty())
                .ifEmpty { exactResults("") }
            if (exact.isEmpty()) return null to null

            val match = year?.let { y ->
                exact.firstOrNull { it.year() == y }
                    ?: exact.firstOrNull { it.year()?.let { r -> kotlin.math.abs(r - y) <= 1 } == true }
            } ?: exact.singleOrNull() ?: return null to null

            val images = mapper.readValue<TmdbImagesResponse>(
                app.get("$API/$kind/${match.id}/images?api_key=$KEY").text
            )
            fun TmdbImageItem.isSvg() = filePath?.endsWith(".svg", ignoreCase = true) == true
            // All languages fetched; the first image of the first listed language wins, else the first image (never svg)
            fun pick(list: List<TmdbImageItem>?, order: List<String?>): String? {
                val items = list?.filter { !it.filePath.isNullOrBlank() && !it.isSvg() }.orEmpty()
                return (order.firstNotNullOfOrNull { l -> items.firstOrNull { it.iso6391 == l } } ?: items.firstOrNull())
                    ?.filePath?.let { "$IMG$it" }
            }
            // Backdrop: no language, en, hi, bn. Logo: en, no language, hi, bn.
            val backdrop = pick(images.backdrops, listOf(null, "en", "hi", "bn"))
            val logo = pick(images.logos, listOf("en", null, "hi", "bn"))
            backdrop to logo
        } catch (e: Exception) {
            null to null
        }
    }
}

// ===================== Crypto =====================

/**
 * Castle responses are AES/CBC/PKCS5 encrypted.
 * Key = base64-decoded security key + build suffix, padded or cut to 16 bytes. IV = key.
 */
object CastleCrypto {
    private fun deriveKey(apiKeyB64: String, suffix: String): ByteArray {
        val keyMaterial = base64DecodeArray(apiKeyB64) + suffix.toByteArray(StandardCharsets.US_ASCII)
        return when {
            keyMaterial.size < 16 -> keyMaterial + ByteArray(16 - keyMaterial.size)
            keyMaterial.size > 16 -> keyMaterial.copyOfRange(0, 16)
            else -> keyMaterial
        }
    }

    fun decrypt(encryptedB64: String, apiKeyB64: String, suffix: String): String? {
        return try {
            val aesKey = deriveKey(apiKeyB64, suffix)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesKey))
            String(cipher.doFinal(base64DecodeArray(encryptedB64)), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

// ===================== HLS URL helpers =====================

data class HlsParts(
    val domain: String,    // fcxmb.com / hscow.com
    val path: String,      // hls_mps, or hls for older titles
    val hash: String,
    val indexFile: String, // index_266.m3u8 (preview suffix removed)
)

object CastleHls {
    private val hlsRegex = Regex(
        """https://img\d*\.([^/]+)/(hls_mps|hls)/([a-f0-9]+)/[^/]+/(index(?:_preview)?_\d+\.m3u8)"""
    )

    /** Parse domain, path, hash and full index file from any Castle HLS URL (preview or full). */
    fun parse(videoUrl: String): HlsParts? {
        val m = hlsRegex.find(videoUrl) ?: return null
        return HlsParts(
            domain = m.groupValues[1],
            path = m.groupValues[2],
            hash = m.groupValues[3],
            indexFile = m.groupValues[4].replace("index_preview_", "index_"),
        )
    }

    /**
     * Full (non-preview) playlist for a quality folder, on the unsigned img.{domain} host
     * (img1 returns 403 without a signature).
     * Each quality of the same title can sit under either hls_mps or hls, so both are returned,
     * the path the API gave first.
     */
    fun qualityUrls(p: HlsParts, quality: Int): List<String> =
        listOf(p.path, if (p.path == "hls") "hls_mps" else "hls").map { path ->
            "https://img.${p.domain}/$path/${p.hash}/$quality/${p.indexFile}"
        }
}

// ===================== getVideo2 request body =====================

object CastleVideoRequest {
    fun body(movieId: String, episodeId: String, resolution: Int, languageId: Int?): Map<String, String> {
        val body = linkedMapOf(
            "mode" to "1",
            "appMarket" to "GuanWang",
            "clientType" to "1",
            "woolUser" to "false",
            "apkSignKey" to "ED0955EB04E67A1D9F3305B95454FED485261475",
            "androidVersion" to "13",
            "movieId" to movieId,
            "episodeId" to episodeId,
            "isNewUser" to "true",
            "resolution" to resolution.toString(),
            "packageName" to "com.external.castle",
        )
        if (languageId != null) body["languageId"] = languageId.toString()
        return body
    }
}
