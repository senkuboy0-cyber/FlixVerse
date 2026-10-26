package com.moviebox

import android.content.SharedPreferences
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ===================== Category list (home page) =====================
// data = "tabId|categoryType" for /wefeed-mobile-bff/tab/ranking-list
object MovieBoxCategories {
    val list = listOf(
        "0|1488104699998914056" to "New Movies",
        "5|6768518077748858592" to "New Series",
        "5|1976033493293449744" to "Asian Drama",
        "5|4903182713986896328" to "Indian Drama",
        "0|414907768299210008" to "Bollywood",
        "0|3859721901924910512" to "South Indian",
        "0|8019599703232971616" to "Hollywood",
        "8|536501058407990248" to "Animated Film",
        "18|7878715743607948784" to "K-Drama",
        "18|8788126208987989488" to "C-Drama",
        "5|8223396272405987416" to "New Release Bengali Series",
        "20|7686549674215833952" to "Bengali-Dubbed Indian Drama",
    )
}

// ===================== API client =====================
object MovieBoxApi {
    val HOST_POOL = listOf(
        "https://api3.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api6.aoneroom.com",
        "https://api7.aoneroom.com",
        "https://apig.inmoviebox.com",
    )
    const val HOST_KEY = "moviebox_host"
    private const val TOKEN_KEY = "moviebox_bearer_token_v4"
    const val BFF = "/wefeed-mobile-bff"

    var sharedPref: SharedPreferences? = null
    val mapper = jacksonObjectMapper()

    private val secretKey = base64DecodeArray(
        String(base64DecodeArray("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="))
    )
    private val deviceId: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    @Volatile private var bearerToken: String? = null

    val preferredHost: String
        get() = sharedPref?.getString(HOST_KEY, HOST_POOL[0])?.takeIf { it in HOST_POOL } ?: HOST_POOL[0]

    private fun hostOrder(): List<String> = listOf(preferredHost) + HOST_POOL.filter { it != preferredHost }

