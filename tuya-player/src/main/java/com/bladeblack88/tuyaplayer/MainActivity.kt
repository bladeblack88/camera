package com.bladeblack88.tuyaplayer

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var video: PlayerView
    private val cameraIp = "192.168.0.151"
    private val username = "admin"
    private val password = "12345"
    private val deviceService = "http://$cameraIp:835/onvif/device_service"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 12)
            setBackgroundColor(0xFF101418.toInt())
        }
        val heading = TextView(this).apply {
            text = "Tuya Camera Player"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }
        urlInput = EditText(this).apply {
            setText("rtsp://$cameraIp:9554/")
            setSingleLine(false)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFBBBBBB.toInt())
            hint = "RTSP-адрес (заполнится автоматически)"
            textSize = 14f
        }
        val discover = Button(this).apply { text = "Найти видеопоток через ONVIF" }
        val play = Button(this).apply { text = "▶ Подключить камеру" }
        val stop = Button(this).apply { text = "■ Остановить" }
        status = TextView(this).apply {
            text = "Камера: $cameraIp · ONVIF: 835 · RTSP: 9554"
            setTextColor(0xFFFFD166.toInt())
            textSize = 14f
        }
        video = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(heading)
        root.addView(urlInput, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(discover)
        root.addView(play)
        root.addView(stop)
        root.addView(status)
        root.addView(video)
        setContentView(root)
        discover.setOnClickListener { discoverStreamUri() }
        play.setOnClickListener { startPlayback() }
        stop.setOnClickListener {
            player?.stop()
            status.text = "Воспроизведение остановлено"
        }
    }

    private fun discoverStreamUri() {
        status.text = "Запрашиваю адрес видеопотока у камеры…"
        executor.execute {
            try {
                val capabilities = soap(deviceService, "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                    """<tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><tds:Category>All</tds:Category></tds:GetCapabilities>""")
                val mediaElement = findElement(capabilities, "Media")
                    ?: throw IllegalStateException("Камера не вернула раздел Media в GetCapabilities")
                val mediaXAddr = findElement(mediaElement, "XAddr")?.textContent?.trim()
                    ?: throw IllegalStateException("Не найден адрес ONVIF Media service")
                val profilesXml = soap(mediaXAddr, "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
                    """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>""")
                val profileNodes = profilesXml.getElementsByTagNameNS("*", "Profiles")
                val profile = (0 until profileNodes.length)
                    .mapNotNull { profileNodes.item(it) as? Element }
                    .firstOrNull { it.getElementsByTagNameNS("*", "VideoEncoderConfiguration").length > 0 }
                    ?: throw IllegalStateException("ONVIF не вернул профиль с VideoEncoderConfiguration. Получен только аудиопрофиль или камера не публикует видео через ONVIF.")
                val token = profile.getAttribute("token").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("У видеопрофиля отсутствует token")
                val uriXml = soap(mediaXAddr, "http://www.onvif.org/ver10/media/wsdl/GetStreamUri",
                    """<trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl"><trt:StreamSetup><tt:Stream xmlns:tt="http://www.onvif.org/ver10/schema">RTP-Unicast</tt:Stream><tt:Transport xmlns:tt="http://www.onvif.org/ver10/schema"><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>$token</trt:ProfileToken></trt:GetStreamUri>""")
                val rawUri = findElement(uriXml, "Uri")?.textContent?.trim()
                    ?: throw IllegalStateException("Камера не вернула RTSP URI")
                if (rawUri.contains("liveaudio", ignoreCase = true)) {
                    throw IllegalStateException("Камера снова вернула аудиопоток liveaudio, а не видео. ONVIF Media у этой камеры настроен некорректно.")
                }
                val finalUri = addCredentials(rawUri)
                runOnUiThread {
                    urlInput.setText(finalUri)
                    status.text = "Адрес видеопотока получен через ONVIF. Нажмите «Подключить камеру»."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "ONVIF: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun soap(endpoint: String, action: String, body: String): Element {
        val createdFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = createdFormat.format(Date())
        val nonce = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        val digestInput = nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(digestInput))
        val nonce64 = Base64.getEncoder().encodeToString(nonce)
        val envelope = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
<s:Header><wsse:Security s:mustUnderstand="1"><wsse:UsernameToken><wsse:Username>$username</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonce64</wsse:Nonce><wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security></s:Header>
<s:Body>$body</s:Body></s:Envelope>"""
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 7000
            doOutput = true
            setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
        }
        try {
            connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val xml = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${xml.take(250)}")
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val fault = findElement(doc.documentElement, "Fault")
            if (fault != null) throw IllegalStateException(fault.textContent.trim().take(300))
            return doc.documentElement
        } finally {
            connection.disconnect()
        }
    }

    private fun findElement(root: Element, name: String): Element? {
        if (root.localName == name || root.nodeName.substringAfter(':') == name) return root
        val nodes = root.getElementsByTagNameNS("*", name)
        if (nodes.length > 0) return nodes.item(0) as? Element
        val fallback = root.getElementsByTagName(name)
        return if (fallback.length > 0) fallback.item(0) as? Element else null
    }

    private fun addCredentials(uri: String): String {
        if (!uri.startsWith("rtsp://") || uri.removePrefix("rtsp://").substringBefore('/').contains("@")) return uri
        return uri.replaceFirst("rtsp://", "rtsp://$username:$password@")
    }

    private fun startPlayback() {
        val uri = urlInput.text.toString().trim()
        if (!uri.startsWith("rtsp://")) {
            status.text = "Сначала получите RTSP-адрес через ONVIF или введите его вручную."
            return
        }
        releasePlayer()
        status.text = "Подключение к видеопотоку…"
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        video.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                status.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "Подключение / буферизация…"
                    Player.STATE_READY -> "Видео подключено"
                    Player.STATE_ENDED -> "Поток завершён"
                    else -> status.text
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Ошибка потока: ${error.errorCodeName}. Получите RTSP URI заново через ONVIF."
            }
        })
        try {
            val source = RtspMediaSource.Factory().setTimeoutMs(15000)
                .createMediaSource(MediaItem.fromUri(uri))
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        } catch (e: Exception) {
            status.text = "Не удалось открыть адрес: ${e.message ?: "ошибка"}"
        }
    }

    private fun releasePlayer() {
        video.player = null
        player?.release()
        player = null
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
