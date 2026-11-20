package com.elfaris.watch

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: WatchSessionStore
    private val api = WatchApi()
    private var account: WatchAccount? = null
    private var selectedAvatar: ByteArray? = null
    private var profileAvatarView: ImageView? = null
    private var pendingRoomUrl: String? = null
    private var pendingActivationToken: String? = null
    private var showingSharingScreen = false
    private val captureStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (showingSharingScreen) {
                showHome(isSharing = false)
                toast(intent?.getStringExtra(WatchCaptureService.EXTRA_STATUS)
                    ?: "A transmissão foi encerrada.")
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(RequestPermission()) {
        launchScreenCapture()
    }

    private val screenCaptureLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val roomUrl = pendingRoomUrl
        pendingRoomUrl = null
        if (result.resultCode != Activity.RESULT_OK || result.data == null || roomUrl == null) {
            toast("O compartilhamento de tela foi cancelado.")
            return@registerForActivityResult
        }
        val includeMicrophone = store.microphoneEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val serviceIntent = Intent(this, WatchCaptureService::class.java).apply {
            action = WatchCaptureService.ACTION_START
            putExtra(WatchCaptureService.EXTRA_SERVER, store.server)
            putExtra(WatchCaptureService.EXTRA_ROOM_URL, roomUrl)
            putExtra(WatchCaptureService.EXTRA_NAME, account?.name ?: "Convidado")
            putExtra(WatchCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(WatchCaptureService.EXTRA_PROJECTION_DATA, result.data)
            putExtra(WatchCaptureService.EXTRA_MICROPHONE, includeMicrophone)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        showingSharingScreen = false
        startActivity(Intent(this, WatchRoomActivity::class.java))
        toast(if (includeMicrophone) "Entrando na sala e iniciando a transmissão." else "Entrando na sala e iniciando a transmissão sem microfone.")
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedAvatar = readAndCompressImage(uri)
            selectedAvatar?.let { bytes ->
                profileAvatarView?.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
            toast(if (selectedAvatar != null) "Foto selecionada." else "Não foi possível abrir essa imagem.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.watch_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.watch_background)
        store = WatchSessionStore(this)
        if (!handleDeepLink(intent)) refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleDeepLink(intent)) refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            captureStoppedReceiver,
            IntentFilter(WatchCaptureService.ACTION_CAPTURE_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(captureStoppedReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (showingSharingScreen && !WatchCaptureService.isCaptureActive) {
            showHome(isSharing = false)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun handleDeepLink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "elfariswatch") return false
        val server = uri.getQueryParameter("server")
        if (!server.isNullOrBlank()) {
            try {
                store.saveServer(WatchApi.normalizeServer(server))
            } catch (error: Exception) {
                showMessage(error.message ?: "Endereço do servidor inválido.")
                return true
            }
        }
        when (uri.host) {
            "connect" -> {
                refresh()
            }
            "activate" -> {
                val token = uri.getQueryParameter("token").orEmpty()
                if (token.isBlank() || store.server.isBlank()) {
                    showActivationScreen("O link está incompleto. Solicite outro pelo WhatsApp.")
                } else {
                    activate(token)
                }
            }
            else -> return false
        }
        return true
    }

    private fun refresh() {
        if (store.server.isBlank() || store.token.isBlank()) return showActivationScreen()
        showLoading("Conferindo sua conta...")
        executor.execute {
            try {
                val result = api.me(store.server, store.token)
                runOnUiThread {
                    account = result
                    showHome()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (error is WatchApiException && error.statusCode == 401) store.clearSession()
                    showActivationScreen(error.message ?: "Não foi possível conectar ao servidor.")
                }
            }
        }
    }

    private fun activate(token: String, name: String = "") {
        pendingActivationToken = token
        showLoading("Ativando sua conta...")
        executor.execute {
            try {
                val session = api.activate(store.server, token, name)
                store.saveSession(store.server, session.token)
                runOnUiThread {
                    pendingActivationToken = null
                    account = session.account
                    showHome()
                    toast("Conta ativada com sucesso.")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (error is WatchApiException && error.statusCode == 410) pendingActivationToken = null
                    showActivationScreen(error.message ?: "Não foi possível ativar sua conta.")
                }
            }
        }
    }

    private fun showConnectScreen() {
        showActivationScreen("Cole aqui o link pessoal enviado pelo comando cadastro no WhatsApp. O servidor será configurado automaticamente.")
    }

    private fun showActivationScreen(message: String? = null) {
        val root = screenBase()
        addHeader(root, "Vincular conta", "Entre com o link pessoal que a Elfi enviou no WhatsApp.")
        val content = card(root)
        addText(content, "Adicionar link de ativação", 20f, true, R.color.watch_text)
        addText(content, message ?: "Copie o link recebido após enviar cadastro no privado do bot e cole no campo abaixo. O app identifica o servidor e ativa sua conta automaticamente.", 14f, false, R.color.watch_muted)
        val activationInput = input(content, "Cole o link completo recebido no WhatsApp")
        val nameInput = input(content, "Nome (opcional, se o WhatsApp não enviou)")
        button(content, "Vincular minha conta").setOnClickListener {
            val raw = activationInput.text?.toString()?.trim().orEmpty()
            if (raw.isBlank()) {
                showMessage("Cole o link de ativação recebido no WhatsApp.")
                return@setOnClickListener
            }
            try {
                val uri = Uri.parse(raw)
                val server: String
                val token: String
                if (uri.scheme == "elfariswatch" && uri.host == "activate") {
                    server = uri.getQueryParameter("server").orEmpty()
                    token = uri.getQueryParameter("token").orEmpty()
                } else {
                    require(uri.scheme == "https" && uri.path == "/watch/activate" && !uri.host.isNullOrBlank()) {
                        "Esse não parece ser um link de ativação do Elfaris Watch."
                    }
                    server = uri.buildUpon().encodedPath("").clearQuery().fragment(null).build().toString().trimEnd('/')
                    val fragment = uri.fragment.orEmpty()
                    token = uri.getQueryParameter("token")
                        ?: Uri.parse("https://activation.local/?$fragment").getQueryParameter("token").orEmpty()
                }
                require(token.matches(Regex("^[A-Za-z0-9_-]{40,60}$"))) {
                    "O link não contém um token válido. Copie o link completo novamente."
                }
                store.saveServer(WatchApi.normalizeServer(server))
                activate(token, nameInput.text?.toString()?.trim().orEmpty())
            } catch (error: Exception) {
                showMessage(error.message ?: "Não foi possível ler o link de ativação.")
            }
        }
        addText(content, "O link é pessoal, de uso único e expira em 10 minutos. Depois da ativação, sua sessão fica salva neste aparelho.", 12f, false, R.color.watch_muted)
    }

    private fun showHome(isSharing: Boolean = false) {
        showingSharingScreen = isSharing
        val user = account ?: return showActivationScreen()
        val root = screenBase(showNavigation = true, selectedTab = 1)
        addHeader(
            root,
            if (isSharing) "Você está na sala" else "Elfaris Watch",
            if (isSharing) "Sua transmissão está ativa e disponível para os participantes." else "Olá, ${user.name}. Entre em uma sala compartilhada."
        )
        val content = card(root)
        if (isSharing) {
            addText(content, "Transmissão ativa", 19f, true, R.color.watch_text)
            addText(content, "Sua tela está sendo enviada diretamente aos outros participantes. Você pode encerrar sua transmissão a qualquer momento.", 14f, false, R.color.watch_muted)
            button(content, "Parar minha transmissão").setOnClickListener {
                WatchCaptureService.stop(this)
                showHome(isSharing = false)
                toast("Encerrando sua transmissão.")
            }
        } else {
            addText(content, "Entrar em uma sala", 19f, true, R.color.watch_text)
            addText(content, "Cole o link compartilhável enviado pelo WhatsApp. Qualquer pessoa com o link pode participar.", 14f, false, R.color.watch_muted)
            val roomInput = input(content, "Link da sala")
            val microphoneSwitch = SwitchMaterial(this).apply {
                text = "Incluir áudio do microfone"
                isChecked = store.microphoneEnabled
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.watch_text))
                thumbTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.watch_primary))
                trackTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.watch_outline))
                setOnCheckedChangeListener { _, checked -> store.saveMicrophoneEnabled(checked) }
            }
            content.addView(microphoneSwitch, marginParams(top = 14))
            button(content, "Entrar na sala e compartilhar tela").setOnClickListener {
                beginRoomCapture(roomInput.text?.toString().orEmpty())
            }
            addText(content, "O compartilhamento começa após a confirmação do Android. O microfone é opcional.", 12f, false, R.color.watch_muted)
        }
        button(content, "Verificar atualizações", outlined = true).setOnClickListener {
            AppUpdater(this).checkForUpdate(executor, ::showMessage)
        }
    }

    private fun showProfile() {
        val user = account ?: return showActivationScreen()
        selectedAvatar = null
        val root = screenBase(showNavigation = true, selectedTab = 2)
        addHeader(root, "Meu perfil", "Gerencie os dados da sua conta Elfaris Watch.")
        val content = card(root)
        addText(content, "Informações pessoais", 19f, true, R.color.watch_text)
        val avatarView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.watch_surface_variant))
            contentDescription = "Foto de perfil"
        }
        profileAvatarView = avatarView
        content.addView(avatarView, LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(14) })
        if (!user.avatarUrl.isNullOrBlank()) {
            executor.execute {
                try {
                    val bytes = api.avatar(store.server, store.token)
                    val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    runOnUiThread { if (bitmap != null) profileAvatarView?.setImageBitmap(bitmap) }
                } catch (_: Exception) { }
            }
        }
        val nameInput = input(content, "Nome")
        nameInput.setText(user.name)
        addText(content, "NÚMERO DO WHATSAPP", 11f, true, R.color.watch_primary)
        addText(content, formatPhone(user.phone), 14f, false, R.color.watch_muted)
        button(content, "Escolher foto de perfil", outlined = true).setOnClickListener { imagePicker.launch("image/*") }
        val save = button(content, "Salvar alterações")
        save.setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) return@setOnClickListener showMessage("Informe seu nome.")
            save.isEnabled = false
            executor.execute {
                try {
                    val updated = api.updateProfile(store.server, store.token, name, selectedAvatar)
                    selectedAvatar = null
                    runOnUiThread {
                        account = updated
                        profileAvatarView = null
                        showHome()
                        toast("Perfil atualizado.")
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        save.isEnabled = true
                        showMessage(error.message ?: "Não foi possível salvar o perfil.")
                    }
                }
            }
        }
        button(content, "Sair da conta", outlined = true).setOnClickListener { logout() }
    }

    private fun logout() {
        val token = store.token
        val server = store.server
        showLoading("Encerrando sessão...")
        executor.execute {
            try { api.logout(server, token) } catch (_: Exception) { }
            store.clearSession()
            runOnUiThread {
                account = null
                showActivationScreen("Você saiu da conta. Para entrar novamente, solicite um novo link pelo WhatsApp.")
            }
        }
    }

    private fun beginRoomCapture(value: String) {
        try {
            val uri = Uri.parse(value.trim())
            val serverUri = Uri.parse(store.server)
            require(uri.scheme == "https" && uri.authority == serverUri.authority &&
                uri.path.orEmpty().matches(Regex("^/watch/[A-Za-z0-9_-]{8,}$")) &&
                uri.getQueryParameter("host").isNullOrBlank()) {
                "Use o link HTTPS compartilhável gerado pelo comando transmitir app."
            }
            pendingRoomUrl = uri.toString()
            if (store.microphoneEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                launchScreenCapture()
            }
        } catch (error: Exception) {
            showMessage(error.message ?: "Link de sala inválido.")
        }
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openRoom(value: String) {
        try {
            val uri = Uri.parse(value.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.path.orEmpty().startsWith("/watch/")) {
                "Cole um link HTTPS válido de uma sala Elfaris Watch."
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Exception) {
            showMessage(error.message ?: "Link de sala inválido.")
        }
    }

    private fun readAndCompressImage(uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 2560) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val maxSide = 1280
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
            val resized = if (scale < 1f) Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true
            ) else bitmap
            val output = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 82, output)
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
            output.toByteArray().takeIf { it.size <= 2 * 1024 * 1024 }
        } catch (_: Exception) {
            null
        }
    }

    private fun screenBase(showNavigation: Boolean = false, selectedTab: Int = 1): LinearLayout {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.watch_background))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.watch_background))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(-1, -2))
        }
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (showNavigation) {
            val navigation = com.google.android.material.bottomnavigation.BottomNavigationView(this).apply {
                menu.add(0, 1, 0, "Início").setIcon(R.drawable.ic_home)
                menu.add(0, 2, 1, "Perfil").setIcon(R.drawable.ic_profile)
                selectedItemId = selectedTab
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.watch_surface))
                itemIconTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(this@MainActivity, R.color.watch_primary),
                        ContextCompat.getColor(this@MainActivity, R.color.watch_muted)
                    )
                )
                itemTextColor = itemIconTintList
                setOnItemSelectedListener { item ->
                    when (item.itemId) {
                        1 -> { if (selectedTab != 1) showHome(); true }
                        2 -> { if (selectedTab != 2) showProfile(); true }
                        else -> false
                    }
                }
            }
            shell.addView(navigation, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(shell)
        return column
    }

    private fun addHeader(root: LinearLayout, title: String, subtitle: String) {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.elfaris_brand)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Logo Elfaris"
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)).apply {
            bottomMargin = dp(16)
        })
        addText(root, title, 26f, true, R.color.watch_text)
        addText(root, subtitle, 14f, false, R.color.watch_muted)
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val surface = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@MainActivity, R.color.watch_outline)
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.watch_surface))
            addView(content)
        }
        parent.addView(surface, marginParams(top = 22, bottom = 4))
        return content
    }

    private fun input(parent: LinearLayout, hint: String): TextInputEditText {
        val wrapper = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = ContextCompat.getColor(this@MainActivity, R.color.watch_primary)
            boxBackgroundColor = ContextCompat.getColor(this@MainActivity, R.color.watch_surface_variant)
            setHintTextColor(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.watch_muted)))
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
        }
        val edit = TextInputEditText(wrapper.context).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.watch_text))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.watch_muted))
            setSingleLine(true)
        }
        wrapper.addView(edit)
        parent.addView(wrapper, marginParams(top = 16))
        return edit
    }

    private fun button(parent: ViewGroup, label: String, outlined: Boolean = false): MaterialButton {
        val view = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            cornerRadius = dp(14)
            minimumHeight = dp(50)
            insetTop = 0
            insetBottom = 0
            if (outlined) {
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.watch_primary))
                strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.watch_outline))
                strokeWidth = dp(1)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.watch_surface))
            } else {
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.watch_on_primary))
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.watch_primary))
            }
        }
        parent.addView(view, marginParams(top = 12))
        return view
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, bold: Boolean, color: Int) {
        val view = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        parent.addView(view, marginParams(top = 7))
    }

    private fun showLoading(message: String) {
        val root = screenBase()
        addHeader(root, "Elfaris Watch", message)
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun toast(message: String) = showMessage(message)

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("Endereço copiado.")
    }

    private fun formatPhone(phone: String): String {
        if (phone.startsWith("55") && phone.length >= 12) {
            val national = phone.drop(2)
            val subscriber = national.drop(2)
            val split = if (subscriber.length == 9) 5 else 4
            return "+55 (${national.take(2)}) ${subscriber.take(split)}-${subscriber.drop(split)}"
        }
        return "+$phone"
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
