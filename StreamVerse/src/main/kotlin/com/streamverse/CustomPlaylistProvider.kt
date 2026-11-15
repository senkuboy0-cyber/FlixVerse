package com.streamverse

import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mainPageOf

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
