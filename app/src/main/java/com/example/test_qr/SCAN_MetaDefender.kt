package com.example.test_qr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class SCAN_MetaDefender(private val urlToScan: String) {
    companion object {
        const val API_KEY = "4eacc6a1ee6e9c7dd3aab8cda6b7018e"
        const val METADEFENDER_API_URL = "https://api.metadefender.com/v4/url"
    }

    suspend fun checkUrl(): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val mapper = jacksonObjectMapper()

            try {
                // Подготовка данных для запроса
                val headers = mapOf(
                    "apikey" to API_KEY,
                    "content-type" to "application/json"
                )
                val data = mapOf("url" to listOf(urlToScan))
                val jsonPayload = mapper.writeValueAsString(data)

                // Создание запроса
                val request = Request.Builder()
                    .url(METADEFENDER_API_URL)
                    .post(jsonPayload.toRequestBody("application/json".toMediaTypeOrNull()))
                    .apply {
                        headers.forEach { (key, value) -> addHeader(key, value) }
                    }
                    .build()

                // Отправка запроса
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext "Ошибка при отправке запроса: ${response.code}"
                }

                val body = response.body?.string() ?: return@withContext "Пустой ответ от сервера"
                val result: Map<String, Any> = mapper.readValue(body)

                // Анализируем ответ
                when {
                    "error" in result -> "Ошибка при проверке сайта: ${result["error"]}"
                    "permalink" in result -> "Результат проверки доступен: ${result["permalink"]}"
                    else -> "Не удалось найти угрозы."
                }

            } catch (e: Exception) {
                e.printStackTrace()
                "Ошибка: ${e.message}"
            }
        }
    }
}
