package ru.polka.reader

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
        primary = Color(0xffb2ceb5), onPrimary = Color(0xff163523),
        primaryContainer = Color(0xff294738), onPrimaryContainer = Color(0xfffff4d8),
        secondary = Color(0xffb9c8b6), onSecondary = Color(0xff203523),
        secondaryContainer = Color(0xff354b3f), onSecondaryContainer = Color(0xffe0efdb),
        tertiary = Color(0xffefb667), onTertiary = Color(0xff412c10),
        surface = Color(0xff18201c), onSurface = Color(0xffe8e8dc),
        surfaceContainer = Color(0xff232e27), surfaceContainerLow = Color(0xff1e2822),
        surfaceContainerHigh = Color(0xff2a352e), surfaceContainerHighest = Color(0xff344038),
        onSurfaceVariant = Color(0xffc3c9ba), outline = Color(0xff98a496),
        background = Color(0xff18201c)
    ) else lightColorScheme(
        primary = Color(0xff294738), onPrimary = Color(0xfffff4d8),
        primaryContainer = Color(0xffdce7d6), onPrimaryContainer = Color(0xff203a2a),
        secondary = Color(0xff405c48), onSecondary = Color.White,
        secondaryContainer = Color(0xffd9e4cf), onSecondaryContainer = Color(0xff213b2b),
        tertiary = Color(0xff81551b), onTertiary = Color.White,
        surface = Color(0xfff7f4e9), onSurface = Color(0xff222c24), background = Color(0xfff7f4e9),
        surfaceContainer = Color(0xffedeadd), surfaceContainerLow = Color(0xfff3f0e4),
        surfaceContainerHigh = Color(0xffe9e6da), surfaceContainerHighest = Color(0xffe2e1d5),
        onSurfaceVariant = Color(0xff4d594d), outline = Color(0xff697368)
    )
    val type = Typography()
    val typography = type.copy(displaySmall = type.displaySmall.copy(fontFamily = FontFamily.Serif),
        headlineLarge = type.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = type.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = type.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = type.titleLarge.copy(fontFamily = FontFamily.Serif))
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

@Composable internal fun BrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.polka_mark), contentDescription = null, modifier = modifier)
}
