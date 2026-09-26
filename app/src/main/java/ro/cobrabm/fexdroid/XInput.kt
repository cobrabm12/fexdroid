package ro.cobrabm.fexdroid

import android.view.KeyEvent

/** JNI bindings to cpp/x11_input.c (XTEST injection into Xvfb). */
object XInput {
    init { System.loadLibrary("fxio") }

    @JvmStatic external fun connect(display: Int): String
    @JvmStatic external fun key(keycode: Int, down: Boolean)
    @JvmStatic external fun button(button: Int, down: Boolean)
    @JvmStatic external fun moveTo(x: Int, y: Int)
    @JvmStatic external fun moveBy(dx: Int, dy: Int)

    /**
     * X keycode for an Android key event. Hardware keyboards (USB/Bluetooth/DeX)
     * report the Linux evdev scancode, and Xvfb's evdev keymap uses evdev + 8.
     * Soft keyboards have no scancode, so a table covers the common keys.
     */
    fun xKeycode(e: KeyEvent): Int {
        if (e.scanCode > 0) return e.scanCode + 8
        return ANDROID_TO_EVDEV[e.keyCode]?.plus(8) ?: -1
    }

    private val ANDROID_TO_EVDEV: Map<Int, Int> = buildMap {
        val letters = "qwertyuiop" to 16
        letters.first.forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + (c - 'a'), letters.second + i) }
        "asdfghjkl".forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + (c - 'a'), 30 + i) }
        "zxcvbnm".forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + (c - 'a'), 44 + i) }
        for (d in 1..9) put(KeyEvent.KEYCODE_0 + d, 1 + d)
        put(KeyEvent.KEYCODE_0, 11)
        put(KeyEvent.KEYCODE_ESCAPE, 1); put(KeyEvent.KEYCODE_MINUS, 12); put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, 14); put(KeyEvent.KEYCODE_TAB, 15)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, 28); put(KeyEvent.KEYCODE_CTRL_LEFT, 29)
        put(KeyEvent.KEYCODE_SEMICOLON, 39); put(KeyEvent.KEYCODE_APOSTROPHE, 40); put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42); put(KeyEvent.KEYCODE_BACKSLASH, 43)
        put(KeyEvent.KEYCODE_COMMA, 51); put(KeyEvent.KEYCODE_PERIOD, 52); put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54); put(KeyEvent.KEYCODE_ALT_LEFT, 56); put(KeyEvent.KEYCODE_SPACE, 57)
        for (f in 1..10) put(KeyEvent.KEYCODE_F1 + f - 1, 58 + f)
        put(KeyEvent.KEYCODE_F11, 87); put(KeyEvent.KEYCODE_F12, 88)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 97); put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102); put(KeyEvent.KEYCODE_DPAD_UP, 103); put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105); put(KeyEvent.KEYCODE_DPAD_RIGHT, 106); put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108); put(KeyEvent.KEYCODE_PAGE_DOWN, 109); put(KeyEvent.KEYCODE_INSERT, 110)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111)
    }
}

/** JNI bindings to cpp/audio_bridge.c (PulseAudio FIFO -> AAudio). */
object AudioBridge {
    init { System.loadLibrary("fxio") }

    @JvmStatic external fun start(fifo: String): String
    @JvmStatic external fun frames(): Long
}
