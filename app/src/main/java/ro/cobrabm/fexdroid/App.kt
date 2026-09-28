package ro.cobrabm.fexdroid

import android.app.Application

/** Settings and text are ready before the activity, the game service or the update receiver run. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        AppSettings.init(this)
    }
}
