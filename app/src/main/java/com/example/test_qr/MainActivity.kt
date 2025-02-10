package com.example.test_qr

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.test_qr.ui.theme.Test_QRTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.UnknownHostException
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.remember
import androidx.camera.core.Camera
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import kotlinx.coroutines.ensureActive

class MainActivity : ComponentActivity() {

    // Запрос разрешения на использование камеры
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Можно обработать результат запроса, если потребуется
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Создаём репозиторий для истории
        val historyRepository = HistoryRepository(this)
        // Создаём SharedData, которому передаём репозиторий
        val sharedData = SharedData(historyRepository = historyRepository)
        // Загружаем историю
        sharedData.loadResult()
        // Запрашиваем разрешение на камеру
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            Test_QRTheme {
                // Создаём NavController для навигации между экранами
                val navController = rememberNavController()
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "scanner",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("scanner") { ScannerScreen(navController, sharedData) }
                        composable("result") { ResultScreen(navController, sharedData) }
                        composable("history") { HistoryScreen(navController, sharedData) }
                    }
                }
            }
        }
    }
}

/**
 * Экран сканирования с использованием CameraX и распознаванием QR-кодов через ML Kit.
 */
@Composable
fun ScannerScreen(navController: NavHostController, sharedData: SharedData) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanTimeout by remember { mutableStateOf(false) }

    // Флаг для остановки анализа после первого найденного QR-кода
    var hasFoundCode by remember { mutableStateOf(false) }

    fun onQrCodeScanned(decodedText: String) {
        if (hasFoundCode) return
        hasFoundCode = true
        scanTimeout = false

        // Сбрасываем флаг отмены для нового сканирования
        sharedData.checkCancelled = false

        // Сохраняем отсканированный текст (ссылка)
        sharedData.qrCodeLink = decodedText
        sharedData.lastScannedResult = ""

        navController.navigate("result")

        // Отменяем предыдущую задачу проверки (если такая была)
        sharedData.safetyCheckJob?.cancel()

        // Запускаем новую задачу проверки с возможностью отмены
        sharedData.safetyCheckJob = lifecycleOwner.lifecycleScope.launch {
            val currentScanned = decodedText

            if (currentScanned.startsWith("http")) {
                // --- 1. Проверка интернет-соединения ---
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkInfo = connectivityManager.activeNetworkInfo
                if (networkInfo == null || !networkInfo.isConnected) {
                    if (sharedData.lastScannedResult.isEmpty() && !sharedData.checkCancelled) {
                        sharedData.setNewResult(
                            scannedText = currentScanned,
                            status = "ошибка проверки",
                            fullReport = "Не удалось получить доступ к сервисам проверки. Проверьте соединение с интернетом."
                        )
                    }
                    return@launch
                }

                if (sharedData.checkCancelled) return@launch

                // --- 2. Вызов сервисов проверки ---
                val vtResult = try {
                    val res = SCAN_VirusTotal(currentScanned).scanAndAnalyze()
                    if (sharedData.checkCancelled) return@launch
                    res
                } catch (e: Exception) {
                    if (e is UnknownHostException || e is ConnectException)
                        "Нет подключения к интернету (VirusTotal)"
                    else{
                        if (e.message?.contains("StandaloneCoroutine") == true) return@launch
                        "Ошибка при проверке VirusTotal: ${e.message}"
                    }
                }

                if (sharedData.checkCancelled) return@launch

                val googleResult = try {
                    val res = SCAN_Google(currentScanned).scanGoogle()
                    if (sharedData.checkCancelled) return@launch
                    res
                } catch (e: Exception) {
                    if (e is UnknownHostException || e is ConnectException)
                        "Нет подключения к интернету (Google Safe Browsing)"
                    else{
                        if (e.message?.contains("StandaloneCoroutine") == true) return@launch
                        "Ошибка при проверке Google Safe Browsing: ${e.message}"
                    }
                }

                if (sharedData.checkCancelled) return@launch

                val metaResult = try {
                    val res = SCAN_MetaDefender(currentScanned).checkUrl()
                    if (sharedData.checkCancelled) return@launch
                    res
                } catch (e: Exception) {
                    if (e is UnknownHostException || e is ConnectException)
                        "Нет подключения к интернету (MetaDefender)"
                    else {
                        if (e.message?.contains("StandaloneCoroutine") == true) return@launch
                        "Ошибка при проверке MetaDefender: ${e.message}"
                    }
                }

                if (sharedData.checkCancelled) return@launch

                // --- 3. Анализ результатов сервисов ---
                val connectionErrorOccurred =
                    vtResult.contains("нет подключения к интернету", ignoreCase = true) ||
                            googleResult.contains("нет подключения к интернету", ignoreCase = true) ||
                            metaResult.contains("нет подключения к интернету", ignoreCase = true)

                if (connectionErrorOccurred) {
                    if (sharedData.lastScannedResult.isEmpty() && !sharedData.checkCancelled) {
                        sharedData.setNewResult(
                            scannedText = currentScanned,
                            status = "ошибка проверки",
                            fullReport = "Не удалось получить доступ к сервисам проверки. Проверьте соединение с интернетом."
                        )
                    }
                    return@launch
                }

                val maliciousSources = mutableListOf<String>()
                if (vtResult.startsWith("Не рекомендуется", ignoreCase = true)) maliciousSources.add("VirusTotal")
                if (googleResult.startsWith("Угрозы найдены", ignoreCase = true)) maliciousSources.add("Google Safe Browsing")
                if (metaResult.startsWith("Не рекомендуется", ignoreCase = true)) maliciousSources.add("MetaDefender")

                val isAnyError =
                    vtResult.contains("Ошибка", ignoreCase = true) ||
                            googleResult.contains("Ошибка", ignoreCase = true) ||
                            metaResult.contains("Ошибка", ignoreCase = true)

                val combinedStatus = when {
                    maliciousSources.isNotEmpty() -> "опасно"
                    isAnyError -> "ошибка проверки"
                    else -> "безопасно"
                }

                val detailedReport = buildString {
                    append("Статус: $combinedStatus\n\n")
                    append("VirusTotal: $vtResult\n")
                    append("Google: $googleResult\n")
                    append("MetaDefender: $metaResult\n")
                    if (maliciousSources.isNotEmpty()) {
                        append("\nИсточники угроз: ${maliciousSources.joinToString(", ")}")
                    }
                }

                if (sharedData.lastScannedResult.isEmpty() && !sharedData.checkCancelled) {
                    sharedData.setNewResult(
                        scannedText = currentScanned,
                        status = combinedStatus,
                        fullReport = detailedReport
                    )
                }
            } else {
                if (sharedData.lastScannedResult.isEmpty() && !sharedData.checkCancelled) {
                    // Если отсканированный текст не является ссылкой
                    sharedData.setNewResult(
                        scannedText = currentScanned,
                        status = "не ссылка",
                        fullReport = null
                    )
                }
            }
        }
    }

    // Запускаем таймер ожидания (10 секунд). Если код так и не найден – показываем сообщение.
    LaunchedEffect(key1 = hasFoundCode) {
        if (!hasFoundCode) {
            kotlinx.coroutines.delay(15000L)
            if (!hasFoundCode) {
                scanTimeout = true
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Превью камеры
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            CameraPreview(
                lifecycleOwner = lifecycleOwner,
                onQrCodeScanned = ::onQrCodeScanned,
                canAnalyze = !hasFoundCode
            )
        }

        // Если время ожидания истекло, выводим оверлей с сообщением и кнопкой повтора
        if (scanTimeout) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Время ожидания истекло. QR-код не распознан.\n" +
                                "Возможные причины:\n" +
                                "1. QR-код повреждён или не корректен (проверьте целостность и правильность QR-кода);\n" +
                                "2. Плохое освещение (включите вспышку);\n" +
                                "3. Плохое качество видео (протрите камеру или отсканируйте с другого устройства).",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                    Button(onClick = {
                        scanTimeout = false
                        hasFoundCode = false
                    }) {
                        Text("Повторить попытку")
                    }
                }
            }
        }

        // Текст-инструкция
        Text(
            text = "Наведите камеру на QR-код",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )

        // Нижняя панель с кнопками
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { /* Можно реализовать ручной запуск сканирования */ }) {
                Text("Сканирование")
            }
            Button(onClick = { navController.navigate("history") }) {
                Text("История")
            }
        }
    }
}

