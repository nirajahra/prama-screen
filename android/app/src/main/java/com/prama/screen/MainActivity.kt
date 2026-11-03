package com.prama.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.random.Random

class MainActivity : Activity() {

    private val REQ_PERMS = 10
    private val REQ_CAPTURE = 11

    private lateinit var etName: EditText
    private lateinit var etPin: EditText
    private lateinit var etServer: EditText
    private lateinit var tvCode: TextView
    private lateinit var tvShareStatus: TextView
    private lateinit var btnShare: Button
    private lateinit var rowSharing: LinearLayout

    private val prefs by lazy { getSharedPreferences("prama", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etName = findViewById(R.id.etName)
        etPin = findViewById(R.id.etPin)
        etServer = findViewById(R.id.etServer)
        tvCode = findViewById(R.id.tvCode)
        tvShareStatus = findViewById(R.id.tvShareStatus)
        btnShare = findViewById(R.id.btnShare)
        rowSharing = findViewById(R.id.rowSharing)

        etName.setText(prefs.getString("name", ""))
        etPin.setText(prefs.getString("pin", ""))
        etServer.setText(prefs.getString("server", ""))
        tvCode.text = roomCode()

        btnShare.setOnClickListener { onShareClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            ScreenShareService.command(this, ScreenShareService.ACTION_STOP)
        }
        findViewById<Button>(R.id.btnView).setOnClickListener { onViewClicked() }
    }

    override fun onResume() {
        super.onResume()
        ScreenShareService.onChange = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        ScreenShareService.onChange = null
        save()
    }

    private fun render() {
        val live = ScreenShareService.running
        tvShareStatus.text = ScreenShareService.status
        tvShareStatus.setTextColor(getColor(if (live) R.color.live else R.color.muted))
        btnShare.visibility = if (live) View.GONE else View.VISIBLE
        rowSharing.visibility = if (live) View.VISIBLE else View.GONE
        etName.isEnabled = !live
    }

    /** Stable 6-digit code for this phone. */
    private fun roomCode(): String {
        var c = prefs.getString("room", null)
        if (c == null) {
            c = Random.nextInt(100000, 999999).toString()
            prefs.edit().putString("room", c).apply()
        }
        return c
    }

    private fun server(): String? {
        var s = etServer.text.toString().trim()
        if (s.isEmpty()) { toast("ENTER SERVER ADDRESS"); return null }
        if (s.startsWith("https://")) s = "wss://" + s.removePrefix("https://")
        else if (s.startsWith("http://")) s = "ws://" + s.removePrefix("http://")
        else if (!s.startsWith("ws://") && !s.startsWith("wss://")) s = "wss://$s"
        return s
    }

    private fun save() {
        prefs.edit()
            .putString("name", etName.text.toString().trim())
            .putString("pin", etPin.text.toString().trim())
            .putString("server", etServer.text.toString().trim())
            .apply()
    }

    // ---------- STAFF ----------

    private fun onShareClicked() {
        if (etName.text.isBlank()) { toast("ENTER YOUR NAME"); return }
        server() ?: return
        save()
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS) else askCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { toast("SCREEN SHARE NOT ALLOWED"); return }
        val url = server() ?: return
        ScreenShareService.status = "STARTING…"
        ScreenShareService.start(
            this, resultCode, data, url, roomCode(),
            etName.text.toString().trim().uppercase()
        )
        tvShareStatus.postDelayed({ render() }, 400)
    }

    // ---------- ADMIN ----------

    private fun onViewClicked() {
        val url = server() ?: return
        val pin = etPin.text.toString().trim()
        if (pin.isEmpty()) { toast("ENTER ADMIN PIN"); return }
        save()
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra("server", url).putExtra("pin", pin)
        )
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
