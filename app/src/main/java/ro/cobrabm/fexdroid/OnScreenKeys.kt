package ro.cobrabm.fexdroid

import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Keys drawn at the sides of the picture, for playing without a keyboard.
 *
 * A layout is a line of key names separated by spaces: letters, digits, F1..F12, Esc, Tab,
 * Space, Enter, Back, Del, Up, Down, Left, Right. Shift, Ctrl and Alt stay pressed until they
 * are tapped again. Two names change what touch does on the picture (InputSurfaceView): Mouse
 * (a tap presses the first or the second button) and Swipe (a swipe turns the wheel, or drags
 * with the middle button, which moves the camera in Dota 2 and many other games).
 */
object OnScreenKeys {
    /** Width kept free for the keys at each side of the picture. */
    const val SIDE_DP = 56
    const val DEFAULT_LEFT = "Esc Q W E R D F"
    const val DEFAULT_RIGHT = "Mouse Swipe A S Alt Ctrl"

    sealed interface Key
    data class Plain(val label: String, val keycode: Int) : Key
    data class Sticky(val label: String, val keycode: Int) : Key
    data object Mouse : Key
    data object Swipe : Key
    /** Opens the app's menu: the floating button would sit on the keys. */
    data object Menu : Key

    private var tapSecond by mutableStateOf(false)
    private var swipeDrags by mutableStateOf(false)
    private val held = mutableStateListOf<Int>()

    /** A tap on the picture presses the second mouse button. */
    val tapIsSecondButton get() = AppSettings.onScreenKeys && tapSecond
    /** A swipe on the picture drags with the middle button. */
    val swipeIsDrag get() = AppSettings.onScreenKeys && swipeDrags

    private val NAMED = mapOf(
        "esc" to KeyEvent.KEYCODE_ESCAPE, "tab" to KeyEvent.KEYCODE_TAB, "space" to KeyEvent.KEYCODE_SPACE,
        "enter" to KeyEvent.KEYCODE_ENTER, "back" to KeyEvent.KEYCODE_DEL, "del" to KeyEvent.KEYCODE_FORWARD_DEL,
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    )
    private val STICKY = mapOf(
        "shift" to KeyEvent.KEYCODE_SHIFT_LEFT, "ctrl" to KeyEvent.KEYCODE_CTRL_LEFT, "alt" to KeyEvent.KEYCODE_ALT_LEFT,
    )

