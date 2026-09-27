package ro.cobrabm.fexdroid

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * SurfaceView showing the X screen that also forwards input to it (PLAN.md phase 4):
 * - hardware keyboard -> X key events (evdev scancodes);
 * - mouse (Bluetooth / DeX): absolute hover + buttons + wheel, or relative when captured;
 * - touch, as on any touchscreen: tap = left click, swipe = scroll (wheel), long press then
 *   move = left button drag (selection boxes, sliders), two-finger tap = right click.
 * Ctrl+Alt releases a captured mouse.
 */
class InputSurfaceView(context: Context, private val xWidth: Int, private val xHeight: Int) : SurfaceView(context) {
    private companion object { const val WHEEL_STEP_X = 40f }

    var inputEnabled = false
    private enum class Touch { NONE, PENDING, SCROLL, DRAG, RIGHT_CLICK }
    private var touch = Touch.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        if (touch == Touch.PENDING) {
            touch = Touch.DRAG
            XInput.button(1, true)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }
    private var ctrlDown = false
    private var altDown = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun toX(vx: Float, vy: Float): Pair<Int, Int> {
        val x = (vx / width.coerceAtLeast(1) * xWidth).toInt().coerceIn(0, xWidth - 1)
        val y = (vy / height.coerceAtLeast(1) * xHeight).toInt().coerceIn(0, xHeight - 1)
        return x to y
    }

    private fun androidButtonToX(b: Int): Int = when (b) {
        MotionEvent.BUTTON_PRIMARY -> 1
        MotionEvent.BUTTON_TERTIARY -> 2
        MotionEvent.BUTTON_SECONDARY -> 3
        MotionEvent.BUTTON_BACK -> 8
        MotionEvent.BUTTON_FORWARD -> 9
        else -> 0
    }

    private fun scroll(e: MotionEvent) {
        val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (v > 0) click(4) else if (v < 0) click(5)
        if (h > 0) click(7) else if (h < 0) click(6)
    }

    private fun click(b: Int) { XInput.button(b, true); XInput.button(b, false) }

    /** One wheel step per [WHEEL_STEP_X] X pixels of finger travel; content follows the finger. */
    private fun touchScroll(e: MotionEvent) {
        val stepX = WHEEL_STEP_X * width.coerceAtLeast(1) / xWidth.toFloat()
        val stepY = WHEEL_STEP_X * height.coerceAtLeast(1) / xHeight.toFloat()
        scrollX += e.x - lastX
        scrollY += e.y - lastY
        lastX = e.x; lastY = e.y
        while (scrollY <= -stepY) { click(5); scrollY += stepY }
        while (scrollY >= stepY) { click(4); scrollY -= stepY }
        while (scrollX <= -stepX) { click(7); scrollX += stepX }
        while (scrollX >= stepX) { click(6); scrollX -= stepX }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!inputEnabled) return false
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return onGenericMotionEvent(e)
        requestFocus()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (x, y) = toX(e.x, e.y)
                XInput.moveTo(x, y) // The wheel and the click go to what is under the finger.
                touch = Touch.PENDING
                lastX = e.x; lastY = e.y
                scrollX = 0f; scrollY = 0f
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                removeCallbacks(longPress)
                if (touch == Touch.DRAG) XInput.button(1, false)
                touch = Touch.RIGHT_CLICK
            }
            MotionEvent.ACTION_MOVE -> when (touch) {
                Touch.PENDING -> if (kotlin.math.hypot(e.x - lastX, e.y - lastY) > slop) {
                    removeCallbacks(longPress)
                    touch = Touch.SCROLL
                    touchScroll(e)
                }
                Touch.SCROLL -> touchScroll(e)
                Touch.DRAG -> { val (x, y) = toX(e.x, e.y); XInput.moveTo(x, y) }
                else -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (touch) {
                    Touch.PENDING -> if (up) click(1)
                    Touch.DRAG -> XInput.button(1, false)
                    Touch.RIGHT_CLICK -> if (up) click(3)
                    else -> {}
                }
                touch = Touch.NONE
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!inputEnabled || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return super.onGenericMotionEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                val (x, y) = toX(e.x, e.y); XInput.moveTo(x, y)
            }
            MotionEvent.ACTION_BUTTON_PRESS -> androidButtonToX(e.actionButton).takeIf { it > 0 }?.let { XInput.button(it, true) }
            MotionEvent.ACTION_BUTTON_RELEASE -> androidButtonToX(e.actionButton).takeIf { it > 0 }?.let { XInput.button(it, false) }
            MotionEvent.ACTION_SCROLL -> scroll(e)
        }
        return true
    }

    /** Relative motion while the pointer is captured (games that warp/lock the cursor). */
    override fun onCapturedPointerEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                for (i in 0 until e.historySize) XInput.moveBy(e.getHistoricalX(i).toInt(), e.getHistoricalY(i).toInt())
                XInput.moveBy(e.x.toInt(), e.y.toInt())
            }
            MotionEvent.ACTION_BUTTON_PRESS -> androidButtonToX(e.actionButton).takeIf { it > 0 }?.let { XInput.button(it, true) }
            MotionEvent.ACTION_BUTTON_RELEASE -> androidButtonToX(e.actionButton).takeIf { it > 0 }?.let { XInput.button(it, false) }
            MotionEvent.ACTION_SCROLL -> scroll(e)
        }
        return true
    }

    private fun key(e: KeyEvent, down: Boolean): Boolean {
        if (!inputEnabled) return false
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.scanCode == 0) return false  // system back gesture
        when (e.keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> ctrlDown = down
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> altDown = down
        }
        if (down && ctrlDown && altDown && hasPointerCapture()) releasePointerCapture()
        val kc = XInput.xKeycode(e)
        if (kc < 0) return false
        XInput.key(kc, down)
        return true
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent) = key(e, true) || super.onKeyDown(keyCode, e)
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = key(e, false) || super.onKeyUp(keyCode, e)

    // Soft keyboard: ask for raw key events instead of composed text.
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return BaseInputConnection(this, false)
    }
}
