package com.castletv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.castletv.settings.SettingsFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CastleTvProviderPlugin : Plugin() {
    override fun load(context: Context) {
        CastleTvProvider.context = context
        val sharedPref = context.getSharedPreferences("CastleTv", Context.MODE_PRIVATE)
        CastleSections.sharedPref = sharedPref
        registerMainAPI(CastleTvProvider())
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            activity?.let { SettingsFragment(this, sharedPref).show(it.supportFragmentManager, "CastleSettings") }
        }
    }
}
