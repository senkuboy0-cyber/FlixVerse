package com.fourkhdhub

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app

// ===================== TMDB models =====================

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

data class TmdbShow(val seasons: List<TmdbSeasonRef>? = null)
data class TmdbSeasonRef(@JsonProperty("season_number") val seasonNumber: Int? = null)
data class TmdbSeason(val episodes: List<TmdbEpisode>? = null)

data class TmdbEpisode(
    val name: String? = null,
    val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
)

/** What TMDB adds to one episode: title, description, thumbnail. */
data class TmdbEpisodeMeta(val name: String?, val description: String?, val still: String?)

data class TmdbArtwork(val id: Int?, val backdrop: String?, val logo: String?)

/**
 * TMDB for 4K-HDHub, same rules as the Castle extension:
 * - Title for search only: (...) and [...] parts removed; the app keeps the site title.
 * - Search name + year first, then name only (series in TMDB tv, movies in TMDB movie).
 * - Only results whose name is exactly the title (case ignored) are accepted.
 *   Among those the year must match (exact, then one year either side);
 *   if no year fits but there is just one exact-name result, that one is used. Otherwise nothing from TMDB.
 * - Backdrop language order: no language, en, hi, bn. Logo: en, no language, hi, bn. Never svg.
 * Episodes (series): title, description and thumbnail (still) per season/episode.
 */
object FourKHDHubTmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private const val IMG = "https://image.tmdb.org/t/p/original"

    private val OFFSET_REFS = setOf(0x10196)

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun norm(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase()

    private fun TmdbSearchItem.year(): Int? = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

    private fun TmdbSearchItem.sameName(title: String): Boolean {
        val t = norm(title)
        return listOf(this.title, name, originalTitle, originalName).any { norm(it) == t }
    }

    private fun searchTitle(title: String): String =
        title.replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
            .ifEmpty { title }

    private suspend inline fun <reified T> getJson(url: String): T = mapper.readValue(app.get(url).text)

    suspend fun artwork(originalTitle: String, year: Int?, isMovie: Boolean): TmdbArtwork {
        val kind = if (isMovie) "movie" else "tv"
        val title = searchTitle(originalTitle)
        return try {
            val query = java.net.URLEncoder.encode(title, "UTF-8")
            suspend fun exactResults(extra: String) = getJson<TmdbSearchResponse>(
                "$API/search/$kind?api_key=$KEY&query=$query$extra"
            ).results.orEmpty().filter { it.id != null && it.sameName(title) }
            // Name + year first, then name only
            val yearParam = if (isMovie) "primary_release_year" else "first_air_date_year"
            val exact = (year?.let { exactResults("&$yearParam=$it") }.orEmpty())
                .ifEmpty { exactResults("") }
            if (exact.isEmpty()) return TmdbArtwork(null, null, null)

            val match = year?.let { y ->
                exact.firstOrNull { it.year() == y }
                    ?: exact.firstOrNull { it.year()?.let { r -> kotlin.math.abs(r - y) <= 1 } == true }
            } ?: exact.singleOrNull() ?: return TmdbArtwork(null, null, null)

            val images = getJson<TmdbImagesResponse>("$API/$kind/${match.id}/images?api_key=$KEY")
            fun TmdbImageItem.isSvg() = filePath?.endsWith(".svg", ignoreCase = true) == true
            fun pick(list: List<TmdbImageItem>?, order: List<String?>): String? {
                val items = list?.filter { !it.filePath.isNullOrBlank() && !it.isSvg() }.orEmpty()
                return (order.firstNotNullOfOrNull { l -> items.firstOrNull { it.iso6391 == l } } ?: items.firstOrNull())
                    ?.filePath?.let { "$IMG$it" }
            }
            TmdbArtwork(
                match.id,
                pick(images.backdrops, listOf(null, "en", "hi", "bn")),
                pick(images.logos, listOf("en", null, "hi", "bn"))
            )
        } catch (e: Exception) {
            TmdbArtwork(null, null, null)
        }
    }

    private fun TmdbEpisode.meta() = TmdbEpisodeMeta(
        name?.trim()?.ifBlank { null },
        overview?.trim()?.ifBlank { null },
        stillPath?.takeIf { it.isNotBlank() }?.let { "$IMG$it" }
    )

    /** Episode title / description / thumbnail keyed by site (season, episode). */
    suspend fun episodes(tvId: Int, siteEpisodes: List<Pair<Int, Int>>): Map<Pair<Int, Int>, TmdbEpisodeMeta> {
        if (siteEpisodes.isEmpty()) return emptyMap()
        return try {
            if (tvId in OFFSET_REFS) {
                val seasons = getJson<TmdbShow>("$API/tv/$tvId?api_key=$KEY").seasons
                    ?.mapNotNull { it.seasonNumber }?.filter { it > 0 }.orEmpty()
                val all = seasons.amap { s ->
                    runCatching { getJson<TmdbSeason>("$API/tv/$tvId/season/$s?api_key=$KEY").episodes.orEmpty() }
                        .getOrDefault(emptyList())
                }.flatten().sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber })).drop(25)
                val s2Plus = siteEpisodes.filter { it.first >= 2 }.sortedWith(compareBy({ it.first }, { it.second }))
                s2Plus.mapIndexedNotNull { i, key -> all.getOrNull(i)?.let { key to it.meta() } }.toMap()
            } else {
                val seasons = siteEpisodes.map { it.first }.distinct()
                val map = HashMap<Pair<Int, Int>, TmdbEpisodeMeta>()
                seasons.amap { s ->
                    s to runCatching { getJson<TmdbSeason>("$API/tv/$tvId/season/$s?api_key=$KEY").episodes.orEmpty() }
                        .getOrDefault(emptyList())
                }.forEach { (s, eps) ->
                    eps.forEach { ep -> ep.episodeNumber?.let { n -> map[s to n] = ep.meta() } }
                }
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
