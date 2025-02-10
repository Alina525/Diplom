package com.example.test_qr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.Job

/**
 * Класс для хранения текущего результата и истории сканирований.
 */
class SharedData(
    private val historyRepository: HistoryRepository
) {
    var lastScannedResult by mutableStateOf("")
    var qrCodeLink: String = ""

    // Отслеживает активность экрана результата (устанавливается/сбрасывается в ResultScreen)
    var resultScreenActive by mutableStateOf(false)

    // Храним запущенную задачу проверки
    var safetyCheckJob: Job? = null

    // Новый флаг, указывающий, что проверка была отменена пользователем
    var checkCancelled: Boolean = false

    fun loadResult() {
        val loadedList = historyRepository.loadHistory()
        historyList.clear()
        historyList.addAll(loadedList)
    }

    fun saveResult() {
        historyRepository.saveHistory(historyList)
        println("История сохранена: ${historyList.size} элементов.")
    }

    /**
     * Установка нового результата сканирования.
     * Если проверка была отменена (checkCancelled == true) и статус равен "ошибка проверки",
     * то в итоговой записи будет сообщение о прерывании проверки.
     */
    fun setNewResult(
        scannedText: String,
        status: String,
        fullReport: String?
    ) {
        val internetErrorMessage = "Не удалось получить доступ к сервисам проверки. Проверьте соединение с интернетом."

        val finalReport = if (checkCancelled && status == "ошибка проверки") {
            "Проверка была прервана. Повторите попытку."
        } else {
            fullReport
        }

        lastScannedResult = buildString {
            append(status)
            append("\n\n")
            append(scannedText)
            if (!finalReport.isNullOrEmpty()) {
                append("\n\n")
                append(finalReport)
            }
        }

        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val now = sdf.format(Date())
        val item = HistoryItem(
            scannedResult = scannedText,
            status = status,
            fullReport = finalReport,
            dateTime = now
        )

        historyList.add(item)
        saveResult()

        // Сбрасываем флаг отмены после сохранения результата
        checkCancelled = false
    }

    fun clearTemp() {
        // НЕ очищаем qrCodeLink, чтобы сохранить сканированную ссылку для истории.
        lastScannedResult = ""
        // Не устанавливаем checkCancelled здесь – отмена определяется в onDispose.
        println("clearTemp вызван. checkCancelled: $checkCancelled")
    }

    var historyList = mutableStateListOf<HistoryItem>()
        private set

    fun clearHistory() {
        historyList.clear()
        saveResult()
    }
}
