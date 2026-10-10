package com.mlsbd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MlsbdPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the provider
        registerMainAPI(MlsbdProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(GoflixSbs())
        registerExtractorAPI(Luluvid())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(FilePress())
    }
}
