// use an integer for version numbers
version = 15

android {
    // Root build sets namespace "com.anime"; resources are looked up under com.moviebox
    namespace = "com.moviebox"
}

dependencies {
    val implementation by configurations
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
}

cloudstream {
    language = "hi"
    description = "MovieBox Movies and Series (multi-audio)"
    authors = listOf("senkuboy0-cyber")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    requiresResources = true

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/FlixVerse/refs/heads/main/MovieBoxProvider/icon.png"
}
