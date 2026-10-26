package com.castletv

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app

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
