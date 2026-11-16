package com.bladeblack88.ivideonfullscreen

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        .build()

    private var webView: WebView? = null
    private var retryJob: Job? = null
    private var pageReady = false
    private var pendingStreamUrl: String? = null
    private var stopping = false

    private data class CloneAccess(val token: String)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        val player = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    pendingStreamUrl?.let { connectInPage(it) }
                }
            }
            addJavascriptInterface(PlayerBridge(), "Android")
        }
        webView = player
        setContentView(player)

        if (BuildConfig.IVIDEON_API_KEY.isBlank()) {
            showStatus("API-ключ не добавлен при сборке приложения")
        } else {
            player.loadUrl("file:///android_asset/player.html")
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

    private fun startPreview() {
        if (stopping || isFinishing) return
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            try {
                showStatus("Подключение к Ivideon…")
                val streamUrl = withContext(Dispatchers.IO) {
                    val access = cloneApiKeyAccess()
                    requestLiveStreamUrl(access.token)
                }
                pendingStreamUrl = streamUrl
                if (pageReady) connectInPage(streamUrl)
            } catch (e: Exception) {
                showStatus("Ошибка подключения: " + (e.message ?: "нет ответа Ivideon"))
                delay(RETRY_MS)
                if (!stopping && !isFinishing) startPreview()
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
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("APIKey FIND HTTP ${response.code}: ${sanitize(raw)}")
            raw
        }
        val findJson = JsonParser.parseString(findRaw).asJsonObject
        val items = findJson.getAsJsonObject("result")?.getAsJsonArray("items")
            ?: error("APIKey FIND: в ответе нет списка ключей")
        val item = (0 until items.size()).asSequence().map { items[it] }
            .firstOrNull { it.isJsonObject && it.asJsonObject.has("id") }?.asJsonObject
            ?: error("APIKey FIND: ключ не найден")
        val keyId = item.get("id").asString

        val cloneUrl = "$API_BASE/api_keys/${Uri.encode(keyId)}".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("op", "CLONE").build()
        val cloneRequest = Request.Builder().url(cloneUrl)
            .header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
            .header("Accept", "application/json")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val cloneRaw = http.newCall(cloneRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("APIKey CLONE HTTP ${response.code}: ${sanitize(raw)}")
            raw
        }
        val token = findTokenValue(JsonParser.parseString(cloneRaw))
            ?: error("APIKey CLONE: временный токен не найден")
        return CloneAccess(token)
    }

    private fun requestLiveStreamUrl(token: String): String {
        val url = "$API_BASE/cameras/$CAMERA_ID/live_stream".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("op", "GET")
            .addQueryParameter("access_token", token)
            .addQueryParameter("q", "2")
            .addQueryParameter("video_codecs", "h264")
            .addQueryParameter("audio_codecs", "aac,mp3,pcma,pcmu,none")
            .addQueryParameter("format", "ws-fmp4")
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get().build()
        val raw = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("live_stream HTTP ${response.code}: ${sanitize(body)}")
            body
        }
        val json = JsonParser.parseString(raw).asJsonObject
        if (json.has("success") && !json.get("success").asBoolean) {
            error("Ivideon не создал поток: ${json.get("message")?.asString ?: "ошибка API"}")
        }
        val result = json.getAsJsonObject("result")
        val streamUrl = result?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            ?: error("Ivideon не вернул адрес видеопотока")
        if (!streamUrl.startsWith("wss://")) error("Ivideon вернул некорректный WebSocket URL")
        return streamUrl
    }

    private fun findTokenValue(element: JsonElement): String? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            for (name in listOf("access_token", "token", "id")) {
                val value = obj.get(name)
                if (value != null && value.isJsonPrimitive && !value.isJsonNull) {
                    val candidate = value.asString
                    if (candidate.startsWith("xtok-")) return candidate
                }
            }
            for ((_, value) in obj.entrySet()) {
                findTokenValue(value)?.let { return it }
            }
        } else if (element.isJsonArray) {
            for (value in element.asJsonArray) findTokenValue(value)?.let { return it }
        }
        return null
    }

    private fun sanitize(value: String): String {
        return value.replace(BuildConfig.IVIDEON_API_KEY, "[ключ скрыт]")
            .replace(Regex("xtok-[A-Za-z0-9_-]+"), "[токен скрыт]")
            .take(700)
    }

    private fun connectInPage(url: String) {
        val quoted = com.google.gson.Gson().toJson(url)
        webView?.evaluateJavascript("window.startIvideonStream($quoted);", null)
        pendingStreamUrl = null
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            val quoted = com.google.gson.Gson().toJson(message)
            webView?.evaluateJavascript("document.getElementById('status').textContent=$quoted;", null)
        }
    }

    private fun scheduleRetry() {
        if (stopping || isFinishing || retryJob?.isActive == true) return
        retryJob = lifecycleScope.launch {
            delay(RETRY_MS)
            if (!stopping && !isFinishing) startPreview()
        }
    }

    private inner class PlayerBridge {
        @JavascriptInterface
        fun onStreamClosed() {
            runOnUiThread { scheduleRetry() }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
    }

    override fun onDestroy() {
        stopping = true
        retryJob?.cancel()
        webView?.evaluateJavascript("window.stopIvideonStream && window.stopIvideonStream();", null)
        webView?.removeJavascriptInterface("Android")
        webView?.destroy()
        webView = null
        http.dispatcher.cancelAll()
        super.onDestroy()
    }
}
