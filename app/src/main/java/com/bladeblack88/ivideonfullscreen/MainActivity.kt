package com.bladeblack88.ivideonfullscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        private const val CAMERA_ID = "100-y4iXBgzCDn0C8Jv08JcXDr:0"
        private const val API_BASE = "https://openapi-alpha-eu01.ivideon.com"
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
    private var lastError = "Пока ошибок нет."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        showPlayer()
        if (BuildConfig.IVIDEON_API_KEY.isBlank()) {
            showError("API-ключ не добавлен при сборке приложения")
        } else {
            startLive()
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
            setPadding(8, 8, 8, 8)
        }
        val copyButton = Button(this).apply {
            text = "Скопировать ошибку"
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Ivideon error", lastError))
                Toast.makeText(this@MainActivity, "Текст ошибки скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(playerView)
        root.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(copyButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView?.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    showError("Ошибка воспроизведения: ${error.errorCodeName}: ${error.message ?: "без описания"}")
                    scheduleRetry()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) statusView?.text = ""
                    if (playbackState == Player.STATE_BUFFERING) statusView?.text = "Загрузка видео…"
                }
            })
        }
    }

    private fun showError(message: String) {
        lastError = message.replace(BuildConfig.IVIDEON_API_KEY, "[ключ скрыт]")
        statusView?.text = lastError
    }

    private fun startLive() {
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            statusView?.text = "Подключение к Ivideon…"
            try {
                val streamUrl = withContext(Dispatchers.IO) { requestLiveUrl() }
                statusView?.text = "Запуск трансляции…"
                player?.apply {
                    setMediaItem(MediaItem.fromUri(streamUrl))
                    prepare()
                    playWhenReady = true
                }
            } catch (e: Exception) {
                showError("Ошибка Ivideon: ${e.message ?: "нет ответа API"}")
                delay(RETRY_MS)
                if (!isFinishing) startLive()
            }
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = lifecycleScope.launch {
            delay(RETRY_MS)
            if (!isFinishing) startLive()
        }
    }

    private fun requestLiveUrl(): String {
        val cameraPath = android.net.Uri.encode(CAMERA_ID)
        val request = Request.Builder()
            .url("$API_BASE/cameras/$cameraPath/live_stream?format=hls&q=2&expect=body&streams=both&video_codecs=h264")
            .header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${body.take(1200)}")
            }
            val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            val url = json?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            url ?: response.request.url.toString().takeIf {
                it.contains(".m3u8") ||
                    response.header("Content-Type")?.contains("mpegurl", true) == true
            } ?: error("API не вернул URL видеопотока: ${body.take(1200)}")
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
