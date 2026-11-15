package com.streamverse

import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newSearchResponseList

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
