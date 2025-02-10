package com.example.test_qr

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.util.Log

/**
 * Модель одного элемента истории сканирований.
 */
data class HistoryItem(
    val scannedResult: String?,
    val status: String?,
    val fullReport: String?,
    val dateTime: String?
)

/**
 * Репозиторий для сохранения и загрузки истории в SharedPreferences.
 */
class HistoryRepository(private val context: Context) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("qr_app_prefs", Context.MODE_PRIVATE)

    fun saveHistory(historyList: List<HistoryItem>) {
        Log.d("HistoryRepository", "Saving history. Size: ${historyList.size}")
        val gson = Gson()
        val historyJson = gson.toJson(historyList)
        prefs.edit().putString("history_list_key", historyJson).apply()
    }

    fun loadHistory(): List<HistoryItem> {
        // Логирование загрузки данных
        Log.d("HistoryRepository", "Loading history...")
        val historyJson = prefs.getString("history_list_key", null)
        if (historyJson != null) {
            Log.d("HistoryRepository", "Loaded history size: ${historyJson.length}")
        }
        return if (!historyJson.isNullOrEmpty()) {
            val gson = Gson()
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            gson.fromJson(historyJson, type)
        } else {
            emptyList()
        }
    }
}
