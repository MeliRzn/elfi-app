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
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native presenter client. Every viewer receives a direct WebRTC connection;
 * no browser or participant is used as a relay/room owner.
 */
class WatchRoomClient(
    private val server: String,
    private val roomUrl: String,
    private val displayName: String,
    private val factory: PeerConnectionFactory,
    private val onStatus: (String) -> Unit,
    private val onParticipants: (List<String>) -> Unit = {},
    private val onFatal: () -> Unit = {},
    private val onRemoteVideo: (VideoTrack) -> Unit = {},
    private val onRemoteVideoCleared: () -> Unit = {}
) {
    private data class PeerLink(
        val connection: PeerConnection,
        val pendingIce: MutableList<IceCandidate> = mutableListOf()
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val eventsHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val network = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val links = ConcurrentHashMap<String, PeerLink>()
    @Volatile private var eventsCall: okhttp3.Call? = null
    @Volatile private var activeRequest: okhttp3.Call? = null
    @Volatile private var roomId = ""
    @Volatile private var peerId = ""
    @Volatile private var peerToken = ""
    @Volatile private var active = false
    @Volatile private var iceServers: List<PeerConnection.IceServer> = emptyList()
    @Volatile private var localVideo: VideoTrack? = null
    @Volatile private var localAudio: AudioTrack? = null
    private val participantNames = LinkedHashMap<String, String>()
    @Volatile private var presenterId = ""

    fun start() {
        network.execute {
            try {
                val roomUri = Uri.parse(roomUrl)
                val origin = roomUri.scheme + "://" + roomUri.authority
                require(origin == WatchApi.normalizeServer(server)) {
                    "O link da sala pertence a outro servidor. Atualize a conexão pelo WhatsApp."
                }
                require(roomUri.scheme == "https" && roomUri.path.orEmpty().matches(Regex("^/watch/[A-Za-z0-9_-]{8,}$"))) {
                    "Cole um link HTTPS válido de uma sala Elfaris Watch."
                }
                require(roomUri.getQueryParameter("host").isNullOrBlank()) {
                    "Este link ainda usa o formato antigo. Gere outro com transmitir app."
                }
                roomId = roomUri.lastPathSegment.orEmpty()
                val joined = post("join", JSONObject().put("name", displayName))
                peerId = joined.getString("peerId")
                peerToken = joined.getString("token")
                if (closed.get()) return@execute
                iceServers = parseIceServers(joined.optJSONArray("iceServers"))
                presenterId = joined.optString("presenterId")
                val initialParticipants = joined.optJSONArray("participants")
                if (initialParticipants != null) {
                    for (index in 0 until initialParticipants.length()) {
                        val item = initialParticipants.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        if (id.isNotBlank()) participantNames[id] = item.optString("name").ifBlank { "Convidado" }
                    }
                }
                publishParticipants()
                connectEvents()
                onStatus(if (presenterId.isBlank()) "Você entrou na sala. Ainda não há transmissão ativa."
                    else "Você entrou na sala. Conectando à transmissão...")
            } catch (error: Exception) {
                onStatus(error.message ?: "Não foi possível entrar na sala.")
                stop()
                onFatal()
            }
        }
    }

    fun startSharing(videoTrack: VideoTrack, audioTrack: AudioTrack?) {
        network.execute {
            if (closed.get() || active) return@execute
            try {
                post("share", auth().put("active", true))
                localVideo = videoTrack
                localAudio = audioTrack
                active = true
                presenterId = peerId
                val existing = synchronized(participantNames) { participantNames.keys.toList() }
                existing.filter { it != peerId }.forEach(::createViewerConnection)
                publishParticipants()
                onStatus("Sua tela está sendo compartilhada.")
            } catch (error: Exception) {
                onStatus(error.message ?: "Não foi possível iniciar o compartilhamento.")
            }
        }
    }

    fun stopSharing() {
        network.execute {
            if (closed.get() || !active) return@execute
            try {
                post("share", auth().put("active", false))
            } catch (error: Exception) {
                onStatus(error.message ?: "Não foi possível encerrar a transmissão.")
            } finally {
                active = false
                presenterId = ""
                links.values.forEach(::closeLink)
                links.clear()
                localVideo = null
                localAudio = null
                publishParticipants()
                onStatus("Compartilhamento encerrado. Você continua na sala.")
            }
        }
    }

    private fun auth(): JSONObject = JSONObject()
        .put("peerId", peerId)
        .put("token", peerToken)

    private fun createViewerConnection(viewerId: String) {
        if (closed.get() || !active || links.containsKey(viewerId)) return
        try {
            val observer = createObserver(viewerId)
            val connection = factory.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), observer)
                ?: throw IOException("Não foi possível criar a conexão WebRTC.")
            val link = PeerLink(connection)
            links[viewerId] = link
            localVideo?.let { connection.addTrack(it, listOf("elfaris-watch")) }
            localAudio?.let { connection.addTrack(it, listOf("elfaris-watch")) }
            connection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (closed.get()) return
                    connection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            if (closed.get()) return
                            val local = connection.localDescription ?: return fail("Não foi possível preparar a transmissão.")
                            sendSignal(viewerId, "offer", JSONObject()
                                .put("type", local.type.canonicalForm())
                                .put("sdp", local.description))
                        }
                        override fun onSetFailure(error: String) = fail(error)
                        override fun onCreateSuccess(description: SessionDescription) = Unit
                        override fun onCreateFailure(error: String) = Unit
                    }, description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) = fail(error)
                override fun onSetFailure(error: String) = fail(error)
            }, org.webrtc.MediaConstraints())
        } catch (error: Exception) {
            onStatus(error.message ?: "Não foi possível conectar um participante.")
        }
    }

    private fun createObserver(remoteId: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> onStatus("Transmissão conectada a um participante.")
                PeerConnection.IceConnectionState.FAILED -> onStatus("Uma conexão falhou. Verifique a rede ou o servidor TURN.")
                PeerConnection.IceConnectionState.DISCONNECTED -> onStatus("Conexão instável. Tentando recuperar...")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            sendSignal(remoteId, "ice", JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let(onRemoteVideo)
        }
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun handleIncomingOffer(remoteId: String, payload: JSONObject) {
        if (closed.get() || remoteId.isBlank()) return
        try {
            links.remove(remoteId)?.let(::closeLink)
            val connection = factory.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), createObserver(remoteId))
                ?: throw IOException("Não foi possível criar a conexão de vídeo.")
            val link = PeerLink(connection)
            links[remoteId] = link
            connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            val offer = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                payload.getString("sdp")
            )
            connection.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    connection.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription) {
                            connection.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    val local = connection.localDescription ?: return
                                    sendSignal(remoteId, "answer", JSONObject()
                                        .put("type", local.type.canonicalForm())
                                        .put("sdp", local.description))
                                    synchronized(link.pendingIce) {
                                        link.pendingIce.forEach { connection.addIceCandidate(it) }
                                        link.pendingIce.clear()
                                    }
                                }
                                override fun onSetFailure(error: String) = onStatus(error)
                                override fun onCreateSuccess(description: SessionDescription) = Unit
                                override fun onCreateFailure(error: String) = Unit
                            }, description)
                        }
                        override fun onSetSuccess() = Unit
                        override fun onCreateFailure(error: String) = onStatus(error)
                        override fun onSetFailure(error: String) = onStatus(error)
                    }, org.webrtc.MediaConstraints())
                }
                override fun onSetFailure(error: String) = onStatus(error)
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            }, offer)
        } catch (error: Exception) {
            onStatus(error.message ?: "Não foi possível receber a transmissão.")
        }
    }

    private fun connectEvents() {
        val url = server.trimEnd('/') + "/watch/api/" + roomId +
            "/events?peer=" + Uri.encode(peerId) + "&token=" + Uri.encode(peerToken)
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
                            throw EventStreamException(response.code, message.ifBlank { "A sala encerrou ou o acesso expirou." })
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
                                if (data.isNotEmpty()) data.append('\n')
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
            "state" -> {
                val state = JSONObject(raw)
                presenterId = state.optString("presenterId")
                val participants = state.optJSONArray("participants") ?: return
                synchronized(participantNames) { participantNames.clear() }
                for (index in 0 until participants.length()) {
                    val item = participants.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isNotBlank()) {
                        synchronized(participantNames) {
                            participantNames[id] = item.optString("name").ifBlank { "Convidado" }
                        }
                        if (id != peerId && active) createViewerConnection(id)
                    }
                }
                publishParticipants()
            }
            "participant-joined" -> {
                val item = JSONObject(raw)
                val id = item.optString("peerId")
                if (id.isNotBlank()) {
                    synchronized(participantNames) {
                        participantNames[id] = item.optString("name").ifBlank { "Convidado" }
                    }
                    if (active) createViewerConnection(id)
                    publishParticipants()
                }
            }
            "participant-left" -> {
                val item = JSONObject(raw)
                val id = item.optString("peerId")
                synchronized(participantNames) { participantNames.remove(id) }
                links.remove(id)?.let { closeLink(it) }
                publishParticipants()
            }
            "signal" -> {
                val data = JSONObject(raw)
                val from = data.optString("from")
                val signalType = data.optString("type")
                if (signalType == "offer") {
                    handleIncomingOffer(from, data.getJSONObject("payload"))
                    return
                }
                val link = links[from] ?: return
                when (signalType) {
                    "answer" -> {
                        val payload = data.getJSONObject("payload")
                        val description = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                            payload.getString("sdp")
                        )
                        link.connection.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                synchronized(link.pendingIce) {
                                    link.pendingIce.forEach { link.connection.addIceCandidate(it) }
                                    link.pendingIce.clear()
                                }
                            }
                            override fun onSetFailure(error: String) = onStatus(error)
                            override fun onCreateSuccess(description: SessionDescription) = Unit
                            override fun onCreateFailure(error: String) = Unit
                        }, description)
                    }
                    "ice" -> {
                        val payload = data.getJSONObject("payload")
                        val candidate = IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.getString("candidate"))
                        if (link.connection.remoteDescription != null) link.connection.addIceCandidate(candidate)
                        else synchronized(link.pendingIce) { link.pendingIce.add(candidate) }
                    }
                }
            }
            "room-ended" -> {
                onStatus(JSONObject(raw).optString("reason").ifBlank { "A sala foi encerrada." })
                stop()
                onFatal()
            }
            "sharing" -> {
                val state = JSONObject(raw)
                presenterId = state.optString("presenterId")
                publishParticipants()
                if (!state.optBoolean("active", true)) {
                    if (active && presenterId == peerId) return
                    links.values.forEach(::closeLink)
                    links.clear()
                    onRemoteVideoCleared()
                    onStatus("A transmissão foi encerrada. Você continua na sala.")
                }
            }
        }
    }

    private fun publishParticipants() {
        val names = synchronized(participantNames) {
            participantNames.map { (id, name) ->
                when {
                    id == peerId && id == presenterId -> "$name · você está transmitindo"
                    id == presenterId -> "$name · transmitindo"
                    id == peerId -> "$name · você"
                    else -> "$name · assistindo"
                }
            }
        }
        onParticipants(names)
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

    private fun sendSignal(to: String, type: String, payload: JSONObject) {
        if (closed.get()) return
        network.execute {
            try {
                post("signal", auth().put("to", to).put("type", type).put("payload", payload))
            } catch (error: Exception) {
                if (!closed.get()) onStatus(error.message ?: "Falha na sinalização WebRTC.")
            }
        }
    }

    private fun closeLink(link: PeerLink) {
        runCatching { link.connection.close() }
        runCatching { link.connection.dispose() }
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
        links.values.forEach(::closeLink)
        links.clear()
        localVideo = null
        localAudio = null
        network.shutdownNow()
        Thread({
            if (currentRoom.isNotBlank() && currentPeer.isNotBlank() && currentToken.isNotBlank()) {
                if (wasActive) runCatching {
                    post("share", JSONObject().put("peerId", currentPeer).put("token", currentToken).put("active", false))
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
