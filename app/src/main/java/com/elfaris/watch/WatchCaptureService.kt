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
    @Volatile private var destroyed = false
    @Volatile private var includeMicrophone = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_JOIN -> {
                if (!isRoomActive) {
                    isRoomActive = true
                    worker.execute { joinRoom(intent) }
                }
            }
            ACTION_START -> {
                if (!isCaptureActive) {
                    isCaptureActive = true
                    includeMicrophone = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
                    startForegroundCompat(includeMicrophone)
                    worker.execute { startCapture(intent) }
                }
            }
            ACTION_STOP -> worker.execute { stopSharingInternal() }
            ACTION_LEAVE -> worker.execute {
                leaveRoomInternal()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun initializeWebRtc() {
        if (factory != null) return
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
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule!!)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun joinRoom(intent: Intent) {
        try {
            initializeWebRtc()
            val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
            val roomUrl = intent.getStringExtra(EXTRA_ROOM_URL).orEmpty()
            val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
            require(server.isNotBlank() && roomUrl.isNotBlank()) { "Link da sala incompleto." }
            roomClient = WatchRoomClient(
                server = server,
                roomUrl = roomUrl,
                displayName = name,
                factory = factory!!,
                onStatus = { broadcastStatus(it) },
                onParticipants = { participants ->
                    val payload = org.json.JSONArray()
                    participants.forEach { payload.put(it) }
                    sendBroadcast(Intent(ACTION_ROOM_PARTICIPANTS).setPackage(packageName)
                        .putExtra(EXTRA_PARTICIPANTS, payload.toString()))
                },
                onFatal = {
                    isRoomActive = false
                    isRoomConnected = false
                    sendBroadcast(Intent(ACTION_ROOM_ERROR).setPackage(packageName)
                        .putExtra(EXTRA_STATUS, "A conexão com a sala foi encerrada."))
                },
                onRemoteVideo = { track ->
                    previewTrack = track
                    previewEglContext = eglBase?.eglBaseContext
                    sendBroadcast(Intent(ACTION_PREVIEW_READY).setPackage(packageName))
                },
                onRemoteVideoCleared = {
                    previewTrack = null
                    sendBroadcast(Intent(ACTION_PREVIEW_CLEARED).setPackage(packageName))
                },
                onJoined = {
                    isRoomConnected = true
                    sendBroadcast(Intent(ACTION_ROOM_JOINED).setPackage(packageName))
                }
            )
            roomClient!!.start()
        } catch (error: Exception) {
            broadcastStatus(error.message ?: "Não foi possível entrar na sala.")
            isRoomActive = false
            isRoomConnected = false
            sendBroadcast(Intent(ACTION_ROOM_ERROR).setPackage(packageName)
                .putExtra(EXTRA_STATUS, error.message ?: "Falha ao entrar na sala."))
            stopSelf()
        }
    }

    private fun startCapture(intent: Intent) {
        try {
            if (roomClient == null) throw IllegalStateException("Entre na sala antes de compartilhar a tela.")
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val projectionData = getProjectionIntent(intent)
            if (resultCode != RESULT_OK || projectionData == null) {
                throw IllegalArgumentException("A permissão de captura de tela não foi concedida.")
            }
            initializeWebRtc()
            videoSource = factory!!.createVideoSource(true)
            surfaceTextureHelper = SurfaceTextureHelper.create("ElfarisWatchScreen", eglBase!!.eglBaseContext)
            screenCapturer = ScreenCapturerAndroid(
                projectionData,
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        worker.execute { stopSharingInternal() }
                    }
                }
            )
            screenCapturer!!.initialize(surfaceTextureHelper!!, applicationContext, videoSource!!.capturerObserver)
            val dimensions = screenDimensions()
            screenCapturer!!.startCapture(dimensions.x, dimensions.y, 15)
            videoTrack = factory!!.createVideoTrack("elfaris-watch-screen", videoSource!!)
            videoTrack!!.setEnabled(true)
            if (includeMicrophone) {
                audioSource = factory!!.createAudioSource(MediaConstraints())
                audioTrack = factory!!.createAudioTrack("elfaris-watch-microphone", audioSource!!)
                audioTrack!!.setEnabled(true)
            }
            previewTrack = videoTrack
            previewEglContext = eglBase!!.eglBaseContext
            roomClient!!.startSharing(videoTrack!!, audioTrack)
            isCaptureActive = true
            sendBroadcast(Intent(ACTION_PREVIEW_READY).setPackage(packageName))
            updateNotification("Compartilhando sua tela na sala.")
            broadcastStatus("Sua tela está sendo compartilhada.")
        } catch (error: Exception) {
            broadcastStatus(error.message ?: "Não foi possível iniciar o compartilhamento.")
            stopSharingInternal()
        }
    }

    private fun screenDimensions(): Point {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = Point()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealSize(size)
        val scale = minOf(1f, 1920f / maxOf(size.x, size.y).coerceAtLeast(1))
        return Point(((size.x * scale).toInt() / 2 * 2).coerceAtLeast(2),
            ((size.y * scale).toInt() / 2 * 2).coerceAtLeast(2))
    }

    private fun getProjectionIntent(source: Intent): Intent? {
        @Suppress("DEPRECATION")
        return source.getParcelableExtra(EXTRA_PROJECTION_DATA) as? Intent
    }

    private fun stopSharingInternal() {
        if (!isCaptureActive && screenCapturer == null) return
        runCatching { roomClient?.stopSharing() }
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        screenCapturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        previewTrack = null
        isCaptureActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        sendBroadcast(Intent(ACTION_PREVIEW_CLEARED).setPackage(packageName))
        broadcastStatus("Compartilhamento encerrado. Você continua na sala.")
    }

    private fun leaveRoomInternal() {
        stopSharingInternal()
        roomClient?.stop()
        roomClient = null
        isRoomActive = false
        isRoomConnected = false
        previewTrack = null
        previewEglContext = null
        sendBroadcast(Intent(ACTION_ROOM_LEFT).setPackage(packageName))
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent(ACTION_ROOM_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
        if (isCaptureActive) updateNotification(message)
    }

    private fun startForegroundCompat(withMicrophone: Boolean) {
        createNotificationChannel()
        val notification = buildNotification("Preparando compartilhamento de tela.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, WatchCaptureService::class.java).setAction(ACTION_STOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, pendingFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("Elfaris Watch")
            .setContentText(message.take(120))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Parar transmissão", stopPendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NOTIFICATION_SERVICE).let { it as NotificationManager }
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Transmissão de tela", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Mostra quando o Elfaris Watch está compartilhando a tela."
        channel.setShowBadge(false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        destroyed = true
        leaveRoomInternal()
        worker.shutdownNow()
        factory?.dispose()
        factory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase?.release()
        eglBase = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "elfaris_watch_capture"
        private const val NOTIFICATION_ID = 7301
        const val ACTION_START = "com.elfaris.watch.action.START_CAPTURE"
        const val ACTION_STOP = "com.elfaris.watch.action.STOP_CAPTURE"
        const val ACTION_JOIN = "com.elfaris.watch.action.JOIN_ROOM"
        const val ACTION_LEAVE = "com.elfaris.watch.action.LEAVE_ROOM"
        const val ACTION_CAPTURE_STOPPED = "com.elfaris.watch.action.CAPTURE_STOPPED"
        const val ACTION_PREVIEW_READY = "com.elfaris.watch.action.PREVIEW_READY"
        const val ACTION_PREVIEW_CLEARED = "com.elfaris.watch.action.PREVIEW_CLEARED"
        const val ACTION_ROOM_PARTICIPANTS = "com.elfaris.watch.action.ROOM_PARTICIPANTS"
        const val ACTION_ROOM_STATUS = "com.elfaris.watch.action.ROOM_STATUS"
        const val ACTION_ROOM_JOINED = "com.elfaris.watch.action.ROOM_JOINED"
        const val ACTION_ROOM_ERROR = "com.elfaris.watch.action.ROOM_ERROR"
        const val ACTION_ROOM_LEFT = "com.elfaris.watch.action.ROOM_LEFT"
        const val EXTRA_PARTICIPANTS = "participants"
        const val EXTRA_STATUS = "status"
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
        @Volatile var isRoomActive = false
            private set
        @Volatile var isRoomConnected = false
            private set
        @Volatile private var previewTrack: VideoTrack? = null
        @Volatile private var previewEglContext: EglBase.Context? = null

        fun attachPreview(renderer: org.webrtc.SurfaceViewRenderer): Boolean {
            val track = previewTrack ?: return false
            val context = previewEglContext ?: return false
            return runCatching {
                renderer.init(context, null)
                renderer.setEnableHardwareScaler(true)
                track.addSink(renderer)
                true
            }.getOrDefault(false)
        }

        fun detachPreview(renderer: org.webrtc.SurfaceViewRenderer) {
            runCatching { previewTrack?.removeSink(renderer) }
            runCatching { renderer.release() }
        }

        fun join(context: android.content.Context, server: String, roomUrl: String, name: String) {
            val intent = Intent(context, WatchCaptureService::class.java).apply {
                action = ACTION_JOIN
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_ROOM_URL, roomUrl)
                putExtra(EXTRA_NAME, name)
            }
            context.startService(intent)
        }

        fun startCapture(context: android.content.Context, resultCode: Int, data: Intent, microphone: Boolean) {
            val intent = Intent(context, WatchCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
                putExtra(EXTRA_MICROPHONE, microphone)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, WatchCaptureService::class.java).setAction(ACTION_STOP))
        }

        fun leave(context: android.content.Context) {
            context.startService(Intent(context, WatchCaptureService::class.java).setAction(ACTION_LEAVE))
        }
    }
}
