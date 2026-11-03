package com.akhome.launcher

/** Global edition: English UI, worldwide streaming apps, Netflix hero card. */
object Flavor {
    /** The big card: trending films and series, shown only (not tied to any app — apps differ per box). */
    val feed: HeroFeed? = TrendingFeed()

    val extras = emptyList<Extra>()

    val favourites = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom")

    val order = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.hbo.hbonow", "com.wbd.stream", "com.apple.atve.androidtv.appletv",
        "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "org.xbmc.kodi",
        "com.android.vending", "com.google.android.videos",
    )
}
