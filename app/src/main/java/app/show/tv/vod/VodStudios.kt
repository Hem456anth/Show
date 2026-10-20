package app.show.tv.vod

/**
 * Curated TMDB studios (companies) and TV networks for the Browse → Studios page (1.8.0).
 *
 * Ids and logo paths were read from each public themoviedb.org/company/<id> and /network/<id> page on
 * 2026-10-20. Each opens a TMDB discover grid (`/discover/movie?with_companies=` or
 * `/discover/tv?with_networks=`), so the list is metadata only: nothing here streams.
 * Logos are TMDB images, drawn with TMDB's negate filter and desaturated to stay black-and-white.
 */
object VodStudios {
    data class Org(val id: String, val name: String, val logoPath: String) {
        /** White-on-transparent rendition via TMDB's image filter (verified to return 200 image/png). */
        val logoUrl: String get() = "${TmdbSource.IMG}/h60_filter(negate,000,666)$logoPath"
    }

    val studios: List<Org> = listOf(
        Org("420", "Marvel Studios", "/hUzeosd33nzE5MCNsZxCGEKTXaQ.png"),
        Org("1", "Lucasfilm", "/tlVSws0RvvtPBwViUyOFAO0vcQS.png"),
        Org("3", "Pixar", "/1TjvGVDMYsj6JBxOAkUHpPEwLf7.png"),
        Org("2", "Walt Disney Pictures", "/wdrCwmRnLFJhEoH8GSfymY85KHT.png"),
        Org("174", "Warner Bros. Pictures", "/zhD3hhtKB5qyv7ZeL4uLpNxgMVU.png"),
        Org("33", "Universal Pictures", "/8lvHyhjr8oUKOOy2dKXoALWKdp0.png"),
        Org("4", "Paramount Pictures", "/jay6WcMgagAklUt7i9Euwj1pzTF.png"),
        Org("5", "Columbia Pictures", "/71BqEFAF4V3qjjMPCpLuyJFB9A.png"),
        Org("25", "20th Century Fox", "/nM2MfoMqzJQRiSynsDabOtFKetD.png"),
        Org("521", "DreamWorks Animation", "/3BPX5VGBov8SDqTV7wC1L1xShAS.png"),
        Org("41077", "A24", "/1ZXsGaFPgrgS6ZZGS37AqD5uU12.png"),
        Org("923", "Legendary Pictures", "/5UQsZrfbfG2dYJbx8DxfoTr2Bvu.png"),
        Org("3172", "Blumhouse Productions", "/rzKluDcRkIwHZK2pHsiT667A2Kw.png"),
        Org("10342", "Studio Ghibli", "/uFuxPEZRUcBTEiYIxjHJq62Vr77.png"),
        Org("1632", "Lionsgate", "/cisLn1YAUuptXVBa0xjq7ST9cH0.png"),
        Org("12", "New Line Cinema", "/2ycs64eqV5rqKYHyQK0GVoKGvfX.png"),
        Org("6704", "Illumination", "/fOG2oY4m1YuYTQh4bMqqZkmgOAI.png"),
        Org("1569", "Yash Raj Films", "/lvzN86o3jrP44DIvn4SMBLOl9PF.png"),
        Org("19146", "Dharma Productions", "/5Ff25ornzVNhm5skuAvMAR556NB.png"),
        Org("111627", "Hombale Films", "/ldpgYauK9FMNsbOSSxSSl63o3mb.png"),
        Org("69125", "Mythri Movie Makers", "/7kRvsVRhT76VTbhGLgJ2CrTgdpa.png"),
        Org("2343", "Red Chillies Entertainment", "/fkrlAFxgAtIHgYJGIQcDggeucKV.png"),
    )

    val networks: List<Org> = listOf(
        Org("213", "Netflix", "/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"),
        Org("49", "HBO", "/tuomPhY2UtuPTqqFnKMVHvSb724.png"),
        Org("2739", "Disney+", "/1edZOYAfoyZyZ3rklNSiUpXX30Q.png"),
        Org("2552", "Apple TV+", "/bngHRFi794mnMq34gfVcm9nDxN1.png"),
        Org("1024", "Prime Video", "/w7HfLNm9CWwRmAMU58udl2L7We7.png"),
        Org("453", "Hulu", "/pqUTCleNUiTLAVlelGxUgWn1ELh.png"),
        Org("3919", "Disney+ Hotstar", "/eBa3TplonEHlR6S2wjJ616KnwIh.png"),
        Org("4", "BBC One", "/uJjcCg3O4DMEjM0xtno9OWFciRP.png"),
        Org("174", "AMC", "/pmvRmATOCaDykE6JrVoeYxlFHw3.png"),
        Org("88", "FX", "/aexGjtcs42DgRtZh7zOxayiry4J.png"),
        Org("6", "NBC", "/cm111bsDVlYaC1foL0itvEI4yLG.png"),
        Org("16", "CBS", "/wju8KhOUsR5y4bH9p3Jc50hhaLO.png"),
        Org("2", "ABC", "/2uy2ZWcplrSObIyt4x0Y9rkG6qO.png"),
        Org("67", "Showtime", "/Allse9kbjiP6ExaQrnSpIhkurEi.png"),
        Org("4330", "Paramount+", "/fi83B1oztoS47xxcemFdPMhIzK.png"),
        Org("3353", "Peacock", "/gIAcGTjKKr0KOHL5s4O36roJ8p7.png"),
        Org("3186", "HBO Max", "/nmU0UMDJB3dRRQSTUqawzF2Od1a.png"),
        Org("71", "The CW", "/hEpcdJ4O6eitG9ADSnDXNUrlovS.png"),
        Org("56", "Cartoon Network", "/c5OC6oVCg6QP4eqzW6XIq17CQjI.png"),
        Org("318", "STARZ", "/qx3Y9LCaK4mq1ykFuDIfjshlo3U.png"),
        Org("1112", "Crunchyroll", "/qqyXcZlJQKlRmAD1TCKV7mGLQlt.png"),
    )

    fun shortcut(org: Org, network: Boolean) =
        VodShortcut(if (network) VodShortcut.Type.NETWORK else VodShortcut.Type.COMPANY, org.name, Provider.TMDB, org.id)
}
