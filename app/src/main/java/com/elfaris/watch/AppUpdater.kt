package com.elfaris.watch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

class AppUpdater(private val activity: MainActivity) {
    private val http = OkHttpClient()

    fun checkForUpdate(executor: Executor, notify: (String) -> Unit) {
        notify("Verificando atualizações...")
        executor.execute {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/MeliRzn/elfi-app/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Elfaris-Watch-Android")
                    .build()
                val release = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Não foi possível consultar as versões (${response.code}).")
                    JSONObject(response.body?.string().orEmpty())
                }
                val tag = release.optString("tag_name").removePrefix("v")
                if (tag.isBlank()) error("A versão publicada não possui uma identificação válida.")
                if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                    activity.runOnUiThread { notify("O Elfaris Watch já está atualizado.") }
                    return@execute
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                    activity.runOnUiThread {
                        AlertDialog.Builder(activity)
                            .setTitle("Permitir atualização")
                            .setMessage("Existe uma nova versão. O Android precisa permitir que o Elfaris Watch instale o APK baixado.")
                            .setNegativeButton("Agora não", null)
                            .setPositiveButton("Abrir configuração") { _, _ ->
                                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                            }
                            .show()
                    }
                    return@execute
                }
                val assets = release.optJSONArray("assets") ?: error("A versão não possui APK anexado.")
                var downloadUrl = ""
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    if (asset.optString("name") == "elfaris-watch.apk") {
                        downloadUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (downloadUrl.isBlank()) error("O APK desta versão ainda não foi publicado.")
                activity.runOnUiThread { notify("Baixando a versão $tag...") }
                val apk = File(activity.cacheDir, "updates/elfaris-watch.apk")
                apk.parentFile?.mkdirs()
                val download = Request.Builder().url(downloadUrl).header("User-Agent", "Elfaris-Watch-Android").build()
                http.newCall(download).execute().use { response ->
                    if (!response.isSuccessful) error("Falha ao baixar o APK (${response.code}).")
                    val body = response.body ?: error("O servidor retornou um APK vazio.")
                    body.byteStream().use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", apk)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.runOnUiThread {
                    notify("Download concluído. Abra o instalador para atualizar.")
                    activity.startActivity(install)
                }
            } catch (error: Exception) {
                activity.runOnUiThread { notify(error.message ?: "Não foi possível verificar atualizações.") }
            }
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }
}
