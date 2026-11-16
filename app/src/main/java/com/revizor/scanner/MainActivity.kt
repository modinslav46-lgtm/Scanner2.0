package com.revizor.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

// ==================== МОДЕЛИ ДАННЫХ ====================

data class ScannedItem(
    val uniqueKey: String,
    val gtin: String,
    val sn: String,
    val raw: String,
    val name: String? = null,
    val expDate: String? = null, // YYYY-MM-DD
    val status: String? = null,
    val infoMsg: String? = null,
    val isLoading: Boolean = true,
    val isManual: Boolean = false,
    val scannedAt: Long = System.currentTimeMillis()
)

data class ParsedDataMatrix(
    val uniqueKey: String,
    val gtin: String,
    val sn: String,
    val cis: String,
    val raw: String
)

enum class FilterTab(val title: String) {
    ALL("Все"),
    EXPIRED("Просрочка"),
    VALID("Годные"),
    NO_DATE("Без даты")
}

enum class FlashType {
    NONE, VALID, EXPIRED
}

// ==================== ПАРСЕР ЧЕСТНОГО ЗНАКА ====================

object CrptParser {
    private const val GS = '\u001D'

    fun parse(rawInput: String): ParsedDataMatrix? {
        val cleaned = rawInput
            .replace("\r", "")
            .replace("\n", "")
            .replace("<GS>", GS.toString())
            .replace("\\u001d", GS.toString(), ignoreCase = true)
            .trim()

        if (cleaned.length < 18) return null

        val startIdx = if (cleaned.startsWith("01")) 0 else cleaned.indexOf("01")
        if (startIdx == -1 || cleaned.length < startIdx + 18) return null

        val gtin = cleaned.substring(startIdx + 2, startIdx + 16)
        if (!gtin.all { it.isDigit() }) return null

        val afterGtin = cleaned.substring(startIdx + 16)
        if (!afterGtin.startsWith("21")) return null

        val snPart = afterGtin.substring(2)
        val gsIdx = snPart.indexOf(GS)
        val sn = if (gsIdx != -1) {
            snPart.substring(0, gsIdx)
        } else {
            val tailMatch = Regex("(91[0-9A-Za-z]{4}|92[0-9A-Za-z]{10,}|93[0-9A-Za-z]{4})").find(snPart)
            if (tailMatch != null && tailMatch.range.first >= 4) {
                snPart.substring(0, tailMatch.range.first)
            } else {
                snPart.take(20)
            }
        }

        if (sn.isBlank()) return null

        return ParsedDataMatrix(
            uniqueKey = "$gtin|$sn",
            gtin = gtin,
            sn = sn,
            cis = "01${gtin}21$sn",
            raw = cleaned
        )
    }
}

// ==================== ХРАНИЛИЩЕ НАСТРОЕК И СМЕНЫ ====================

