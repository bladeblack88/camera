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

    private data class CloneAccess(val token: String, val apiHost: String, val api5Host: String, val api4Host: String)

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
            text = "Подключение к Ivideon…"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 8, 8, 8)
        }
        root.addView(imageView)
        root.addView(statusView, LinearLayout.LayoutParams(
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
    private fun runClonePreview(useApiHost: Boolean) {
        retryJob?.cancel()
        previewSocket?.cancel()
        previewSocket = null
        stopping = false
        statusView?.text = "Получаю APIKey и создаю временный AccessToken…"
        retryJob = lifecycleScope.launch {
            try {
                val access = withContext(Dispatchers.IO) { cloneApiKeyAccess() }
                val selectedHost = if (useApiHost) access.apiHost else access.api5Host
                statusView?.text = "CLONE готов. STREAM через $selectedHost…"
                val wsUrl = withContext(Dispatchers.IO) { requestPreviewUrl(access.token, selectedHost) }
                connectPreviewSocket(wsUrl, access.token)
            } catch (e: Exception) {
                showError("CLONE / preview: ${e.message ?: "нет ответа Ivideon"}")
            }
        }
    }

    private fun cloneApiKeyAccess(): CloneAccess {
        val findUrl = "$API_BASE/api_keys".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("op", "FIND").build()
        val findRequest = Request.Builder().url(findUrl)
            .header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
            .header("Accept", "application/json")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val findRaw = http.newCall(findRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("FIND HTTP ${response.code}: ${sanitizeApiText(text)}")
            text
        }
        val findJson = JsonParser.parseString(findRaw).asJsonObject
        val items = findJson.getAsJsonObject("result")?.getAsJsonArray("items")
            ?: error("FIND: в ответе нет result.items")
        val item = (0 until items.size()).asSequence().map { items[it] }
            .firstOrNull { it.isJsonObject && it.asJsonObject.has("id") }?.asJsonObject
            ?: error("FIND: APIKey не найден")
        val keyId = item.get("id").asString
        val cloneUrl = "$API_BASE/api_keys/${Uri.encode(keyId)}".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("op", "CLONE").build()
        val cloneRequest = Request.Builder().url(cloneUrl)
            .header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
            .header("Accept", "application/json")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val cloneRaw = http.newCall(cloneRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("CLONE HTTP ${response.code}: ${sanitizeApiText(text)}")
            text
        }
        val cloneJson = JsonParser.parseString(cloneRaw)
        val token = findTokenValue(cloneJson)
            ?: error("CLONE: AccessToken не найден. Ответ: ${sanitizeApiText(cloneRaw)}")
        val result = cloneJson.asJsonObject.getAsJsonObject("result")
            ?: error("CLONE: в ответе нет result")
        fun host(name: String, fallback: String): String {
            val raw = result.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            val value = (raw ?: fallback).trim().trimEnd('/')
            return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        }
        return CloneAccess(token, host("api_host", API_BASE), host("api5_host", API_BASE), host("api4_host", API_BASE))
    }

    private fun runCloneSnapshot() {
        retryJob?.cancel()
        previewSocket?.cancel()
        previewSocket = null
        stopping = false
        statusView?.text = "CLONE: получаю временный токен для GET snapshot…"
        retryJob = lifecycleScope.launch {
            try {
                val access = withContext(Dispatchers.IO) { cloneApiKeyAccess() }
                val hosts = listOf(access.api5Host, access.apiHost, access.api4Host).distinct()
                var lastResult = "Snapshot не получен"
                var bitmap: android.graphics.Bitmap? = null
                for (host in hosts) {
                    val result = withContext(Dispatchers.IO) { requestJpegSnapshot(access.token, host) }
                    lastResult = host + ": " + result.first
                    if (result.second != null) {
                        bitmap = result.second
                        break
                    }
                }
                if (bitmap != null) {
                    imageView?.setImageBitmap(bitmap)
                    statusView?.text = "JPEG snapshot получен. " + lastResult
                } else {
                    showError("GET snapshot: " + lastResult)
                }
            } catch (e: Exception) {
                showError("CLONE / GET snapshot: " + (e.message ?: "нет ответа Ivideon"))
            }
        }
    }

    private fun requestJpegSnapshot(token: String, apiHost: String): Pair<String, android.graphics.Bitmap?> {
        // Keep the camera id colon literal: Ivideon's endpoint rejects an encoded %3A path.
        val url = "$apiHost/cameras/$CAMERA_ID/live_preview?op=GET&q=$PREVIEW_QUALITY".toHttpUrlOrNull()
            ?: error("Некорректный адрес snapshot API: $apiHost")
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "image/jpeg")
            .header("Cache-Control", "no-cache")
            .get().build()
        return http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: byteArrayOf()
            if (!response.isSuccessful) {
                Pair("HTTP " + response.code + ": " + sanitizeApiText(bytes.toString(Charsets.UTF_8)), null)
            } else {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                Pair("HTTP " + response.code + ", Content-Type=" + (response.header("Content-Type") ?: "не указан") + ", bytes=" + bytes.size, bitmap)
            }
        }
    }

    private fun findTokenValue(element: com.google.gson.JsonElement): String? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            for (name in listOf("access_token", "token")) {
                val value = obj.get(name)
                if (value != null && value.isJsonPrimitive && !value.isJsonNull) {
                    val candidate = value.asString
                    if (candidate.isNotBlank() && candidate != "null") return candidate
                }
            }
            // Ivideon CLONE returns the temporary AccessToken in result.id (xtok-...).
            val idValue = obj.get("id")
            if (idValue != null && idValue.isJsonPrimitive && !idValue.isJsonNull) {
                val candidate = idValue.asString
                if (candidate.startsWith("xtok-")) return candidate
            }
            for ((_, value) in obj.entrySet()) {
                val found = findTokenValue(value)
                if (found != null) return found
            }
        } else if (element.isJsonArray) {
            for (value in element.asJsonArray) {
                val found = findTokenValue(value)
                if (found != null) return found
            }
        }
        return null
    }

    private fun sanitizeApiText(value: String): String {
        return value.replace(BuildConfig.IVIDEON_API_KEY, "[ключ скрыт]")
            .replace(Regex("\\\"(access_token|token|refresh_token)\\\"\\s*:\\s*\\\"[^\\\"]*\\\""),
                "\\\"$1\\\":\\\"[токен скрыт]\\\"")
            .replace(Regex("\\\"id\\\"\\s*:\\s*\\\"xtok-[^\\\"]*\\\""),
                "\\\"id\\\":\\\"[временный токен скрыт]\\\"")
            .take(1000)
    }

    private fun connectPreviewSocket(wsUrl: String, token: String) {
        val socketRequest = Request.Builder().url(wsUrl)
            .header("Authorization", "Bearer $token").build()
        statusView?.text = "Временный токен получен. Подключаю WebSocket…"
        previewSocket = wsClient.newWebSocket(socketRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { statusView?.text = "WebSocket подключён, жду кадр…" }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size)
                if (bitmap != null) runOnUiThread { imageView?.setImageBitmap(bitmap); statusView?.text = "" }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread { showError("WebSocket: ${text.take(400)}") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopping && !isFinishing) scheduleRetry("Канал закрыт: $code")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code}" } ?: (t.message ?: "нет описания")
                if (!stopping && !isFinishing) scheduleRetry("WebSocket: $detail")
            }
        })
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
            statusView?.text = "Подключение к камере Ivideon…"
            try {
                val access = withContext(Dispatchers.IO) { cloneApiKeyAccess() }
                statusView?.text = "Камера найдена. Получаю изображение…"
                while (!stopping && !isFinishing) {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            requestJpegSnapshot(access.token, API_BASE)
                        }
                        val bitmap = result.second
                        if (bitmap != null) {
                            imageView?.setImageBitmap(bitmap)
                            statusView?.text = ""
                        } else {
                            statusView?.text = "Кадр не получен: " + result.first
                        }
                    } catch (e: Exception) {
                        statusView?.text = "Ошибка связи: " + (e.message ?: "нет ответа")
                    }
                    delay(1000L)
                }
            } catch (e: Exception) {
                showError("Не удалось подключиться к Ivideon: " + (e.message ?: "нет ответа API"))
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

    private fun requestPreviewUrl(authToken: String = BuildConfig.IVIDEON_API_KEY, apiHost: String = API_BASE): String {
        val cameraPath = Uri.encode(CAMERA_ID)
        val body = """{"fps":$PREVIEW_FPS,"q":$PREVIEW_QUALITY}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val useApiQueryToken = authModeIndex == 2 || authModeIndex == 3
        val apiUrlBuilder = "$apiHost/cameras/$cameraPath/live_preview".toHttpUrlOrNull()?.newBuilder()
            ?: error("Некорректный адрес Ivideon API")
        apiUrlBuilder.addQueryParameter("op", "STREAM")
        if (useApiQueryToken) {
            apiUrlBuilder.addQueryParameter("access_token", authToken)
        }
        val requestBuilder = Request.Builder()
            .url(apiUrlBuilder.build())
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
        if (!useApiQueryToken) {
            requestBuilder.header("Authorization", "Bearer $authToken")
        }
        val request = requestBuilder.post(body).build()

        return http.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${sanitizeApiText(responseBody)}")
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
