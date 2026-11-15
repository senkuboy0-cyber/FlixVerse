package com.streamverse.setting

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.streamverse.PlaylistSource
import java.io.File

/**
 * Persists the playlists the user adds from the settings screen.
 * Each item carries an extension name plus either a playlist URL or a local file path;
 * the plugin registers one separate provider for every saved item on the next app start
 * (Save & Reset restarts the app, which is when the providers appear).
 */
object PlaylistStore {

    data class Item(
        val name: String,
        val url: String,
        val localPath: String? = null,
    ) {
        fun source(): PlaylistSource =
            localPath?.takeIf { it.isNotBlank() }?.let { PlaylistSource.Local(it) }
                ?: PlaylistSource.Remote(url)
    }

    private const val PREFS = "StreamVerse"
    private const val KEY = "custom_playlists"
    private val mapper = jacksonObjectMapper()

    fun load(context: Context): List<Item> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        mapper.readValue<List<Item>>(raw)
    } catch (_: Exception) {
        emptyList()
    }

    fun save(context: Context, items: List<Item>) {
        val json = try {
            mapper.writeValueAsString(items)
        } catch (_: Exception) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).apply()
    }

    /** Copies imported playlist text into app-private storage and returns the stored path. */
    fun importFile(context: Context, text: String): String? = try {
        val dir = File(context.filesDir, "streamverse").apply { mkdirs() }
        val file = File(dir, "pl_${System.currentTimeMillis()}.m3u")
        file.writeText(text, Charsets.UTF_8)
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    fun deleteLocal(path: String): Boolean = try {
        File(path).delete()
    } catch (_: Exception) {
        false
    }
}