/**
 * Composable с превью камеры и анализом кадров через ML Kit.
 */
@Composable
fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    onQrCodeScanned: (String) -> Unit,
    canAnalyze: Boolean
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    // Состояния для управления вспышкой и зумом
    var flashOn by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(key1 = canAnalyze) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get() // Получаем CameraProvider

        // Создаем Preview и ImageAnalysis
        val preview = Preview.Builder()
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK) // Выбираем заднюю камеру
            .build()

        // Настроим Preview на PreviewView
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // Создаем сканер для QR-кодов
        val scannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        val scanner = BarcodeScanning.getClient(scannerOptions)

        // Устанавливаем анализ изображения для сканирования QR-кодов
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            if (!canAnalyze) {
                imageProxy.close()
                return@setAnalyzer
            }
            processImageProxy(scanner, imageProxy, onQrCodeScanned)
        }

        try {
            cameraProvider.unbindAll() // Развязываем все текущие use cases
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, // Подключаем к жизненному циклу
                cameraSelector, // Выбираем камеру
                preview,        // Превью
                imageAnalysis   // Анализ изображения
            )
            cameraRef = camera // Сохраняем ссылку на камеру
        } catch (exc: Exception) {
            exc.printStackTrace()
        }
    }

    // Родительский контейнер на весь экран
    Box(modifier = Modifier.fillMaxSize()) {
        // Превью камеры
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        // Оверлей для элементов управления
        Box(modifier = Modifier.fillMaxSize()) {
            // Кнопка вспышки в верхнем правом углу
            Button(
                onClick = {
                    flashOn = !flashOn
                    cameraRef?.cameraControl?.enableTorch(flashOn)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                colors = if (flashOn)
                    ButtonDefaults.buttonColors(containerColor = Color.Yellow)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Text(text = if (flashOn) "Свет" else "Свет")
            }
            // Вертикальный слайдер зума
            // Контейнер для слайдера выравнивается по центру правой стороны
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (90).dp)
                    .width(250.dp)    // Задает длину шкалы после поворота
                    .height(40.dp)    // Толщина слайдера
            ) {
                Slider(
                    value = zoomRatio,
                    onValueChange = { newZoom ->
                        zoomRatio = newZoom
                        cameraRef?.cameraControl?.setZoomRatio(newZoom)
                    },
                    valueRange = 1f..5f,
                    modifier = Modifier
                        .fillMaxWidth()   // Заполняет контейнер по ширине (350.dp)
                        .rotate(-90f)     // Поворот на -90° для вертикальной ориентации
                )
            }
        }
    }
}

