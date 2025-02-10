package com.example.test_qr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class SCAN_VirusTotal(private val urlToScan: String) {
    companion object {
        const val API_KEY = "02ce5c7a181ea4b1058ec2cdac5eda5dc577bd3e18f7abbc8f2b841f47d7edc3"
        const val SCAN_URL = "https://www.virustotal.com/vtapi/v2/url/scan"
        const val REPORT_URL = "https://www.virustotal.com/vtapi/v2/url/report"
    }

    suspend fun scanAndAnalyze(): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val mapper = jacksonObjectMapper()

            try {
                // Отправка URL на сканирование
                val scanBody = FormBody.Builder()
                    .add("apikey", API_KEY)
                    .add("url", urlToScan)
                    .build()

                val scanRequest = Request.Builder()
                    .url(SCAN_URL)
                    .post(scanBody)
                    .build()

                val scanResponse = client.newCall(scanRequest).execute()
                if (!scanResponse.isSuccessful) {
                    return@withContext "Ошибка отправки URL: ${scanResponse.code}"
                }

                val scanResBody = scanResponse.body?.string()
                if (scanResBody.isNullOrBlank()) {
                    return@withContext "Ошибка - частые запросы, попробуйте снова через минуту."
                }
                var scanResult = try {
                    mapper.readValue<Map<String, Any>>(scanResBody)
                } catch (e: Exception) {
                    return@withContext "Ошибка с запросом сканирования"
                }
                val resource = scanResult["resource"] as String

                // Ждём завершения сканирования
//                Thread.sleep(2000)

                // Получение отчета по URL
                val reportRequest = Request.Builder()
                    .url("$REPORT_URL?apikey=$API_KEY&resource=$resource")
                    .get()
                    .build()

                var reportResponse = client.newCall(reportRequest).execute()
                if (!reportResponse.isSuccessful) {
                    return@withContext "Ошибка получения отчета: ${reportResponse.code}"
                }

                val responseBody = reportResponse.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext "Ошибка - пустой ответ от сервера"
                }

                var reportResult = try {
                    mapper.readValue<Map<String, Any>>(responseBody)
                } catch (e: Exception) {
                    return@withContext "Ошибка с запросом" // останавливаем цикл при ошибке десериализации
                }

                var responseCode = (reportResult["response_code"] as? Int) ?: 0
                var attempts = 0
                val maxAttempts = 10  // максимальное число попыток

                while (responseCode == 0 && attempts < maxAttempts) {
                    Thread.sleep(1000)  // ждем 1 секунду
                    attempts++

                    reportResponse = client.newCall(reportRequest).execute()
                    if (!reportResponse.isSuccessful) {
                        return@withContext "Ошибка получения отчета: ${reportResponse.code}"
                    }

                    reportResult = mapper.readValue<Map<String, Any>>(reportResponse.body?.string()!!)
                    responseCode = (reportResult["response_code"] as? Int) ?: 0
                }

                if (responseCode == 0) {
                    return@withContext "Ошибка - отчет не готов после ожидания"
                }

                val scans = reportResult["scans"] as Map<String, Map<String, String>>

                // Анализ отчета
                var malicious = 0
                var suspicious = 0
                val maliciousList = mutableListOf<String>()
                val suspiciousList = mutableListOf<String>()

                for ((key, value) in scans) {
                    when (value["result"]?.lowercase()) {
                        "malicious site", "malware site" -> {
                            malicious++
                            maliciousList.add(key)
                        }
                        "suspicious site", "not recommended site" -> {
                            suspicious++
                            suspiciousList.add(key)
                        }
                    }
                }

                return@withContext if (malicious > 0) {
                    "Не рекомендуется: опасный сайт (${maliciousList.joinToString(", ")})"
                } else if (suspicious > 0) {
                    "Не рекомендуется: подозрительный сайт (${suspiciousList.joinToString(", ")})"
                } else {
                    "Сайт безопасен"
                }

            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext "Ошибка: ${e.message}"
            }
        }
    }
}
