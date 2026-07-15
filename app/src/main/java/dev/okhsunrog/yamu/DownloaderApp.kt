package dev.okhsunrog.yamu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

private data class DeviceSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Long,
    val interval: Long,
    val startedAt: Long = SystemClock.elapsedRealtime(),
)

private sealed interface AuthStatus {
    data object Idle : AuthStatus
    data object Requesting : AuthStatus
    data class Waiting(val session: DeviceSession) : AuthStatus
    data class Failure(val message: String) : AuthStatus
}

internal sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data class Downloading(val progress: NativeDownloadProgress) : DownloadStatus
    data object Cancelling : DownloadStatus
    data object Cancelled : DownloadStatus
    data class Success(val download: PublishedDownload) : DownloadStatus
    data class Failure(val message: String) : DownloadStatus
}

internal data class NativeDownloadProgress(
    val phase: String = "preparing",
    val downloaded: Long = 0,
    val total: Long? = null,
    val cancellable: Boolean = true,
    val item: Int = 0,
    val itemTotal: Int = 0,
    val itemLabel: String = "",
)

internal val DownloadStatus.isBusy: Boolean
    get() = this is DownloadStatus.Downloading || this is DownloadStatus.Cancelling

private enum class AppSection {
    Download,
    Settings,
}

@Composable
internal fun DownloaderApp(
    tokenStore: TokenStore,
    incomingLink: IncomingLink?,
) {
    var accessToken by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tokenStore) {
        accessToken = withContext(Dispatchers.IO) { tokenStore.load() }
    }

    val loadedToken = accessToken
    if (loadedToken == null) {
        AppScaffold {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    AnimatedContent(
        targetState = loadedToken.isNotBlank(),
        label = "authentication-gate",
    ) { authenticated ->
        if (authenticated) {
            DownloaderScreen(
                incomingLink = incomingLink,
                onLogout = {
                    scope.launch {
                        withContext(Dispatchers.IO) { tokenStore.clear() }
                        accessToken = ""
                    }
                },
            )
        } else {
            AuthScreen(
                pendingLink = incomingLink?.url,
                onAuthorized = { token ->
                    withContext(Dispatchers.IO) { tokenStore.save(token) }
                    accessToken = token
                },
            )
        }
    }
}

@Composable
private fun AuthScreen(
    pendingLink: String?,
    onAuthorized: suspend (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AuthStatus>(AuthStatus.Idle) }
    val waiting = status as? AuthStatus.Waiting

    fun openBrowser(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    fun requestAuthorization() {
        scope.launch {
            status = AuthStatus.Requesting
            status = try {
                val response = withContext(Dispatchers.IO) {
                    JSONObject(NativeBridge.requestDeviceCode())
                }
                val session = DeviceSession(
                    deviceCode = response.getString("deviceCode"),
                    userCode = response.getString("userCode"),
                    verificationUrl = response.getString("verificationUrl"),
                    expiresIn = response.getLong("expiresIn"),
                    interval = response.getLong("interval").coerceAtLeast(1),
                )
                openBrowser(session.verificationUrl)
                AuthStatus.Waiting(session)
            } catch (error: Throwable) {
                AuthStatus.Failure(error.message ?: "Не удалось начать авторизацию")
            }
        }
    }

    LaunchedEffect(waiting?.session?.deviceCode) {
        val session = waiting?.session ?: return@LaunchedEffect
        var consecutiveErrors = 0
        var pollInterval = session.interval
        while (isActive) {
            val elapsed = SystemClock.elapsedRealtime() - session.startedAt
            val remaining = session.expiresIn * 1_000 - elapsed
            if (remaining <= 0) {
                status = AuthStatus.Failure("Код истёк. Начните вход ещё раз.")
                return@LaunchedEffect
            }
            delay(minOf(pollInterval * 1_000, remaining))
            if (SystemClock.elapsedRealtime() - session.startedAt >= session.expiresIn * 1_000) {
                status = AuthStatus.Failure("Код истёк. Начните вход ещё раз.")
                return@LaunchedEffect
            }
            try {
                val response = withContext(Dispatchers.IO) {
                    JSONObject(NativeBridge.pollDeviceToken(session.deviceCode))
                }
                consecutiveErrors = 0
                when (response.getString("status")) {
                    "pending" -> Unit
                    "slow_down" -> pollInterval += 5
                    "authorized" -> {
                        onAuthorized(response.getString("accessToken"))
                        return@LaunchedEffect
                    }
                    else -> error("Неизвестный ответ авторизации")
                }
            } catch (error: Throwable) {
                consecutiveErrors += 1
                if (consecutiveErrors >= 3) {
                    status = AuthStatus.Failure(error.message ?: "Ошибка авторизации")
                    return@LaunchedEffect
                }
            }
        }
    }

    AppScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AppHeader(subtitle = "Музыка по ссылке — в лучшем качестве")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    Text(
                        text = "Войдите через Яндекс",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Откроем официальную страницу Яндекса в браузере. " +
                            "Приложение автоматически продолжит после подтверждения.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    if (pendingLink != null) {
                        StatusCard(
                            icon = Icons.Rounded.Link,
                            title = "Ссылка уже получена",
                            detail = "После входа музыка начнёт скачиваться автоматически.",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    when (val current = status) {
                        AuthStatus.Idle -> Button(
                            onClick = ::requestAuthorization,
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.size(10.dp))
                            Text("Войти через Яндекс", fontWeight = FontWeight.SemiBold)
                        }

                        AuthStatus.Requesting -> Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.size(10.dp))
                            Text("Получаю код…")
                        }

                        is AuthStatus.Waiting -> AuthorizationCode(
                            session = current.session,
                            onOpenBrowser = { openBrowser(current.session.verificationUrl) },
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Код Яндекс OAuth", current.session.userCode),
                                )
                            },
                        )

                        is AuthStatus.Failure -> {
                            StatusCard(
                                icon = Icons.Rounded.Error,
                                title = "Не удалось войти",
                                detail = current.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = ::requestAuthorization,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text("Попробовать снова")
                            }
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeaturePill(Icons.Rounded.Security, "Данные входа защищены")
                FeaturePill(Icons.Rounded.OpenInBrowser, "Вход на странице Яндекса")
            }
        }
    }
}

