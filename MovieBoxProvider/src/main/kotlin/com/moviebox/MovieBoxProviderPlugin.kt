package com.moviebox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.moviebox.settings.SettingsFragment

@CloudstreamPlugin
class MovieBoxProviderPlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("MovieBox", Context.MODE_PRIVATE)
        MovieBoxApi.sharedPref = sharedPref
        registerMainAPI(MovieBoxProvider())
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            activity?.let { SettingsFragment(this, sharedPref).show(it.supportFragmentManager, "MovieBoxSettings") }
        }
    }
}
