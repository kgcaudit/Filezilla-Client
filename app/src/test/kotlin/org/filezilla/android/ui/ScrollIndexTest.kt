package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The letters down the side of a long list. */
class ScrollIndexTest {

    @Test
    fun `a syllable files under its leading consonant`() {
        assertEquals("ㄱ", ScrollIndex.headingFor("가족"))
        assertEquals("ㅇ", ScrollIndex.headingFor("영업"))
        assertEquals("ㅎ", ScrollIndex.headingFor("휴가"))
    }

    /** The doubles fold, because no index anybody uses separates them. */
    @Test
    fun `a double consonant files under its single`() {
        assertEquals("ㄱ", ScrollIndex.headingFor("까치"))
        assertEquals("ㄷ", ScrollIndex.headingFor("딸기"))
        assertEquals("ㅂ", ScrollIndex.headingFor("빨강"))
        assertEquals("ㅅ", ScrollIndex.headingFor("쌍둥이"))
        assertEquals("ㅈ", ScrollIndex.headingFor("짜장"))
    }

    @Test
    fun `a latin name files under a capital`() {
        assertEquals("A", ScrollIndex.headingFor("avatar.mkv"))
        assertEquals("A", ScrollIndex.headingFor("Avatar.mkv"))
        assertEquals("Z", ScrollIndex.headingFor("zulu"))
    }

    /** Digits and punctuation share one heading rather than getting ten. */
    @Test
    fun `everything else shares one heading`() {
        assertEquals("#", ScrollIndex.headingFor("28년 후(2025)"))
        assertEquals("#", ScrollIndex.headingFor("365일"))
        assertEquals("#", ScrollIndex.headingFor("_notes"))
        assertEquals("#", ScrollIndex.headingFor(""))
    }

    /** The user's folder, in the order the screen showed it. */
    @Test
    fun `the rail follows the rows it was built from`() {
        val names = listOf(
            "28년 후 - 뼈의 사원(2026)", "28년 후(2025)", "28일 후(2002)", "28주 후(2007)",
            "2번 배심원(2024)", "2분마다 타임루프(2023)", "30일(2023)", "365일(2020)",
            "3일(2025)", "3일의 휴가(2023)", "40 에이커스(2024)",
            "가족", "나비", "다이하드", "Avatar",
        )
        assertEquals(
            listOf(
                ScrollIndex.Stop("#", 0),
                ScrollIndex.Stop("ㄱ", 11),
                ScrollIndex.Stop("ㄴ", 12),
                ScrollIndex.Stop("ㄷ", 13),
                ScrollIndex.Stop("A", 14),
            ),
            ScrollIndex.stopsFor(names),
        )
    }

    /**
     * A heading per run, not per letter.
     *
     * Folders are hoisted above files, so a list can climb through the
     * alphabet twice. Collapsing the second run into the first would send a
     * tap on ㄱ to a folder when the rows under the finger are files.
     */
    @Test
    fun `a letter that comes round again gets its own stop`() {
        val names = listOf("가 폴더", "나 폴더", "가 파일", "나 파일")
        assertEquals(
            listOf(
                ScrollIndex.Stop("ㄱ", 0), ScrollIndex.Stop("ㄴ", 1),
                ScrollIndex.Stop("ㄱ", 2), ScrollIndex.Stop("ㄴ", 3),
            ),
            ScrollIndex.stopsFor(names),
        )
    }

    @Test
    fun `an empty list has no rail`() {
        assertEquals(emptyList<ScrollIndex.Stop>(), ScrollIndex.stopsFor(emptyList()))
    }

    @Test
    fun `a rail that fits is left alone`() {
        val stops = (1..10).map { ScrollIndex.Stop("$it", it) }
        assertEquals(stops, ScrollIndex.thin(stops, 20))
    }

    /** Thinning keeps both ends, which are the two a finger goes for. */
    @Test
    fun `a rail too long to fit keeps its ends`() {
        val stops = (0 until 100).map { ScrollIndex.Stop("$it", it) }
        val thinned = ScrollIndex.thin(stops, 10)

        assertEquals(10, thinned.size)
        assertEquals(stops.first(), thinned.first())
        assertEquals(stops.last(), thinned.last())
    }
}