@Composable
private fun AuthorizationCode(
    session: DeviceSession,
    onOpenBrowser: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Введите код на странице Яндекса", style = MaterialTheme.typography.labelLarge)
            Text(
                session.userCode,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Копировать")
                }
                Button(onClick = onOpenBrowser) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Открыть")
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                Text("Ожидаю подтверждения…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloaderScreen(
    incomingLink: IncomingLink?,
    onLogout: () -> Unit,
) {
    var link by rememberSaveable { mutableStateOf(incomingLink?.url.orEmpty()) }
    val context = LocalContext.current
    val status by DownloadCoordinator.status.collectAsState()
    val backend = remember { NativeBridge.mediaBackend() }
    val settingsStore = remember(context) { SettingsStore(context) }
    var selectedSection by rememberSaveable { mutableStateOf(AppSection.Download) }
    var preferMp3 by rememberSaveable { mutableStateOf(settingsStore.preferMp3) }

    fun download(rawLink: String) {
        val normalizedLink = MainActivity.extractResourceLink(rawLink)
        if (normalizedLink == null) {
            DownloadCoordinator.reject("Вставьте ссылку на трек, альбом или плейлист")
            return
        }
        link = normalizedLink
        DownloadCoordinator.start(context, normalizedLink)
    }

    LaunchedEffect(incomingLink?.sequence) {
        incomingLink ?: return@LaunchedEffect
        selectedSection = AppSection.Download
        link = incomingLink.url
        download(incomingLink.url)
    }

    AppScaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedSection == AppSection.Download,
                    onClick = { selectedSection = AppSection.Download },
                    icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                    label = { Text("Скачать") },
                )
                NavigationBarItem(
                    selected = selectedSection == AppSection.Settings,
                    onClick = { selectedSection = AppSection.Settings },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("Настройки") },
                )
            }
        },
    ) {
        when (selectedSection) {
            AppSection.Download -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AppHeader(subtitle = "из Яндекс Музыки · $backend", onLogout = onLogout)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = lerp(
                    MaterialTheme.colorScheme.surface,
                    Color(0xFF2E7D32),
                    if (isSystemInDarkTheme()) 0.22f else 0.12f,
                ),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF66BB6A),
                    )
                    Text("Вход выполнен", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onLogout) { Text("Выйти") }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Что скачать",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Вставьте ссылку на трек, альбом или плейлист либо отправьте её через «Поделиться».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = link,
                        onValueChange = {
                            link = it
                            if (!status.isBusy) DownloadCoordinator.reset()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !status.isBusy,
                        label = { Text("Ссылка на музыку") },
                        placeholder = { Text("Трек, альбом или плейлист") },
                        leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                        trailingIcon = {
                            AnimatedVisibility(link.isNotBlank()) {
                                IconButton(onClick = { link = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Очистить")
                                }
                            }
                        },
                        minLines = 2,
                        maxLines = 3,
                        shape = RoundedCornerShape(18.dp),
                    )
                    Button(
                        onClick = { download(link) },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        enabled = !status.isBusy,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        AnimatedContent(
                            targetState = status.isBusy,
                            label = "download-button",
                        ) { downloading ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (downloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Text("Скачиваю…")
                                } else {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                                    Text("Скачать", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            DownloadStatusCard(
                status = status,
                onShare = { track -> TrackPublisher.share(context, track) },
                onCancel = {
                    val current = status as? DownloadStatus.Downloading
                    if (current?.progress?.cancellable == true) {
                        DownloadCoordinator.cancel(context)
                    }
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeaturePill(Icons.Rounded.Share, "Принимает shared-ссылки")
                FeaturePill(Icons.Rounded.CheckCircle, "Лучшее качество")
            }
                Spacer(Modifier.height(4.dp))
            }

            AppSection.Settings -> SettingsContent(
                preferMp3 = preferMp3,
                onPreferMp3Change = { enabled ->
                    preferMp3 = enabled
                    settingsStore.preferMp3 = enabled
                },
                onLogout = onLogout,
            )
        }
    }
}

@Composable
private fun SettingsContent(
    preferMp3: Boolean,
    onPreferMp3Change: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    if (showLicenses) {
        OpenSourceLicensesContent(onBack = { showLicenses = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AppHeader(subtitle = "Настройки скачивания", onLogout = onLogout)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Формат аудио",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "MP3 вместо AAC/M4A",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Перекодировать AAC/M4A через FFmpeg и libmp3lame " +
                                "в MP3 320 кбит/с. FLAC и готовые MP3 не изменяются.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preferMp3,
                        onCheckedChange = onPreferMp3Change,
                    )
                }
            }
        }

        StatusCard(
            icon = Icons.Rounded.Info,
            title = "О качестве",
            detail = "Перекодирование AAC в MP3 не улучшает исходный звук и занимает больше " +
                "времени и энергии. Включайте его только для совместимости с устройствами.",
            color = MaterialTheme.colorScheme.tertiary,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "О приложении",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Мультимедиа обрабатывают FFmpeg и LAME. Их исходники, " +
                        "лицензии и инструкции по пересборке опубликованы вместе с релизами.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { showLicenses = true }) {
                    Icon(Icons.Rounded.Security, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Лицензии открытого ПО")
                }
            }
        }
    }
}

