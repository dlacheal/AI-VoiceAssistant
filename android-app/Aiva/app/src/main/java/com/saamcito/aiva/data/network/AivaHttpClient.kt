package com.saamcito.aiva.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AivaHttpClient {

    // Puerto 18789 = gateway HTTP plano (sin TLS, a diferencia del WebSocket 18790)
    private val HTTP_URL = "http://192.168.1.X:18789/v1/chat/completions" // Reemplaza con la IP de tu servidor
    private val TOKEN = "<YOUR_TOKEN>" // Reemplaza con tu token de openclaw.json

    // Ignorar certificados autofirmados
    private val client = crearClienteSSL()

    suspend fun sendMessage(text: String): String? = withContext(Dispatchers.IO) {
        // Formato OpenAI: { model, messages: [{role, content}] }
        val body = JSONObject().apply {
            put("model", "ollama/qwen2.5:3b")   // modelo primario de openclaw.json
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("stream", false)
        }

        val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(HTTP_URL)
            .addHeader("Authorization", "Bearer $TOKEN")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val rawBody = response.body?.string()
            Log.d("AivaHttpClient", "HTTP ${response.code} → $rawBody")

            if (response.isSuccessful && rawBody != null) {
                return@withContext try {
                    // Parsear respuesta formato OpenAI: choices[0].message.content
                    val json = JSONObject(rawBody)
                    val choices = json.optJSONArray("choices")
                    choices?.getJSONObject(0)
                        ?.getJSONObject("message")
                        ?.optString("content")
                        ?: rawBody
                } catch (e: Exception) {
                    rawBody
                }
            } else {
                Log.e("AivaHttpClient", "Error HTTP ${response.code}: $rawBody")
                return@withContext "ERROR_HTTP|${response.code}|$rawBody"
            }
        } catch (e: Exception) {
            Log.e("AivaHttpClient", "Exception: ${e.message}")
            return@withContext "ERROR_CONN|${e.message}"
        }
    }

    private fun crearClienteSSL(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            // Timeouts extendidos para modelos de IA locales lentos
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // 2 min para generar respuesta
            .build()
    }
}
