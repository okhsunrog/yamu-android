package dev.okhsunrog.yamusdownloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
    data class Success(val track: PublishedTrack) : DownloadStatus
    data class Failure(val message: String) : DownloadStatus
}

internal data class NativeDownloadProgress(
    val phase: String = "preparing",
    val downloaded: Long = 0,
    val total: Long? = null,
    val cancellable: Boolean = true,
)

internal val DownloadStatus.isBusy: Boolean
    get() = this is DownloadStatus.Downloading || this is DownloadStatus.Cancelling

@Composable
internal fun DownloaderApp(
    tokenStore: TokenStore,
    incomingLink: IncomingLink?,
) {
    var accessToken by remember { mutableStateOf(tokenStore.load()) }

    AnimatedContent(
        targetState = accessToken.isNotBlank(),
        label = "authentication-gate",
    ) { authenticated ->
        if (authenticated) {
            DownloaderScreen(
                incomingLink = incomingLink,
                onLogout = {
                    tokenStore.clear()
                    accessToken = ""
                },
            )
        } else {
            AuthScreen(
                pendingLink = incomingLink?.url,
                onAuthorized = { token ->
                    tokenStore.save(token)
                    accessToken = token
                },
            )
        }
    }
}

@Composable
private fun AuthScreen(
    pendingLink: String?,
    onAuthorized: (String) -> Unit,
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
        while (isActive) {
            val elapsed = SystemClock.elapsedRealtime() - session.startedAt
            if (elapsed >= session.expiresIn * 1_000) {
                status = AuthStatus.Failure("Код истёк. Начните вход ещё раз.")
                return@LaunchedEffect
            }
            delay(session.interval * 1_000)
            try {
                val token = withContext(Dispatchers.IO) {
                    NativeBridge.pollDeviceToken(session.deviceCode)
                }
                consecutiveErrors = 0
                if (token.isNotBlank()) {
                    onAuthorized(token)
                    return@LaunchedEffect
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
            AppHeader(subtitle = "Треки по ссылке — в лучшем качестве")

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
                            detail = "После входа трек начнёт скачиваться автоматически.",
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

    fun download(rawLink: String) {
        val normalizedLink = MainActivity.extractTrackLink(rawLink)
        if (normalizedLink == null) {
            DownloadCoordinator.reject("Вставьте ссылку на трек из Яндекс Музыки")
            return
        }
        link = normalizedLink
        DownloadCoordinator.start(context, normalizedLink)
    }

    LaunchedEffect(incomingLink?.sequence) {
        incomingLink ?: return@LaunchedEffect
        link = incomingLink.url
        download(incomingLink.url)
    }

    AppScaffold {
        Column(
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
                        text = "Вставьте ссылку на трек или отправьте её через «Поделиться».",
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
                        label = { Text("Ссылка на трек") },
                        placeholder = { Text("music.yandex.ru/album/…/track/…") },
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
                                    Text("Скачать трек", fontWeight = FontWeight.SemiBold)
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
    }
}

@Composable
private fun AppScaffold(content: @Composable () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
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
            title = "Трек сохранён",
            detail = "${status.track.location}/${status.track.displayName}",
            color = Color(0xFF2E7D32),
            action = {
                TextButton(onClick = { onShare(status.track) }) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Поделиться")
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
    )
}

internal fun progressDescription(progress: NativeDownloadProgress): String {
    val phase = when (progress.phase) {
        "preparing" -> "Получаю информацию о треке…"
        "connecting" -> "Подключаюсь к серверу…"
        "retrying" -> "Повторяю подключение…"
        "downloading" -> "Скачиваю аудио"
        "normalizing" -> "Преобразую в FLAC…"
        "finalizing" -> "Сохраняю файл…"
        "artwork" -> "Загружаю обложку…"
        "metadata" -> "Записываю метаданные и обложку…"
        "verifying" -> "Проверяю аудиофайл…"
        "publishing" -> "Добавляю трек в Music/Ya Music…"
        else -> "Обрабатываю трек…"
    }
    if (progress.phase != "downloading" || progress.downloaded <= 0) return phase
    val downloaded = formatBytes(progress.downloaded)
    return progress.total
        ?.takeIf { it > 0 }
        ?.let { "$phase · $downloaded / ${formatBytes(it)}" }
        ?: "$phase · $downloaded"
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
