package com.streamverse

/**
 * Minimal M3U / M3U8 playlist parser for IPTV lists.
 *
 * Supported input features:
 *  - "#EXTINF:<duration> attr=\"value\" ...,Channel Name" entries
 *  - tvg-logo, group-title and tvg-name attributes
 *  - "#EXTVLCOPT:http-user-agent=..." and "#EXTVLCOPT:http-referrer=..."
 *  - "#EXTHTTP:{\"User-Agent\":\"...\"}" JSON options
 *  - per-entry "|user-agent=..." / "|referer=..." URL suffixes used by common IPTV apps
 *
 * Only http(s) stream URLs are returned; rtmp / plugin / unknown schemes are skipped
 * because the in-app player cannot open them.
 */
data class M3uChannel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
)

object M3uParser {

    private val attrRegex = Regex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")
    private val vlcOptRegex = Regex("#EXTVLCOPT:\\s*([\\w-]+)\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
    private val extHttpUaRegex = Regex("\"User-Agent\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val extHttpRefRegex = Regex("\"Referer\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val urlSuffixRegex = Regex("\\|(user-agent|referer|referrer)\\s*=\\s*([^|]*)$", RegexOption.IGNORE_CASE)

    /** Splits "|user-agent=..." / "|referer=..." off the end of a stream URL. */
    fun splitUrlSuffix(url: String): Triple<String, String?, String?> {
        var u = url
        var ua: String? = null
        var ref: String? = null
        while (true) {
            val m = urlSuffixRegex.find(u) ?: break
            when (m.groupValues[1].lowercase()) {
                "user-agent" -> ua = m.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: ua
                else -> ref = m.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: ref
            }
            u = u.substring(0, m.range.first)
        }
        return Triple(u, ua, ref)
    }

    /** First comma outside of quotes: attribute section before it, display name after it. */
    private fun splitExtinf(line: String): Pair<String, String> {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return line.substring(0, i) to line.substring(i + 1).trim()
            }
        }
        return line to ""
    }

    fun parse(text: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var ua: String? = null
        var ref: String? = null

        fun flush(url: String) {
            val (clean, urlUa, urlRef) = splitUrlSuffix(url)
            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                channels.add(
                    M3uChannel(
                        name = name?.takeIf { it.isNotBlank() } ?: clean.substringAfterLast('/'),
                        url = clean,
                        group = group?.takeIf { it.isNotBlank() },
                        logo = logo?.takeIf { it.isNotBlank() },
                        userAgent = ua ?: urlUa,
                        referrer = ref ?: urlRef,
                    )
                )
            }
            name = null; logo = null; group = null; ua = null; ref = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF") -> {
                    val (attrs, title) = splitExtinf(line)
                    name = title.ifBlank { null }
                    for (m in attrRegex.findAll(attrs)) {
                        when (m.groupValues[1].lowercase()) {
                            "tvg-logo", "logo" -> logo = m.groupValues[2]
                            "group-title", "group" -> group = m.groupValues[2]
                            "tvg-name" -> if (name.isNullOrBlank()) name = m.groupValues[2]
                        }
                    }
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val m = vlcOptRegex.find(line) ?: return@forEach
                    when (m.groupValues[1].lowercase()) {
                        "http-user-agent" -> ua = m.groupValues[2].trim()
                        "http-referrer", "http-referer" -> ref = m.groupValues[2].trim()
                    }
                }
                line.startsWith("#EXTHTTP:") -> {
                    extHttpUaRegex.find(line)?.let { ua = it.groupValues[1] }
                    extHttpRefRegex.find(line)?.let { ref = it.groupValues[1] }
                }
                line.startsWith("#") -> {}
                else -> flush(line)
            }
        }
        return channels
    }
}
