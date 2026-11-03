package com.prama.screen

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.TimeUnit

/** Admin side: list of live staff → tap → watch screen. */
class ViewerActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var server: String
    private lateinit var pin: String

    private lateinit var listPane: View
    private lateinit var videoPane: View
    private lateinit var staffList: LinearLayout
    private lateinit var tvListStatus: TextView
    private lateinit var tvWatchName: TextView
    private lateinit var tvWatchStatus: TextView
    private lateinit var renderer: SurfaceViewRenderer

    private val http = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).build()
    private var listWs: WebSocket? = null
    private var listening = false

    private var client: RtcClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        server = intent.getStringExtra("server") ?: ""
        pin = intent.getStringExtra("pin") ?: ""

        listPane = findViewById(R.id.listPane)
        videoPane = findViewById(R.id.videoPane)
        staffList = findViewById(R.id.staffList)
        tvListStatus = findViewById(R.id.tvListStatus)
        tvWatchName = findViewById(R.id.tvWatchName)
        tvWatchStatus = findViewById(R.id.tvWatchStatus)
        renderer = findViewById(R.id.renderer)

        Rtc.init(this)
        renderer.init(Rtc.egl.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)

        findViewById<Button>(R.id.btnEnd).setOnClickListener { endWatch() }
    }

    override fun onStart() {
        super.onStart()
        if (client == null) startList()
    }

    override fun onStop() {
        super.onStop()
        stopList()
    }

    override fun onDestroy() {
        endWatch(showList = false)
        renderer.release()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (client != null) endWatch() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------- LIVE LIST (polls server every 3s) ----------

    private fun startList() {
        if (listening) return
        listening = true
        tvListStatus.text = "CONNECTING… (FIRST TIME CAN TAKE 1 MIN)"
        listWs = http.newWebSocket(Request.Builder().url(server).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { poll(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                main.post { onListMessage(m) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onFailure(webSocket, RuntimeException("closed"), null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    if (!listening || webSocket !== listWs) return@post
                    tvListStatus.text = "SERVER NOT REACHABLE — RETRYING…"
                    listening = false
                    main.postDelayed({ if (client == null && !isFinishing) startList() }, 3000)
                }
            }
        })
    }

    private fun poll(ws: WebSocket) {
        if (!listening || ws !== listWs) return
        ws.send(JSONObject().put("type", "list").put("pin", pin).toString())
        main.postDelayed({ poll(ws) }, 3000)
    }

    private fun stopList() {
        listening = false
        listWs?.close(1000, null)
        listWs = null
    }

    private fun onListMessage(m: JSONObject) {
        when (m.optString("type")) {
            "error" -> {
                toast(m.optString("message"))
                if (m.optString("message").contains("PIN")) finish()
            }
            "rooms" -> {
                val rooms = m.optJSONArray("rooms") ?: return
                tvListStatus.text = if (rooms.length() == 0) "NO STAFF SHARING RIGHT NOW" else "${rooms.length()} LIVE · TAP TO VIEW"
                staffList.removeAllViews()
                for (i in 0 until rooms.length()) {
                    val r = rooms.getJSONObject(i)
                    val room = r.getString("room")
                    val name = r.optString("name", "STAFF")
                    val b = layoutInflater.inflate(R.layout.item_staff, staffList, false) as Button
                    b.text = "●  $name   ·   $room"
                    b.setOnClickListener { watch(room, name) }
                    staffList.addView(b)
                }
            }
        }
    }

    // ---------- WATCH ----------

    private fun watch(room: String, name: String) {
        stopList()
        tvWatchName.text = name
        tvWatchStatus.text = "CONNECTING…"
        listPane.visibility = View.GONE
        videoPane.visibility = View.VISIBLE

        client = RtcClient(
            this,
            serverUrl = server,
            room = room,
            role = "viewer",
            name = "ADMIN",
            pin = pin,
            localVideo = null,
            remoteSink = renderer,
            listener = object : RtcClient.Listener {
                override fun onStatus(text: String) { main.post { tvWatchStatus.text = text } }
                override fun onError(message: String) { main.post { toast(message); endWatch() } }
                override fun onEnded() { main.post { toast("$name STOPPED SHARING"); endWatch() } }
            },
        ).also { it.start() }
    }

    private fun endWatch(showList: Boolean = true) {
        val c = client ?: return
        client = null
        c.stop()
        renderer.clearImage()
        videoPane.visibility = View.GONE
        listPane.visibility = View.VISIBLE
        if (showList && !isFinishing) startList()
    }

    private fun toast(t: String) = Toast.makeText(this, t.uppercase(), Toast.LENGTH_SHORT).show()
}
