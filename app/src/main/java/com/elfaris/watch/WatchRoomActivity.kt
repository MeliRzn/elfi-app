package com.elfaris.watch

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.json.JSONArray

class WatchRoomActivity : AppCompatActivity() {
    private lateinit var preview: SurfaceViewRenderer
    private lateinit var participantsList: LinearLayout
    private lateinit var participantCount: TextView
    private lateinit var status: TextView
    private lateinit var shareButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var microphoneSwitch: SwitchMaterial
    private var previewAttached = false
    private var joined = false
    private var server = ""
    private var roomUrl = ""
    private var displayName = "Convidado"

    private val roomReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WatchCaptureService.ACTION_PREVIEW_READY -> attachPreview()
                WatchCaptureService.ACTION_PREVIEW_CLEARED -> clearPreview()
                WatchCaptureService.ACTION_ROOM_PARTICIPANTS ->
                    renderParticipants(intent.getStringExtra(WatchCaptureService.EXTRA_PARTICIPANTS).orEmpty())
                WatchCaptureService.ACTION_ROOM_STATUS -> {
                    status.text = intent.getStringExtra(WatchCaptureService.EXTRA_STATUS).orEmpty()
                    syncButtons()
                }
                WatchCaptureService.ACTION_ROOM_ERROR -> {
                    status.text = intent.getStringExtra(WatchCaptureService.EXTRA_STATUS).orEmpty()
                    Toast.makeText(this@WatchRoomActivity, status.text, Toast.LENGTH_LONG).show()
                    finish()
                }
                WatchCaptureService.ACTION_ROOM_LEFT -> finish()
            }
        }
    }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            WatchCaptureService.startCapture(
                this, result.resultCode, data,
                microphoneSwitch.isChecked &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            )
            status.text = "Preparando o compartilhamento..."
        } else {
            status.text = "Compartilhamento cancelado. Você continua na sala."
        }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapturePermission()
        else {
            microphoneSwitch.isChecked = false
            launchCapturePermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.watch_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.watch_background)
        server = intent.getStringExtra(WatchCaptureService.EXTRA_SERVER).orEmpty()
        roomUrl = intent.getStringExtra(WatchCaptureService.EXTRA_ROOM_URL).orEmpty()
        displayName = intent.getStringExtra(WatchCaptureService.EXTRA_NAME).orEmpty().ifBlank { "Convidado" }
        if (server.isBlank() || roomUrl.isBlank()) {
            Toast.makeText(this, "Dados da sala incompletos.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildRoomScreen()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WatchCaptureService.ACTION_PREVIEW_READY)
            addAction(WatchCaptureService.ACTION_PREVIEW_CLEARED)
            addAction(WatchCaptureService.ACTION_ROOM_PARTICIPANTS)
            addAction(WatchCaptureService.ACTION_ROOM_STATUS)
            addAction(WatchCaptureService.ACTION_ROOM_ERROR)
            addAction(WatchCaptureService.ACTION_ROOM_LEFT)
        }
        ContextCompat.registerReceiver(this, roomReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (!joined) {
            joined = true
            WatchCaptureService.join(this, server, roomUrl, displayName)
        }
        attachPreview()
        syncButtons()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(roomReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        if (previewAttached) {
            WatchCaptureService.detachPreview(preview)
            previewAttached = false
        } else if (::preview.isInitialized) runCatching { preview.release() }
        super.onDestroy()
    }

    private fun buildRoomScreen() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(22))
            setBackgroundColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_background))
        }
        val brand = TextView(this).apply {
            text = "ELFARIS WATCH  ·  SALA AO VIVO"
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_primary))
        }
        val heading = TextView(this).apply {
            text = "Sua sala"
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_text))
        }
        status = TextView(this).apply {
            text = "Conectando à sala..."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_muted))
        }
        root.addView(brand)
        root.addView(heading, params(top = 5))
        root.addView(status, params(top = 4))

        val previewCard = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_outline)
            setCardBackgroundColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_surface))
        }
        val frame = FrameLayout(this)
        preview = SurfaceViewRenderer(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setSecure(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            contentDescription = "Tela compartilhada na sala"
        }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val empty = TextView(this).apply {
            text = "Ninguém está compartilhando a tela no momento"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            tag = "empty_preview"
        }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        previewCard.addView(frame, LinearLayout.LayoutParams(-1, dp(250)))
        root.addView(previewCard, params(top = 18))

        val peopleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val peopleTitle = TextView(this).apply {
            text = "Pessoas na sala"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_text))
        }
        participantCount = TextView(this).apply {
            text = "0"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_primary))
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setBackgroundColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_surface_variant))
        }
        peopleHeader.addView(peopleTitle, LinearLayout.LayoutParams(0, -2, 1f))
        peopleHeader.addView(participantCount)
        root.addView(peopleHeader, params(top = 20))

        val peopleCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_outline)
            setCardBackgroundColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_surface))
        }
        participantsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        peopleCard.addView(participantsList)
        root.addView(peopleCard, params(top = 10))

        microphoneSwitch = SwitchMaterial(this).apply {
            text = "Incluir áudio do microfone"
            isChecked = false
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_text))
        }
        root.addView(microphoneSwitch, params(top = 12))

        shareButton = MaterialButton(this).apply {
            text = "Compartilhar tela"
            isAllCaps = false
            cornerRadius = dp(14)
            minimumHeight = dp(52)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_primary))
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_on_primary))
            setOnClickListener { requestShare() }
        }
        root.addView(shareButton, params(top = 14))

        stopButton = MaterialButton(this).apply {
            text = "Parar compartilhamento"
            isAllCaps = false
            cornerRadius = dp(14)
            minimumHeight = dp(52)
            setOnClickListener { WatchCaptureService.stop(this@WatchRoomActivity); syncButtons() }
        }
        root.addView(stopButton, params(top = 8))

        val leave = MaterialButton(this).apply {
            text = "Sair da sala"
            isAllCaps = false
            cornerRadius = dp(14)
            minimumHeight = dp(50)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_surface_variant))
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_text))
            setOnClickListener {
                WatchCaptureService.leave(this@WatchRoomActivity)
                finish()
            }
        }
        root.addView(leave, params(top = 8))
        scroll.addView(root)
        setContentView(scroll)
        renderParticipants("[]")
        syncButtons()
    }

    private fun requestShare() {
        if (microphoneSwitch.isChecked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else launchCapturePermission()
    }

    private fun launchCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun syncButtons() {
        if (!::shareButton.isInitialized) return
        val sharing = WatchCaptureService.isCaptureActive
        shareButton.visibility = if (sharing) View.GONE else View.VISIBLE
        stopButton.visibility = if (sharing) View.VISIBLE else View.GONE
    }

    private fun attachPreview() {
        if (!::preview.isInitialized || previewAttached) return
        previewAttached = WatchCaptureService.attachPreview(preview)
        if (previewAttached) {
            preview.parent?.let { parent ->
                if (parent is FrameLayout) parent.findViewWithTag<View>("empty_preview")?.visibility = View.GONE
            }
        }
    }

    private fun clearPreview() {
        if (::preview.isInitialized) {
            val parent = preview.parent as? FrameLayout
            val index = parent?.indexOfChild(preview) ?: -1
            val oldLayoutParams = preview.layoutParams
            if (previewAttached) WatchCaptureService.detachPreview(preview)
            else runCatching { preview.release() }
            previewAttached = false
            if (parent != null && index >= 0) {
                parent.removeViewAt(index)
                preview = SurfaceViewRenderer(this).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setSecure(true)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    contentDescription = "Tela compartilhada na sala"
                }
                parent.addView(preview, index, oldLayoutParams)
                parent.findViewWithTag<View>("empty_preview")?.visibility = View.VISIBLE
            }
        }
        syncButtons()
    }

    private fun renderParticipants(raw: String) {
        if (!::participantsList.isInitialized) return
        val names = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        participantsList.removeAllViews()
        participantCount.text = names.size.toString()
        names.ifEmpty { listOf("Aguardando participantes...") }.forEach { name ->
            val row = TextView(this).apply {
                text = "●   $name"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_text))
                setPadding(0, dp(9), 0, dp(9))
            }
            participantsList.addView(row)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        WatchCaptureService.leave(this)
        super.onBackPressed()
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
