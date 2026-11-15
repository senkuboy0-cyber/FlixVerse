package com.streamverse

import com.lagradost.cloudstream3.app
import java.io.File

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

    /** How many categories already have usable cached channels (shown in search results). */
    fun warmCategories(): Int = synchronized(cache) { cache.values.count { it.second.isNotEmpty() } }
}
