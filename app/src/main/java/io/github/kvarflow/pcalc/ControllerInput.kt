package io.github.kvarflow.pcalc

/**
 * The game's controller isn't the calculator's. On the Thor, once you touch the calculator, Android
 * sends the controller to it instead of to RetroArch; a D-pad press would then change whatever the
 * calculator had focused (four presses down on a Status list made a Pokémon "Paralyzed", B went back
 * a page). So the app ignores controller buttons and sticks: the calculator is used by touch, and a
 * keyboard (or the on-screen one) still types into it.
 */
object ControllerInput {
    // InputDevice sources (android.view.InputDevice; plain values so this runs in unit tests).
    private const val SOURCE_DPAD = 0x00000201
    private const val SOURCE_GAMEPAD = 0x00000401
    private const val SOURCE_JOYSTICK = 0x01000010

    /** A key from a controller: its buttons, or the D-pad of a controller (not a keyboard's arrows). */
    fun isControllerKey(keyCode: Int, source: Int): Boolean {
        if (keyCode in 96..110 || keyCode in 188..203) return true // KEYCODE_BUTTON_A..MODE, BUTTON_1..16
        val dpad = keyCode in 19..23 || keyCode in 268..271 // KEYCODE_DPAD_*
        return dpad && (has(source, SOURCE_GAMEPAD) || has(source, SOURCE_JOYSTICK) || has(source, SOURCE_DPAD))
    }

    /** Stick movement from a controller. */
    fun isControllerMotion(source: Int): Boolean = has(source, SOURCE_JOYSTICK) || has(source, SOURCE_GAMEPAD)

    private fun has(source: Int, wanted: Int) = source and wanted == wanted
}
