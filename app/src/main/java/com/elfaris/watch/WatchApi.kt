package com.elfaris.watch

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WatchAccount(
    val id: String,
    val name: String,
    val phone: String,
    val avatarUrl: String?
)

data class WatchSession(
    val token: String,
    val account: WatchAccount
)

class WatchApiException(val statusCode: Int, message: String) : IOException(message)

class WatchApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun activate(server: String, activationToken: String, name: String): WatchSession {
        val body = JSONObject()
            .put("token", activationToken)
            .apply { if (name.isNotBlank()) put("name", name.trim()) }
        val response = request(server, "/watch/auth/activate", "POST", body, null)
        return WatchSession(response.getString("token"), parseAccount(response.getJSONObject("account")))
    }

    fun me(server: String, token: String): WatchAccount {
        val response = request(server, "/watch/auth/me", "GET", null, token)
        return parseAccount(response.getJSONObject("account"))
    }

    fun updateProfile(
        server: String,
        token: String,
        name: String,
        avatarBytes: ByteArray?
    ): WatchAccount {
        val body = JSONObject().put("name", name.trim())
        if (avatarBytes != null) {
            body.put("avatarBase64", Base64.encodeToString(avatarBytes, Base64.NO_WRAP))
            body.put("avatarMime", "image/jpeg")
        }
        val response = request(server, "/watch/auth/profile", "PATCH", body, token)
        return parseAccount(response.getJSONObject("account"))
    }

    fun avatar(server: String, token: String): ByteArray? {
        val request = Request.Builder()
            .url(normalizeServer(server) + "/watch/auth/avatar")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw WatchApiException(response.code, "Não foi possível carregar a foto de perfil.")
            return response.body?.bytes()
        }
    }

    fun logout(server: String, token: String) {
        request(server, "/watch/auth/logout", "POST", JSONObject(), token)
    }

    private fun parseAccount(json: JSONObject) = WatchAccount(
        id = json.optString("id"),
        name = json.optString("name"),
        phone = json.optString("phone"),
        avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" }
    )

    private fun request(
        server: String,
        path: String,
        method: String,
        payload: JSONObject?,
        token: String?
    ): JSONObject {
        val base = normalizeServer(server)
        val builder = Request.Builder().url(base + path)
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")

        val body = payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        when (method) {
            "POST" -> builder.post(body ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            "PATCH" -> builder.patch(body ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            "GET" -> builder.get()
            else -> throw IllegalArgumentException("Método não suportado.")
        }

        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try {
                JSONObject(raw.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }
            if (!response.isSuccessful) {
                val message = json.optString("error").ifBlank {
                    "O servidor respondeu com erro HTTP ${response.code}."
                }
                throw WatchApiException(response.code, message)
            }
            return json
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeServer(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            val uri = android.net.Uri.parse(trimmed)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                "Use o endereço HTTPS enviado pelo bot em transmitir app."
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.encodedPath.isNullOrBlank() || uri.encodedPath == "/")) {
                "Informe somente a origem HTTPS do servidor, sem caminhos ou parâmetros."
            }
            return trimmed
        }
    }
}
