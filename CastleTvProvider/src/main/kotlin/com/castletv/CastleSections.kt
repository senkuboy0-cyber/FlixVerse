package com.castletv

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

// ===================== Home page sections =====================
// key = "locationId|rowId" (row of a Castle tab from category/home)
object CastleSections {
    data class S(val key: String, val title: String, val label: String, val isDefault: Boolean = false)

    /** User's state for one section (order is the list order). name = custom title (non-default only). */
    data class State(val key: String, var enabled: Boolean, var name: String? = null)

    var sharedPref: android.content.SharedPreferences? = null

    private const val PREF_KEY = "castle_sections_v1"

    private const val MIGRATED_KEY = "castle_sections_migrated_v1"
    private val NEW_DEFAULTS = emptyList<Pair<String, String>>()
    private val mapper = jacksonObjectMapper()

    // Developer's own categories: always listed first, enabled by default, title can't be changed.
    val DEFAULTS = listOf(
        S("1001|5661125979266048", "Popular Movies", "Home · Watch Popular Movies", true),
        S("1001|5836681858493440", "Popular WebSeries", "Home · Popular WebSeries", true),
        S("1003|5893762742585344", "International Movies", "Movies · International Movies", true),
        S("1003|5806276744741888", "Bollywood", "Movies · Bollywood", true),
        S("1001|6117579693457408", "Tollywood Movies", "Home · Tollywood Movies", true),
        S("1003|5760725379365888", "Comedy", "Movies · Comedy", true),
        S("1003|5760730682670080", "Romance", "Movies · Romance", true),
        S("1002|7954955612732416", "Korean Drama Hindi", "Series · Korean Drama Hindi dubbed", true),
        S("1069|8702100632647680", "New Anime", "Anime · Ongoing Anime", true),
        S("1069|8807267191114752", "Anime Movie", "Anime · Movie Anime", true),
    )

    // Every other row of the Castle tabs (actor and single-poster rows left out), disabled by default.
    val CATALOG = listOf(
        S("1001|8095894681127936", "Most Watching Trending Now", "Home · Most Watching Trending Now"),
        S("1001|1008", "Upcoming", "Home · Upcoming"),
        S("1001|5806042579633152", "Hottest International Film", "Home · Hottest International Film 🔥"),
        S("1001|6315704893219840", "Recommended for You", "Home · Recommended for You"),
        S("1001|5806137486148608", "Most Romantic Movie&Series", "Home · Most Romantic Movie&Series"),
        S("1001|5896055859841024", "Marvel Universe", "Home · Marvel Universe"),
        S("1001|6431722761332736", "Hot Erotic Series", "Home · Hot Erotic Series"),
        S("1001|5842829427480576", "Reality Shows", "Home · Reality Shows"),
        S("1001|5970434002863104", "Fantasy and Adventure", "Home · Fantasy and Adventure"),
        S("1001|5806128172106752", "Bollywood Movie", "Home · Bollywood Movie"),
        S("1001|5806153203516416", "Horror, Supernatural & Thriller", "Home · Horror, Supernatural & Thriller"),
        S("1001|7318125683225600", "Crazy Short Shows", "Home · Crazy Short Shows"),
        S("1001|5806164505297920", "Popular Comedy", "Home · Popular Comedy"),
        S("1001|5806108431975424", "Crime, Investigation & Mystery", "Home · Crime, Investigation & Mystery"),
        S("1002|5590366446204928", "Popular TV Shows & WebSeries", "Series · Popular TV Shows & WebSeries"),
        S("1002|5995542638829568", "International Web Series", "Series · International Web Series"),
        S("1002|6445430037584896", "Top 20 Series This Week", "Series · Top 20 Series This Week"),
        S("1002|5811094870230016", "Recommend Series", "Series · Recommend Series"),
        S("1002|5760749297417216", "Best Of Reality-Show", "Series · Best Of Reality-Show"),
        S("1002|5590379038718976", "Mystery and Thriller", "Series · Mystery and Thriller"),
        S("1002|5590364672769024", "Most Watched Netflix Series", "Series · Most Watched Netflix Series"),
        S("1002|5760752697719808", "Marvel series", "Series · Marvel series"),
        S("1002|5811119191131136", "Good Action Series", "Series · Good Action Series"),
        S("1002|5811505962428416", "Erotic Series", "Series · Erotic Series"),
        S("1002|5811187600074752", "Romantic Love", "Series · Romantic Love"),
        S("1002|5811231825209344", "Crime Series", "Series · Crime Series"),
        S("1002|5811241662261248", "Hilarious Comedy", "Series · Hilarious Comedy"),
        S("1002|5811484152899584", "Long Series", "Series · Long Series"),
        S("1002|5811500309620736", "Human and Nature", "Series · Human and Nature"),
        S("1003|5661144766962688", "Hit Movies", "Movies · Hit Movies"),
        S("1003|5590371098212352", "Most Popular Movies for Boys", "Movies · Most Popular Movies for Boys"),
        S("1003|6111273613918208", "Tollywood", "Movies · Tollywood"),
        S("1003|5811512233666560", "Erotic", "Movies · Erotic"),
        S("1003|5811541131044864", "Blockbuster Movies", "Movies · Blockbuster Movies"),
        S("1003|5760721146919936", "Action & Adventure", "Movies · Action & Adventure"),
        S("1003|5811082087695360", "Crime", "Movies · Crime"),
        S("1003|5811116188566528", "Thriller", "Movies · Thriller"),
        S("1003|6972369541309440", "X-Men", "Movies · X-Men"),
        S("1003|7054380237992960", "Indian Regional Movies", "Movies · Indian Regional Movies"),
        S("1003|5811556425443328", "Fast and Furious, Game and Magic", "Movies · Fast and Furious · Game and Magic"),
        S("1003|5811582366197760", "Classic Collection", "Movies · Classic Collection"),
        S("1003|5811598197499904", "Cartoon", "Movies · cartoon"),
        S("1069|8701737011465216", "The Most Popular Anime", "Anime · The Most Popular Anime"),
        S("1069|8702094621443072", "Must Watch Anime For Solo Leveling Fans!", "Anime · Must Watch Anime For Solo Leveling Fans!"),
        S("1069|9390546142694278", "Top 10 Anime This Week", "Anime · Top 10 Anime This Week"),
        S("1069|8702186735643648", "Sweet love", "Anime · Sweet love"),
        S("1069|8702127380215808", "Adrenaline Rush Anime", "Anime · Adrenaline Rush Anime"),
        S("1069|8702544858262528", "Featured Recommendations", "Anime · Featured Recommendations"),
        S("1069|8702232415939584", "Isekai", "Anime · Isekai"),
        S("1069|8744247669204992", "Fantasy Adventure", "Anime · Fantasy Adventure"),
        S("1069|8710275025160192", "Adult Animes", "Anime · Adult Animes"),
        S("1069|8710242819295232", "Happy time", "Anime · Happy time"),
    )

