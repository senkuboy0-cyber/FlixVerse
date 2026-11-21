package com.mlsbd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MlsbdPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the provider
        registerMainAPI(MlsbdProvider())

        // Filepress - three mainUrl variants: subdomain wildcard, apex wildcard and a
        // robust brand handle that also matches any TLD rotation (filepress.lat/.link/...)
        registerExtractorAPI(Filepress())        // https://*.filepress.*
        registerExtractorAPI(FilepressApex())    // https://filepress.*
        registerExtractorAPI(FilepressHandle())  // filepress.*

        // Multicloudlinks - same three mainUrl variants
        registerExtractorAPI(Multicloudlinks())        // https://*.multicloudlinks.*
        registerExtractorAPI(MulticloudlinksApex())    // https://multicloudlinks.*
        registerExtractorAPI(MulticloudlinksHandle())  // multicloudlinks.*

        // Gdflix - same three mainUrl variants
        registerExtractorAPI(Gdflix())        // https://*.gdflix.*
        registerExtractorAPI(GdflixApex())    // https://gdflix.*
        registerExtractorAPI(GdflixHandle())  // gdflix.*

        // Gdxfiles - same three mainUrl variants
        registerExtractorAPI(Gdxfiles())        // https://*.gdxfiles.*
        registerExtractorAPI(GdxfilesApex())    // https://gdxfiles.*
        registerExtractorAPI(GdxfilesHandle())  // gdxfiles.*

        // generic file-host mirrors still reachable from the site
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GoflixSbs())
    }
}
