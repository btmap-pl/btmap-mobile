package pl.btmap.mobile.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** App language setting; SYSTEM follows the phone (Polish when the phone is Polish, English otherwise). */
enum class AppLanguage { SYSTEM, PL, EN }

/**
 * The language the UI is drawn in. Backed by Compose state, so screens redraw as soon as the
 * setting changes; plain code (notification, workers) reads the same value.
 */
object Lang {
    var english by mutableStateOf(systemIsEnglish())
        private set

    val locale: Locale get() = if (english) Locale.ENGLISH else Locale("pl")

    fun apply(language: AppLanguage) {
        english = when (language) {
            AppLanguage.SYSTEM -> systemIsEnglish()
            AppLanguage.PL -> false
            AppLanguage.EN -> true
        }
    }

    private fun systemIsEnglish() = Locale.getDefault().language != "pl"
}

/** Text in the current language. */
fun tr(pl: String, en: String): String = if (Lang.english) en else pl

/** Polish plural: 1 komórka, 2–4 komórki, 5+ komórek; English: 1 cell, n cells. */
fun plural(n: Int, one: String, few: String, many: String, enOne: String, enMany: String): String =
    if (Lang.english) (if (n == 1) enOne else enMany)
    else when {
        n == 1 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }

/** Cell history entries are stored in Polish by the core; shown translated. */
fun translateChange(change: String): String {
    if (!Lang.english) return change
    return when (change) {
        "utrata zasięgu" -> "service lost"
        "odzyskano zasięg" -> "service restored"
        "zmiana konfiguracji" -> "configuration change"
        else -> if (change.startsWith("komórka ")) "cell " + change.removePrefix("komórka ") else change
    }
}

/** Messages that come from the core library, which speaks Polish. */
fun translateMessage(message: String): String {
    if (!Lang.english) return message
    return when {
        message == "Limit zapytań BTSearch" -> "BTSearch request limit"
        message.startsWith("Serwer nie odpowiada") -> "Server not responding" + message.removePrefix("Serwer nie odpowiada")
        message == "Brak połączenia" -> "No connection"
        message == "Nie wybrano operatorów" -> "No operators selected"
        message == "Brak danych" -> "No data"
        message.startsWith("Brak uprawnień: ") -> "Missing permission: " + message.removePrefix("Brak uprawnień: ")
        message.startsWith("Modem: błąd ") -> "Modem: error " + message.removePrefix("Modem: błąd ")
        else -> message
    }
}
