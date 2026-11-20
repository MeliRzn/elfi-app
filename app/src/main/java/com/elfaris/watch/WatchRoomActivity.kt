package com.elfaris.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.json.JSONArray

class WatchRoomActivity : AppCompatActivity() {
    private lateinit var preview: SurfaceViewRenderer
    private lateinit var participantsList: LinearLayout
    private lateinit var participantCount: TextView
    private lateinit var status: TextView
    private var previewAttached = false

    private val roomReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WatchCaptureService.ACTION_PREVIEW_READY -> attachPreview()
                WatchCaptureService.ACTION_ROOM_PARTICIPANTS ->
                    renderParticipants(intent.getStringExtra(WatchCaptureService.EXTRA_PARTICIPANTS).orEmpty())
                WatchCaptureService.ACTION_CAPTURE_STOPPED -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.watch_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.watch_background)
        buildRoomScreen()
        attachPreview()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WatchCaptureService.ACTION_PREVIEW_READY)
            addAction(WatchCaptureService.ACTION_ROOM_PARTICIPANTS)
            addAction(WatchCaptureService.ACTION_CAPTURE_STOPPED)
        }
        ContextCompat.registerReceiver(this, roomReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        attachPreview()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(roomReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        if (previewAttached) {
            WatchCaptureService.detachPreview(preview)
            previewAttached = false
        } else {
            runCatching { preview.release() }
        }
        super.onDestroy()
    }

    private fun buildRoomScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_background))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
            text = "Conectando aos participantes..."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_muted))
        }
        top.addView(brand)
        top.addView(heading, params(top = 5))
        top.addView(status, params(top = 4))
        root.addView(top)

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
            contentDescription = "Prévia da tela compartilhada"
        }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val live = TextView(this).apply {
            text = "●  VOCÊ ESTÁ COMPARTILHANDO"
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(0xCC20242B.toInt())
        }
        frame.addView(live, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(10), dp(10), 0, 0)
        })
        previewCard.addView(frame, LinearLayout.LayoutParams(-1, dp(260)))
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
            text = "1"
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

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val stop = MaterialButton(this).apply {
            text = "Parar compartilhamento"
            isAllCaps = false
            cornerRadius = dp(14)
            minimumHeight = dp(52)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_primary))
            setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_on_primary))
            setOnClickListener { stopAndLeave() }
        }
        root.addView(stop, params(top = 16))
        setContentView(root)
        renderParticipants("[\"Você · transmitindo\"]")
    }

    private fun attachPreview() {
        if (previewAttached || !::preview.isInitialized) return
        previewAttached = WatchCaptureService.attachPreview(preview)
        if (previewAttached) status.text = "Sua transmissão está ativa e visível para quem entrou na sala."
    }

    private fun renderParticipants(raw: String) {
        if (!::participantsList.isInitialized) return
        val names = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        participantsList.removeAllViews()
        participantCount.text = names.size.coerceAtLeast(1).toString()
        (names.ifEmpty { listOf("Você · transmitindo") }).forEach { name ->
            val row = TextView(this).apply {
                text = "●   $name"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@WatchRoomActivity, R.color.watch_text))
                setPadding(0, dp(9), 0, dp(9))
            }
            participantsList.addView(row)
        }
    }

    private fun stopAndLeave() {
        WatchCaptureService.stop(this)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        stopAndLeave()
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
