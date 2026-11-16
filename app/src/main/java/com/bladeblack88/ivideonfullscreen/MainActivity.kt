package com.bladeblack88.ivideonfullscreen

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        private const val CAMERA_ID = "100-y4iXBgzCDn0C8Jv08JcXDr:0"
        private const val DEFAULT_OAUTH_HOST = "https://openapi-alpha.ivideon.com"
        private const val RETRY_MS = 7000L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var player: ExoPlayer? = null
    private var retryJob: Job? = null
    private var statusView: TextView? = null
    private var playerView: PlayerView? = null

    private data class Config(
        val oauthHost: String,
        val clientId: String,
        val clientSecret: String,
        val username: String,
        val password: String
    )

    private fun prefs() = EncryptedSharedPreferences.create(
        this,
        "ivideon_secure",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        val p = prefs()
        val config = Config(
            p.getString("oauth_host", DEFAULT_OAUTH_HOST) ?: DEFAULT_OAUTH_HOST,
            p.getString("client_id", "") ?: "",
            p.getString("client_secret", "") ?: "",
            p.getString("username", "") ?: "",
            p.getString("password", "") ?: ""
        )
        if (config.clientId.isBlank() || config.clientSecret.isBlank() ||
            config.username.isBlank() || config.password.isBlank()) {
            showSetup(config)
        } else {
            showPlayer()
            startLive(config)
        }
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun showSetup(existing: Config) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(0xFF101418.toInt())
        }
        scroll.addView(layout)
        fun label(text: String) {
            layout.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(0xFFEAF0F6.toInt())
                setPadding(0, 12, 0, 5)
            })
        }
        fun field(hint: String, value: String, secret: Boolean = false): EditText {
            val edit = EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
                setText(value)
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF9AA6B2.toInt())
                if (secret) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            layout.addView(edit)
            return edit
        }

        layout.addView(TextView(this).apply {
            text = "Первичная настройка Ivideon"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        })
        layout.addView(TextView(this).apply {
            text = "Нужны OAuth-реквизиты интеграции Ivideon и данные учётной записи. Они сохраняются в зашифрованном локальном хранилище телефона."
            textSize = 14f
            setTextColor(0xFFB8C4CE.toInt())
            setPadding(0, 8, 0, 8)
        })
        label("OAuth host")
        val host = field("https://openapi-alpha.ivideon.com", existing.oauthHost)
        label("Client ID")
        val clientId = field("Client ID", existing.clientId)
        label("Client secret")
        val clientSecret = field("Client secret", existing.clientSecret, true)
        label("Логин Ivideon (email)")
        val username = field("Email", existing.username)
        label("Пароль Ivideon")
        val password = field("Пароль", existing.password, true)

        val save = Button(this).apply {
            text = "Сохранить и подключиться"
            setOnClickListener {
                val h = host.text.toString().trim().trimEnd('/')
                val id = clientId.text.toString().trim()
                val secret = clientSecret.text.toString()
                val user = username.text.toString().trim()
                val pass = password.text.toString()
                if (h.isBlank() || id.isBlank() || secret.isBlank() || user.isBlank() || pass.isBlank()) {
                    statusView?.text = "Заполните все поля"
                    return@setOnClickListener
                }
                prefs().edit()
                    .putString("oauth_host", h)
                    .putString("client_id", id)
                    .putString("client_secret", secret)
                    .putString("username", user)
                    .putString("password", pass)
                    .apply()
                showPlayer()
                startLive(Config(h, id, secret, user, pass))
            }
        }
        layout.addView(save)
        setContentView(scroll)
    }

    private fun showPlayer() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }
        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        statusView = TextView(this).apply {
            text = "Подключение к камере…"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 4, 8, 4)
        }
        root.addView(playerView)
        root.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView?.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    statusView?.text = "Связь потеряна. Переподключение…"
                    scheduleRetry()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) statusView?.text = ""
                    if (playbackState == Player.STATE_BUFFERING) statusView?.text = "Загрузка видео…"
                }
            })
        }
    }

    private fun startLive(config: Config) {
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            statusView?.text = "Авторизация Ivideon…"
            try {
                val streamUrl = withContext(Dispatchers.IO) { requestLiveUrl(config) }
                statusView?.text = "Запуск трансляции…"
                player?.apply {
                    setMediaItem(MediaItem.fromUri(streamUrl))
                    prepare()
                    playWhenReady = true
                }
            } catch (e: Exception) {
                statusView?.text = "Не удалось подключиться: ${e.message ?: "ошибка API"}. Повтор…"
                delay(RETRY_MS)
                if (!isFinishing) startLive(config)
            }
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val p = prefs()
        val c = Config(
            p.getString("oauth_host", DEFAULT_OAUTH_HOST) ?: DEFAULT_OAUTH_HOST,
            p.getString("client_id", "") ?: "",
            p.getString("client_secret", "") ?: "",
            p.getString("username", "") ?: "",
            p.getString("password", "") ?: ""
        )
        retryJob = lifecycleScope.launch {
            delay(RETRY_MS)
            if (!isFinishing) startLive(c)
        }
    }

    private fun requestLiveUrl(config: Config): String {
        val basicRaw = "${config.clientId}:${config.clientSecret}"
        val basic = Base64.getEncoder().encodeToString(basicRaw.toByteArray(Charsets.UTF_8))
        val tokenRequest = Request.Builder()
            .url("${config.oauthHost}/auth/oauth/token")
            .header("Authorization", "Basic $basic")
            .header("Cache-Control", "no-cache")
            .post(
                FormBody.Builder()
                    .add("grant_type", "password")
                    .add("username", config.username)
                    .add("password", config.password)
                    .build()
            )
            .build()

        val tokenJson = http.newCall(tokenRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OAuth HTTP ${response.code}: ${body.take(180)}")
            JsonParser.parseString(body).asJsonObject
        }
        val accessToken = tokenJson.get("access_token")?.asString
            ?: error("OAuth не вернул access_token")
        val apiHost = tokenJson.get("api_host")?.asString
            ?: config.oauthHost.removePrefix("https://").removePrefix("http://")
        val apiBase = if (apiHost.startsWith("http")) apiHost else "https://$apiHost"
        val cameraPath = android.net.Uri.encode(CAMERA_ID)
        val streamRequest = Request.Builder()
            .url("$apiBase/cameras/$cameraPath/live_stream?format=hls&q=2&expect=body&streams=both&video_codecs=h264")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return http.newCall(streamRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Live API HTTP ${response.code}: ${body.take(220)}")
            val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            val url = json?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            url ?: response.request.url.toString().takeIf {
                it.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl", true) == true
            } ?: error("API не вернул URL потока: ${body.take(180)}")
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
    }

    override fun onDestroy() {
        retryJob?.cancel()
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