class AppStorage(context: Context) {
    private val prefs = context.getSharedPreferences("aor_cz_scanner_v3", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = prefs.getString("api_url", "https://functions.yandexcloud.net/ВАШ_ID_ФУНКЦИИ") ?: ""
        set(v) = prefs.edit().putString("api_url", v.trim()).apply()

    var apiMode: String
        get() = prefs.getString("api_mode", "mobile_api") ?: "mobile_api"
        set(v) = prefs.edit().putString("api_mode", v).apply()

    var apiToken: String
        get() = prefs.getString("api_token", "") ?: ""
        set(v) = prefs.edit().putString("api_token", v.trim()).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound_enabled", true)
        set(v) = prefs.edit().putBoolean("sound_enabled", v).apply()

    var tsdMode: Boolean
        get() = prefs.getBoolean("tsd_mode", false)
        set(v) = prefs.edit().putBoolean("tsd_mode", v).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(v) = prefs.edit().putBoolean("dark_theme", v).apply()

    var largeFont: Boolean
        get() = prefs.getBoolean("large_font", false)
        set(v) = prefs.edit().putBoolean("large_font", v).apply()

    var flashEnabled: Boolean
        get() = prefs.getBoolean("flash_enabled", true)
        set(v) = prefs.edit().putBoolean("flash_enabled", v).apply()

    var fifoBarEnabled: Boolean
        get() = prefs.getBoolean("fifo_bar_enabled", true)
        set(v) = prefs.edit().putBoolean("fifo_bar_enabled", v).apply()

    var groupByName: Boolean
        get() = prefs.getBoolean("group_by_name", false)
        set(v) = prefs.edit().putBoolean("group_by_name", v).apply()

    var offsetDays: Int
        get() = prefs.getInt("offset_days", 1)
        set(v) = prefs.edit().putInt("offset_days", v).apply()

    fun saveItems(items: List<ScannedItem>) {
        val arr = JSONArray()
        for (it in items) {
            val obj = JSONObject().apply {
                put("uniqueKey", it.uniqueKey)
                put("gtin", it.gtin)
                put("sn", it.sn)
                put("raw", it.raw)
                put("name", it.name ?: JSONObject.NULL)
                put("expDate", it.expDate ?: JSONObject.NULL)
                put("status", it.status ?: JSONObject.NULL)
                put("infoMsg", it.infoMsg ?: JSONObject.NULL)
                put("isLoading", false)
                put("isManual", it.isManual)
                put("scannedAt", it.scannedAt)
            }
            arr.put(obj)
        }
        prefs.edit().putString("saved_items_json", arr.toString()).apply()
    }

    fun loadItems(): List<ScannedItem> {
        val raw = prefs.getString("saved_items_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<ScannedItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ScannedItem(
                        uniqueKey = o.getString("uniqueKey"),
                        gtin = o.optString("gtin", ""),
                        sn = o.optString("sn", ""),
                        raw = o.optString("raw", ""),
                        name = if (o.isNull("name")) null else o.getString("name"),
                        expDate = if (o.isNull("expDate")) null else o.getString("expDate"),
                        status = if (o.isNull("status")) null else o.getString("status"),
                        infoMsg = if (o.isNull("infoMsg")) null else o.getString("infoMsg"),
                        isLoading = false,
                        isManual = o.optBoolean("isManual", false),
                        scannedAt = o.optLong("scannedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}

// ==================== MAIN ACTIVITY ====================

class MainActivity : ComponentActivity() {

    private val hardwareScanFlow = Channel<String>(Channel.UNLIMITED)
    private val keyBuffer = StringBuilder()
    private var lastKeyTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AorScannerApp(hardwareScanFlow = hardwareScanFlow)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastKeyTime > 250L && keyBuffer.isNotEmpty()) {
                keyBuffer.clear()
            }
            lastKeyTime = now

            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_TAB) {
                if (keyBuffer.length >= 18) {
                    val code = keyBuffer.toString()
                    keyBuffer.clear()
                    hardwareScanFlow.trySend(code)
                    return true
                }
                keyBuffer.clear()
            } else {
                val unicode = event.unicodeChar
                if (unicode > 0) {
                    keyBuffer.append(unicode.toChar())
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

// ==================== ЦВЕТОВЫЕ СХЕМЫ ====================

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    background = Color(0xFF090B0E),
    surface = Color(0xFF131720),
    surfaceVariant = Color(0xFF1C2230),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2B3446)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    background = Color(0xFFF1F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

// ==================== УТИЛИТЫ ДАТ ====================

private fun todayZeroMillis(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun addDaysToTodayMillis(days: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = todayZeroMillis()
    c.add(Calendar.DAY_OF_YEAR, days)
    return c.timeInMillis
}

private fun parseIsoToMillis(iso: String?): Long? {
    if (iso.isNullOrBlank() || iso.length < 10) return null
    return try {
        val parts = iso.take(10).split("-")
        if (parts.size != 3) return null
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, parts[0].toInt())
        c.set(Calendar.MONTH, parts[1].toInt() - 1)
        c.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.timeInMillis
    } catch (_: Exception) {
        null
    }
}

private fun formatShortDayMonth(millis: Long): String {
    val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun formatIsoFileDate(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date())
}

private fun formatRuDate(iso: String?): String {
    if (iso.isNullOrBlank() || iso.length < 10) return "—"
    val parts = iso.take(10).split("-")
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else iso
}

private fun parseRuToIso(ru: String): String? {
    val parts = ru.trim().split(".")
    if (parts.size != 3) return null
    val d = parts[0].padStart(2, '0')
    val m = parts[1].padStart(2, '0')
    val y = parts[2]
    if (y.length != 4) return null
    return "$y-$m-$d"
}

// ==================== ОСНОВНОЙ UI ПРИЛОЖЕНИЯ ====================

@Composable
fun AorScannerApp(hardwareScanFlow: Channel<String>) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val storage = remember { AppStorage(context) }

    var items by remember { mutableStateOf(storage.loadItems()) }
    var soundEnabled by remember { mutableStateOf(storage.soundEnabled) }
    var tsdMode by remember { mutableStateOf(storage.tsdMode) }
    var darkTheme by remember { mutableStateOf(storage.darkTheme) }
    var largeFont by remember { mutableStateOf(storage.largeFont) }
    var flashEnabled by remember { mutableStateOf(storage.flashEnabled) }
    var fifoBarEnabled by remember { mutableStateOf(storage.fifoBarEnabled) }
    var groupByName by remember { mutableStateOf(storage.groupByName) }
    var offsetDays by remember { mutableIntStateOf(storage.offsetDays) }

    var apiUrl by remember { mutableStateOf(storage.apiUrl) }
    var apiMode by remember { mutableStateOf(storage.apiMode) }
    var apiToken by remember { mutableStateOf(storage.apiToken) }

    var showListScreen by remember { mutableStateOf(false) }
    var activeBannerKey by remember { mutableStateOf<String?>(null) }
    var isDuplicateBanner by remember { mutableStateOf(false) }
    var flashType by remember { mutableStateOf(FlashType.NONE) }
    var isShelfLocked by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showManualAddDialog by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    var torchEnabled by remember { mutableStateOf(false) }
    var fullFrameScan by remember { mutableStateOf(false) }
    var zoomPresetIndex by remember { mutableIntStateOf(1) }

    var selectedTab by remember { mutableStateOf(FilterTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val apiQueue = remember { Channel<ParsedDataMatrix>(Channel.UNLIMITED) }

    val todayMillis = remember { todayZeroMillis() }
    val thresholdMillis = remember(offsetDays) { addDaysToTodayMillis(offsetDays) }

    fun isExpired(expIso: String?): Boolean {
        val expMs = parseIsoToMillis(expIso) ?: return false
        return expMs <= thresholdMillis
    }

    fun updateItems(newList: List<ScannedItem>) {
        items = newList
        storage.saveItems(newList)
    }

    val toneGen = remember {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 90)
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose { toneGen?.release() }
    }

    fun triggerFeedback(expired: Boolean, isDuplicate: Boolean = false) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (isDuplicate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18, 90))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
            return
        }

        if (expired) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 90, 220), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 220, 90, 220), -1)
            }
            if (soundEnabled) {
                toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 350)
            }
            if (flashEnabled) {
                flashType = FlashType.EXPIRED
                scope.launch {
                    delay(480)
                    flashType = FlashType.NONE
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(38, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(38)
            }
            if (soundEnabled) {
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
            }
            if (flashEnabled) {
                flashType = FlashType.VALID
                scope.launch {
                    delay(350)
                    flashType = FlashType.NONE
                }
            }
        }
    }

    var lastScanStamp by remember { mutableStateOf("" to 0L) }

    fun onRawBarcodeDetected(rawText: String) {
        val parsed = CrptParser.parse(rawText) ?: return
        val now = System.currentTimeMillis()
        if (lastScanStamp.first == parsed.uniqueKey && now - lastScanStamp.second < 1100L) {
            return
        }
        lastScanStamp = parsed.uniqueKey to now

        val existing = items.find { it.uniqueKey == parsed.uniqueKey }
        if (existing != null) {
            activeBannerKey = existing.uniqueKey
            isDuplicateBanner = true
            triggerFeedback(expired = isExpired(existing.expDate), isDuplicate = true)
            return
        }

        isDuplicateBanner = false
        activeBannerKey = parsed.uniqueKey
        val newItem = ScannedItem(
            uniqueKey = parsed.uniqueKey,
            gtin = parsed.gtin,
            sn = parsed.sn,
            raw = parsed.raw,
            isLoading = true
        )
        updateItems(listOf(newItem) + items)
        apiQueue.trySend(parsed)
    }

    LaunchedEffect(Unit) {
        for (task in apiQueue) {
            delay(350L + Random.nextLong(50L, 140L))
            var attempt = 0
            var done = false

            while (attempt < 3 && !done) {
                attempt++
                if (attempt > 1) {
                    updateItems(items.map {
                        if (it.uniqueKey == task.uniqueKey) {
                            it.copy(infoMsg = "Повтор запроса ($attempt/3)...")
                        } else it
                    })
                    delay(2200L * (attempt - 1))
                }

                val res = fetchFromGateway(
                    endpoint = storage.apiUrl,
                    mode = storage.apiMode,
                    token = storage.apiToken,
                    parsed = task
                )

                if (res.shouldRetry && attempt < 3) {
                    continue
                }

                done = true
                val expFlag = isExpired(res.expDate) || res.status == "RETIRED"
                updateItems(items.map {
                    if (it.uniqueKey == task.uniqueKey) {
                        it.copy(
                            name = res.name ?: it.name,
                            expDate = res.expDate,
                            status = res.status,
                            infoMsg = res.error,
                            isLoading = false
                        )
                    } else it
                })

                if (res.expDate != null || res.status == "RETIRED") {
                    triggerFeedback(expired = expFlag, isDuplicate = false)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        for (hwCode in hardwareScanFlow) {
            onRawBarcodeDetected(hwCode)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val code = intent.getStringExtra("scannerdata")
                    ?: intent.getStringExtra("barcode_string")
                    ?: intent.getStringExtra("data")
                    ?: intent.getStringExtra("SCAN_BARCODE1")
                    ?: intent.getStringExtra("value")
                if (!code.isNullOrBlank()) {
                    onRawBarcodeDetected(code)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.android.server.scannerservice.broadcast")
            addAction("android.intent.ACTION_DECODE_DATA")
            addAction("nlscan.action.SCANNER_RESULT")
            addAction("com.scanner.broadcast")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, 2)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(tsdMode) {
        if (!tsdMode && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var csvContentToSave by remember { mutableStateOf("") }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        writer.write("\uFEFF")
                        writer.write(csvContentToSave)
                    }
                }
                Toast.makeText(context, "CSV успешно сохранён", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val colors = if (darkTheme) DarkScheme else LightScheme
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, largeFont) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * (if (largeFont) 1.22f else 1.0f)
        )
    }

    val flashAlpha by animateFloatAsState(
        targetValue = if (flashType != FlashType.NONE) 0.45f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "flashAlpha"
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = colors) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = !isShelfLocked,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.width(310.dp)
                    ) {
                        DrawerSettingsContent(
                            tsdMode = tsdMode,
                            onTsdModeChange = {
                                tsdMode = it
                                storage.tsdMode = it
                                if (it) {
                                    torchEnabled = false
                                    showListScreen = false
                                }
                            },
                            darkTheme = darkTheme,
                            onDarkThemeChange = {
                                darkTheme = it
                                storage.darkTheme = it
                            },
                            largeFont = largeFont,
                            onLargeFontChange = {
                                largeFont = it
                                storage.largeFont = it
                            },
                            flashEnabled = flashEnabled,
                            onFlashEnabledChange = {
                                flashEnabled = it
                                storage.flashEnabled = it
                            },
                            fifoBarEnabled = fifoBarEnabled,
                            onFifoBarEnabledChange = {
                                fifoBarEnabled = it
                                storage.fifoBarEnabled = it
                            },
                            soundEnabled = soundEnabled,
                            onSoundEnabledChange = {
                                soundEnabled = it
                                storage.soundEnabled = it
                            },
                            apiUrl = apiUrl,
                            onApiUrlChange = { apiUrl = it },
                            apiMode = apiMode,
                            onApiModeChange = { apiMode = it },
                            apiToken = apiToken,
                            onApiTokenChange = { apiToken = it },
                            onSaveApi = {
                                storage.apiUrl = apiUrl
                                storage.apiMode = apiMode
                                storage.apiToken = apiToken
                                Toast.makeText(context, "Настройки шлюза сохранены", Toast.LENGTH_SHORT).show()
                            },
                            onOpenManualAdd = {
                                scope.launch { drawerState.close() }
                                showManualAddDialog = true
                            }
                        )
                    }
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (showListScreen && !tsdMode) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SmallActionChip(text = "← К камере", onClick = { showListScreen = false })
                                Text(
                                    text = "Отсканировано: ${items.size}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                SmallActionChip(
                                    text = "Очистить",
                                    onClick = { if (items.isNotEmpty()) showResetConfirm = true }
                                )
                            }

                            ScannedListSection(
                                items = items,
                                selectedTab = selectedTab,
                                onSelectTab = { selectedTab = it },
                                searchQuery = searchQuery,
                                onSearchChange = { searchQuery = it },
                                groupByName = groupByName,
                                onToggleGroup = {
                                    groupByName = it
                                    storage.groupByName = it
                                },
                                expandedGroups = expandedGroups,
                                onToggleGroupExpand = { key ->
                                    expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key
                                },
                                isExpiredFn = { isExpired(it.expDate) || it.status == "RETIRED" },
                                todayMillis = todayMillis,
                                thresholdMillis = thresholdMillis,
                                fifoBarEnabled = fifoBarEnabled,
                                onDeleteItem = { key -> updateItems(items.filterNot { it.uniqueKey == key }) },
                                onExportExpiredCsv = {
                                    csvContentToSave = buildCsv(items.filter { isExpired(it.expDate) || it.status == "RETIRED" }, thresholdMillis)
                                    csvLauncher.launch("AOR_Prosrochka_${formatIsoFileDate()}.csv")
                                },
                                onExportAllCsv = {
                                    csvContentToSave = buildCsv(items, thresholdMillis)
                                    csvLauncher.launch("AOR_Reviziya_${formatIsoFileDate()}.csv")
                                },
                                onCopySummary = {
                                    val txt = buildMessengerSummary(items, thresholdMillis) { isExpired(it.expDate) || it.status == "RETIRED" }
                                    clipboard.setText(AnnotatedString(txt))
                                    Toast.makeText(context, "Сводка скопирована для мессенджера", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TopControlHeader(
                                thresholdMillis = thresholdMillis,
                                offsetDays = offsetDays,
                                onSelectOffset = { days ->
                                    offsetDays = days
                                    storage.offsetDays = days
                                },
                                tsdMode = tsdMode,
                                zoomPresetIndex = zoomPresetIndex,
                                onCycleZoom = { zoomPresetIndex = (zoomPresetIndex + 1) % 4 },
                                torchEnabled = torchEnabled,
                                onToggleTorch = { torchEnabled = !torchEnabled },
                                fullFrameScan = fullFrameScan,
                                onToggleScanArea = { fullFrameScan = !fullFrameScan },
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onLockScreen = { isShelfLocked = true },
                                onRequestReset = {
                                    if (items.isNotEmpty()) showResetConfirm = true
                                }
                            )

                            val activeItem = items.find { it.uniqueKey == activeBannerKey } ?: items.firstOrNull()
                            val expiredCount = items.count { isExpired(it.expDate) || it.status == "RETIRED" }
                            val noDateCount = items.count { it.expDate == null && !it.isLoading }

                            if (tsdMode) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp)
                                ) {
                                    ActiveScanBannerCard(
                                        item = activeItem,
                                        isDuplicate = isDuplicateBanner,
                                        isExpired = isExpired(activeItem?.expDate) || activeItem?.status == "RETIRED",
                                        todayMillis = todayMillis,
                                        thresholdMillis = thresholdMillis,
                                        fifoBarEnabled = fifoBarEnabled,
                                        isTsdLarge = true
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    ScannedListSection(
                                        items = items,
                                        selectedTab = selectedTab,
                                        onSelectTab = { selectedTab = it },
                                        searchQuery = searchQuery,
                                        onSearchChange = { searchQuery = it },
                                        groupByName = groupByName,
                                        onToggleGroup = {
                                            groupByName = it
                                            storage.groupByName = it
                                        },
                                        expandedGroups = expandedGroups,
                                        onToggleGroupExpand = { key ->
                                            expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key
                                        },
                                        isExpiredFn = { isExpired(it.expDate) || it.status == "RETIRED" },
                                        todayMillis = todayMillis,
                                        thresholdMillis = thresholdMillis,
                                        fifoBarEnabled = fifoBarEnabled,
                                        onDeleteItem = { key -> updateItems(items.filterNot { it.uniqueKey == key }) },
                                        onExportExpiredCsv = {
                                            csvContentToSave = buildCsv(items.filter { isExpired(it.expDate) || it.status == "RETIRED" }, thresholdMillis)
                                            csvLauncher.launch("AOR_Prosrochka_${formatIsoFileDate()}.csv")
                                        },
                                        onExportAllCsv = {
                                            csvContentToSave = buildCsv(items, thresholdMillis)
                                            csvLauncher.launch("AOR_Reviziya_${formatIsoFileDate()}.csv")
                                        },
                                        onCopySummary = {
                                            val txt = buildMessengerSummary(items, thresholdMillis) { isExpired(it.expDate) || it.status == "RETIRED" }
                                            clipboard.setText(AnnotatedString(txt))
                                            Toast.makeText(context, "Сводка скопирована для мессенджера", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    if (hasCameraPermission) {
                                        CameraScannerView(
                                            zoomPresetIndex = zoomPresetIndex,
                                            torchEnabled = torchEnabled,
                                            fullFrameScan = fullFrameScan,
                                            onBarcodeScanned = ::onRawBarcodeDetected
                                        )
                                    } else {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                                Text("Разрешить доступ к камере")
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                                            .navigationBarsPadding()
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            StatCounterChip(
                                                count = items.size,
                                                label = "Всего ↗",
                                                accent = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    selectedTab = FilterTab.ALL
                                                    showListScreen = true
                                                }
                                            )
                                            StatCounterChip(
                                                count = expiredCount,
                                                label = "Просрочка ↗",
                                                accent = Color(0xFFEF4444),
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    selectedTab = FilterTab.EXPIRED
                                                    showListScreen = true
                                                }
                                            )
                                            StatCounterChip(
                                                count = noDateCount,
                                                label = "Без даты ↗",
                                                accent = Color(0xFFF59E0B),
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    selectedTab = FilterTab.NO_DATE
                                                    showListScreen = true
                                                }
                                            )
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        ActiveScanBannerCard(
                                            item = activeItem,
                                            isDuplicate = isDuplicateBanner,
                                            isExpired = isExpired(activeItem?.expDate) || activeItem?.status == "RETIRED",
                                            todayMillis = todayMillis,
                                            thresholdMillis = thresholdMillis,
                                            fifoBarEnabled = fifoBarEnabled,
                                            isTsdLarge = false,
                                            onClick = { showListScreen = true }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (flashAlpha > 0f) {
                        val flashColor = if (flashType == FlashType.EXPIRED) Color(0xFFEF4444) else Color(0xFF10B981)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(10.dp, flashColor.copy(alpha = flashAlpha * 2f))
                                .background(flashColor.copy(alpha = flashAlpha * 0.35f))
                        )
                    }

                    if (isShelfLocked) {
                        ShelfLockOverlay(onUnlock = { isShelfLocked = false })
                    }
                }
            }

            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("Очистить смену?") },
                    text = { Text("Будут удалены все отсканированные позиции (${items.size} шт.). Это действие нельзя отменить.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                updateItems(emptyList())
                                activeBannerKey = null
                                showResetConfirm = false
                            }
                        ) {
                            Text("Очистить", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) {
                            Text("Отмена")
                        }
                    }
                )
            }

            if (showManualAddDialog) {
                ManualAddItemDialog(
                    existingNames = items.mapNotNull { it.name }.distinct(),
                    onDismiss = { showManualAddDialog = false },
                    onAdd = { name, expIso, qty ->
                        val now = System.currentTimeMillis()
                        val added = (1..qty).map { idx ->
                            ScannedItem(
                                uniqueKey = "MANUAL_${now}_$idx",
                                gtin = "РУЧНОЙ_ВВОД",
                                sn = "№$idx",
                                raw = "",
                                name = name.ifBlank { "Товар без штрихкода" },
                                expDate = expIso,
                                status = "MANUAL",
                                infoMsg = "Добавлено вручную",
                                isLoading = false,
                                isManual = true,
                                scannedAt = now + idx
                            )
                        }
                        updateItems(added + items)
                        activeBannerKey = added.first().uniqueKey
                        isDuplicateBanner = false
                        showManualAddDialog = false
                        Toast.makeText(context, "Добавлено: $qty шт.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

// ==================== ВЕРХНЯЯ ПАНЕЛЬ ====================

@Composable
private fun TopControlHeader(
    thresholdMillis: Long,
    offsetDays: Int,
    onSelectOffset: (Int) -> Unit,
    tsdMode: Boolean,
    zoomPresetIndex: Int,
    onCycleZoom: () -> Unit,
    torchEnabled: Boolean,
    onToggleTorch: () -> Unit,
    fullFrameScan: Boolean,
    onToggleScanArea: () -> Unit,
    onOpenDrawer: () -> Unit,
    onLockScreen: () -> Unit,
    onRequestReset: () -> Unit
) {
    val zoomLabel = when (zoomPresetIndex) {
        0 -> "0.6x"
        1 -> "1x"
        2 -> "2x"
        else -> "3x"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Меню", tint = MaterialTheme.colorScheme.onSurface)
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f)
            ) {
                val dateStr = formatShortDayMonth(thresholdMillis)
                Text(
                    text = if (tsdMode) "ТСД • До $dateStr" else "До $dateStr",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!tsdMode) {
                SmallActionChip(text = zoomLabel, onClick = onCycleZoom)
                SmallActionChip(
                    text = if (fullFrameScan) "Весь" else "Центр",
                    onClick = onToggleScanArea
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (torchEnabled) Color(0xFFF59E0B) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(onClick = onToggleTorch)
                ) {
                    Text(
                        text = "🔦",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        fontSize = 13.sp
                    )
                }
            }

            IconButton(
                onClick = onLockScreen,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Замок экрана",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
            }

            SmallActionChip(text = "Сброс", onClick = onRequestReset)
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val presets = listOf(0 to "Сегодня", 1 to "+1 дн", 3 to "+3 дн", 7 to "+7 дн", 14 to "+14 дн")
            presets.forEach { (days, label) ->
                val active = offsetDays == days
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectOffset(days) }
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(vertical = 6.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallActionChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ==================== КАРТОЧКА ТЕКУЩЕГО / ПОСЛЕДНЕГО СКАНА ====================

@Composable
private fun ActiveScanBannerCard(
    item: ScannedItem?,
    isDuplicate: Boolean,
    isExpired: Boolean,
    todayMillis: Long,
    thresholdMillis: Long,
    fifoBarEnabled: Boolean,
    isTsdLarge: Boolean,
    onClick: (() -> Unit)? = null
) {
    if (item == null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        ) {
            Text(
                text = "Наведите сканер на DataMatrix «Честного ЗНАКа»",
                modifier = Modifier.padding(if (isTsdLarge) 18.dp else 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (isTsdLarge) 15.sp else 13.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val borderColor = when {
        item.isLoading -> Color(0xFF3B82F6)
        isExpired -> Color(0xFFEF4444)
        item.expDate != null -> Color(0xFF10B981)
        else -> Color(0xFFF59E0B)
    }

    val bgTint = when {
        item.isLoading -> MaterialTheme.colorScheme.surface
        isExpired -> Color(0xFFEF4444).copy(alpha = 0.14f)
        item.expDate != null -> Color(0xFF10B981).copy(alpha = 0.12f)
        else -> Color(0xFFF59E0B).copy(alpha = 0.12f)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(
            modifier = Modifier
                .background(bgTint)
                .padding(if (isTsdLarge) 14.dp else 11.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                val statusTitle = when {
                    item.isLoading -> "⏳ Запрос в Честный ЗНАК..."
                    item.status == "RETIRED" -> "⛔ ВЫБЫЛ ИЗ ОБОРОТА!"
                    isExpired -> "🚨 ПРОСРОЧКА / СПИСАТЬ"
                    item.expDate != null -> "✅ ГОДЕН"
                    else -> "⚠️ НЕТ ДАТЫ В ЧЗ"
                }
                Text(
                    text = statusTitle,
                    color = borderColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (isTsdLarge) 15.sp else 13.sp
                )

                if (isDuplicate) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Уже в списке",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = item.name ?: "GTIN: ${item.gtin}",
                fontWeight = FontWeight.Bold,
                fontSize = if (isTsdLarge) 16.sp else 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(3.dp))

            val dateLine = when {
                item.expDate != null -> "Срок годности: до ${formatRuDate(item.expDate)}"
                !item.infoMsg.isNullOrBlank() -> item.infoMsg
                else -> "GTIN ${item.gtin} • SN: ${item.sn}"
            }

            Text(
                text = dateLine,
                fontSize = if (isTsdLarge) 15.sp else 13.sp,
                fontWeight = if (item.expDate != null) FontWeight.Bold else FontWeight.Normal,
                color = if (isExpired) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface
            )

            if (fifoBarEnabled && item.expDate != null) {
                Spacer(Modifier.height(6.dp))
                FifoFreshnessBar(expDateIso = item.expDate, todayMillis = todayMillis, thresholdMillis = thresholdMillis)
            }
        }
    }
}

@Composable
private fun StatCounterChip(
    count: Int,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = count.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = accent)
            Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ==================== ШКАЛА СВЕЖЕСТИ FIFO ====================

@Composable
private fun FifoFreshnessBar(expDateIso: String, todayMillis: Long, thresholdMillis: Long) {
    val expMs = parseIsoToMillis(expDateIso) ?: return
    val daysLeft = ((expMs - todayMillis) / 86_400_000L).toInt()
    val (barColor, fraction, label) = when {
        expMs <= thresholdMillis -> Triple(Color(0xFFEF4444), 1.0f, "Списать")
        daysLeft <= 3 -> Triple(Color(0xFFF59E0B), 0.65f, "Ротация ($daysLeft дн.)")
        else -> Triple(Color(0xFF10B981), 0.35f, "Запас ($daysLeft дн.)")
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(barColor)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text = label, fontSize = 10.sp, color = barColor, fontWeight = FontWeight.SemiBold)
    }
}

// ==================== СПИСОК С АККОРДЕОН-ГРУППИРОВКОЙ И ЭКСПОРТОМ ====================

@Composable
private fun ScannedListSection(
    items: List<ScannedItem>,
    selectedTab: FilterTab,
    onSelectTab: (FilterTab) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    groupByName: Boolean,
    onToggleGroup: (Boolean) -> Unit,
    expandedGroups: Set<String>,
    onToggleGroupExpand: (String) -> Unit,
    isExpiredFn: (ScannedItem) -> Boolean,
    todayMillis: Long,
    thresholdMillis: Long,
    fifoBarEnabled: Boolean,
    onDeleteItem: (String) -> Unit,
    onExportExpiredCsv: () -> Unit,
    onExportAllCsv: () -> Unit,
    onCopySummary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filtered = remember(items, selectedTab, searchQuery, thresholdMillis) {
        items.filter { item ->
            val tabMatch = when (selectedTab) {
                FilterTab.ALL -> true
                FilterTab.EXPIRED -> isExpiredFn(item)
                FilterTab.VALID -> item.expDate != null && !isExpiredFn(item)
                FilterTab.NO_DATE -> item.expDate == null && !item.isLoading
            }
            val q = searchQuery.trim().lowercase()
            val queryMatch = q.isEmpty() ||
                (item.name?.lowercase()?.contains(q) == true) ||
                item.gtin.contains(q) ||
                item.sn.lowercase().contains(q)
            tabMatch && queryMatch
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            FilterTab.values().forEach { tab ->
                val active = selectedTab == tab
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onSelectTab(tab) }
                ) {
                    Text(
                        text = tab.title,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (groupByName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onToggleGroup(!groupByName) }
            ) {
                Text(
                    text = "Группы",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (groupByName) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Поиск по названию, GTIN или SN...", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                    }
                }
            }
        )

        Spacer(Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (groupByName) {
                val grouped = filtered.groupBy { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } ?: "GTIN: ${it.gtin}" }
                items(grouped.entries.toList(), key = { it.key }) { (groupTitle, groupItems) ->
                    val isExpanded = groupTitle in expandedGroups
                    val expInGroup = groupItems.count { isExpiredFn(it) }
                    val groupBorder = if (expInGroup > 0) Color(0xFFEF4444) else MaterialTheme.colorScheme.outline

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (expInGroup > 0) 1.5.dp else 1.dp, groupBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleGroupExpand(groupTitle) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = groupTitle,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "Всего: ${groupItems.size} шт.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (expInGroup > 0) {
                                            Text(
                                                text = "• Просрочка: $expInGroup",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFEF4444)
                                            )
                                        }
                                    }
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Раскрыть",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isExpanded) {
                                Spacer(Modifier.height(8.dp))
                                Divider(color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(6.dp))
                                groupItems.forEach { subItem ->
                                    SingleUnitRow(
                                        item = subItem,
                                        expired = isExpiredFn(subItem),
                                        todayMillis = todayMillis,
                                        thresholdMillis = thresholdMillis,
                                        fifoBarEnabled = fifoBarEnabled,
                                        compact = true,
                                        onDelete = { onDeleteItem(subItem.uniqueKey) }
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                items(filtered, key = { it.uniqueKey }) { item ->
                    SingleUnitRow(
                        item = item,
                        expired = isExpiredFn(item),
                        todayMillis = todayMillis,
                        thresholdMillis = thresholdMillis,
                        fifoBarEnabled = fifoBarEnabled,
                        compact = false,
                        onDelete = { onDeleteItem(item.uniqueKey) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onExportExpiredCsv,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier.weight(1.2f)
            ) {
                Text("📥 CSV Просрочка", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            }

            OutlinedButton(
                onClick = onCopySummary,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("📋 Сводка", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }

            OutlinedButton(
                onClick = onExportAllCsv,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier.weight(0.9f)
            ) {
                Text("📥 Всё", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SingleUnitRow(
    item: ScannedItem,
    expired: Boolean,
    todayMillis: Long,
    thresholdMillis: Long,
    fifoBarEnabled: Boolean,
    compact: Boolean,
    onDelete: () -> Unit
) {
    val borderColor = when {
        item.isLoading -> MaterialTheme.colorScheme.primary
        expired -> Color(0xFFEF4444)
        item.expDate != null -> Color(0xFF10B981)
        else -> Color(0xFFF59E0B)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (compact) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!compact) {
                    Text(
                        text = item.name ?: "Загрузка названия...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                }

                Text(
                    text = "GTIN: ${item.gtin} • SN: ${item.sn}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(3.dp))

                val statusText = when {
                    item.isLoading -> "Проверка в Честном ЗНАКе..."
                    item.status == "RETIRED" -> "ВЫБЫЛ (до ${formatRuDate(item.expDate)})"
                    expired && item.expDate != null -> "ПРОСРОЧКА: до ${formatRuDate(item.expDate)}"
                    item.expDate != null -> "Годен: до ${formatRuDate(item.expDate)}"
                    else -> item.infoMsg ?: "Срок не указан"
                }

                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = borderColor
                )

                if (fifoBarEnabled && item.expDate != null && !compact) {
                    Spacer(Modifier.height(5.dp))
                    FifoFreshnessBar(expDateIso = item.expDate, todayMillis = todayMillis, thresholdMillis = thresholdMillis)
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ==================== БОКОВАЯ ШТОРКА НАСТРОЕК (AOR SYSTEMS) ====================

@Composable
private fun DrawerSettingsContent(
    tsdMode: Boolean,
    onTsdModeChange: (Boolean) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    largeFont: Boolean,
    onLargeFontChange: (Boolean) -> Unit,
    flashEnabled: Boolean,
    onFlashEnabledChange: (Boolean) -> Unit,
    fifoBarEnabled: Boolean,
    onFifoBarEnabledChange: (Boolean) -> Unit,
    soundEnabled: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    apiMode: String,
    onApiModeChange: (String) -> Unit,
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    onSaveApi: () -> Unit,
    onOpenManualAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Параметры терминала",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(12.dp))

        DrawerSectionTitle("Оборудование")
        DrawerSwitchRow(
            title = "Режим ТСД (без камеры)",
            subtitle = "Для MERTECH Seuic AutoID Q9C и 2D-сканеров",
            checked = tsdMode,
            onCheckedChange = onTsdModeChange
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        DrawerSectionTitle("Дизайн и индикация")
        DrawerSwitchRow(
            title = "Тёмная тема (AMOLED)",
            subtitle = "Выключите для светлой темы в ярком зале",
            checked = darkTheme,
            onCheckedChange = onDarkThemeChange
        )
        DrawerSwitchRow(
            title = "Крупный шрифт",
            subtitle = "Увеличенный интерфейс для 4\" экранов",
            checked = largeFont,
            onCheckedChange = onLargeFontChange
        )
        DrawerSwitchRow(
            title = "Цветовая вспышка экрана",
            subtitle = "Подсветка краёв зелёным/красным при скане",
            checked = flashEnabled,
            onCheckedChange = onFlashEnabledChange
        )
        DrawerSwitchRow(
            title = "Шкала свежести (FIFO)",
            subtitle = "Индикатор ротации по остатку дней",
            checked = fifoBarEnabled,
            onCheckedChange = onFifoBarEnabledChange
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        DrawerSectionTitle("Звук и ввод")
        DrawerSwitchRow(
            title = "Звуковой сигнал",
            subtitle = "Виброотклик работает всегда даже без звука",
            checked = soundEnabled,
            onCheckedChange = onSoundEnabledChange
        )

        Spacer(Modifier.height(6.dp))

        OutlinedButton(
            onClick = onOpenManualAdd,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Добавить позицию вручную")
        }

        Divider(modifier = Modifier.padding(vertical = 10.dp))

        DrawerSectionTitle("Настройки API / Шлюз ЧЗ")
        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text("URL шлюза API", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("mobile_api" to "Mobile API", "true_api" to "True API").forEach { (mKey, mLabel) ->
                val active = apiMode == mKey
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onApiModeChange(mKey) }
                ) {
                    Text(
                        text = mLabel,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (apiMode == "true_api") {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = apiToken,
                onValueChange = onApiTokenChange,
                label = { Text("Bearer токен", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onSaveApi,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить параметры API")
        }

        Spacer(Modifier.height(24.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "AOR Systems",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Версия 3.0",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun DrawerSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ==================== ЗАМОК ОТ СЛУЧАЙНЫХ КАСАНИЙ ====================

@Composable
private fun ShelfLockOverlay(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onUnlock() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF131720),
            border = BorderStroke(1.dp, Color(0xFF3B82F6)),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Экран заблокирован от полки",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Сканер и вибрация активны.\nУдерживайте палец на экране 1 сек, чтобы разблокировать.",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ==================== ДИАЛОГ РУЧНОГО ДОБАВЛЕНИЯ ====================

@Composable
private fun ManualAddItemDialog(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onAdd: (name: String, expIso: String?, qty: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dateInput by remember {
        mutableStateOf(SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date()))
    }
    var qtyInput by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить вручную") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Наименование товара") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (existingNames.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        existingNames.take(2).forEach { suggestion ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { name = suggestion }
                            ) {
                                Text(
                                    text = suggestion.take(18),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = dateInput,
                    onValueChange = { dateInput = it },
                    label = { Text("Срок годности (ДД.ММ.ГГГГ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = qtyInput,
                    onValueChange = { qtyInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Количество (шт.)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val iso = parseRuToIso(dateInput)
                    val qty = (qtyInput.toIntOrNull() ?: 1).coerceIn(1, 99)
                    onAdd(name.trim(), iso, qty)
                }
            ) {
                Text("Добавить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// ==================== КАМЕРА CAMERAX С МАКРО 0.6X И ML KIT ====================

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraScannerView(
    zoomPresetIndex: Int,
    torchEnabled: Boolean,
    fullFrameScan: Boolean,
    onBarcodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }

    val latestFullFrame by rememberUpdatedState(fullFrameScan)
    val latestCallback by rememberUpdatedState(onBarcodeScanned)

    LaunchedEffect(cameraRef, torchEnabled) {
        cameraRef?.cameraControl?.enableTorch(torchEnabled)
    }

    LaunchedEffect(cameraRef, zoomPresetIndex) {
        val cam = cameraRef ?: return@LaunchedEffect
        val zoomState = cam.cameraInfo.zoomState.value ?: return@LaunchedEffect
        val targetRatio = when (zoomPresetIndex) {
            0 -> zoomState.minZoomRatio.coerceAtLeast(0.5f)
            1 -> 1.0f.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            2 -> 2.0f.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            else -> 3.0f.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        }
        cam.cameraControl.setZoomRatio(targetRatio)
    }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
                        .build()
                    val scanner = BarcodeScanning.getClient(options)

                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            if (!latestFullFrame) {
                                val w = mediaImage.width
                                val h = mediaImage.height
                                val cropSize = (minOf(w, h) * 0.56f).toInt()
                                val left = (w - cropSize) / 2
                                val top = (h - cropSize) / 2
                                imageProxy.setCropRect(android.graphics.Rect(left, top, left + cropSize, top + cropSize))
                            }
                            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { barcodes ->
                                    for (b in barcodes) {
                                        val raw = b.rawValue
                                        if (!raw.isNullOrBlank()) {
                                            latestCallback(raw)
                                            break
                                        }
                                    }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        provider.unbindAll()
                        cameraRef = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!fullFrameScan) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-45).dp)
                    .size(210.dp)
                    .border(2.dp, Color(0xFF38BDF8).copy(alpha = 0.75f), RoundedCornerShape(16.dp))
            )
        }
    }
}

// ==================== СЕТЕВОЙ ЗАПРОС К ШЛЮЗУ ====================

private data class GatewayResult(
    val expDate: String?,
    val name: String?,
    val status: String?,
    val error: String?,
    val shouldRetry: Boolean
)

private suspend fun fetchFromGateway(
    endpoint: String,
    mode: String,
    token: String,
    parsed: ParsedDataMatrix
): GatewayResult = withContext(Dispatchers.IO) {
    try {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Api-Mode", mode)
            if (token.isNotBlank()) {
                setRequestProperty("X-Api-Token", token)
            }
        }

        val payload = JSONObject().apply {
            val arr = JSONArray()
            arr.put(JSONObject().apply {
                put("uniqueKey", parsed.uniqueKey)
                put("gtin", parsed.gtin)
                put("sn", parsed.sn)
                put("cis", parsed.cis)
                put("raw", parsed.raw)
            })
            put("items", arr)
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
        }

        val code = conn.responseCode
        if (code == 429 || code >= 500) {
            return@withContext GatewayResult(null, null, null, "Сервер занят ($code)", shouldRetry = true)
        }
        if (code !in 200..299) {
            return@withContext GatewayResult(null, null, null, "Ошибка шлюза HTTP $code", shouldRetry = false)
        }

        val respText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val json = JSONObject(respText)
        val results = json.optJSONArray("results")
        if (results == null || results.length() == 0) {
            return@withContext GatewayResult(null, null, null, "Пустой ответ шлюза", shouldRetry = false)
        }

        val first = results.getJSONObject(0)
        val err = if (first.isNull("error")) null else first.optString("error")
        val retryFromCrpt = err != null && (err.contains("429") || err.contains("Таймаут"))

        GatewayResult(
            expDate = if (first.isNull("expDate")) null else first.optString("expDate"),
            name = if (first.isNull("name")) null else first.optString("name"),
            status = if (first.isNull("status")) null else first.optString("status"),
            error = err,
            shouldRetry = retryFromCrpt
        )
    } catch (e: Exception) {
        GatewayResult(
            expDate = null,
            name = null,
            status = null,
            error = "Сбой сети: ${e.localizedMessage ?: "таймаут"}",
            shouldRetry = true
        )
    }
}

// ==================== ФОРМАТИРОВАНИЕ ОТЧЁТОВ ====================

private fun buildCsv(list: List<ScannedItem>, thresholdMillis: Long): String {
    val sb = StringBuilder()
    sb.appendLine("Наименование;GTIN;Серийный номер;Срок годности;Статус;Порог проверки")
    val thrStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(thresholdMillis))
    for (it in list) {
        val cleanName = (it.name ?: "Не определено").replace(";", ",")
        val expStr = formatRuDate(it.expDate)
        val expMs = parseIsoToMillis(it.expDate)
        val statusStr = when {
            it.status == "RETIRED" -> "ВЫБЫЛ"
            expMs != null && expMs <= thresholdMillis -> "ПРОСРОЧКА"
            expMs != null -> "ГОДЕН"
            else -> it.infoMsg ?: "БЕЗ ДАТЫ"
        }
        sb.appendLine("$cleanName;\"${it.gtin}\";\"${it.sn}\";$expStr;$statusStr;$thrStr")
    }
    return sb.toString()
}

private fun buildMessengerSummary(
    list: List<ScannedItem>,
    thresholdMillis: Long,
    isExpiredFn: (ScannedItem) -> Boolean
): String {
    val nowStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
    val thrStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(thresholdMillis))
    val expired = list.filter(isExpiredFn)

    val sb = StringBuilder()
    sb.appendLine("📋 Сводка ревизии (AOR Systems)")
    sb.appendLine("Дата: $nowStr | Порог: до $thrStr")
    sb.appendLine("Всего проверено: ${list.size} шт. | На списание: ${expired.size} шт.")
    sb.appendLine()

    if (expired.isEmpty()) {
        sb.appendLine("✅ Просроченных позиций не обнаружено.")
    } else {
        sb.appendLine("🚨 Позиции на списание:")
        val grouped = expired.groupBy { (it.name ?: "GTIN ${it.gtin}") to formatRuDate(it.expDate) }
        var idx = 1
        for ((key, group) in grouped) {
            sb.appendLine("$idx. ${key.first} — ${group.size} шт. (до ${key.second})")
            idx++
        }
    }
    return sb.toString()
}
