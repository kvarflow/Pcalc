package io.github.kvarflow.pcalc

/**
 * Which screen Pcalc belongs on. On the AYN Thor, and other handhelds with two screens, it's the
 * smaller one under the game; on a one-screen device, or one with an external monitor plugged in
 * (bigger), it stays where it is.
 */
object BottomScreen {

    class Screen(val id: Int, val width: Int, val height: Int, val on: Boolean = true, val private: Boolean = false) {
        val area: Long get() = width.toLong() * height
    }

    /** The screen to move to from [current], or null to stay: the smallest screen that's on, if smaller. */
    fun pick(current: Screen, screens: List<Screen>): Int? =
        screens.filter { it.id != current.id && it.on && !it.private && it.area in 1 until current.area }
            .minByOrNull { it.area }?.id
}
