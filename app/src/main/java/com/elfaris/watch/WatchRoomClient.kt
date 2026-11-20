package com.elfaris.watch

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native guest/presenter client for the existing Watch signaling protocol.
 * The host browser remains the relay for other guests, so no second room server is needed.
 */
class WatchRoomClient(
    private val server: String,
    private val roomUrl: String,
    private val displayName: String,
    private val factory: PeerConnectionFactory,
    private val onStatus: (String) -> Unit,
    private val onFatal: () -> Unit = {}
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val eventsHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val network = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    @Volatile private var eventsCall: okhttp3.Call? = null
    @Volatile private var activeRequest: okhttp3.Call? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var roomId = ""
    @Volatile private var peerId = ""
    @Volatile private var peerToken = ""
    @Volatile private var active = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    fun start(videoTrack: VideoTrack, audioTrack: AudioTrack?) {
        network.execute {
            try {
                val roomUri = Uri.parse(roomUrl)
                val origin = roomUri.scheme + "://" + roomUri.authority
                require(origin == WatchApi.normalizeServer(server)) {
                    "O link da sala pertence a outro servidor. Atualize a conexão pelo WhatsApp."
                }
                require(roomUri.scheme == "https" && roomUri.path.orEmpty().matches(Regex("^/watch/[A-Za-z0-9_-]{8,}$"))) {
                    "Cole o link de convidado de uma sala Elfaris Watch."
                }
                require(roomUri.getQueryParameter("host").isNullOrBlank()) {
                    "Use o link de convidado, não o painel privado do anfitrião."
                }
                roomId = roomUri.lastPathSegment.orEmpty()
                val joined = post("join", JSONObject().put("name", displayName))
                peerId = joined.getString("peerId")
                peerToken = joined.getString("token")
                if (closed.get()) return@execute

                val currentPresenter = joined.optString("activePresenter")
                if (currentPresenter.isNotBlank() && currentPresenter != "null") {
                    throw IOException("Outra pessoa já está transmitindo nesta sala.")
                }

                val iceServers = parseIceServers(joined.optJSONArray("iceServers"))
                val observer = createObserver()
                val connection = synchronized(lifecycleLock) {
                    if (closed.get()) return@execute
                    val created = factory.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), observer)
                        ?: throw IOException("Não foi possível criar a conexão WebRTC.")
                    peerConnection = created
                    created
                }
                synchronized(lifecycleLock) {
                    if (closed.get()) return@execute
                    connection.addTrack(videoTrack, listOf("elfaris-watch"))
                    if (audioTrack != null) connection.addTrack(audioTrack, listOf("elfaris-watch"))
                }

                connectEvents()
                post("share", JSONObject().put("token", peerToken).put("peerId", peerId).put("active", true))
                active = true

                synchronized(lifecycleLock) {
                    if (closed.get()) return@execute
                    connection.createOffer(object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription) {
                            synchronized(lifecycleLock) {
                                if (closed.get()) return
                                connection.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        val local = synchronized(lifecycleLock) {
                                            if (closed.get()) return
                                            connection.localDescription ?: return fail("Não foi possível preparar a transmissão.")
                                        }
                                        try {
                                            sendSignal("offer", JSONObject().put("type", local.type.canonicalForm()).put("sdp", local.description))
                                            onStatus("Conectando à sala...")
                                        } catch (error: Exception) {
                                            fail(error.message ?: "Falha ao enviar a oferta WebRTC.")
                                        }
                                    }
                                    override fun onSetFailure(error: String) = fail(error)
                                    override fun onCreateSuccess(description: SessionDescription) = Unit
                                    override fun onCreateFailure(error: String) = Unit
                                }, description)
                            }
                        }
                        override fun onSetSuccess() = Unit
                        override fun onCreateFailure(error: String) = fail(error)
                        override fun onSetFailure(error: String) = fail(error)
                    }, org.webrtc.MediaConstraints())
                }
            } catch (error: Exception) {
                onStatus(error.message ?: "Não foi possível entrar na sala.")
                stop()
                onFatal()
            }
        }
    }

    private fun createObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> onStatus("Sua tela está sendo transmitida.")
                PeerConnection.IceConnectionState.FAILED -> fail("A conexão com a sala falhou. Verifique sua rede ou o servidor TURN.")
                PeerConnection.IceConnectionState.DISCONNECTED -> onStatus("Conexão instável. Tentando reconectar...")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            val payload = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            try { sendSignal("ice", payload) } catch (error: Exception) {
                onStatus(error.message ?: "Falha ao enviar candidato ICE.")
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun connectEvents() {
        val url = server.trimEnd('/') + "/watch/api/" + roomId +
            "/events?role=guest&peer=" + Uri.encode(peerId) + "&token=" + Uri.encode(peerToken)
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").get().build()
        Thread({
            while (!closed.get()) {
                try {
                    val call = eventsHttp.newCall(request)
                    eventsCall = call
                    if (closed.get()) {
                        call.cancel()
                        return@Thread
                    }
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val message = response.body?.string().orEmpty()
                            throw EventStreamException(
                                response.code,
                                message.ifBlank { "A sala encerrou ou o link de participante expirou." }
                            )
                        }
                        val source = response.body?.source() ?: throw IOException("Conexão de eventos vazia.")
                        var eventName = ""
                        val data = StringBuilder()
                        while (!closed.get()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    handleEvent(eventName, data.toString())
                                    data.setLength(0)
                                }
                                eventName = ""
                            } else if (line.startsWith("event:")) {
                                eventName = line.substringAfter(':').trim()
                            } else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\\n')
                                data.append(line.substringAfter(':').trimStart())
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (!closed.get()) {
                        onStatus(error.message ?: "Reconectando à sala...")
                        if (error is EventStreamException && error.statusCode in setOf(401, 404, 410)) {
                            stop()
                            onFatal()
                            return@Thread
                        }
                        try { Thread.sleep(1200) } catch (_: InterruptedException) { return@Thread }
                    }
                }
            }
        }, "WatchRoomEvents").apply { isDaemon = true }.start()
    }

    private fun handleEvent(event: String, raw: String) {
        when (event) {
            "signal" -> {
                val data = JSONObject(raw)
                when (data.optString("type")) {
                    "answer" -> {
                        val payload = data.getJSONObject("payload")
                        val description = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                            payload.getString("sdp")
                        )
                        synchronized(lifecycleLock) {
                            if (closed.get()) return
                            val current = peerConnection ?: return
                            current.setRemoteDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    synchronized(lifecycleLock) {
                                        if (closed.get()) return
                                        val pending = synchronized(pendingRemoteIce) {
                                            pendingRemoteIce.toList().also { pendingRemoteIce.clear() }
                                        }
                                        pending.forEach { current.addIceCandidate(it) }
                                    }
                                    onStatus("Conectado. Sua tela está sendo transmitida.")
                                }
                                override fun onSetFailure(error: String) = fail(error)
                                override fun onCreateSuccess(description: SessionDescription) = Unit
                                override fun onCreateFailure(error: String) = Unit
                            }, description)
                        }
                    }
                    "ice" -> {
                        val payload = data.getJSONObject("payload")
                        val candidate = IceCandidate(
                            payload.optString("sdpMid"),
                            payload.optInt("sdpMLineIndex"),
                            payload.getString("candidate")
                        )
                        synchronized(lifecycleLock) {
                            if (closed.get()) return
                            val connection = peerConnection ?: return
                            if (connection.remoteDescription != null) {
                                connection.addIceCandidate(candidate)
                            } else {
                                synchronized(pendingRemoteIce) { pendingRemoteIce.add(candidate) }
                            }
                        }
                    }
                }
            }
            "room-ended" -> {
                onStatus("O anfitrião encerrou a sala.")
                stop()
                onFatal()
            }
            "sharing" -> {
                val state = JSONObject(raw)
                if (!state.optBoolean("active", true) && active) {
                    onStatus("A transmissão foi encerrada.")
                    stop()
                    onFatal()
                }
            }
        }
    }

    private fun parseIceServers(json: JSONArray?): List<PeerConnection.IceServer> {
        val result = mutableListOf<PeerConnection.IceServer>()
        if (json == null) return result
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val urls = item.opt("urls")
            val list = when (urls) {
                is String -> listOf(urls)
                is JSONArray -> (0 until urls.length()).mapNotNull { urls.optString(it).takeIf(String::isNotBlank) }
                else -> emptyList()
            }
            if (list.isEmpty()) continue
            val builder = PeerConnection.IceServer.builder(list)
            builder.setUsername(item.optString("username"))
            builder.setPassword(item.optString("credential"))
            result += builder.createIceServer()
        }
        return result
    }

    private fun post(action: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(server.trimEnd('/') + "/watch/api/" + roomId + "/" + action)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        val call = http.newCall(request)
        activeRequest = call
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = JSONObject(raw.ifBlank { "{}" })
                if (!response.isSuccessful) throw IOException(json.optString("error").ifBlank { "Erro HTTP ${response.code}." })
                return json
            }
        } finally {
            if (activeRequest === call) activeRequest = null
        }
    }

    private fun sendSignal(type: String, payload: JSONObject) {
        if (closed.get()) return
        val body = JSONObject()
            .put("from", peerId)
            .put("to", "host")
            .put("type", type)
            .put("payload", payload)
            .put("token", peerToken)
        network.execute {
            try { post("signal", body) } catch (error: Exception) {
                if (!closed.get()) fail(error.message ?: "Falha na sinalização WebRTC.")
            }
        }
    }

    private fun fail(message: String) {
        if (closed.get()) return
        runCatching {
            network.execute {
                if (closed.get()) return@execute
                onStatus(message)
                stop()
                onFatal()
            }
        }.onFailure {
            if (!closed.get()) onStatus(message)
        }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        eventsCall?.cancel()
        activeRequest?.cancel()
        val currentRoom = roomId
        val currentPeer = peerId
        val currentToken = peerToken
        val wasActive = active
        active = false
        synchronized(lifecycleLock) {
            val connection = peerConnection
            peerConnection = null
            runCatching { connection?.close() }
            runCatching { connection?.dispose() }
        }
        network.shutdownNow()
        Thread({
            if (currentRoom.isNotBlank() && currentPeer.isNotBlank() && currentToken.isNotBlank()) {
                if (wasActive) {
                    runCatching {
                        post("share", JSONObject().put("token", currentToken).put("peerId", currentPeer).put("active", false))
                    }
                }
                runCatching {
                    post("leave", JSONObject().put("peerId", currentPeer).put("token", currentToken))
                }
            }
        }, "WatchRoomCleanup").apply { isDaemon = true }.start()
    }

    private class EventStreamException(val statusCode: Int, message: String) : IOException(message)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
