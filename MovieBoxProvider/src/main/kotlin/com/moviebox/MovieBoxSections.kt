package com.moviebox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

// ===================== Home page sections =====================
// key = "r|tabId|categoryType" (GET tab/ranking-list) or "g|tabId|type" (POST subject-api/genre-top)
object MovieBoxSections {
    data class S(val key: String, val title: String, val label: String, val isDefault: Boolean = false)

    /** User's state for one section (order is the list order). name = custom title (non-default only). */
    data class State(val key: String, var enabled: Boolean, var name: String? = null)

    private const val PREF_KEY = "moviebox_sections_v1"
    private val mapper = jacksonObjectMapper()

    // Developer's own categories: always listed first, enabled by default, title can't be changed.
    val DEFAULTS = listOf(
        S("r|0|1488104699998914056", "New Movies", "Home · New Release", true),
        S("r|5|6768518077748858592", "New Series", "TV Series · New Release", true),
        S("r|5|1976033493293449744", "Asian Drama", "TV Series · Asian Drama", true),
        S("r|5|4903182713986896328", "Indian Drama", "TV Series · Indian Drama", true),
        S("r|0|414907768299210008", "Bollywood", "Home · Bollywood", true),
        S("r|0|3859721901924910512", "South Indian", "Home · South Indian", true),
        S("r|0|8019599703232971616", "Hollywood", "Home · Hollywood", true),
        S("r|8|536501058407990248", "Animated Film", "Anime · Animated Film", true),
        S("r|18|7878715743607948784", "K-Drama", "Asian Drama · K-Drama", true),
        S("r|18|8788126208987989488", "C-Drama", "Asian Drama · C-Drama", true),
        S("r|5|8223396272405987416", "Bengali Series", "TV Series · New Bengali", true),
        S("r|20|7686549674215833952", "Bengali-Dubbed", "Indian Drama · Bengali-Dubbed", true),
    )

