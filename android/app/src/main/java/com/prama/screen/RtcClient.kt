package com.prama.screen

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One side of a 1-to-1 screen share (video only, no audio).
 *  role = "sharer": sends screen video.
 *  role = "viewer": receives screen video.
 * All work runs on a single thread so there are no races.
 */
class RtcClient(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val serverUrl: String,
    private val room: String,
    private val role: String,
    private val name: String,
    private val pin: String,
    private val localVideo: VideoTrack?,
    private val remoteSink: VideoSink?,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onError(message: String) {}
        fun onEnded() {}
    }

    private val isSharer = role == "sharer"
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS) // free servers can take ~50s to wake up
        .build()

    private var ws: WebSocket? = null
    private var pc: PeerConnection? = null
    private var ice: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var remoteVideo: VideoTrack? = null
    @Volatile private var closed = false

    /** Run on our worker thread; silently ignored after stop(). */
    private fun post(block: () -> Unit) {
        try { exec.execute(Runnable { block() }) } catch (_: Exception) {}
    }

    // ---------- public ----------

    fun start() = post { connect() }

    /** Blocks (max 3s) until everything is released. */
    fun stop() {
        closed = true
        try {
            exec.submit(Runnable {
                closePc()
                ws?.close(1000, "bye")
                ws = null
            }).get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        exec.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    // ---------- signaling ----------

    private fun status(t: String) { if (!closed) listener.onStatus(t) }

    private fun connect() {
        if (closed) return
        status("CONNECTING TO SERVER…")
        val req = try {
            Request.Builder().url(serverUrl).build()
        } catch (e: Exception) {
            closed = true
            listener.onError("WRONG SERVER ADDRESS")
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post {
                if (webSocket !== ws) return@post
                send(
                    JSONObject().put("type", "join").put("room", room).put("role", role)
                        .put("name", name).put("pin", pin)
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) = post {
                if (webSocket !== ws) return@post
                try { handle(JSONObject(text)) } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                post { if (webSocket === ws) retry("NO CONNECTION") }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                post { if (webSocket === ws) retry("DISCONNECTED") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                post { if (webSocket === ws) retry("DISCONNECTED") }
        })
    }

    private fun retry(why: String) {
        if (closed) return
        ws = null
        closePc()
        status("$why — RETRYING…")
        try { exec.schedule(Runnable { connect() }, 3, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "joined" -> {
                parseIce(m.optJSONArray("ice"))
                status(if (isSharer) "LIVE · WAITING FOR ADMIN" else "WAITING FOR SCREEN…")
            }
            "peer-joined" -> if (isSharer) makeOffer()
            "peer-left" -> {
                closePc()
                if (isSharer) status("LIVE · WAITING FOR ADMIN")
                else { status("STAFF STOPPED SHARING"); listener.onEnded() }
            }
            "offer" -> if (!isSharer) onOffer(m.getString("sdp"))
            "answer" -> {
                val p = pc ?: return
                p.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() = post {
                        if (pc === p) { remoteSet = true; drainIce() }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            }
            "candidate" -> {
                val c = IceCandidate(m.optString("sdpMid"), m.optInt("sdpMLineIndex"), m.getString("candidate"))
                if (remoteSet) pc?.addIceCandidate(c) else pendingIce.add(c)
            }
            "error" -> {
                closed = true
                listener.onError(m.optString("message", "ERROR"))
                ws?.close(1000, null)
            }
        }
    }

    private fun parseIce(arr: JSONArray?) {
        if (arr == null) return
        val out = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val urls = mutableListOf<String>()
            val u = s.opt("urls")
            if (u is JSONArray) for (j in 0 until u.length()) urls.add(u.getString(j))
            else if (u is String) urls.add(u)
            if (urls.isEmpty()) continue
            out.add(
                PeerConnection.IceServer.builder(urls)
                    .setUsername(s.optString("username", ""))
                    .setPassword(s.optString("credential", ""))
                    .createIceServer()
            )
        }
        if (out.isNotEmpty()) ice = out
    }

    // ---------- WebRTC ----------

    private fun newPc(): PeerConnection? {
        closePc()
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        remoteSet = false
        pendingIce.clear()
        val p = Rtc.factory.createPeerConnection(cfg, observer)
        pc = p
        return p
    }

    private fun closePc() {
        remoteVideo?.let { v -> remoteSink?.let { try { v.removeSink(it) } catch (_: Exception) {} } }
        remoteVideo = null
        pc?.let { try { it.dispose() } catch (_: Exception) {} }
        pc = null
        remoteSet = false
        pendingIce.clear()
    }

    private fun drainIce() {
        pendingIce.forEach { pc?.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun makeOffer() {
        val p = newPc() ?: return
        localVideo?.let {
            p.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("prama")))
        }
        status("ADMIN CONNECTING…")
        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) = post {
                if (pc !== p) return@post
                p.setLocalDescription(SdpAdapter(), desc)
                send(JSONObject().put("type", "offer").put("sdp", desc.description))
            }
        }, MediaConstraints())
    }

    private fun onOffer(sdp: String) {
        val p = newPc() ?: return
        p.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() = post {
                if (pc !== p) return@post
                remoteSet = true
                drainIce()
                p.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription) = post {
                        if (pc !== p) return@post
                        p.setLocalDescription(SdpAdapter(), desc)
                        send(JSONObject().put("type", "answer").put("sdp", desc.description))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = post {
            send(
                JSONObject().put("type", "candidate").put("sdpMid", c.sdpMid)
                    .put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)
            )
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED ->
                    status(if (isSharer) "LIVE · ADMIN IS WATCHING" else "LIVE")
                PeerConnection.IceConnectionState.DISCONNECTED -> status("WEAK NETWORK…")
                PeerConnection.IceConnectionState.FAILED -> status("CONNECTION FAILED · ADD TURN SERVER")
                else -> {}
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack && remoteSink != null) post {
                remoteVideo = track
                track.addSink(remoteSink)
            }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }
}

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
