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
 * - touch: one finger = left button drag, two-finger tap = right click.
 * Ctrl+Alt releases a captured mouse.
 */
class InputSurfaceView(context: Context, private val xWidth: Int, private val xHeight: Int) : SurfaceView(context) {
    var inputEnabled = false
    private var touchLeftDown = false
    private var twoFingerTap = false
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
        fun click(b: Int) { XInput.button(b, true); XInput.button(b, false) }
        if (v > 0) click(4) else if (v < 0) click(5)
        if (h > 0) click(7) else if (h < 0) click(6)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!inputEnabled) return false
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return onGenericMotionEvent(e)
        requestFocus()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFingerTap = false
                val (x, y) = toX(e.x, e.y)
                XInput.moveTo(x, y)
                XInput.button(1, true)
                touchLeftDown = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                // Second finger: turn the gesture into a right click.
                if (touchLeftDown) { XInput.button(1, false); touchLeftDown = false }
                twoFingerTap = true
            }
            MotionEvent.ACTION_MOVE -> if (!twoFingerTap) {
                val (x, y) = toX(e.x, e.y)
                XInput.moveTo(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchLeftDown) XInput.button(1, false)
                if (twoFingerTap && e.actionMasked == MotionEvent.ACTION_UP) {
                    XInput.button(3, true); XInput.button(3, false)
                }
                touchLeftDown = false
                twoFingerTap = false
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
