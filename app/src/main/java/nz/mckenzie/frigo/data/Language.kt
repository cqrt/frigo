package nz.mckenzie.frigo.data

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The languages Frigo has words for.
 *
 * [System] is the phone's own language, and is what the app does until someone
 * picks otherwise. The tag is the name Android knows the language by; an empty
 * one means "follow the phone", which is the same as having picked nothing.
 *
 * The order is the order the picker lists them in.
 */
enum class Language(val tag: String) {
    System(""),
    English("en"),
    Spanish("es"),
    French("fr"),
    German("de"),
    Portuguese("pt"),
    ;

    /**
     * The name the language goes by in itself, which is how the picker lists it:
     * someone who reads Spanish finds Spanish by looking for Español. Empty for
     * [System], which the picker names from the app's own words instead.
     */
    val ownName: String
        get() = when (this) {
            System -> ""
            English -> "English"
            Spanish -> "Español"
            French -> "Français"
            German -> "Deutsch"
            Portuguese -> "Português"
        }

    companion object {
        /**
         * The language the app is in now.
         *
         * Read back from the same place it is written, so the picker agrees with
         * Android's own per-app language screen: whichever of the two changed it,
         * this answers the same.
         */
        fun current(): Language {
            val chosen = AppCompatDelegate.getApplicationLocales()
            if (chosen.isEmpty) return System
            val tag = chosen[0]?.language.orEmpty()
            return entries.firstOrNull { it.tag == tag } ?: System
        }

        /** Picks one, or goes back to following the phone when given [System]. */
        fun choose(language: Language) {
            AppCompatDelegate.setApplicationLocales(
                if (language.tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(language.tag)
                },
            )
        }

        /**
         * The language a saved tag stands for. Anything this version doesn't know,
         * including the empty tag a backup writes for following the phone, reads as
         * [System].
         */
        fun named(tag: String): Language = entries.firstOrNull { it.tag == tag } ?: System
    }
}

/**
 * The language the app is being read in, which is the app's own choice rather
 * than the phone's when the two differ.
 *
 * Dates and times are drawn with this rather than with the phone's language, so
 * a screen is never in one language with its days of the week in another.
 */
fun Context.appLocale(): Locale =
    resources.configuration.locales[0] ?: Locale.getDefault()

/**
 * A string in the app's language, for the places that are not a screen.
 *
 * Compose reads strings through `stringResource`, which is already in the app's
 * language. The alert notification, the background service and the messages the
 * data layer builds are not, because a service is handed the phone's own context
 * and would otherwise speak the phone's language.
 */
fun Context.words(@StringRes id: Int, vararg args: Any): String =
    inAppLanguage().getString(id, *args)

/** The same, for a count that decides the wording. */
fun Context.counted(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    inAppLanguage().resources.getQuantityString(id, count, *args)

/** This context, with the app's language on it rather than the phone's. */
private fun Context.inAppLanguage(): Context {
    val chosen = AppCompatDelegate.getApplicationLocales()
    if (chosen.isEmpty) return this
    val locale = chosen[0] ?: return this
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}
