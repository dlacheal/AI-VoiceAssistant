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
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AivaHttpClient {

    // Apunta al backend RAG alojado en Fedora (Spring Boot)
    private val HTTP_URL = "http://[IP_ADDRESS]:8082/api/chat" // Servidor RAG local

    // Ignorar certificados autofirmados (por si en un futuro hay HTTPS)
    private val client = crearClienteSSL()

    // ID de conversación único por sesión de la app para mantener el contexto en el backend RAG
    private val conversationId = UUID.randomUUID().toString()

    suspend fun sendMessage(text: String): String? = withContext(Dispatchers.IO) {
        // Formato esperado por el RAG (ChatRequest DTO)
        val body = JSONObject().apply {
            put("message", text)
            put("conversationId", conversationId)
        }

        val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(HTTP_URL)
            // No requiere Token actualmente ya que es local
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val rawBody = response.body?.string()
            Log.d("AivaHttpClient", "HTTP ${response.code} → $rawBody")

            if (response.isSuccessful && rawBody != null) {
                return@withContext try {
                    // Parsear respuesta formato ChatResponse: { "reply": "...", "conversationId": "..." }
                    val json = JSONObject(rawBody)
                    json.optString("reply", rawBody) // si no existe 'reply', devuelve todo el body
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