    // ---------- signing ----------
    private fun md5(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

    private fun clientToken(): String {
        val ts = System.currentTimeMillis().toString()
        return "$ts,${md5(ts.reversed().toByteArray())}"
    }

    private fun signature(method: String, contentType: String, url: String, body: String?): String {
        val ts = System.currentTimeMillis()
        val parsed = Uri.parse(url)
        val query = parsed.queryParameterNames.sorted().joinToString("&") { key ->
            parsed.getQueryParameters(key).joinToString("&") { "$key=$it" }
        }
        val path = (parsed.path ?: "") + if (query.isNotEmpty()) "?$query" else ""
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = bytes?.let { md5(if (it.size > 102400) it.copyOfRange(0, 102400) else it) } ?: ""
        val canonical = "${method.uppercase()}\napplication/json\n$contentType\n${bytes?.size ?: ""}\n$ts\n$bodyHash\n$path"
        val mac = Mac.getInstance("HmacMD5").apply { init(SecretKeySpec(secretKey, "HmacMD5")) }
        return "$ts|2|${base64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))}"
    }

    private fun headers(method: String, url: String, body: String?, token: String?): Map<String, String> {
        val contentType = if (body != null) "application/json; charset=utf-8" else "application/json"
        val h = mutableMapOf(
            "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
            "accept" to "application/json",
            "content-type" to contentType,
            "x-client-token" to clientToken(),
            "x-tr-signature" to signature(method, contentType, url, body),
            "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","device_id":"$deviceId","install_store":"ps","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
        )
        if (!token.isNullOrBlank()) h["Authorization"] = "Bearer $token"
        return h
    }

    // ---------- token ----------
    private fun jwtExpiry(token: String): Long = try {
        val part = token.split(".")[1].replace('-', '+').replace('_', '/')
        val padded = part + "=".repeat((4 - part.length % 4) % 4)
        mapper.readTree(String(base64DecodeArray(padded)))["exp"]?.asLong() ?: 0L
    } catch (_: Exception) { 0L }

    private fun isValid(token: String?): Boolean =
        !token.isNullOrBlank() && jwtExpiry(token) > System.currentTimeMillis() / 1000 + 3600

    private fun saveFromHeader(xUser: String?) {
        if (xUser.isNullOrBlank()) return
        val token = runCatching { mapper.readTree(xUser)["token"]?.asText() }.getOrNull()
        if (isValid(token)) {
            bearerToken = token
            sharedPref?.edit()?.putString(TOKEN_KEY, token)?.apply()
        }
    }

    private fun clearToken() {
        bearerToken = null
        sharedPref?.edit()?.remove(TOKEN_KEY)?.apply()
    }

    /** Cached anonymous JWT (~90 days). A fresh one comes from the x-user header of the home feed. */
    suspend fun token(): String? {
        if (isValid(bearerToken)) return bearerToken
        sharedPref?.getString(TOKEN_KEY, null)?.takeIf { isValid(it) }?.let { bearerToken = it; return it }
        for (host in hostOrder()) {
            try {
                val url = "$host$BFF/tab-operating?page=1&tabId=0&version="
                val res = app.get(url, headers = headers("GET", url, null, null))
                saveFromHeader(res.headers["x-user"])
                if (isValid(bearerToken)) return bearerToken
            } catch (_: Exception) { }
        }
        return null
    }

    // ---------- requests (host failover + token refresh) ----------
    private suspend fun request(path: String, body: String?, auth: Boolean): JsonNode? {
        for (attempt in 0..1) {
            val token = if (auth) token() else null
            for (host in hostOrder()) {
                val url = host + path
                try {
                    val h = headers(if (body == null) "GET" else "POST", url, body, token)
                    val res = if (body == null) app.get(url, headers = h)
                    else app.post(url, headers = h, requestBody = body.toRequestBody("application/json".toMediaType()))
                    saveFromHeader(res.headers["x-user"])
                    if (res.code == 401 || res.code == 441) { clearToken(); break }
                    if (res.code >= 500 || res.code == 403) continue
                    val root = mapper.readTree(res.text)
                    val code = root["code"]?.asInt() ?: 0
                    if (code == 401 || code == 441) { clearToken(); break }
                    return root["data"]
                } catch (_: Exception) { continue }
            }
        }
        return null
    }

    suspend fun get(path: String, auth: Boolean = true): JsonNode? = request(path, null, auth)
    suspend fun post(path: String, body: Any, auth: Boolean = true): JsonNode? =
        request(path, mapper.writeValueAsString(body), auth)

    // ---------- endpoints ----------
    suspend fun rankingList(tabId: String, category: String, page: Int, perPage: Int = 20) =
        get("$BFF/tab/ranking-list?tabId=$tabId&categoryType=$category&page=$page&perPage=$perPage")

    suspend fun search(keyword: String, page: Int) =
        post("$BFF/subject-api/search/v2", mapOf("page" to page, "perPage" to 20, "keyword" to keyword))

    suspend fun subject(id: String) = get("$BFF/subject-api/get?subjectId=$id")
    suspend fun seasonInfo(id: String) = get("$BFF/subject-api/season-info?subjectId=$id")

    suspend fun resourceList(id: String, se: Int, ep: Int, page: Int, perPage: Int, resolution: Int) =
        get("$BFF/subject-api/resource?subjectId=$id&se=$se&ep=$ep&page=$page&perPage=$perPage&all=0&pagerMode=0&resolution=$resolution")

    suspend fun resourcePosition(id: String, resourceId: String) =
        get("$BFF/subject-api/resource-position?subjectId=$id&resourceId=$resourceId&failUrl=x&failCode=404&resourceNum=0")

    suspend fun playInfo(id: String, se: Int, ep: Int) =
        get("$BFF/subject-api/play-info?subjectId=$id&se=$se&ep=$ep")

    suspend fun streamCaptions(id: String, streamId: String) =
        get("$BFF/subject-api/get-stream-captions?subjectId=$id&streamId=$streamId")

    suspend fun extCaptions(id: String, resourceId: String, episode: Int) =
        get("$BFF/subject-api/get-ext-captions?subjectId=$id&resourceId=$resourceId&episode=$episode")

    suspend fun recommendations(id: String, kind: String) =
        post("$BFF/subject-api/$kind", mapOf("subjectId" to id, "page" to 1, "perPage" to 12))
}

// ===================== Helpers =====================
object MovieBoxUtils {
    /** 23-second "Update now" clip the API returns instead of the real video. */
    fun isPlaceholder(url: String?, durationSec: Int? = null): Boolean =
        url.isNullOrBlank() || url.contains("macdn.aoneroom.com/other/") ||
                (durationSec != null && durationSec in 1..60)

