package dev.okhsunrog.yamusdownloader

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {
    private lateinit var tokenStore: TokenStore
    private var incomingLink by mutableStateOf<IncomingLink?>(null)
    private var intentSequence by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBridge.initialize(applicationContext)
        enableEdgeToEdge()
        tokenStore = TokenStore(this)
        publishShareShortcut()
        if (savedInstanceState == null) handleIncomingIntent(intent)

        setContent {
            DownloaderTheme {
                DownloaderApp(
                    tokenStore = tokenStore,
                    incomingLink = incomingLink,
                    outputDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.absolutePath,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val candidate = when {
            intent?.action == Intent.ACTION_VIEW -> intent.dataString
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        extractTrackLink(candidate)?.let {
            getSystemService(ShortcutManager::class.java)
                ?.reportShortcutUsed("download-shared-track")
            intentSequence += 1
            incomingLink = IncomingLink(it, intentSequence)
        }
    }

    private fun publishShareShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = getSystemService(ShortcutManager::class.java) ?: return
        val builder = ShortcutInfo.Builder(this, "download-shared-track")
            .setShortLabel("Скачать")
            .setLongLabel("Скачать из Яндекс Музыки")
            .setIcon(Icon.createWithResource(this, android.R.drawable.stat_sys_download_done))
            .setIntent(Intent(Intent.ACTION_VIEW).setClass(this, MainActivity::class.java))
            .setCategories(setOf(SHARE_CATEGORY))
            .setRank(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setLongLived(true)
        manager.addDynamicShortcuts(listOf(builder.build()))
    }

    companion object {
        private const val SHARE_CATEGORY =
            "dev.okhsunrog.yamusdownloader.category.DOWNLOAD"
        private val YANDEX_MUSIC_LINK = Pattern.compile(
            "https://music\\.yandex\\.ru/[^\\s]+",
        )

        fun extractTrackLink(text: String?): String? {
            val matcher = YANDEX_MUSIC_LINK.matcher(text?.trim().orEmpty())
            if (!matcher.find()) return null
            return matcher.group().replace(Regex("[),.;!?]+$"), "")
        }
    }
}

internal data class IncomingLink(val url: String, val sequence: Long)
