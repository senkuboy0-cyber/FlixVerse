// use an integer for version numbers
version = 1

cloudstream {
    language = "hi"
    description = "4K-HDHub Movies and Series (all qualities, HubCloud FSL / Buzz / Pixel)"
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
        "TvSeries",
        "Anime"
    )

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/FlixVerse/refs/heads/main/FourKHDHubProvider/icon.png"
}
