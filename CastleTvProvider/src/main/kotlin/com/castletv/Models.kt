package com.castletv

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app

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

// ===================== Home categories =====================

/**
 * A home category is one row of a Castle tab.
 * The home API is called with the tab's locationId and the row is picked by its id.
 * locationId: 1001 = Home, 1002 = Series, 1003 = Movies, 1069 = Anime
 */
data class CastleCategory(
    val name: String,
    val locationId: Int,
    val rowId: Long,
)

object CastleCategories {
    val list = listOf(
        CastleCategory("Popular Movies", 1001, 5661125979266048),
        CastleCategory("Popular WebSeries", 1001, 5836681858493440),
        CastleCategory("International Movies", 1003, 5893762742585344),
        CastleCategory("Bollywood", 1003, 5806276744741888),
        CastleCategory("Tollywood Movies", 1001, 6117579693457408),
        CastleCategory("Comedy", 1003, 5760725379365888),
        CastleCategory("Romance", 1003, 5760730682670080),
        CastleCategory("Korean Drama Hindi", 1002, 7954955612732416),
    )

    /** mainPageOf key: "locationId|rowId" */
    fun key(c: CastleCategory) = "${c.locationId}|${c.rowId}"
}

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
 * Castle titles are already clean, so the title is searched as it is:
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

    suspend fun artwork(title: String, year: Int?, isMovie: Boolean): Pair<String?, String?> {
        val kind = if (isMovie) "movie" else "tv"
        return try {
            val query = java.net.URLEncoder.encode(title, "UTF-8")
            val exact = mapper.readValue<TmdbSearchResponse>(
                app.get("$API/search/$kind?api_key=$KEY&query=$query").text
            ).results.orEmpty().filter { it.id != null && it.sameName(title) }
            if (exact.isEmpty()) return null to null

            val match = year?.let { y ->
                exact.firstOrNull { it.year() == y }
                    ?: exact.firstOrNull { it.year()?.let { r -> kotlin.math.abs(r - y) <= 1 } == true }
            } ?: exact.singleOrNull() ?: return null to null

            val images = mapper.readValue<TmdbImagesResponse>(
                app.get("$API/$kind/${match.id}/images?api_key=$KEY&include_image_language=en,hi,null").text
            )
            // Textless backdrop first
            val backdrop = images.backdrops?.let { b ->
                (b.firstOrNull { it.iso6391 == null } ?: b.firstOrNull())?.filePath
            }?.let { "$IMG$it" }
            // English logo, then Hindi, then any
            val logo = images.logos?.let { l ->
                (l.firstOrNull { it.iso6391 == "en" } ?: l.firstOrNull { it.iso6391 == "hi" } ?: l.firstOrNull())?.filePath
            }?.let { "$IMG$it" }
            backdrop to logo
        } catch (e: Exception) {
            null to null
        }
    }
}
