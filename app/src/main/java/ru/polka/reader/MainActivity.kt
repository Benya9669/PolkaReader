package ru.polka.reader

import android.os.Bundle
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.polka.reader.data.Account
import ru.polka.reader.data.PolkaData
import ru.polka.reader.reader.ReaderVolumeKeys
import ru.polka.reader.sync.ProgressSync

class MainActivity : ComponentActivity() {
    internal val readerVolumeKeys = ReaderVolumeKeys()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Intercept only opted-in reader keys, before a focused WebView or the volume panel handles them.
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent) =
                readerVolumeKeys.handle(event) || original.dispatchKeyEvent(event)
        }
        enableEdgeToEdge()
        val data = PolkaData.get(applicationContext)
        val sync = ProgressSync.get(applicationContext)
        val appearance = Appearance(applicationContext)
        setContent {
            val account by data.active.collectAsState()
            val theme by appearance.theme.collectAsState(initial = "Системная")
            PolkaTheme(theme) {
                val useDarkIcons = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = useDarkIcons
                        isAppearanceLightNavigationBars = useDarkIcons
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    key(account?.scope) {
                        val current = account
                        var signIn by rememberSaveable { mutableStateOf(current == null) }
                        val scope = rememberCoroutineScope()
                        LaunchedEffect(current?.scope, current?.authenticated) {
                            if (current?.authenticated == true) {
                                sync.resume(current.scope)
                                try { data.verify(current.scope) } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { /* Downloads stay available during an outage. */ }
                            }
                        }
                        if (current == null || signIn) {
                            BackHandler(current != null) { signIn = false }
                            LoginScreen(current, data, { signIn = false }, if (current == null) null else ({ signIn = false }))
                        } else {
                            val transfers: Transfers = viewModel(key = current.scope)
                            Shelf(current, data, sync, transfers, appearance, theme,
                                { scope.launch { appearance.setTheme(it) } }, { signIn = true })
                        }
                    }
                }
            }
        }
    }
}

internal fun friendly(error: Throwable): String = when (error) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        "Сервер недоступен. Проверьте адрес и подключение к домашней сети."
    is javax.net.ssl.SSLException -> "Не удалось проверить защищённое соединение с сервером."
    else -> error.localizedMessage ?: "Не удалось выполнить запрос. Попробуйте ещё раз."
}

@Composable
private fun LoginScreen(account: Account?, data: PolkaData, done: () -> Unit, back: (() -> Unit)?) {
    val coroutine = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(account?.server ?: "") }
    var name by rememberSaveable { mutableStateOf(account?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark(Modifier.size(72.dp))
            Text("Polka Reader", style = MaterialTheme.typography.displaySmall)
            Text(if (account?.authenticated == false) "Войдите повторно, чтобы синхронизировать чтение." else "Подключение к вашей библиотеке",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(url, { url = it }, label = { Text("Адрес сервера „Полка“") },
                placeholder = { Text("https://polka.example.org") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(name, { name = it }, label = { Text("Имя пользователя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Пароль") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (url.startsWith("http://")) Text("В домашней сети HTTP передаёт данные входа без шифрования.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                busy = true
                error = null
                coroutine.launch {
                    try { data.login(url.trim(), name.trim(), password); password = ""; done() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = friendly(e) }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && url.isNotBlank() && name.isNotBlank() && password.isNotEmpty()) {
                Text(if (busy) "Подключение…" else "Подключиться")
            }
            if (back != null) TextButton(onClick = back) { Text("Вернуться в приложение") }
        }
    }
}
