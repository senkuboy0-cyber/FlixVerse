// use an integer for version numbers
version = 35

android {
    // Root build sets namespace "com.anime"; override so BuildConfig is generated in com.castletv
    namespace = "com.castletv"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // In the original repo this field was injected from the root build.gradle.kts
        buildConfigField("String", "CASTLE_SUFFIX", "\"T!BgJB\"")
    }
}

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Castle TV Movies and Series Provider"
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

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/FlixVerse/refs/heads/main/CastleTvProvider/icon.png"
}