    private fun key(name: String): Key? {
        val n = name.lowercase()
        if (n == "mouse") return Mouse
        if (n == "swipe") return Swipe
        STICKY[n]?.let { return Sticky(name, XInput.xKeycode(it)) }
        val android = NAMED[n]
            ?: n.singleOrNull()?.let { c ->
                when (c) {
                    in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                    in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                    else -> null
                }
            }
            ?: Regex("f(\\d{1,2})").matchEntire(n)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..12 }
                ?.let { KeyEvent.KEYCODE_F1 + it - 1 }
            ?: return null
        val code = XInput.xKeycode(android)
        return if (code < 0) null else Plain(if (name.length == 1) name.uppercase() else name, code)
    }

    /** The keys of [layout]; names that mean nothing are left out, see [unknown]. */
    fun parse(layout: String): List<Key> = layout.split(' ', ',', '\n').filter { it.isNotBlank() }.mapNotNull(::key)

    fun unknown(layout: String): List<String> = layout.split(' ', ',', '\n').filter { it.isNotBlank() && key(it) == null }

    /** A key on the screen: where it is in the window, and what it does. */
    private class Spot(var area: RectF, var onDown: () -> Unit, var onUp: () -> Unit)

    // Main thread only.
    private val spots = HashMap<String, Spot>()
    /** The finger (pointer id) that holds each key. */
    private val fingers = HashMap<Int, String>()
    private val pressed = mutableStateListOf<String>()
    private var pictureDown = 0L

    private fun lift(finger: Int) {
        val name = fingers.remove(finger) ?: return
        if (name !in fingers.values) { pressed.remove(name); spots[name]?.onUp?.invoke() }
    }

    /**
     * Takes the fingers that are on keys out of a touch event of the window (MainActivity), so
     * that the picture gets the other fingers as if they were alone. Compose cannot do it: a
     * view inside it gets nothing from a finger that goes down while another one is on
     * something else. Returns the event for the rest of the window: [e] itself, a new one,
     * or null when all of it was for the keys.
     */
    fun filter(e: MotionEvent): MotionEvent? {
        if (spots.isEmpty() && fingers.isEmpty()) return e
        if (!e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return e
        val action = e.actionMasked
        val index = e.actionIndex
        val finger = e.getPointerId(index)
        var mine = false // This event is about a finger on a key.
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (action == MotionEvent.ACTION_DOWN) for (f in fingers.keys.toList()) lift(f)
                val x = e.getX(index)
                val y = e.getY(index)
                val name = if (InputSurfaceView.overlayOpen) null else spots.entries.firstOrNull { it.value.area.contains(x, y) }?.key
                if (name != null) {
                    mine = true
                    val held = name in fingers.values
                    fingers[finger] = name
                    if (!held) { pressed += name; spots[name]?.onDown?.invoke() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (finger in fingers) { mine = true; lift(finger) }
            MotionEvent.ACTION_CANCEL -> { for (f in fingers.keys.toList()) lift(f); return e }
        }
        if (!mine && fingers.isEmpty()) return e
        val keep = (0 until e.pointerCount).filter { i -> !(mine && i == index) && e.getPointerId(i) !in fingers }
        if (keep.isEmpty()) return null
        val place = keep.indexOf(index) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val rest = when {
            mine -> MotionEvent.ACTION_MOVE
            action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN ->
                if (keep.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or place
            action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP ->
                if (keep.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or place
            else -> action
        }
        if (rest == MotionEvent.ACTION_DOWN) pictureDown = e.eventTime
        val props = Array(keep.size) { MotionEvent.PointerProperties().also { p -> e.getPointerProperties(keep[it], p) } }
        val coords = Array(keep.size) { MotionEvent.PointerCoords().also { c -> e.getPointerCoords(keep[it], c) } }
        return MotionEvent.obtain(pictureDown, e.eventTime, rest, keep.size, props, coords, e.metaState, e.buttonState,
            e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, e.source, e.flags)
    }

    /** Lets go of what is held and goes back to what touch does without keys. */
    fun reset() {
        for (f in fingers.keys.toList()) lift(f)
        for (k in held) XInput.key(k, false)
        held.clear()
        tapSecond = false
        swipeDrags = false
    }

    /** One column of keys, as tall as the screen. [id] tells the columns apart. */
    @Composable
    fun Strip(id: String, keys: List<Key>, modifier: Modifier = Modifier, onMenu: () -> Unit = {}) {
        Column(
            modifier.width(SIDE_DP.dp).fillMaxHeight().padding(horizontal = 3.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            keys.forEachIndexed { i, k ->
                val m = Modifier.weight(1f, fill = false).heightIn(max = 56.dp).fillMaxWidth()
                val name = "$id $i"
                when (k) {
                    is Plain -> Cap(name, m, k.label, onDown = { XInput.key(k.keycode, true) }, onUp = { XInput.key(k.keycode, false) })
                    is Sticky -> Cap(name, m, k.label, lit = k.keycode in held, onDown = {
                        if (held.remove(k.keycode)) XInput.key(k.keycode, false)
                        else { held += k.keycode; XInput.key(k.keycode, true) }
                    })
                    Mouse -> Cap(name, m, str(if (tapSecond) R.string.key_right else R.string.key_left), str(R.string.key_tap),
                        lit = tapSecond, onDown = { tapSecond = !tapSecond })
                    Swipe -> Cap(name, m, str(if (swipeDrags) R.string.key_camera else R.string.key_wheel), str(R.string.key_swipe),
                        lit = swipeDrags, onDown = { swipeDrags = !swipeDrags })
                    Menu -> Cap(name, m, null, onDown = onMenu)
                }
            }
        }
    }

    /**
     * One key. [caption]: small text over the label. A key without a label shows the menu sign.
     * Touches come from [filter], not from Compose.
     */
    @Composable
    private fun Cap(
        name: String, modifier: Modifier, label: String?, caption: String? = null, lit: Boolean = false,
        onDown: () -> Unit, onUp: () -> Unit = {},
    ) {
        val view = LocalView.current
        val spot = remember(name) { Spot(RectF(), {}, {}) }
        spot.onDown = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onDown() }
        spot.onUp = onUp
        DisposableEffect(name) {
            spots[name] = spot
            onDispose { if (spots[name] === spot) spots.remove(name) }
        }
        val shape = RoundedCornerShape(10.dp)
        val fill = when {
            name in pressed -> Color.White.copy(alpha = 0.40f)
            lit -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> Color.White.copy(alpha = 0.10f)
        }
        Box(
            modifier.fillMaxHeight().background(fill, shape).border(1.dp, Color.White.copy(alpha = 0.30f), shape)
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    spot.area = RectF(b.left, b.top, b.right, b.bottom)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (label == null) {
                Icon(AppIcons.Menu, str(R.string.player_menu), tint = Color.White.copy(alpha = 0.85f))
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (caption != null) Text(caption, fontSize = 9.sp, lineHeight = 10.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                Text(label, fontSize = if (label.length > 2) 12.sp else 17.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f), maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}
