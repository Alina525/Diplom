package com.example.test_qr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class SCAN_Google(private val urlToScan: String) {
    companion object {
        const val API_KEY = "AIzaSyAG8-fyoJRAo8RRRVZsFSKB75nU3mLfCrc"
        private const val SAFE_BROWSING_API_URL =
            "https://safebrowsing.googleapis.com/v4/threatMatches:find?key="
    }

    suspend fun scanGoogle(): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val mapper = jacksonObjectMapper()

            try {
                // Формируем тело запроса
                val payload = mapOf(
                    "client" to mapOf(
                        "clientId" to "yourcompanyname",
                        "clientVersion" to "1.0"
                    ),
                    "threatInfo" to mapOf(
                        "threatTypes" to listOf("MALWARE", "SOCIAL_ENGINEERING"),
                        "platformTypes" to listOf("ANY_PLATFORM"),
                        "threatEntryTypes" to listOf("URL"),
                        "threatEntries" to listOf(mapOf("url" to urlToScan))
                    )
                )

                val jsonPayload = mapper.writeValueAsString(payload)
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = jsonPayload.toRequestBody(mediaType)

                // Отправляем запрос
                val request = Request.Builder()
                    .url(SAFE_BROWSING_API_URL + API_KEY)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext "Ошибка при запросе: ${response.code}"
                }

                val body = response.body?.string() ?: return@withContext "Пустой ответ от сервера"
                val result: Map<String, Any> = mapper.readValue(body)

                // Анализируем ответ
                if ("matches" in result) {
                    val matches = result["matches"] as List<Map<String, Any>>
                    if (matches.isNotEmpty()) {
                        val threats = matches.joinToString("\n") { match ->
                            "- ${match["threatType"]} (${match["platformType"]}): ${match["threat"]}"
                        }
                        "Угрозы найдены:\n$threats"
                    } else {
                        "Угроз не обнаружено."
                    }
                } else {
                    "Угроз не обнаружено."
                }

            } catch (e: Exception) {
                e.printStackTrace()
                "Ошибка: ${e.message}"
            }
        }
    }
}
