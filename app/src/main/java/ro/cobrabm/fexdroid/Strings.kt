package ro.cobrabm.fexdroid

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import java.util.Locale

/** The app's language: the phone's, or one chosen in Setări. */
enum class Language(val tag: String?, val label: String) {
    SYSTEM(null, ""), ENGLISH("en", "English"), ROMANIAN("ro", "Română"),
}

/**
 * Text from the string resources (res/values: English, res/values-ro: Romanian) for composables and for
 * code that has no Context at hand (the session's log, errors from worker threads).
 * Call [init] once (MainActivity.onCreate) before [str].
 */
object Strings {
    private lateinit var app: Context
    private val cached = HashMap<Language, Resources>()

    fun init(ctx: Context) { app = ctx.applicationContext }

    private val forced = ThreadLocal<Language?>()

    /** The language [str] uses on this thread: the forced one inside [inEnglish], else the setting. */
    val language: Language get() = forced.get() ?: AppSettings.language

    /** Reports are read by whoever debugs them, whatever the phone's language is. */
    fun <T> inEnglish(block: () -> T): T {
        val before = forced.get()
        forced.set(Language.ENGLISH)
        try { return block() } finally { forced.set(before) }
    }

    @Synchronized
    fun resources(language: Language): Resources {
        // The phone's language can change while the app runs: only the chosen ones are kept.
        if (language.tag == null) return app.resources
        return cached.getOrPut(language) {
            val config = Configuration(app.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language.tag))
            app.createConfigurationContext(config).resources
        }
    }
}

/** The text [id] in the app's language, with [args] in place of %1$s, %2$d ... */
fun str(@StringRes id: Int, vararg args: Any?): String {
    // Reading the setting here makes composables that show the text follow a change of language.
    val r = Strings.resources(Strings.language)
    return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
}
