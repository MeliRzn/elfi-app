package com.elfaris.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

class WatchCaptureService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var eglBase: EglBase? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var roomClient: WatchRoomClient? = null
    @Volatile private var stopped = false
    @Volatile private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        if (started) {
            updateNotification("Já existe uma transmissão ativa neste aparelho.")
            return START_NOT_STICKY
        }
        started = true
        isCaptureActive = true
        val includeMicrophone = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
        startForegroundCompat(includeMicrophone)
        val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        val roomUrl = intent.getStringExtra(EXTRA_ROOM_URL).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = getProjectionIntent(intent)
        if (server.isBlank() || roomUrl.isBlank() || projectionData == null || resultCode != RESULT_OK) {
            stopCapture()
            return START_NOT_STICKY
        }

        worker.execute {
            try {
                startWebRtc(server, roomUrl, name, projectionData, includeMicrophone)
            } catch (error: Exception) {
                updateNotification(error.message ?: "Não foi possível iniciar a transmissão.")
                stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startWebRtc(
        server: String,
        roomUrl: String,
        name: String,
        projectionData: Intent,
        includeMicrophone: Boolean
    ) {
        if (stopped) return
        synchronized(WEBRTC_LOCK) {
            if (!webRtcInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
                )
                webRtcInitialized = true
            }
        }
        eglBase = EglBase.create()
        audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext).createAudioDeviceModule()
        val eglContext = eglBase!!.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule!!)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        if (stopped) return
        videoSource = factory!!.createVideoSource(true)
        surfaceTextureHelper = SurfaceTextureHelper.create("ElfarisWatchScreen", eglContext)
        screenCapturer = ScreenCapturerAndroid(
            projectionData,
            object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    updateNotification("O compartilhamento de tela foi interrompido pelo Android.")
                    stopCapture()
                }
            }
        )
        screenCapturer!!.initialize(surfaceTextureHelper!!, applicationContext, videoSource!!.capturerObserver)
        if (stopped) return

        val dimensions = screenDimensions()
        screenCapturer!!.startCapture(dimensions.x, dimensions.y, 15)
        if (stopped) return
        videoTrack = factory!!.createVideoTrack("elfaris-watch-screen", videoSource!!)
        videoTrack!!.setEnabled(true)

        if (includeMicrophone) {
            audioSource = factory!!.createAudioSource(MediaConstraints())
            audioTrack = factory!!.createAudioTrack("elfaris-watch-microphone", audioSource!!)
            audioTrack!!.setEnabled(true)
        }

        if (stopped) return
        roomClient = WatchRoomClient(
            server,
            roomUrl,
            name,
            factory!!,
            onStatus = { status -> updateNotification(status) },
            onFatal = { stopCapture() }
        )
        roomClient!!.start(videoTrack!!, audioTrack)
        updateNotification("Conectando à sala Elfaris Watch...")
    }

    private fun screenDimensions(): Point {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = manager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        val maxDimension = 1920
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(size.x, size.y).coerceAtLeast(1))
        val width = ((size.x * scale).toInt() / 2) * 2
        val height = ((size.y * scale).toInt() / 2) * 2
        return Point(width.coerceAtLeast(2), height.coerceAtLeast(2))
    }

    private fun getProjectionIntent(source: Intent): Intent? {
        @Suppress("DEPRECATION")
        return source.getParcelableExtra(EXTRA_PROJECTION_DATA) as? Intent
    }

    private fun startForegroundCompat(includeMicrophone: Boolean) {
        createNotificationChannel()
        val notification = buildNotification("Preparando compartilhamento de tela.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (includeMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, WatchCaptureService::class.java).setAction(ACTION_STOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, pendingFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("Elfaris Watch")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Parar transmissão", stopPendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        if (stopped) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message.take(120)))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transmissão de tela",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mostra quando o Elfaris Watch está compartilhando a tela."
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        isCaptureActive = false
        roomClient?.stop()
        roomClient = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    @Synchronized
    override fun onDestroy() {
        stopped = true
        roomClient?.stop()
        roomClient = null
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        screenCapturer = null
        videoTrack?.dispose()
        videoTrack = null
        audioTrack?.dispose()
        audioTrack = null
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        factory?.dispose()
        factory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase?.release()
        eglBase = null
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "elfaris_watch_capture"
        private const val NOTIFICATION_ID = 7301
        const val ACTION_START = "com.elfaris.watch.action.START_CAPTURE"
        const val ACTION_STOP = "com.elfaris.watch.action.STOP_CAPTURE"
        const val EXTRA_SERVER = "server"
        const val EXTRA_ROOM_URL = "room_url"
        const val EXTRA_NAME = "name"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_MICROPHONE = "microphone"

        private const val RESULT_OK = -1
        private val WEBRTC_LOCK = Any()
        @Volatile private var webRtcInitialized = false
        @Volatile var isCaptureActive = false
            private set

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, WatchCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
