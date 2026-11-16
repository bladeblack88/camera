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

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var video: PlayerView

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
            setText("rtsp://admin:12345@192.168.0.151:9554/")
            setSingleLine(false)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFBBBBBB.toInt())
            hint = "Полный RTSP URL из Onvier"
            textSize = 14f
        }
        val play = Button(this).apply { text = "▶ Подключить камеру" }
        val stop = Button(this).apply { text = "■ Остановить" }
        status = TextView(this).apply {
            text = "IP: 192.168.0.151 · ONVIF: 835 · RTSP: 9554\nЕсли видео не появится, вставьте полный Stream URI из Onvier."
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
        root.addView(play)
        root.addView(stop)
        root.addView(status)
        root.addView(video)
        setContentView(root)
        play.setOnClickListener { startPlayback() }
        stop.setOnClickListener {
            player?.stop()
            status.text = "Воспроизведение остановлено"
        }
    }

    private fun startPlayback() {
        val uri = urlInput.text.toString().trim()
        if (!uri.startsWith("rtsp://")) {
            status.text = "Введите RTSP-адрес, начинающийся с rtsp://"
            return
        }
        releasePlayer()
        status.text = "Подключение к камере…"
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
                status.text = "Ошибка потока: ${error.errorCodeName}. Проверьте RTSP-путь и учётные данные."
            }
        })
        try {
            val source = RtspMediaSource.Factory().setTimeoutMs(10000)
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
}
