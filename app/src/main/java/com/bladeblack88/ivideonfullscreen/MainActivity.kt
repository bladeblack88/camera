package com.bladeblack88.ivideonfullscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        private const val CAMERA_ID = "100-y4iXBgzCDn0C8Jv08JcXDr:0"
        private const val API_BASE = "https://openapi-alpha-eu01.ivideon.com"
        private const val RETRY_MS = 7000L
        private const val PREVIEW_FPS = 5
        private const val PREVIEW_QUALITY = 2
        private val AUTH_MODES = listOf(
            "Bearer в заголовках API и WebSocket",
            "Bearer API + access_token в WebSocket URL",
            "access_token в API URL + Bearer WebSocket",
            "access_token в обоих URL"
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val wsClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var retryJob: Job? = null
    private var statusView: TextView? = null
    private var imageView: ImageView? = null
    private var previewSocket: WebSocket? = null
    private var lastError = "Пока ошибок нет."
    private var stopping = false
    private var authModeIndex = 0
    private var modeButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        showPreview()
        if (BuildConfig.IVIDEON_API_KEY.isBlank()) {
            showError("API-ключ не добавлен при сборке приложения")
        } else {
            startPreview()
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

    private fun showPreview() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
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
        modeButton = Button(this).apply {
            text = "Способ 1/4: ${AUTH_MODES[authModeIndex]}"
            isAllCaps = false
            setOnClickListener {
                authModeIndex = (authModeIndex + 1) % AUTH_MODES.size
                text = "Способ ${authModeIndex + 1}/${AUTH_MODES.size}: ${AUTH_MODES[authModeIndex]}"
                startPreview()
            }
        }
        val diagnosticButton = Button(this).apply {
            text = "Проверить API-ключ (APIKey FIND)"
            isAllCaps = false
            setOnClickListener { runApiKeyDiagnostic() }
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
        root.addView(imageView)
        root.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(modeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(diagnosticButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(copyButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
    }

    private fun runApiKeyDiagnostic() {
        retryJob?.cancel()
        statusView?.text = "Проверяю API-ключ через POST /api_keys?op=FIND…"
        retryJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { requestApiKeyList() }
            showError(result)
        }
    }

    private fun requestApiKeyList(): String {
        val url = "$API_BASE/api_keys".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("op", "FIND")?.build()
            ?: return "Ошибка: некорректный адрес Ivideon API"
        val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .post(body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val safe = raw.replace(BuildConfig.IVIDEON_API_KEY, "[ключ скрыт]")
                    .replace(Regex("\\\"(access_token|token|refresh_token)\\\"\\s*:\\s*\\\"[^\\\"]*\\\""), "\\\"$1\\\":\\\"[значение скрыто]\\\"")
                    .take(1200)
                "APIKey FIND: HTTP " + response.code +
                    (if (response.isSuccessful) " (успех)" else " (ошибка)") +
                    "\nОтвет Ivideon:\n" + safe
            }
        } catch (e: Exception) {
            "APIKey FIND: ошибка соединения: " + e.javaClass.simpleName + ": " + (e.message ?: "нет описания")
        }
    }
    private fun showError(message: String) {
        lastError = message
            .replace(BuildConfig.IVIDEON_API_KEY, "[ключ скрыт]")
            .take(1500)
        statusView?.text = lastError
    }

    private fun startPreview() {
        retryJob?.cancel()
        previewSocket?.cancel()
        previewSocket = null
        stopping = false
        retryJob = lifecycleScope.launch {
            statusView?.text = "Запрос предпросмотра Ivideon…"
            try {
                val wsUrl = withContext(Dispatchers.IO) { requestPreviewUrl() }
                val socketUrl = wsUrl.toHttpUrlOrNull()
                    ?: error("Некорректный WebSocket URL от Ivideon")
                val useWsQueryToken = authModeIndex == 1 || authModeIndex == 3
                val finalSocketUrl = if (useWsQueryToken) {
                    socketUrl.newBuilder()
                        .addQueryParameter("access_token", BuildConfig.IVIDEON_API_KEY)
                        .build()
                } else socketUrl
                val socketBuilder = Request.Builder().url(finalSocketUrl)
                if (!useWsQueryToken) {
                    socketBuilder.header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
                }
                val socketRequest = socketBuilder.build()
                statusView?.text = "Подключение к потоку изображений…"
                previewSocket = wsClient.newWebSocket(socketRequest, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        runOnUiThread {
                            statusView?.text = "Ожидание изображения с камеры…"
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size)
                        if (bitmap != null) {
                            runOnUiThread {
                                imageView?.setImageBitmap(bitmap)
                                statusView?.text = ""
                            }
                        } else {
                            showError("WebSocket подключён, но получен непонятный JPEG-кадр")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val safeText = text.take(500)
                        runOnUiThread {
                            showError("Сообщение WebSocket: $safeText")
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!stopping && !isFinishing) scheduleRetry("Канал предпросмотра закрыт: $code $reason")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val detail = response?.let { "HTTP ${it.code}" } ?: (t.message ?: "нет описания")
                        if (!stopping && !isFinishing) scheduleRetry("Ошибка WebSocket: $detail")
                    }
                })
            } catch (e: Exception) {
                showError("Ошибка Ivideon: ${e.message ?: "нет ответа API"}")
                delay(RETRY_MS)
                if (!isFinishing) startPreview()
            }
        }
    }

    private fun scheduleRetry(message: String) {
        if (retryJob?.isActive == true) return
        runOnUiThread { showError("$message. Повторное подключение…") }
        retryJob = lifecycleScope.launch {
            delay(RETRY_MS)
            if (!isFinishing) startPreview()
        }
    }

    private fun requestPreviewUrl(): String {
        val cameraPath = Uri.encode(CAMERA_ID)
        val body = """{"fps":$PREVIEW_FPS,"q":$PREVIEW_QUALITY}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val useApiQueryToken = authModeIndex == 2 || authModeIndex == 3
        val apiUrlBuilder = "$API_BASE/cameras/$cameraPath/live_preview".toHttpUrlOrNull()?.newBuilder()
            ?: error("Некорректный адрес Ivideon API")
        apiUrlBuilder.addQueryParameter("op", "STREAM")
        if (useApiQueryToken) {
            apiUrlBuilder.addQueryParameter("access_token", BuildConfig.IVIDEON_API_KEY)
        }
        val requestBuilder = Request.Builder()
            .url(apiUrlBuilder.build())
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
        if (!useApiQueryToken) {
            requestBuilder.header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
        }
        val request = requestBuilder.post(body).build()

        return http.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${responseBody.take(1200)}")
            }
            val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull()
            val url = json?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                ?: json?.get("data")?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            val result = url ?: error("API не вернул WebSocket URL: ${responseBody.take(1000)}")
            if (!result.startsWith("wss://") && !result.startsWith("ws://")) {
                error("API вернул URL не WebSocket: ${result.take(300)}")
            }
            result
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
    }

    override fun onDestroy() {
        stopping = true
        retryJob?.cancel()
        previewSocket?.close(1000, "Activity destroyed")
        previewSocket = null
        wsClient.dispatcher.cancelAll()
        super.onDestroy()
    }
}