private data class OpenSourceLicense(
    val title: String,
    val detail: String,
    val assetName: String,
)

private val OpenSourceLicenses = listOf(
    OpenSourceLicense(
        title = "FFmpeg",
        detail = "GNU LGPL 2.1 или новее · статическая сборка без GPL и nonfree",
        assetName = "FFmpeg-LGPL-2.1.txt",
    ),
    OpenSourceLicense(
        title = "LAME 3.100",
        detail = "GNU Library General Public License 2",
        assetName = "LAME-LGPL-2.0.txt",
    ),
    OpenSourceLicense(
        title = "ffmpeg-next / ffmpeg-sys-next",
        detail = "WTFPL 2",
        assetName = "ffmpeg-rust-WTFPL.txt",
    ),
    OpenSourceLicense(
        title = "mp3lame-sys 0.1.11",
        detail = "GNU LGPL 3.0",
        assetName = "mp3lame-sys-LGPL-3.0.txt",
    ),
)

@Composable
private fun OpenSourceLicensesContent(onBack: () -> Unit) {
    var selectedAsset by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = OpenSourceLicenses.firstOrNull { it.assetName == selectedAsset }
    BackHandler {
        if (selectedAsset != null) selectedAsset = null else onBack()
    }

    if (selected != null) {
        LicenseTextContent(license = selected, onBack = { selectedAsset = null })
        return
    }

    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(title = "Лицензии открытого ПО", onBack = onBack)
        Text(
            text = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME} " +
                "распространяется по лицензиям " +
                "MIT или Apache 2.0.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "FFmpeg ${BuildConfig.FFMPEG_REVISION.take(12)} встроен статически. " +
                "Исходники FFmpeg и LAME и параметры сборки для каждой архитектуры " +
                "приложены к GitHub-релизу.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OpenSourceLicenses.forEach { license ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(license.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        license.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { selectedAsset = license.assetName }) {
                        Text("Открыть текст лицензии")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/okhsunrog/yamu-android/releases".toUri(),
                    ),
                )
            },
        ) {
            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Исходники релизов")
        }
    }
}

