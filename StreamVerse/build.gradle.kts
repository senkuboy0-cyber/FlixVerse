// use an integer for version numbers
version = 1

android {
    // Root build sets namespace "com.anime"; override for StreamVerse
    namespace = "com.streamverse"
}

dependencies {
    val implementation by configurations
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
}

cloudstream {
    language = "hi"
    description = "StreamVerse IPTV - 101 live TV playlist categories plus user-added playlists with a modern settings screen"
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
        "Live"
    )

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/FlixVerse/refs/heads/main/StreamVerse/icon.png"
}