/**
 * Обработка кадра с использованием ML Kit BarcodeScanner.
 */
@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onQrCodeScanned: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { rawValue ->
                        onQrCodeScanned(rawValue)
                        return@addOnSuccessListener
                    }
                }
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

/**
 * Экран результата сканирования.
 * Текст результата обёрнут в SelectionContainer для выделения и копирования.
 */
@Composable
fun ResultScreen(navController: NavHostController, sharedData: SharedData) {
    val result = sharedData.lastScannedResult
    val scannedValue = sharedData.qrCodeLink

    val status = if (result.isBlank()) {
        "идёт проверка..."
    } else {
        result.substringBefore("\n").lowercase()
    }

    var showLinkDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Обработка системной кнопки "Назад" для отмены проверки
    BackHandler {
        sharedData.safetyCheckJob?.cancel()
        sharedData.safetyCheckJob = null
        sharedData.checkCancelled = true
        navController.navigate("scanner") {
            popUpTo("scanner") { inclusive = true }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SelectionContainer {
                Text(
                    text = scannedValue,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            SelectionContainer {
                Text(
                    text = status,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = when (status) {
                        "опасно" -> Color.Red
                        "безопасно" -> Color.Green
                        "не ссылка" -> Color.Gray
                        "идёт проверка..." -> Color.DarkGray
                        "ошибка проверки" -> Color.Yellow
                        else -> Color.Black
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (scannedValue.startsWith("http") && result.isNotBlank() && (status == "опасно" || status == "безопасно" || status == "ошибка проверки")) {
                var showDialog by remember { mutableStateOf(false) }
                TextButton(onClick = { showDialog = true }) {
                    Text("Подробный отчёт")
                }
                if (showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog = false },
                        title = { Text("Детали") },
                        text = {
                            SelectionContainer {
                                Text(result)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDialog = false }) {
                                Text("Закрыть")
                            }
                        }
                    )
                }
            }
        }

        if (scannedValue.startsWith("http")) {
            Button(
                onClick = {
                    if (status == "безопасно") {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scannedValue))
                        context.startActivity(intent)
                    } else {
                        showLinkDialog = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text("Перейти по ссылке")
            }
        }

        if (showLinkDialog) {
            AlertDialog(
                onDismissRequest = { showLinkDialog = false },
                title = { Text("Подтверждение") },
                text = { Text("Вы действительно хотите перейти по ссылке?") },
                confirmButton = {
                    TextButton(onClick = {
                        showLinkDialog = false
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scannedValue))
                        context.startActivity(intent)
                    }) {
                        Text("Да")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLinkDialog = false }) {
                        Text("Нет")
                    }
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    // Явно отменяем проверку перед переходом
                    sharedData.safetyCheckJob?.cancel()
                    sharedData.safetyCheckJob = null
                    sharedData.checkCancelled = true
                    if (sharedData.lastScannedResult.isEmpty()) {
                        val link = if (sharedData.qrCodeLink.isNotEmpty()) sharedData.qrCodeLink else "Нет ссылки"
                        sharedData.setNewResult(
                            scannedText = link,
                            status = "ошибка проверки",
                            fullReport = "Проверка была прервана. Повторите попытку."
                        )
                    }
                    sharedData.clearTemp()
                    navController.navigate("scanner") {
                        popUpTo("scanner") { inclusive = true }
                    }
                }
            ) {
                Text("Сканировать ещё")
            }
            Button(
                onClick = {
                    // Если переходим в историю, тоже отменяем проверку
                    sharedData.safetyCheckJob?.cancel()
                    sharedData.safetyCheckJob = null
                    sharedData.checkCancelled = true
                    if (sharedData.lastScannedResult.isEmpty()) {
                        val link = if (sharedData.qrCodeLink.isNotEmpty()) sharedData.qrCodeLink else "Нет ссылки"
                        sharedData.setNewResult(
                            scannedText = link,
                            status = "ошибка проверки",
                            fullReport = "Проверка была прервана. Повторите попытку."
                        )
                    }
                    navController.navigate("history")
                }
            ) {
                Text("История")
            }
        }
    }
}

