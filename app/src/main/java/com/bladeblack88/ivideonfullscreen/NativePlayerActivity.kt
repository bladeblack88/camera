package com.bladeblack88.ivideonfullscreen

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Experimental native AVC path. Keeps the existing WebView player intact.
 * It consumes the same Ivideon ws-fmp4 stream and feeds AVC access units to MediaCodec.
 */
class NativePlayerActivity : AppCompatActivity() {
    companion object {
        private const val CAMERA_ID = "100-y4iXBgzCDn0C8Jv08JcXDr:0"
        private const val API_BASE = "https://openapi-alpha-eu01.ivideon.com"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var decoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private var surfaceView: SurfaceView? = null
    private var status: TextView? = null
    private var selectedName: String? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var started = false
    private var waitingForKeyFrame = true
    private var frameIndex = 0L
    private var initTrackId: Long? = null
    private var currentTrackId: Long? = null
    private val codecLock = Any()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff000000.toInt())
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val c2 = Button(this).apply { text = "c2.mtk AVC" }
        val omx = Button(this).apply { text = "OMX.MTK AVC" }
        c2.setOnClickListener { chooseDecoder("c2.mtk.avc.decoder") }
        omx.setOnClickListener { chooseDecoder("OMX.MTK.VIDEO.DECODER.AVC") }
        controls.addView(c2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(omx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        status = TextView(this).apply {
            setTextColor(0xffffffff.toInt())
            textSize = 13f
            text = "Нативный MediaCodec: подключение…"
            setPadding(10, 8, 10, 8)
        }
        root.addView(status)
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        surfaceView?.holder?.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                decoderSurface = holder.surface
                maybeStartDecoder()
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                decoderSurface = null
                stopDecoder()
            }
        })
        chooseDecoder("c2.mtk.avc.decoder")
        lifecycleScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    val token = cloneApiKeyAccess()
                    requestLiveStreamUrl(token)
                }
                connect(url)
            } catch (e: Exception) {
                show("Ошибка подключения: ${e.message}")
            }
        }
    }

    private fun chooseDecoder(name: String) {
        selectedName = name
        waitingForKeyFrame = true
        frameIndex = 0
        stopDecoder()
        show("Выбран $name. Жду SPS/PPS и ключевой кадр…")
        maybeStartDecoder()
    }

    private fun availableAvcDecoders(): List<String> = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
            .map { it.name }
    } catch (_: Exception) { emptyList() }

    private fun maybeStartDecoder() {
        val name = selectedName ?: return
        val surface = decoderSurface ?: return
        val csdSps = sps ?: return
        val csdPps = pps ?: return
        if (decoder != null) return
        if (videoWidth <= 0 || videoHeight <= 0) {
            show("SPS/PPS получены, но не удалось определить размер кадра. Декодеры: ${availableAvcDecoders().joinToString()}")
            return
        }
        try {
            val codec = MediaCodec.createByCodecName(name)
            val format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + csdSps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + csdPps))
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            show("Декодер запущен: $name, ${videoWidth}×${videoHeight}. Жду IDR…")
        } catch (e: Exception) {
            show("Не удалось запустить $name: ${e.javaClass.simpleName}: ${e.message}")
            stopDecoder()
        }
    }

    private fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                show("WebSocket подключён. Ожидаю init и видео…")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JsonParser.parseString(text).asJsonObject
                    if (message.get("type")?.asString == "stream-init") {
                        val data = message.get("data")?.asString ?: return
                        parseAvcConfig(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                        initTrackId = message.get("track_id")?.asLong
                        maybeStartDecoder()
                    }
                } catch (e: Exception) {
                    show("Ошибка stream-init: ${e.message}")
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleFragment(bytes.toByteArray())
                } catch (e: Exception) {
                    show("Ошибка MP4-фрагмента: ${e.message}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                show("WebSocket: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                show("Поток закрыт: $code $reason")
            }
        })
    }

    private fun parseAvcConfig(data: ByteArray) {
        val avcc = findBoxPayload(data, "avcC") ?: throw IllegalArgumentException("В init нет avcC")
        if (avcc.size < 7) throw IllegalArgumentException("Короткий avcC")
        var p = 6
        val spsCount = avcc[5].toInt() and 0x1f
        var foundSps: ByteArray? = null
        repeat(spsCount) {
            if (p + 2 > avcc.size) return@repeat
            val n = u16(avcc, p); p += 2
            if (p + n <= avcc.size) {
                if (foundSps == null) foundSps = avcc.copyOfRange(p, p + n)
                p += n
            }
        }
        if (p < avcc.size) {
            val ppsCount = avcc[p++].toInt() and 0xff
            var foundPps: ByteArray? = null
            repeat(ppsCount) {
                if (p + 2 > avcc.size) return@repeat
                val n = u16(avcc, p); p += 2
                if (p + n <= avcc.size) {
                    if (foundPps == null) foundPps = avcc.copyOfRange(p, p + n)
                    p += n
                }
            }
            sps = foundSps
            pps = foundPps
        }
        val dimensions = foundSps?.let { parseSpsDimensions(it) }
        videoWidth = dimensions?.first ?: 0
        videoHeight = dimensions?.second ?: 0
        show("AVC init: SPS/PPS ${if (sps != null && pps != null) "получены" else "неполные"}, размер ${videoWidth}×${videoHeight}.")
    }

    private fun handleFragment(data: ByteArray) {
        val fragment = parseFragment(data) ?: return
        currentTrackId = fragment.trackId
        val videoTrack = initTrackId
        if (videoTrack != null && fragment.trackId != videoTrack) {
            show("Пропущен track_ID=${fragment.trackId}; видеодорожка init track_ID=$videoTrack")
            return
        }
        if (fragment.samples.isEmpty()) return
        var decoded = 0
        for (sample in fragment.samples) {
            val nals = splitLengthPrefixed(sample) ?: continue
            val isIdr = nals.any { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 5 }
            if (waitingForKeyFrame && !isIdr) continue
            if (isIdr) waitingForKeyFrame = false
            maybeStartDecoder()
            val codec = decoder ?: continue
            val accessUnit = nals.fold(ByteArray(0)) { acc, nal -> acc + START_CODE + nal }
            if (accessUnit.isEmpty()) continue
            val pts = frameIndex * 33_333L
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex) ?: continue
                input.clear()
                if (accessUnit.size > input.remaining()) {
                    show("Кадр больше входного буфера MediaCodec: ${accessUnit.size} bytes")
                    continue
                }
                input.put(accessUnit)
                codec.queueInputBuffer(inputIndex, 0, accessUnit.size, pts,
                    if (isIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                frameIndex++
                decoded++
            }
            drainOutput(codec)
        }
        if (decoded > 0) show("Получено кадров: $frameIndex; decoder=${selectedName}; fragment track_ID=${fragment.trackId}; init track_ID=${initTrackId}")
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index >= 0) codec.releaseOutputBuffer(index, true)
            else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                show("Формат декодера: ${codec.outputFormat}")
            } else break
        }
    }

    private data class ParsedFragment(val trackId: Long, val samples: List<ByteArray>)

    private fun parseFragment(data: ByteArray): ParsedFragment? {
        var moofStart = -1
        var moofEnd = -1
        var mdatStart = -1
        var mdatEnd = -1
        var trackId = -1L
        var sampleSizes = emptyList<Int>()
        var p = 0
        while (p + 8 <= data.size) {
            val size = u32(data, p)
            val type = fourcc(data, p + 4)
            if (size < 8 || p + size > data.size) break
            if (type == "moof") { moofStart = p; moofEnd = p + size }
            if (type == "mdat") { mdatStart = p + 8; mdatEnd = p + size }
            p += size
        }
        if (moofStart < 0 || mdatStart < 0 || mdatEnd < mdatStart) return null
        p = moofStart + 8
        while (p + 8 <= moofEnd) {
            val size = u32(data, p)
            val type = fourcc(data, p + 4)
            if (size < 8 || p + size > moofEnd) break
            if (type == "traf") {
                var q = p + 8
                while (q + 8 <= p + size) {
                    val n = u32(data, q)
                    val t = fourcc(data, q + 4)
                    if (n < 8 || q + n > p + size) break
                    if (t == "tfhd" && n >= 16) trackId = u32(data, q + 12).toLong()
                    if (t == "trun" && n >= 16) {
                        val flags = u24(data, q + 9)
                        val count = u32(data, q + 12)
                        var r = q + 16
                        if ((flags and 1) != 0) r += 4
                        if ((flags and 4) != 0) r += 4
                        val sizes = ArrayList<Int>()
                        repeat(count.coerceAtMost(10000)) {
                            if ((flags and 0x100) != 0) r += 4
                            if ((flags and 0x200) != 0) {
                                if (r + 4 > q + n) return null
                                sizes.add(u32(data, r)); r += 4
                            } else return null
                            if ((flags and 0x400) != 0) r += 4
                            if ((flags and 0x800) != 0) r += 4
                            if (r > q + n) return null
                        }
                        sampleSizes = sizes
                    }
                    q += n
                }
            }
            p += size
        }
        if (trackId < 0 || sampleSizes.isEmpty()) return null
        val total = sampleSizes.sumOf { it.toLong() }
        if (total != (mdatEnd - mdatStart).toLong()) return null
        var offset = mdatStart
        val samples = ArrayList<ByteArray>(sampleSizes.size)
        for (size in sampleSizes) {
            if (size < 0 || offset + size > mdatEnd) return null
            samples.add(data.copyOfRange(offset, offset + size))
            offset += size
        }
        return ParsedFragment(trackId, samples)
    }

    private fun splitLengthPrefixed(sample: ByteArray): List<ByteArray>? {
        val result = ArrayList<ByteArray>()
        var p = 0
        while (p + 4 <= sample.size) {
            val n = u32(sample, p)
            p += 4
            if (n <= 0 || p + n > sample.size) return null
            result.add(sample.copyOfRange(p, p + n))
            p += n
        }
        return if (p == sample.size && result.isNotEmpty()) result else null
    }

    private fun findBoxPayload(data: ByteArray, wanted: String): ByteArray? {
        var p = 0
        while (p + 8 <= data.size) {
            val n = u32(data, p)
            if (n < 8 || p + n > data.size) break
            if (fourcc(data, p + 4) == wanted) return data.copyOfRange(p + 8, p + n)
            p += n
        }
        // avcC is nested in stsd/avc1, so scan for its marker as a fallback.
        for (i in 4 until data.size - 8) {
            if (fourcc(data, i) == wanted) {
                val n = u32(data, i - 4)
                if (n >= 8 && i - 4 + n <= data.size) return data.copyOfRange(i + 4, i - 4 + n)
            }
        }
        return null
    }

    private class BitReader(private val bytes: ByteArray) {
        private var bit = 0
        fun read(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or readBit() }; return v }
        private fun readBit(): Int {
            if (bit >= bytes.size * 8) return 0
            val v = (bytes[bit / 8].toInt() shr (7 - bit % 8)) and 1
            bit++; return v
        }
        fun ue(): Int { var zeros = 0; while (readBit() == 0 && zeros < 31) zeros++; return (1 shl zeros) - 1 + if (zeros == 0) 0 else read(zeros) }
        fun se(): Int { val x = ue(); return if (x % 2 == 0) -(x / 2) else (x + 1) / 2 }
    }

    private fun parseSpsDimensions(nal: ByteArray): Pair<Int, Int>? = try {
        val rbsp = ArrayList<Byte>()
        var zeros = 0
        for (i in 1 until nal.size) {
            val b = nal[i]
            if (zeros >= 2 && b.toInt() == 3) { zeros = 0; continue }
            rbsp.add(b)
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        val bits = BitReader(rbsp.toByteArray())
        val profile = bits.read(8); bits.read(8); bits.read(8); bits.ue()
        var chroma = 1
        if (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue()
            if (chroma == 3) bits.read(1)
            bits.ue(); bits.ue(); bits.read(1)
            if (bits.read(1) == 1) {
                val count = if (chroma == 3) 12 else 8
                repeat(count) { if (bits.read(1) == 1) skipScalingList(bits, if (it < 6) 16 else 64) }
            }
        }
        bits.ue()
        val poc = bits.ue()
        if (poc == 0) bits.ue() else if (poc == 1) {
            bits.read(1); bits.se(); bits.se()
            repeat(bits.ue().coerceAtMost(255)) { bits.se() }
        }
        bits.ue(); bits.read(1)
        val widthMbs = bits.ue() + 1
        val heightMap = bits.ue() + 1
        val frameOnly = bits.read(1)
        if (frameOnly == 0) bits.read(1)
        bits.read(1)
        val crop = bits.read(1)
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (crop == 1) { left = bits.ue(); right = bits.ue(); top = bits.ue(); bottom = bits.ue() }
        val subWidth = if (chroma == 1 || chroma == 2) 2 else 1
        val subHeight = if (chroma == 1) 2 else 1
        val cropX = if (chroma == 0) 1 else subWidth
        val cropY = if (chroma == 0) 2 - frameOnly else subHeight * (2 - frameOnly)
        val width = widthMbs * 16 - (left + right) * cropX
        val height = heightMap * 16 * (2 - frameOnly) - (top + bottom) * cropY
        if (width > 0 && height > 0) width to height else null
    } catch (_: Exception) { null }

    private fun skipScalingList(bits: BitReader, size: Int) {
        var last = 8; var next = 8
        repeat(size) {
            if (next != 0) next = (last + bits.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
    private fun u24(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 16) or ((b[p + 1].toInt() and 255) shl 8) or (b[p + 2].toInt() and 255)
    private fun u32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 255) shl 24) or ((b[p + 1].toInt() and 255) shl 16) or
            ((b[p + 2].toInt() and 255) shl 8) or (b[p + 3].toInt() and 255)
    private fun fourcc(b: ByteArray, p: Int) = String(b, p, 4, Charsets.US_ASCII)

    private fun cloneApiKeyAccess(): String {
        val findUrl = "$API_BASE/api_keys".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("op", "FIND").build()
        val find = Request.Builder().url(findUrl)
            .header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val findRaw = http.newCall(find).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("APIKey FIND HTTP ${it.code}")
            raw
        }
        val items = JsonParser.parseString(findRaw).asJsonObject.getAsJsonObject("result").getAsJsonArray("items")
        val keyId = (0 until items.size()).asSequence().map { items[it] }
            .firstOrNull { it.isJsonObject && it.asJsonObject.has("id") }?.asJsonObject?.get("id")?.asString
            ?: error("API key не найден")
        val cloneUrl = "$API_BASE/api_keys/${android.net.Uri.encode(keyId)}".toHttpUrlOrNull()!!
            .newBuilder().addQueryParameter("op", "CLONE").build()
        val clone = Request.Builder().url(cloneUrl)
            .header("Authorization", "Bearer ${BuildConfig.IVIDEON_API_KEY}")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val cloneRaw = http.newCall(clone).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("APIKey CLONE HTTP ${it.code}")
            raw
        }
        val root = JsonParser.parseString(cloneRaw)
        fun findToken(el: com.google.gson.JsonElement): String? {
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                for ((_, v) in obj.entrySet()) {
                    if (v.isJsonPrimitive && v.asString.startsWith("xtok-")) return v.asString
                    findToken(v)?.let { return it }
                }
            } else if (el.isJsonArray) {
                el.asJsonArray.forEach { findToken(it)?.let { token -> return token } }
            }
            return null
        }
        return findToken(root) ?: error("Временный токен не найден")
    }

    private fun requestLiveStreamUrl(token: String): String {
        val url = "$API_BASE/cameras/$CAMERA_ID/live_stream".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("op", "GET").addQueryParameter("access_token", token)
            .addQueryParameter("q", "2").addQueryParameter("video_codecs", "h264")
            .addQueryParameter("streams", "image")
            .addQueryParameter("format", "ws-fmp4").build()
        val request = Request.Builder().url(url).get().build()
        val raw = http.newCall(request).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("live_stream HTTP ${it.code}")
            body
        }
        return JsonParser.parseString(raw).asJsonObject.getAsJsonObject("result").get("url").asString
    }

    private fun stopDecoder() {
        synchronized(codecLock) {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
        }
    }

    private fun show(message: String) {
        runOnUiThread { status?.text = message }
    }

    override fun onDestroy() {
        socket?.close(1000, "activity closed")
        socket = null
        stopDecoder()
        http.dispatcher.cancelAll()
        super.onDestroy()
    }
}
