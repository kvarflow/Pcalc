package io.github.kvarflow.pcalc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerInputTest {
    private val gamepad = 0x00000401 or 0x01000010 // what the Thor's built-in controls report
    private val dpad = 0x00000201
    private val keyboard = 0x00000101

    @Test
    fun ignoresTheControllersButtonsAndDpad() {
        for (key in listOf(96, 97, 99, 100, 102, 103, 104, 105, 108, 109, 110, 188)) assertTrue("button $key", ControllerInput.isControllerKey(key, gamepad))
        for (key in 19..23) {
            assertTrue("dpad $key from a controller", ControllerInput.isControllerKey(key, gamepad))
            assertTrue("dpad $key", ControllerInput.isControllerKey(key, dpad))
        }
        assertTrue(ControllerInput.isControllerMotion(gamepad))
    }

    @Test
    fun keepsAKeyboard() {
        for (key in listOf(19, 20, 21, 22, 66, 67, 29, 62)) assertFalse("keyboard $key", ControllerInput.isControllerKey(key, keyboard))
        assertFalse(ControllerInput.isControllerKey(4, keyboard)) // Back
        assertFalse(ControllerInput.isControllerMotion(0x00001002)) // touchscreen
    }
}