/**
 * Экран истории.
 */
@Composable
fun HistoryScreen(navController: NavHostController, sharedData: SharedData) {
    // Логируем количество элементов в истории
    Log.d("HistoryScreen", "History list size: ${sharedData.historyList.size}")

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF333333))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Дата/время",
                modifier = Modifier.weight(0.8f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "Результат сканирования",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "Результат проверки",
                modifier = Modifier.weight(1.2f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            sharedData.historyList.reversed().forEach { item ->
                HistoryRow(item)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = { sharedData.clearHistory() }) {
                Text("Очистить историю")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { navController.navigate("scanner") }) {
                Text("Сканирование")
            }
            Button(onClick = { /* Уже на экране истории */ }) {
                Text("История")
            }
        }
    }
}

/**
 * Элемент списка истории. Текстовые поля обёрнуты в SelectionContainer для возможности выделения.
 */
@Composable
fun HistoryRow(item: HistoryItem) {
    var showReportDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val statusText = item.status ?: "нет статуса"
    val statusColor = when (statusText.lowercase()) {
        "опасно" -> Color.Red
        "безопасно" -> Color.Green
        "не ссылка" -> Color.Gray
        "ошибка проверки" -> Color.Yellow
        else -> Color.Black
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        SelectionContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Столбец 1: Дата/время
                Box(modifier = Modifier.weight(0.8f)) {
                    Text(
                        text = item.dateTime ?: "нет информации",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Столбец 2: Результат сканирования
                Box(modifier = Modifier.weight(1f)) {
                    if (item.scannedResult?.startsWith("http") == true) {
                        // Если результат является ссылкой, делаем его кликабельным
                        ClickableText(
                            text = AnnotatedString(item.scannedResult),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White, // Цвет ссылки
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                textDecoration = TextDecoration.Underline
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (statusText.lowercase() == "безопасно") {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.scannedResult))
                                    context.startActivity(intent)
                                } else {
                                    showLinkDialog = true
                                }
                            }
                        )
                    } else {
                        Text(
                            text = item.scannedResult ?: "нет результата",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                // Столбец 3: Статус и кнопка "Отчёт"
                Row(
                    modifier = Modifier
                        .weight(1.2f)
                        .padding(start = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!item.fullReport.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(0.dp))
                        TextButton(onClick = { showReportDialog = true }) {
                            Text("Отчёт")
                        }
                    }
                }
            }
        }
    }

    // Диалог для подробного отчёта (если fullReport не пустой)
    if (showReportDialog && !item.fullReport.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Подробный отчёт") },
            text = {
                Text(item.fullReport)
            },
            confirmButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    // Диалог подтверждения перехода по ссылке, если статус не "безопасно"
    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Подтверждение") },
            text = { Text("Вы действительно хотите перейти по ссылке?") },
            confirmButton = {
                TextButton(onClick = {
                    showLinkDialog = false
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.scannedResult))
                    context.startActivity(intent)
                }) {
                    Text("Да")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text("Нет")
                }
            }
        )
    }
}

/**
 * Кнопка для открытия ссылки в браузере.
 */
@Composable
fun OpenLinkButton(link: String) {
    if (link.isNotEmpty()) {
        val context = LocalContext.current
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text("Перейти по ссылке")
        }
    }
}
