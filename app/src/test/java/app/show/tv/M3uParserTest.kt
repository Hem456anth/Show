package app.show.tv

import app.show.tv.data.Channel
import app.show.tv.data.M3uParser
import app.show.tv.data.StreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test fun `parses header epg url and basic entry`() {
        val p = M3uParser.parse(
            """
            #EXTM3U x-tvg-url="https://example.org/guide.xml.gz"
            #EXTINF:-1 tvg-id="BBCNews.uk@SD" tvg-logo="https://i.imgur.com/a.png" group-title="News",BBC News (720p)
            https://example.org/bbc/playlist.m3u8
            """.trimIndent(),
        )
        assertEquals("https://example.org/guide.xml.gz", p.epgUrl)
        val ch = p.channels.single()
        assertEquals("BBC News", ch.name)
        assertEquals("720p", ch.resolution)
        assertEquals(listOf("News"), ch.categories)
        assertEquals("United Kingdom", ch.country)
        assertEquals("https://i.imgur.com/a.png", ch.logoUrl)
        assertEquals(StreamProtocol.HLS, ch.protocol)
        assertEquals(0, p.skippedEntries)
    }

    @Test fun `title split ignores commas inside quoted attributes`() {
        val p = M3uParser.parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"CN.sg@SD\" http-user-agent=\"Mozilla/5.0 (KHTML, like Gecko) Chrome\" group-title=\"Animation\",Cartoon Net, Asia (720p)\n" +
                "https://cdn.example/cn.m3u8\n",
        )
        val ch = p.channels.single()
        assertEquals("Cartoon Net, Asia", ch.name)
        assertEquals("Mozilla/5.0 (KHTML, like Gecko) Chrome", ch.userAgent)
    }

    @Test fun `EXTVLCOPT applies to next entry only and wins over inline attribute`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 http-user-agent="inline-ua",One
            #EXTVLCOPT:http-user-agent=vlcopt-ua
            #EXTVLCOPT:http-referrer=https://ref.example/
            http://a.example/1.m3u8
            #EXTINF:-1,Two
            http://a.example/2.m3u8
            """.trimIndent(),
        )
        val (one, two) = p.channels
        assertEquals("vlcopt-ua", one.userAgent)
        assertEquals("https://ref.example/", one.referrer)
        assertNull(two.userAgent)
        assertNull(two.referrer)
    }

    @Test fun `flags, multi categories, EXTGRP fallback and missing logo`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="" group-title="Kids;Animation",Toon (1080p) [Geo-blocked] [Not 24/7]
            https://x.example/t.m3u8
            #EXTGRP:Music
            #EXTINF:-1,Radio Rock (Spain)
            https://x.example/r.mp3
            """.trimIndent(),
        )
        val toon = p.channels[0]
        assertEquals("Toon", toon.name)
        assertEquals("1080p", toon.resolution)
        assertTrue(toon.isGeoBlocked)
        assertTrue(toon.isNot247)
        assertEquals(listOf("Kids", "Animation"), toon.categories)
        assertNull(toon.logoUrl)
        val radio = p.channels[1]
        assertEquals("Radio Rock (Spain)", radio.name) // non-resolution parentheses are part of the name
        assertEquals(listOf("Music"), radio.categories)
        assertEquals(StreamProtocol.PROGRESSIVE, radio.protocol)
    }

    @Test fun `duplicate urls merge categories instead of duplicating channels`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",Same
            https://dup.example/s.m3u8
            #EXTINF:-1 group-title="Business",Same
            https://dup.example/s.m3u8
            """.trimIndent(),
        )
        assertEquals(1, p.channels.size)
        assertEquals(listOf("News", "Business"), p.channels.single().categories)
    }

    @Test fun `tolerates BOM, CRLF, blank lines, junk and orphan entries`() {
        val text = "\uFEFF#EXTM3U\r\n\r\n#EXTINF:-1,No url\r\n#EXTINF:-1,Good\r\nhttps://ok.example/a.m3u8\r\nnot a url at all\r\n#EXTINF:-1,Tail\r\n"
        val p = M3uParser.parse(text)
        assertEquals(listOf("Good"), p.channels.map { it.name })
        assertEquals(3, p.skippedEntries) // orphan EXTINF, junk line, trailing EXTINF
    }

    @Test fun `entries without group are Undefined and ids are stable`() {
        val a = M3uParser.parse("#EXTM3U\n#EXTINF:-1,X\nhttps://s.example/x\n").channels.single()
        val b = M3uParser.parse("#EXTM3U\n#EXTINF:-1 group-title=\"News\",Renamed\nhttps://s.example/x\n").channels.single()
        assertEquals(Channel.UNCATEGORISED, a.primaryCategory)
        assertEquals(a.id, b.id)
        assertEquals(16, a.id.length)
    }

    @Test fun `rejects non-http logos and urls with whitespace`() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-logo=\"javascript:alert(1)\",Evil\nhttps://e.example/a b\n#EXTINF:-1 tvg-logo=\"file:///etc/passwd\",Ok\nrtmp://e.example/live\n")
        assertEquals(1, p.channels.size)
        assertNull(p.channels.single().logoUrl)
        assertEquals(StreamProtocol.RTMP, p.channels.single().protocol)
    }

    @Test fun `attribute parser handles unquoted values and first-wins`() {
        val attrs = M3uParser.parseAttributes(""" tvg-id=abc tvg-id="def" group-title="A B" orphan novalue= """)
        assertEquals("abc", attrs["tvg-id"])
        assertEquals("A B", attrs["group-title"])
        assertFalse("orphan" in attrs)
    }

    @Test fun `country from tvg id`() {
        assertEquals("US", M3uParser.countryFromTvgId("3ABNKids.us@SD"))
        assertEquals("FR", M3uParser.countryFromTvgId("ADNTVPlus.fr"))
        assertNull(M3uParser.countryFromTvgId("NoCountry"))
        assertNull(M3uParser.countryFromTvgId("Bad.c0@x"))
    }

    /** Real iptv-org snapshot (Oct 2026): every entry must parse and stay within budget. */
    @Test fun `parses the real iptv-org category playlist`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("iptv_org_index_category_snapshot.m3u")
        val started = System.nanoTime()
        val p = stream.bufferedReader().use(M3uParser::parse)
        val ms = (System.nanoTime() - started) / 1_000_000

        assertTrue("expected >10k channels, got ${p.channels.size}", p.channels.size > 10_000)
        assertEquals(p.channels.size, p.channels.map { it.streamUrl }.toSet().size)
        assertEquals(p.channels.size, p.channels.map { it.id }.toSet().size)
        assertTrue(p.channels.all { it.name.isNotBlank() && it.categories.isNotEmpty() })
        val withLogo = p.channels.count { it.logoUrl != null }
        assertTrue("most channels have logos ($withLogo)", withLogo > p.channels.size * 0.8)
        assertTrue(p.channels.any { it.userAgent != null })
        assertTrue(p.channels.any { it.referrer != null })
        val cats = p.channels.flatMap { it.categories }.toSet()
        assertTrue(cats.containsAll(listOf("News", "Sports", "Movies", "Kids", "Music")))
        assertTrue("parse took $ms ms", ms < 5_000)
        println("Parsed ${p.channels.size} channels, ${cats.size} categories, skipped ${p.skippedEntries}, in $ms ms")
    }

    @Test fun `free-tv SD mark becomes an SD resolution`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Kanali 7\" tvg-id=\"Kanali7.al\" tvg-country=\"AL\" group-title=\"Albania\",Kanali 7 \u24C8\nhttps://fe.example/1200_1.m3u8\n" +
            "#EXTINF:-1 tvg-name=\"Rai 1\" group-title=\"Italy\",Rai 1\nhttps://rai.example/1.m3u8\n"
        val chs = app.show.tv.data.M3uParser.parse(java.io.StringReader(text)).channels
        org.junit.Assert.assertEquals("Kanali 7", chs[0].name)
        org.junit.Assert.assertEquals("SD", chs[0].resolution)
        org.junit.Assert.assertEquals(null, chs[1].resolution)
    }
}