    // Every other category found in the app API (tab-operating), disabled by default.
    val CATALOG = listOf(
        S("g|0|4175575772020854200", "Hot Short TV", "Home · 🔥Hot Short TV"),
        S("g|0|8610422883619422240", "Home Trending now", "Home · 🔥Trending now"),
        S("r|0|5692654647815587592", "Cinema", "Home · 🔥Cinema"),
        S("g|0|3910636007619709856", "Western TV", "Home · Western TV"),
        S("g|0|5606549574572819920", "Home Top Series", "Home · Top Series"),
        S("g|0|8434602210994128512", "Top Anime Series", "Home · Top Anime Series"),
        S("r|2|3343007159239034280", "Top Movies", "Movies · Top Movies"),
        S("g|2|8151385393116628944", "Bollywood Love Stories", "Movies · 💗Bollywood Love Stories"),
        S("g|2|3926022815467587728", "Fantastic Adventure Journey", "Movies · 🪄Fantastic Adventure Journey"),
        S("g|2|4635476890789234888", "One-Person Army Action", "Movies · One-Person Army Action👊"),
        S("g|2|7980544539113259704", "Laugh Out Loud", "Movies · 😀Laugh Out Loud"),
        S("g|2|79489258091955560", "Adventure Unfolded", "Movies · 🧗🏻Adventure Unfolded"),
        S("g|2|8206959542085763104", "Tomb Tales", "Movies · 🪦Tomb Tales"),
        S("g|2|3599860175605233504", "Indian Horror Nights", "Movies · 😱Indian Horror Nights"),
        S("g|2|5944287463219888688", "Laugh or Scream", "Movies · 😨Laugh or Scream"),
        S("g|2|3131393992252948904", "Thrills and Crimes", "Movies · 🥶Thrills and Crimes"),
        S("g|2|896410179733012848", "Movies Super Hero", "Movies · 🦸🏻Super Hero"),
        S("g|2|8001493900840125768", "Sci-Fi Future", "Movies · 🔭Sci-Fi Future"),
        S("g|4|1899430559525340216", "Most-Watched Music Videos", "Music · Most-Watched Music Videos"),
        S("g|4|7154150703639283736", "Most-Watched Mix Videos", "Music · Most-Watched Mix Videos"),
        S("g|5|3737790647819030080", "Series In Progress", "TV Series · ✈️ Series In Progress"),
        S("g|5|7430814629783652312", "TV Series Campus Romance", "TV Series · 💘Campus Romance"),
        S("g|5|4246770642420489272", "TV Series Feel-good Dramas", "TV Series · 😊 Feel-good Dramas"),
        S("g|5|498717889118323864", "Plutocrat's Stories", "TV Series · 💗 Plutocrat's Stories"),
        S("g|5|3884420897339572240", "TV Series All Villains", "TV Series · 🦹 All Villains"),
        S("g|5|816466796495734184", "TV Series My Fantastic Lover", "TV Series · 🪄 My Fantastic Lover"),
        S("g|5|1323417248324799752", "Modren Romance", "TV Series · 💕 Modren Romance"),
        S("g|5|1247456295129678272", "Indian Firepower", "TV Series · 💥 Indian Firepower"),
        S("g|5|8694496692344966224", "TV Series Have A Good Laugh", "TV Series · 🤣 Have A Good Laugh"),
        S("g|5|3729321862980142088", "Fantasy Chronicles", "TV Series · 🪄Fantasy Chronicles"),
        S("g|5|5665995596830834704", "Action Hardcore Tough Guy!", "TV Series · Action 👊 Hardcore Tough Guy!"),
        S("g|5|5165329998026342432", "TV Series Super Hero", "TV Series · 🦸 Super Hero"),
        S("g|7|157704701360980752", "Short TV Trending Now", "Short TV · Trending Now🔥"),
        S("g|7|5460417072570189048", "New Arrival", "Short TV · New Arrival🆕"),
        S("g|7|3635474061066650872", "Most Popular", "Short TV · Most Popular👍"),
        S("g|7|5989210909875520056", "Mini TV in Bengali", "Short TV · Mini TV in Bengali"),
        S("g|7|3894570890218063368", "Romantic Conflict", "Short TV · Romantic Conflict❤"),
        S("g|7|8673153977648842376", "Hidden Identity", "Short TV · Hidden Identity🥸"),
        S("g|7|477165605787961264", "Betrayal", "Short TV · Betrayal 👿"),
        S("g|7|1176888442710547312", "Revenge", "Short TV · Revenge👊"),
        S("g|7|5230501941297433160", "Mini TV in Hindi", "Short TV · Mini TV in Hindi"),
        S("r|8|8434602210994128512", "Anime Trending Now", "Anime · Trending Now🔥"),
        S("g|8|7225420846562011440", "Anime in Progress", "Anime · Anime in Progress ✈️"),
        S("r|8|6087417564575974904", "Burning with Ardour", "Anime · Burning with Ardour❤️‍🔥"),
        S("r|8|3085012826717671128", "Anime Dark Fantasy", "Anime · Dark Fantasy👹"),
        S("r|8|26746744553834104", "Game On: Driven To Win!", "Anime · Game On：Driven To Win！"),
        S("r|8|1984688681323581120", "Isekai Odyssey", "Anime · Isekai Odyssey 🧗"),
        S("r|8|7676543306654269528", "Enchanted Campus Life", "Anime · Enchanted Campus Life🏫"),
        S("r|8|5720200271855268792", "Echoes of Suspense", "Anime · Echoes of Suspense"),
        S("r|8|3872057201007156976", "Sci-Fi Saga Collection", "Anime · Sci-Fi Saga Collection"),
        S("r|8|6369393711893598568", "Heart Warming", "Anime · Heart Warming"),
        S("r|8|8094101944874193256", "Comedy Zone", "Anime · Comedy Zone"),
        S("g|8|8969684064567448920", "Flim to Cure My Life", "Anime · Flim to Cure My Life❤️‍🩹"),
        S("g|9|3436880071141867768", "Porn Top Videos", "18+ · Porn Top Videos"),
        S("r|9|7462810956096595192", "UllU Drama List", "18+ · UllU Drama List"),
        S("r|9|3613148068369032104", "UllU Movie List", "18+ · UllU Movie List"),
        S("g|9|4283712533380449360", "Late-night Shorts", "18+ · Late-night Shorts"),
        S("r|9|1846783103277105520", "Hentai Anime", "18+ · Hentai Anime"),
        S("r|9|4534646032008989032", "Indian 18+", "18+ · Indian 18+"),
        S("r|9|7364316894532720656", "Japanese 18+", "18+ · Japanese 18+"),
        S("r|9|4105487575106966448", "Korea 18+", "18+ · Korea 18+"),
        S("g|9|3628141198977782704", "Chinese 18+", "18+ · Chinese 18+"),
        S("r|9|8170622407217234072", "Vivamax Movies", "18+ · Vivamax Movies"),
        S("r|9|4543422163096946912", "Tbonx Collection", "18+ · Tbonx Collection"),
        S("r|9|8805880198798493664", "Cinepop Collection", "18+ · Cinepop Collection"),
        S("r|9|8448366367312612120", "18+ Dramas", "18+ · 18+ Dramas"),
        S("g|18|2258593095275217344", "Trending Bengali", "Asian Drama · 🔥Trending Bengali"),
        S("r|18|1397508691376622224", "New Releases-Original Audio", "Asian Drama · 🆕New Releases-Original Audio"),
        S("g|18|2351385573794939088", "Soldier's Love Stories", "Asian Drama · Soldier's Love Stories❤"),
        S("r|18|498717889118323864", "My Arbitrary CEO", "Asian Drama · My Arbitrary CEO🤑"),
        S("r|18|4246770642420489272", "Asian Drama Feel-good Dramas", "Asian Drama · Feel-good Dramas😏"),
        S("r|18|6561459032090443792", "A Journey of Healing", "Asian Drama · A Journey of Healing❤️‍🩹"),
        S("r|18|6331752959961448656", "Survival Game", "Asian Drama · Survival Game☠️"),
        S("r|18|2777721819497907088", "Hidden Love", "Asian Drama · Hidden Love💌"),
        S("r|18|3884420897339572240", "Asian Drama All Villains", "Asian Drama · All Villains😈"),
        S("r|18|7359146196826735032", "Workplace Romance", "Asian Drama · Workplace Romance❤"),
        S("r|18|5023498508486296064", "Traveling in Time", "Asian Drama · Traveling in Time⏳"),
        S("r|18|1212178339998454184", "Marriage Come Before Love", "Asian Drama · Marriage Come Before Love💒"),
        S("r|18|7430814629783652312", "Asian Drama Campus Romance", "Asian Drama · Campus Romance❤"),
        S("r|18|816466796495734184", "Asian Drama My Fantastic Lover", "Asian Drama · My Fantastic Lover🪄"),
        S("r|19|3910636007619709856", "Western TV Trending Now", "Western TV · 🔥Trending Now🔥"),
        S("r|19|480876680870004496", "New Releases In OrgDub", "Western TV · 🆕New Releases In OrgDub"),
        S("r|19|3729321862980142088", "Epic Fantasy", "Western TV · Epic Fantasy🪄"),
        S("r|19|9087043367325893056", "Adventures in Time", "Western TV · Adventures in Time⚔️"),
        S("r|19|5165329998026342432", "Western TV Super Hero", "Western TV · Super Hero🦸🏻‍♀️"),
        S("r|19|856328192012026416", "Western TV Dark Fantasy", "Western TV · Dark Fantasy😈"),
        S("r|19|5846800663291856368", "Youthful Days", "Western TV · Youthful Days❤️‍🔥"),
        S("r|19|5665995596830834704", "ActionHardcore Tough Guy!", "Western TV · Action👊Hardcore Tough Guy!"),
        S("r|19|5554080156530212880", "The Sci-Fi Spectrum", "Western TV · The Sci-Fi Spectrum🪐"),
        S("r|19|7732907582503223312", "Crime and Thriller", "Western TV · Crime and Thriller👿"),
        S("r|19|2712796173273826096", "Midnight Pleasures", "Western TV · Midnight Pleasures💗"),
        S("r|19|1589289605281580040", "New Releases", "Western TV · 🆕New Releases"),
        S("r|19|5319831403255709880", "American Animation", "Western TV · American Animation"),
        S("r|20|1957859476619384760", "New Release", "Indian Drama · New Release 🆕"),
        S("r|20|1255898847918934600", "Reality-TV Shows", "Indian Drama · Reality-TV Shows😀"),
        S("r|20|8694496692344966224", "Indian Drama Have A Good Laugh", "Indian Drama · Have A Good Laugh😊"),
        S("r|20|1323417248324799752", "Modern Romance", "Indian Drama · Modern Romance❤"),
        S("r|20|7874751636433240512", "College Life", "Indian Drama · College Life🏫"),
        S("r|20|2029180568054760944", "Epics & Fantasy", "Indian Drama · Epics & Fantasy🪄"),
        S("r|20|1247456295129678272", "Actionwith Suspense", "Indian Drama · Action👊with Suspense"),
        S("r|20|5590718632746780144", "History Dramas", "Indian Drama · History Dramas"),
        S("r|20|4803059288933551368", "About Famliy", "Indian Drama · About Famliy👨‍👩‍👧‍👦"),
        S("r|21|5177200225164885656", "Turkish Drama Trending Now", "Turkish Drama · Trending Now"),
        S("g|21|9185447506233147928", "Turkish Drama in Bengali", "Turkish Drama · Turkish Drama in Bengali"),
        S("r|21|4076640268886443504", "Love is in the air", "Turkish Drama · Love is in the air"),
        S("r|21|2314501361561470408", "Period Dramas", "Turkish Drama · Period Dramas"),
        S("r|21|7088774363411351024", "Turkish Remakes", "Turkish Drama · Turkish Remakes"),
        S("r|21|1072668948686143504", "Streaming in Netflix", "Turkish Drama · Streaming in Netflix"),
        S("r|21|5996088503820584392", "Must-Watch Turkish Dramas", "Turkish Drama · Must-Watch Turkish Dramas"),
        S("g|23|4136345283041153336", "Must-watch Classic Movies", "Kids · Must-watch Classic Movies"),
        S("g|23|1373877246590704328", "Princess Collection", "Kids · Princess Collection"),
        S("g|23|365128603417413048", "Must-watch Classic Stories", "Kids · Must-watch Classic Stories"),
        S("g|23|8064313641383985616", "Best Kids' Movies on Netflix", "Kids · Best Kids' Movies on Netflix"),
        S("g|23|7019372974717681992", "Best Disney Movies Of All Time", "Kids · Best Disney Movies Of All Time"),
        S("g|23|1232361853758625072", "Smart Start Cartoons", "Kids · Smart Start Cartoons"),
        S("g|28|8216283712045280", "2026 Nollywood Movie", "Nollywood · 2026 Nollywood Movie"),
        S("g|28|3924878967966254944", "RuthKadiri247", "Nollywood · RuthKadiri247"),
        S("g|28|4411267727722268512", "Uchenna Mbunabo Tv", "Nollywood · Uchenna Mbunabo Tv"),
        S("g|28|2033367124470555856", "Maurice Sam TV", "Nollywood · Maurice Sam TV"),
        S("g|28|3798778178399789216", "Omoni Oboli TV", "Nollywood · Omoni Oboli TV"),
        S("g|28|6041570792830296808", "Sonia Uche Tv", "Nollywood · Sonia Uche Tv"),
        S("g|28|1778913745524121760", "Chinemelu's Channel", "Nollywood · Chinemelu's Channel"),
        S("g|28|477386647353674568", "Uduak Isong TV", "Nollywood · Uduak Isong TV"),
        S("g|28|8435247188917341000", "LizzyGold Tv", "Nollywood · LizzyGold Tv"),
        S("g|28|4134296350638984656", "Frederick Leonard TV", "Nollywood · Frederick Leonard TV"),
        S("g|28|4159066148589522384", "BOLAJI OGUNMOLA TV", "Nollywood · BOLAJI OGUNMOLA TV"),
        S("g|28|5037268075926767456", "Bimbo Ademoye TV", "Nollywood · Bimbo Ademoye TV"),
        S("g|28|373790661784619848", "Clinton Joshua Tv", "Nollywood · Clinton Joshua Tv"),
        S("g|28|4012699160699981264", "Uche Montana TV", "Nollywood · Uche Montana TV"),
        S("g|28|4755793099216021176", "NollyFamily tv", "Nollywood · NollyFamily tv"),
        S("g|28|2467977682651433800", "AdaKirikiriTv", "Nollywood · AdaKirikiriTv"),
        S("g|28|3476770805042791568", "StarKingsTv", "Nollywood · StarKingsTv"),
        S("g|28|4530613117847496376", "Royal Arts TV", "Nollywood · Royal Arts TV"),
        S("g|28|2816993459633021088", "Uche Nancy tv", "Nollywood · Uche Nancy tv"),
        S("g|28|3235828224978477800", "Sarian Martin Tv", "Nollywood · Sarian Martin Tv"),
        S("g|28|1488444763698351568", "Yorubaplus", "Nollywood · Yorubaplus"),
        S("g|28|6627051938527986832", "Itelediconstudio", "Nollywood · Itelediconstudio"),
        S("g|28|3513375746154810832", "ADEKAZ PREMIUM TV", "Nollywood · ADEKAZ PREMIUM TV"),
        S("g|28|583208044457348560", "ChinneyLoveEze Tv", "Nollywood · ChinneyLoveEze Tv"),
        S("g|28|851172222285893072", "IBAKATV", "Nollywood · IBAKATV"),
        S("g|28|3141252632803789640", "CHIZZYFLIX", "Nollywood · CHIZZYFLIX"),
        S("g|28|2762950264104568504", "BIODUNSTEPHEN TV", "Nollywood · BIODUNSTEPHEN TV"),
        S("g|28|109217377816135520", "Tokejamiu TV", "Nollywood · Tokejamiu TV"),
        S("g|28|4276159738901062864", "Uche Nancy Update Tv", "Nollywood · Uche Nancy Update Tv"),
        S("g|28|781366428061549712", "Blessing Obasi TV", "Nollywood · Blessing Obasi TV"),
        S("r|0|6144409817256968824", "New Bengali", "Home · Trending Movies › New Bengali"),
        S("g|0|1255898847918934600", "Reality-TV", "Home · Trending TV Series › Reality-TV"),
        S("g|0|5177200225164885656", "Turkish Drama", "Home · Trending TV Series › Turkish Drama"),
        S("r|2|5837669637445565960", "Hot Bengali", "Movies · Trending Movies › Hot Bengali"),
        S("r|5|8992795696977837544", "TV Series Top Series", "TV Series · Trending TV Series › Top Series"),
        S("r|5|735765054104261208", "TV Series Bengali Series", "TV Series · Trending TV Series › Bengali Series"),
    )

    val ALL: List<S> = DEFAULTS + CATALOG
    private val byKey = ALL.associateBy { it.key }

    fun find(key: String): S? = byKey[key]

    private fun defaultState(): List<State> = ALL.map { State(it.key, it.isDefault) }

    /** Saved order + flags; unknown keys dropped, new catalog entries appended. */
    fun load(): MutableList<State> {
        val raw = MovieBoxApi.sharedPref?.getString(PREF_KEY, null) ?: return defaultState().toMutableList()
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
        return saved
    }

    fun save(list: List<State>) {
        val arr = list.map { st ->
            val m = mutableMapOf<String, Any>("k" to st.key, "e" to st.enabled)
            st.name?.trim()?.takeIf { it.isNotEmpty() && find(st.key)?.isDefault == false }?.let { m["n"] = it }
            m
        }
        MovieBoxApi.sharedPref?.edit()?.putString(PREF_KEY, mapper.writeValueAsString(arr))?.apply()
    }

    fun reset() { MovieBoxApi.sharedPref?.edit()?.remove(PREF_KEY)?.apply() }

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
