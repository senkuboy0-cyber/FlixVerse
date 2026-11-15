package com.streamverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * StreamVerse plugin entry point.
 * Registers the curated 101-category IPTV provider plus one separate provider
 * for every user-added playlist, and wires the playlist manager settings screen.
 */
@CloudstreamPlugin
class StreamVersePlugin : Plugin() {

    companion object {
        /** Provider keys already registered in this process ("name|source"), so Save & Reset never double-registers. */
        private val registeredCustom = mutableSetOf<String>()
    }

    override fun load(context: Context) {
        PlaylistStore.load(context).forEach { item ->
            registerCustom(item)
        }
        registerMainAPI(StreamVerseProvider())

        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            activity?.let {
                SettingsDialog { items -> onPlaylistsSaved(items) }
                    .show(it.supportFragmentManager, "StreamVerseSettings")
            }
        }
    }

    /** Called from the settings screen after Save & Reset with the full saved list. */
    private fun onPlaylistsSaved(items: List<PlaylistStore.Item>) {
        items.forEach { item ->
            registerCustom(item)
        }
    }

    private fun registerCustom(item: PlaylistStore.Item) {
        val sourceKey = when (val src = item.source()) {
            is PlaylistSource.Local -> "local|${src.path}"
            is PlaylistSource.Remote -> "remote|${src.url}"
        }
        val key = "${item.name}|$sourceKey"
        if (!registeredCustom.add(key)) return
        registerMainAPI(CustomPlaylistProvider(item))
    }
}
