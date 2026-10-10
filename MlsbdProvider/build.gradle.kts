version = 2

cloudstream {
    language = "hi"
    authors = listOf("Your Name")
    description = "MLSBD Provider for Movies and TV Series"
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
        "TvSeries",
        "AnimeMovie",
        "Anime",
        "Cartoon"
    )

    iconUrl = "https://mlsbd.co/wp-content/uploads/2020/09/mlsbd-icon.png"
}