@Composable
private fun LicenseTextContent(license: OpenSourceLicense, onBack: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember(license.assetName) {
        runCatching {
            context.assets.open(license.assetName).bufferedReader().use { it.readText() }
        }.getOrElse { "Не удалось открыть встроенный текст лицензии." }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(title = license.title, onBack = onBack)
        Text(
            text = license.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = licenseText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScreenTitle(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AppScaffold(
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = { bottomBar?.invoke() },
    ) { safePadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lerp(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.primary,
                                if (isSystemInDarkTheme()) 0.10f else 0.16f,
                            ),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .padding(safePadding)
                .imePadding(),
        ) { content() }
    }
}

@Composable
private fun AppHeader(subtitle: String, onLogout: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(19.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(31.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Скачать музыку",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onLogout != null) {
            IconButton(onClick = onLogout) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Выйти")
            }
        }
    }
}

@Composable
private fun DownloadStatusCard(
    status: DownloadStatus,
    onShare: (PublishedTrack) -> Unit,
    onCancel: () -> Unit,
) {
    when (status) {
        DownloadStatus.Idle -> Unit
        is DownloadStatus.Downloading -> StatusCard(
            icon = Icons.Rounded.CloudDownload,
            title = "Скачивание",
            detail = progressDescription(status.progress),
            color = MaterialTheme.colorScheme.primary,
            action = {
                val total = status.progress.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (status.progress.downloaded.toFloat() / total.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                if (status.progress.cancellable) {
                    TextButton(onClick = onCancel) { Text("Отменить") }
                }
            },
        )
        DownloadStatus.Cancelling -> StatusCard(
            Icons.Rounded.CloudDownload,
            "Отменяю скачивание",
            "Останавливаю запрос и удаляю временный файл…",
            MaterialTheme.colorScheme.primary,
        )
        DownloadStatus.Cancelled -> StatusCard(
            Icons.Rounded.Info,
            "Скачивание отменено",
            "Временный файл удалён. Можно попробовать снова.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is DownloadStatus.Success -> StatusCard(
            icon = Icons.Rounded.CheckCircle,
            title = if (status.download.isCollection) "Коллекция сохранена" else "Трек сохранён",
            detail = if (!status.download.isCollection) {
                status.download.shareTrack?.let { "${it.location}/${it.displayName}" }
                    ?: status.download.location
            } else {
                "${status.download.title} · ${status.download.fileCount} треков\n${status.download.location}"
            },
            color = Color(0xFF2E7D32),
            action = {
                status.download.shareTrack?.let { track ->
                    TextButton(onClick = { onShare(track) }) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Поделиться")
                    }
                }
            },
        )
        is DownloadStatus.Failure -> StatusCard(
            Icons.Rounded.Error,
            "Не удалось скачать",
            status.message,
            MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    detail: String,
    color: Color,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = lerp(MaterialTheme.colorScheme.surface, color, 0.14f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, color = color)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                action?.invoke()
            }
        }
    }
}

