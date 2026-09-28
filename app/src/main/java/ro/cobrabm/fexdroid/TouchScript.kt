package ro.cobrabm.fexdroid

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Test help: plays touches of several fingers into the app's own window, which
 * `adb shell input` cannot do (`am start --es touches "<script>"`, MainActivity).
 *
 * A script is a line of steps: `d0:168,1000` finger 0 down at that place of the window,
 * `m0:200,1000` moves it, `u0` lifts it, `w400` waits 400 ms.
 */
object TouchScript {
    fun play(activity: Activity, script: String) {
        val handler = Handler(Looper.getMainLooper())
        val fingers = LinkedHashMap<Int, Pair<Float, Float>>()
        val began = SystemClock.uptimeMillis()
        var at = 0L

        fun send(action: Int, finger: Int) {
            val ids = fingers.keys.toList()
            val index = ids.indexOf(finger)
            val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(ids.size) { i ->
                MotionEvent.PointerCoords().apply { x = fingers[ids[i]]!!.first; y = fingers[ids[i]]!!.second; pressure = 1f; size = 1f }
            }
            val masked = if (ids.size == 1 || action == MotionEvent.ACTION_MOVE) action
                else (if (action == MotionEvent.ACTION_DOWN) MotionEvent.ACTION_POINTER_DOWN else MotionEvent.ACTION_POINTER_UP) or
                    (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val e = MotionEvent.obtain(began, SystemClock.uptimeMillis(), masked, ids.size, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val taken = activity.dispatchTouchEvent(e)
            android.util.Log.i("fexdroid-touch", "${MotionEvent.actionToString(masked)} finger $finger of ${ids.size}: taken $taken")
            e.recycle()
        }

        for (step in script.trim().split(Regex("\\s+"))) {
            val kind = step.firstOrNull() ?: continue
            val rest = step.drop(1)
            if (kind == 'w') { at += rest.toLongOrNull() ?: 0; continue }
            val finger = rest.substringBefore(':').toIntOrNull() ?: continue
            val place = rest.substringAfter(':', "").split(',').mapNotNull { it.toFloatOrNull() }
            handler.postDelayed({
                when (kind) {
                    'd' -> if (place.size == 2) { fingers[finger] = place[0] to place[1]; send(MotionEvent.ACTION_DOWN, finger) }
                    'm' -> if (place.size == 2 && finger in fingers) { fingers[finger] = place[0] to place[1]; send(MotionEvent.ACTION_MOVE, finger) }
                    'u' -> if (finger in fingers) { send(MotionEvent.ACTION_UP, finger); fingers.remove(finger) }
                }
            }, at)
        }
    }
}
