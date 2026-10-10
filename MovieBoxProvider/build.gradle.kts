// use an integer for version numbers
version = 26

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    val cloudstream by configurations
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Multi Language Movies and Series Provider"
    authors = listOf("senkuboy0-cyber")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    requiresResources = true

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/FlixVerse/refs/heads/main/MovieBoxProvider/icon.png"

    isCrossPlatform = false
}
