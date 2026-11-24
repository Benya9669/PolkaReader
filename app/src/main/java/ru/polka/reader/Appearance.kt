package ru.polka.reader

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import ru.polka.reader.reader.BookSettings

private val Context.preferences by preferencesDataStore("appearance")
private val font = intPreferencesKey("reader_font")
private val leading = floatPreferencesKey("reader_leading")
private val margin = intPreferencesKey("reader_margin")
private val paper = stringPreferencesKey("reader_paper")
private val appTheme = stringPreferencesKey("app_theme")

internal class Appearance(private val context: Context) {
    val book = context.preferences.data.map {
        BookSettings(it[font] ?: 110, it[leading] ?: 1.5f, it[margin] ?: 16, it[paper] ?: "light")
    }
    val theme = context.preferences.data.map { it[appTheme] ?: "Системная" }
    suspend fun setTheme(value: String) { context.preferences.edit { it[appTheme] = value } }
    suspend fun save(value: BookSettings) {
        context.preferences.edit {
            it[font] = value.fontPercent
            it[leading] = value.lineHeight
            it[margin] = value.marginDp
            it[paper] = value.theme
        }
    }
}

@Composable
internal fun PolkaTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "Тёмная" || theme == "Системная" && isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xffa3d2bc), secondary = Color(0xffc6c9ac),
        surface = Color(0xff151c19), background = Color(0xff151c19)
    ) else lightColorScheme(
        primary = Color(0xff295946), onPrimary = Color.White, primaryContainer = Color(0xffdaebdc),
        secondary = Color(0xff626b50), surface = Color(0xfffcfaf5), background = Color(0xfffcfaf5),
        surfaceContainer = Color(0xffefeee6), surfaceContainerLow = Color(0xfff5f3eb)
    )
    MaterialTheme(colorScheme = colors, content = content)
}