    private val special = mapOf("esla" to "Spanish (LA)", "ptbr" to "Portuguese (BR)")

    /** "Hindi dub" -> Hindi, "Original Audio" (ja) -> Japanese, "English sub" -> English Sub */
    fun languageName(lanName: String?, lanCode: String?, original: Boolean, title: String?): String {
        val code = lanCode?.trim()?.lowercase().orEmpty()
        if (original || lanName.isNullOrBlank() || lanName.startsWith("Original", true)) {
            special[code]?.let { return it }
            val fromCode = if (code.isNotBlank()) Locale(code).getDisplayLanguage(Locale.ENGLISH) else ""
            if (fromCode.isNotBlank() && !fromCode.equals(code, true)) return fromCode
            return bracketLanguage(title) ?: "Original"
        }
        var name = lanName.trim()
        if (name.endsWith(" dub", true)) name = name.dropLast(4).trim()
        if (name.endsWith(" sub", true)) name = name.dropLast(4).trim() + " Sub"
        special[name.lowercase()]?.let { return it }
        return name.replaceFirstChar { it.uppercase() }
    }

    fun bracketLanguage(title: String?): String? =
        title?.let { Regex("""\[([^\]]+)]""").find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotBlank() }

    fun cleanTitle(title: String): String = title.substringBefore("[").trim().ifBlank { title }

    fun durationMinutes(duration: String?, seconds: Int?): Int? {
        duration?.let { d ->
            val h = Regex("""(\d+)\s*h""").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("""(\d+)\s*m""").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (h + m > 0) return h * 60 + m
        }
        return seconds?.takeIf { it > 0 }?.let { it / 60 }
    }
}

// ===================== Episode metadata (Cinemeta, no TMDB) =====================
data class EpisodeMeta(val title: String?, val description: String?, val thumbnail: String?, val aired: String?)

object Cinemeta {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    suspend fun episodes(title: String, year: Int?): Map<Pair<Int, Int>, EpisodeMeta> {
        return try {
            val q = Uri.encode(title)
            val search = MovieBoxApi.mapper.readTree(
                app.get("https://v3-cinemeta.strem.io/catalog/series/top/search=$q.json").text
            )["metas"] ?: return emptyMap()
            val match = search.firstOrNull { m ->
                norm(m["name"]?.asText().orEmpty()) == norm(title) &&
                        (year == null || m["releaseInfo"]?.asText()?.take(4)?.toIntOrNull()?.let { kotlin.math.abs(it - year) <= 1 } != false)
            } ?: return emptyMap()
            val id = match["imdb_id"]?.asText() ?: match["id"]?.asText() ?: return emptyMap()
            val videos = MovieBoxApi.mapper.readTree(
                app.get("https://v3-cinemeta.strem.io/meta/series/$id.json").text
            )["meta"]?.get("videos") ?: return emptyMap()
            videos.mapNotNull { v ->
                val se = v["season"]?.asInt() ?: return@mapNotNull null
                val ep = (v["episode"] ?: v["number"])?.asInt() ?: return@mapNotNull null
                (se to ep) to EpisodeMeta(
                    title = v["name"]?.asText()?.takeIf { it.isNotBlank() },
                    description = (v["overview"] ?: v["description"])?.asText()?.takeIf { it.isNotBlank() },
                    thumbnail = v["thumbnail"]?.asText()?.takeIf { it.isNotBlank() },
                    aired = v["released"]?.asText()?.take(10),
                )
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }
}