internal fun readNativeProgress(): NativeDownloadProgress {
    val response = JSONObject(NativeBridge.downloadProgress())
    return NativeDownloadProgress(
        phase = response.optString("phase", "preparing"),
        downloaded = response.optLong("downloaded", 0),
        total = if (response.isNull("total")) null else response.optLong("total"),
        item = response.optInt("item", 0),
        itemTotal = response.optInt("itemTotal", 0),
        itemLabel = response.optString("itemLabel", ""),
    )
}

internal fun progressDescription(progress: NativeDownloadProgress): String {
    val phase = when (progress.phase) {
        "preparing" -> "Получаю информацию о треке…"
        "connecting" -> "Подключаюсь к серверу…"
        "retrying" -> "Повторяю подключение…"
        "downloading" -> "Скачиваю аудио"
        "normalizing" -> "Меняю контейнер без перекодирования…"
        "transcoding_mp3" -> "Конвертирую в MP3…"
        "finalizing" -> "Сохраняю файл…"
        "artwork" -> "Загружаю обложку…"
        "metadata" -> "Записываю метаданные и обложку…"
        "verifying" -> "Проверяю аудиофайл…"
        "publishing" -> "Добавляю файлы в Music/Ya Music…"
        else -> "Обрабатываю трек…"
    }
    val item = if (progress.itemTotal > 1 && progress.item > 0) {
        "Трек ${progress.item} из ${progress.itemTotal}" +
            progress.itemLabel.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty() + "\n"
    } else {
        ""
    }
    if (progress.phase != "downloading" || progress.downloaded <= 0) return item + phase
    val downloaded = formatBytes(progress.downloaded)
    return progress.total
        ?.takeIf { it > 0 }
        ?.let { "$item$phase · $downloaded / ${formatBytes(it)}" }
        ?: "$item$phase · $downloaded"
}

private fun formatBytes(bytes: Long): String {
    val mebibytes = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mebibytes >= 1) {
        String.format(Locale.getDefault(), "%.1f МБ", mebibytes)
    } else {
        String.format(Locale.getDefault(), "%.0f КБ", bytes / 1024.0)
    }
}

@Composable
private fun FeaturePill(icon: ImageVector, text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFFFCC00),
    onPrimary = Color(0xFF181400),
    primaryContainer = Color(0xFFFFE680),
    onPrimaryContainer = Color(0xFF251F00),
    secondary = Color(0xFF675F3A),
    tertiary = Color(0xFF4E6355),
    background = Color(0xFFFFF9EF),
    surface = Color(0xFFFFFCF5),
    surfaceVariant = Color(0xFFECE5D4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFCC00),
    onPrimary = Color(0xFF201B00),
    primaryContainer = Color(0xFF3D3400),
    onPrimaryContainer = Color(0xFFFFE477),
    secondary = Color(0xFFD2C89B),
    onSecondary = Color(0xFF373016),
    secondaryContainer = Color(0xFF292A22),
    onSecondaryContainer = Color(0xFFE9E7DD),
    tertiary = Color(0xFFAFCDB6),
    background = Color(0xFF0F100D),
    onBackground = Color(0xFFF2F0E7),
    surface = Color(0xFF191A16),
    onSurface = Color(0xFFF2F0E7),
    surfaceVariant = Color(0xFF292A24),
    onSurfaceVariant = Color(0xFFC9C7BD),
    outline = Color(0xFF918F86),
)

@Composable
internal fun DownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