    val ALL: List<S> = DEFAULTS + CATALOG
    private val byKey = ALL.associateBy { it.key }

    fun find(key: String): S? = byKey[key]

    private fun defaultState(): List<State> = ALL.map { State(it.key, it.isDefault) }

    /** Saved order + flags; unknown keys dropped, new catalog entries appended. */
    fun load(): MutableList<State> {
        val raw = sharedPref?.getString(PREF_KEY, null)
            ?: return defaultState().toMutableList().also { markMigrated() }
        val saved = try {
            mapper.readTree(raw).mapNotNull { n ->
                val key = n["k"]?.asText() ?: return@mapNotNull null
                val s = byKey[key] ?: return@mapNotNull null
                val name = n["n"]?.asText()?.trim()?.takeIf { it.isNotEmpty() && !s.isDefault }
                State(key, n["e"]?.asBoolean() ?: false, name)
            }.distinctBy { it.key }.toMutableList()
        } catch (_: Exception) { return defaultState().toMutableList() }
        val have = saved.map { it.key }.toSet()
        ALL.filter { it.key !in have }.forEach { saved.add(State(it.key, it.isDefault)) }
        migrate(saved)
        return saved
    }

    private fun migrate(list: MutableList<State>) {
        val pref = sharedPref ?: return
        val done = pref.getStringSet(MIGRATED_KEY, emptySet()) ?: emptySet()
        val todo = NEW_DEFAULTS.filter { it.first !in done }
        if (todo.isEmpty()) return
        todo.forEach { (key, after) ->
            list.removeAll { it.key == key }
            val at = list.indexOfFirst { it.key == after }
            list.add(if (at >= 0) at + 1 else 0, State(key, true))
        }
        save(list)
        pref.edit().putStringSet(MIGRATED_KEY, done + todo.map { it.first }).apply()
    }

    fun save(list: List<State>) {
        val arr = list.map { st ->
            val m = mutableMapOf<String, Any>("k" to st.key, "e" to st.enabled)
            st.name?.trim()?.takeIf { it.isNotEmpty() && find(st.key)?.isDefault == false }?.let { m["n"] = it }
            m
        }
        sharedPref?.edit()?.putString(PREF_KEY, mapper.writeValueAsString(arr))?.apply()
    }

    /** Fresh/default lists already have the new defaults in place. */
    private fun markMigrated() {
        sharedPref?.edit()?.putStringSet(MIGRATED_KEY, NEW_DEFAULTS.map { it.first }.toSet())?.apply()
    }

    fun reset() { sharedPref?.edit()?.remove(PREF_KEY)?.apply(); markMigrated() }

    fun titleOf(st: State): String {
        val s = find(st.key) ?: return st.key
        return if (s.isDefault) s.title else st.name?.takeIf { it.isNotBlank() } ?: s.title
    }

    /** (data, name) pairs for mainPageOf, in the user's order. */
    fun mainPage(): List<Pair<String, String>> {
        val used = mutableSetOf<String>()
        val list = load().filter { it.enabled }.map { st ->
            var name = titleOf(st)
            var i = 2
            while (!used.add(name.lowercase())) name = "${titleOf(st)} ($i)".also { i++ }
            st.key to name
        }
        return list.ifEmpty { DEFAULTS.map { it.key to it.title } }
    }
}
