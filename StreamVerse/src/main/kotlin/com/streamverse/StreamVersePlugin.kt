package com.streamverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.streamverse.setting.PlaylistStore
import com.streamverse.setting.SettingsDialog

/**
 * StreamVerse plugin entry point.
 * Registers the curated 101-category IPTV provider plus one separate provider
 * for every user-added playlist, and wires the playlist manager settings screen.
 */
@CloudstreamPlugin
class StreamVersePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamVerseProvider())
        PlaylistStore.load(context).forEach { registerMainAPI(CustomPlaylistProvider(it)) }
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            activity?.let { SettingsDialog().show(it.supportFragmentManager, "StreamVerseSettings") }
        }
    }
}
