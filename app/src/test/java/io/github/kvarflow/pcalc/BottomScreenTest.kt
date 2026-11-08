package io.github.kvarflow.pcalc

import io.github.kvarflow.pcalc.BottomScreen.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BottomScreenTest {
    private val top = Screen(0, 1920, 1080)
    private val bottom = Screen(4, 1240, 1080)

    @Test
    fun movesFromTheTopScreenToTheBottomOne() {
        assertEquals(4, BottomScreen.pick(top, listOf(top, bottom)))
    }

    @Test
    fun staysOnTheBottomScreen() {
        assertNull(BottomScreen.pick(bottom, listOf(top, bottom)))
    }

    @Test
    fun staysWithOneScreenOrABiggerMonitor() {
        assertNull(BottomScreen.pick(top, listOf(top)))
        assertNull(BottomScreen.pick(top, listOf(top, Screen(2, 3840, 2160))))
    }

    @Test
    fun ignoresScreensThatAreOffOrPrivate() {
        assertNull(BottomScreen.pick(top, listOf(top, Screen(4, 1240, 1080, on = false))))
        assertNull(BottomScreen.pick(top, listOf(top, Screen(5, 720, 480, private = true))))
    }
}
