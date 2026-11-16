package com.revizor.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import kotlin.math.abs
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

data class ThemePalette(
    val bg: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val border: Int,
    val primary: Int
)

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

    fun loadItems(): MutableList<ScannedItem> {
        val raw = prefs.getString("saved_items_json", null) ?: return mutableListOf()
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
            mutableListOf()
        }
    }
}

// ==================== MAIN ACTIVITY ====================

class MainActivity : ComponentActivity() {

    private lateinit var storage: AppStorage
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val apiQueue = Channel<ParsedDataMatrix>(Channel.UNLIMITED)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val items = mutableListOf<ScannedItem>()
    private var activeBannerKey: String? = null
    private var isDuplicateBanner = false

    // Состояния UI
    private var showListScreen = false
    private var isDrawerOpen = false
    private var isShelfLocked = false
    private var torchEnabled = false
    private var fullFrameScan = false
    private var zoomPresetIndex = 1 // 0=0.6x, 1=1x, 2=2x, 3=3x
    private var selectedTab = FilterTab.ALL
    private var searchQuery = ""
    private val expandedGroups = mutableSetOf<String>()

    private var lastScanKey = ""
    private var lastScanTime = 0L

    // Перехват HID сканера
    private val keyBuffer = StringBuilder()
    private var lastKeyTime = 0L

    // Камера и звук
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var toneGen: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Основные View
    private lateinit var rootFrame: FrameLayout
    private lateinit var headerContainer: LinearLayout
    private lateinit var tvThresholdBadge: TextView
    private lateinit var btnZoom: TextView
    private lateinit var btnScanArea: TextView
    private lateinit var btnTorch: TextView
    private lateinit var btnLock: TextView
    private lateinit var btnReset: TextView
    private val presetViews = mutableMapOf<Int, TextView>()

    // Экран камеры
    private lateinit var cameraSection: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var reticleView: View
    private lateinit var tvPermissionHint: TextView
    private lateinit var bottomHud: LinearLayout
    private lateinit var tvStatAll: TextView
    private lateinit var tvStatExpired: TextView
    private lateinit var tvStatNoDate: TextView

    // Карточка активного скана (используется и в камере, и в ТСД)
    private lateinit var bannerSlotCamera: FrameLayout
    private lateinit var bannerSlotTsd: FrameLayout
    private lateinit var activeBannerCard: LinearLayout
    private lateinit var tvBannerStatus: TextView
    private lateinit var tvBannerDuplicateBadge: TextView
    private lateinit var tvBannerName: TextView
    private lateinit var tvBannerDate: TextView
    private lateinit var fifoContainerBanner: LinearLayout
    private lateinit var fifoFillBanner: View
    private lateinit var tvFifoLabelBanner: TextView

    // Экран списка / ТСД
    private lateinit var listSection: LinearLayout
    private lateinit var phoneListTopBar: LinearLayout
    private lateinit var tvPhoneListTitle: TextView
    private val tabViews = mutableMapOf<FilterTab, TextView>()
    private lateinit var btnGroupToggle: TextView
    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private lateinit var listAdapter: ScanListAdapter
    private lateinit var btnExportExpired: TextView
    private lateinit var btnCopySummary: TextView
    private lateinit var btnExportAll: TextView

    // Вспышка и замок
    private lateinit var flashOverlay: View
    private lateinit var lockOverlay: FrameLayout

    // Боковая шторка
    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: ScrollView

    // CSV экспорт
    private var pendingCsvContent = ""
    private val csvExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        writer.write("\uFEFF")
                        writer.write(pendingCsvContent)
                    }
                }
                Toast.makeText(this, "CSV успешно сохранён", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            tvPermissionHint.visibility = View.GONE
            startCameraIfNeeded()
        } else {
            tvPermissionHint.visibility = View.VISIBLE
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val code = intent.getStringExtra("scannerdata") // MERTECH Seuic AutoID Q9C (ScanTool)
                ?: intent.getStringExtra("barcode_string")
                ?: intent.getStringExtra("data")
                ?: intent.getStringExtra("SCAN_BARCODE1")
                ?: intent.getStringExtra("value")
            if (!code.isNullOrBlank()) {
                onRawBarcodeDetected(code)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = AppStorage(this)
        items.clear()
        items.addAll(storage.loadItems())

        toneGen = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 90)
        } catch (_: Exception) {
            null
        }

        buildUi()
        registerScannerBroadcast()
        startApiWorker()
        refreshAllUi()

        if (!storage.tsdMode) {
            ensureCameraStarted()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Exception) {
        }
        toneGen?.release()
        cameraExecutor.shutdown()
        uiScope.cancel()
    }

    // ==================== ПЕРЕХВАТ АППАРАТНОГО СКАНЕРА (HID) ====================

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
                    onRawBarcodeDetected(code)
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

    private fun registerScannerBroadcast() {
        val filter = IntentFilter().apply {
            addAction("com.android.server.scannerservice.broadcast") // Seuic ScanTool
            addAction("android.intent.ACTION_DECODE_DATA")
            addAction("nlscan.action.SCANNER_RESULT")
            addAction("com.scanner.broadcast")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, filter, 2)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(scanReceiver, filter)
        }
    }

    // ==================== ПОСТРОЕНИЕ ИНТЕРФЕЙСА ====================

    private fun palette(): ThemePalette {
        return if (storage.darkTheme) {
            ThemePalette(
                bg = Color.parseColor("#090B0E"),
                surface = Color.parseColor("#131720"),
                surfaceAlt = Color.parseColor("#1C2230"),
                textPrimary = Color.parseColor("#F1F5F9"),
                textSecondary = Color.parseColor("#94A3B8"),
                border = Color.parseColor("#2B3446"),
                primary = Color.parseColor("#3B82F6")
            )
        } else {
            ThemePalette(
                bg = Color.parseColor("#F1F5F9"),
                surface = Color.parseColor("#FFFFFF"),
                surfaceAlt = Color.parseColor("#E2E8F0"),
                textPrimary = Color.parseColor("#0F172A"),
                textSecondary = Color.parseColor("#475569"),
                border = Color.parseColor("#CBD5E1"),
                primary = Color.parseColor("#2563EB")
            )
        }
    }

    private fun spSize(base: Float): Float = if (storage.largeFont) base * 1.2f else base

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun roundedBg(fillColor: Int, radiusDp: Int = 10, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeDp > 0) {
                setStroke(dp(strokeDp), strokeColor)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        rootFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Жест свайпа слева направо для открытия шторки настроек
        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isShelfLocked || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) && abs(dx) > dp(60)) {
                    if (dx > 0 && !isDrawerOpen && e1.x < dp(65)) {
                        openDrawer()
                        return true
                    } else if (dx < 0 && isDrawerOpen) {
                        closeDrawer()
                        return true
                    }
                }
                return false
            }
        })

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ===== 1. ВЕРХНЯЯ ПАНЕЛЬ УПРАВЛЕНИЯ =====
        headerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnMenu = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { openDrawer() }
        }
        topRow.addView(btnMenu)

        tvThresholdBadge = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(6)
            }
        }
        topRow.addView(tvThresholdBadge)

        btnZoom = makeHeaderChip("1x") {
            zoomPresetIndex = (zoomPresetIndex + 1) % 4
            applyCameraZoom()
            refreshAllUi()
        }
        topRow.addView(btnZoom, marginRightParams(5))

        btnScanArea = makeHeaderChip("Центр") {
            fullFrameScan = !fullFrameScan
            refreshAllUi()
        }
        topRow.addView(btnScanArea, marginRightParams(5))

        btnTorch = makeHeaderChip("🔦") {
            torchEnabled = !torchEnabled
            boundCamera?.cameraControl?.enableTorch(torchEnabled)
            refreshAllUi()
        }
        topRow.addView(btnTorch, marginRightParams(5))

        btnLock = makeHeaderChip("🔒") {
            isShelfLocked = true
            lockOverlay.visibility = View.VISIBLE
        }
        topRow.addView(btnLock, marginRightParams(5))

        btnReset = makeHeaderChip("Сброс") {
            if (items.isNotEmpty()) confirmResetDialog()
        }
        topRow.addView(btnReset)

        headerContainer.addView(topRow)

        // Ряд пресетов дат
        val presetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val presets = listOf(0 to "Сегодня", 1 to "+1 дн", 3 to "+3 дн", 7 to "+7 дн", 14 to "+14 дн")
        presets.forEachIndexed { idx, (days, label) ->
            val tv = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < presets.lastIndex) marginEnd = dp(5)
                }
                setOnClickListener {
                    storage.offsetDays = days
                    refreshAllUi()
                }
            }
            presetViews[days] = tv
            presetsRow.addView(tv)
        }
        headerContainer.addView(presetsRow)
        mainColumn.addView(headerContainer)

        // ===== 2. КАРТОЧКА ТЕКУЩЕГО / ПОСЛЕДНЕГО СКАНА =====
        activeBannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                if (!storage.tsdMode) {
                    showListScreen = true
                    refreshAllUi()
                }
            }
        }

        val bannerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvBannerStatus = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvBannerDuplicateBadge = TextView(this).apply {
            text = "Уже в списке"
            setPadding(dp(7), dp(2), dp(7), dp(2))
            visibility = View.GONE
        }
        bannerTopRow.addView(tvBannerStatus)
        bannerTopRow.addView(tvBannerDuplicateBadge)
        activeBannerCard.addView(bannerTopRow)

        tvBannerName = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        activeBannerCard.addView(tvBannerName)

        tvBannerDate = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
        }
        activeBannerCard.addView(tvBannerDate)

        // Шкала свежести FIFO в карточке скана
        fifoContainerBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val fifoTrack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(5), 1f)
        }
        fifoFillBanner = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        fifoTrack.addView(fifoFillBanner)

        tvFifoLabelBanner = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
        fifoContainerBanner.addView(fifoTrack)
        fifoContainerBanner.addView(tvFifoLabelBanner)
        activeBannerCard.addView(fifoContainerBanner)

        // ===== 3. ОБЛАСТЬ КАМЕРЫ (РЕЖИМ ТЕЛЕФОНА) =====
        cameraSection = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        cameraSection.addView(previewView)

        reticleView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(210), dp(210), Gravity.CENTER).apply {
                bottomMargin = dp(45)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.parseColor("#BF38BDF8"))
            }
        }
        cameraSection.addView(reticleView)

        tvPermissionHint = TextView(this).apply {
            text = "Нажмите, чтобы разрешить доступ к камере"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBg(Color.parseColor("#CC1E293B"), 12)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            setOnClickListener { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
        }
        cameraSection.addView(tvPermissionHint)

        // Нижняя панель счётчиков + активный скан
        bottomHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        tvStatAll = makeStatChip {
            selectedTab = FilterTab.ALL
            showListScreen = true
            refreshAllUi()
        }
        tvStatExpired = makeStatChip {
            selectedTab = FilterTab.EXPIRED
            showListScreen = true
            refreshAllUi()
        }
        tvStatNoDate = makeStatChip {
            selectedTab = FilterTab.NO_DATE
            showListScreen = true
            refreshAllUi()
        }
        statsRow.addView(tvStatAll, statWeightParams(6))
        statsRow.addView(tvStatExpired, statWeightParams(6))
        statsRow.addView(tvStatNoDate, statWeightParams(0))
        bottomHud.addView(statsRow)

        bannerSlotCamera = FrameLayout(this)
        bottomHud.addView(bannerSlotCamera)
        cameraSection.addView(bottomHud)
        mainColumn.addView(cameraSection)

        // ===== 4. ОБЛАСТЬ СПИСКА (И ДЛЯ ТСД, И ДЛЯ ПРОСМОТРА НА ТЕЛЕФОНЕ) =====
        listSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
        }

        // Верхняя полоска возврата к камере (показывается только в режиме смартфона)
        phoneListTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val btnBackCamera = makeHeaderChip("← К камере") {
            showListScreen = false
            refreshAllUi()
        }
        tvPhoneListTitle = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClearList = makeHeaderChip("Очистить") {
            if (items.isNotEmpty()) confirmResetDialog()
        }
        phoneListTopBar.addView(btnBackCamera)
        phoneListTopBar.addView(tvPhoneListTitle)
        phoneListTopBar.addView(btnClearList)
        listSection.addView(phoneListTopBar)

        // Слот для плашки последнего скана в режиме ТСД
        bannerSlotTsd = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        listSection.addView(bannerSlotTsd)

        // Вкладки фильтрации + кнопка Группы
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        FilterTab.values().forEach { tab ->
            val tv = TextView(this).apply {
                text = tab.title
                setPadding(dp(9), dp(6), dp(9), dp(6))
                setOnClickListener {
                    selectedTab = tab
                    refreshAllUi()
                }
            }
            tabViews[tab] = tv
            tabsRow.addView(tv, marginRightParams(5))
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        tabsRow.addView(spacer)

        btnGroupToggle = TextView(this).apply {
            text = "Группы"
            setPadding(dp(9), dp(6), dp(9), dp(6))
            setOnClickListener {
                storage.groupByName = !storage.groupByName
                refreshAllUi()
            }
        }
        tabsRow.addView(btnGroupToggle)
        listSection.addView(tabsRow)

        // Поле поиска
        etSearch = EditText(this).apply {
            hint = "Поиск по названию, GTIN или SN..."
            isSingleLine = true
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString() ?: ""
                    refreshListOnly()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        listSection.addView(etSearch)

        // Сам список позиций / аккордеон-групп
        listAdapter = ScanListAdapter()
        listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(6)
            adapter = listAdapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listSection.addView(listView)

        // Нижние кнопки экспорта
        val exportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        btnExportExpired = TextView(this).apply {
            text = "📥 CSV Просрочка"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = roundedBg(Color.parseColor("#DC2626"), 10)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                pendingCsvContent = buildCsv(items.filter { isItemExpired(it) })
                csvExportLauncher.launch("AOR_Prosrochka_${formatIsoFileDate()}.csv")
            }
        }
        btnCopySummary = TextView(this).apply {
            text = "📋 Сводка"
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AOR Сводка", buildMessengerSummary()))
                Toast.makeText(this@MainActivity, "Сводка скопирована для мессенджера", Toast.LENGTH_SHORT).show()
            }
        }
        btnExportAll = TextView(this).apply {
            text = "📥 Всё"
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
            setOnClickListener {
                pendingCsvContent = buildCsv(items)
                csvExportLauncher.launch("AOR_Reviziya_${formatIsoFileDate()}.csv")
            }
        }
        exportRow.addView(btnExportExpired)
        exportRow.addView(btnCopySummary)
        exportRow.addView(btnExportAll)
        listSection.addView(exportRow)
        mainColumn.addView(listSection)

        rootFrame.addView(mainColumn)

        // ===== 5. ПОЛНОЭКРАННАЯ ЦВЕТОВАЯ ВСПЫШКА =====
        flashOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            isClickable = false
        }
        rootFrame.addView(flashOverlay)

        // ===== 6. ЗАМОК ОТ СЛУЧАЙНЫХ КАСАНИЙ НА ПОЛКЕ =====
        lockOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#B3000000"))
            visibility = View.GONE
            isClickable = true
            isLongClickable = true
            setOnLongClickListener {
                isShelfLocked = false
                visibility = View.GONE
                Toast.makeText(this@MainActivity, "Экран разблокирован", Toast.LENGTH_SHORT).show()
                true
            }
        }
        val lockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = roundedBg(Color.parseColor("#131720"), 16, Color.parseColor("#3B82F6"), 1)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = dp(24)
                marginEnd = dp(24)
            }
        }
        lockCard.addView(TextView(this).apply {
            text = "🔒"
            textSize = 32f
            gravity = Gravity.CENTER
        })
        lockCard.addView(TextView(this).apply {
            text = "Экран заблокирован от полки"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
        })
        lockCard.addView(TextView(this).apply {
            text = "Сканер и вибрация активны.\nУдерживайте палец на экране 1 сек для разблокировки."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        lockOverlay.addView(lockCard)
        rootFrame.addView(lockOverlay)

        // ===== 7. БОКОВАЯ ШТОРКА НАСТРОЕК (СВАЙП СЛЕВА НАПРАВО) =====
        drawerScrim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        rootFrame.addView(drawerScrim)

        drawerPanel = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(305), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
            visibility = View.GONE
            translationX = -dp(305).toFloat()
        }
        buildDrawerContent()
        rootFrame.addView(drawerPanel)

        rootFrame.setOnTouchListener { _, event ->
            swipeDetector.onTouchEvent(event)
            false
        }

        setContentView(rootFrame)
    }

    private fun buildDrawerContent() {
        drawerPanel.removeAllViews()
        val p = palette()
        drawerPanel.setBackgroundColor(p.surface)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "Параметры терминала"
            textSize = spSize(18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.textPrimary)
        })

        col.addView(makeDrawerSectionHeader("Оборудование", p))
        col.addView(makeDrawerSwitch(
            title = "Режим ТСД (без камеры)",
            subtitle = "Для MERTECH Seuic AutoID Q9C и 2D-сканеров",
            checked = storage.tsdMode,
            p = p
        ) { enabled ->
            storage.tsdMode = enabled
            if (enabled) {
                torchEnabled = false
                showListScreen = false
                cameraProvider?.unbindAll()
            } else {
                ensureCameraStarted()
            }
            refreshAllUi()
        })

        col.addView(makeDrawerSectionHeader("Дизайн и индикация", p))
        col.addView(makeDrawerSwitch(
            title = "Тёмная тема (AMOLED)",
            subtitle = "Выключите для светлой темы в ярком зале",
            checked = storage.darkTheme,
            p = p
        ) { enabled ->
            storage.darkTheme = enabled
            buildDrawerContent()
            refreshAllUi()
        })

        col.addView(makeDrawerSwitch(
            title = "Крупный шрифт",
            subtitle = "Увеличенный интерфейс для 4\" экранов",
            checked = storage.largeFont,
            p = p
        ) { enabled ->
            storage.largeFont = enabled
            buildDrawerContent()
            refreshAllUi()
        })

        col.addView(makeDrawerSwitch(
            title = "Цветовая вспышка экрана",
            subtitle = "Подсветка краёв зелёным/красным при скане",
            checked = storage.flashEnabled,
            p = p
        ) { enabled ->
            storage.flashEnabled = enabled
        })

        col.addView(makeDrawerSwitch(
            title = "Шкала свежести (FIFO)",
            subtitle = "Индикатор ротации по остатку дней",
            checked = storage.fifoBarEnabled,
            p = p
        ) { enabled ->
            storage.fifoBarEnabled = enabled
            refreshAllUi()
        })

        col.addView(makeDrawerSectionHeader("Звук и ручной ввод", p))
        col.addView(makeDrawerSwitch(
            title = "Звуковой сигнал",
            subtitle = "Вибрация работает всегда даже без звука",
            checked = storage.soundEnabled,
            p = p
        ) { enabled ->
            storage.soundEnabled = enabled
        })

        val btnManual = TextView(this).apply {
            text = "+ Добавить позицию вручную"
            gravity = Gravity.CENTER
            textSize = spSize(13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.textPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(p.surfaceAlt, 10, p.border, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            setOnClickListener {
                closeDrawer()
                openManualAddDialog()
            }
        }
        col.addView(btnManual)

        col.addView(makeDrawerSectionHeader("Настройки API / Шлюз ЧЗ", p))
        val etApiUrl = EditText(this).apply {
            setText(storage.apiUrl)
            hint = "URL шлюза API"
            textSize = spSize(13f)
            setTextColor(p.textPrimary)
            setHintTextColor(p.textSecondary)
            isSingleLine = true
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBg(p.surfaceAlt, 8, p.border, 1)
        }
        col.addView(etApiUrl)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        var draftMode = storage.apiMode
        val btnMobileApi = TextView(this)
        val btnTrueApi = TextView(this)
        val etToken = EditText(this).apply {
            setText(storage.apiToken)
            hint = "Bearer токен True API"
            textSize = spSize(13f)
            setTextColor(p.textPrimary)
            setHintTextColor(p.textSecondary)
            isSingleLine = true
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBg(p.surfaceAlt, 8, p.border, 1)
            visibility = if (draftMode == "true_api") View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }

        fun updateModeChips() {
            btnMobileApi.background = roundedBg(if (draftMode == "mobile_api") p.primary else p.surfaceAlt, 8)
            btnMobileApi.setTextColor(if (draftMode == "mobile_api") Color.WHITE else p.textPrimary)
            btnTrueApi.background = roundedBg(if (draftMode == "true_api") p.primary else p.surfaceAlt, 8)
            btnTrueApi.setTextColor(if (draftMode == "true_api") Color.WHITE else p.textPrimary)
            etToken.visibility = if (draftMode == "true_api") View.VISIBLE else View.GONE
        }

        btnMobileApi.apply {
            text = "Mobile API"
            gravity = Gravity.CENTER
            textSize = spSize(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener { draftMode = "mobile_api"; updateModeChips() }
        }
        btnTrueApi.apply {
            text = "True API"
            gravity = Gravity.CENTER
            textSize = spSize(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { draftMode = "true_api"; updateModeChips() }
        }
        updateModeChips()
        modeRow.addView(btnMobileApi)
        modeRow.addView(btnTrueApi)
        col.addView(modeRow)
        col.addView(etToken)

        val btnSaveApi = TextView(this).apply {
            text = "Сохранить параметры API"
            gravity = Gravity.CENTER
            textSize = spSize(13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(p.primary, 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            setOnClickListener {
                storage.apiUrl = etApiUrl.text.toString()
                storage.apiMode = draftMode
                storage.apiToken = etToken.text.toString()
                Toast.makeText(this@MainActivity, "Настройки шлюза сохранены", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(btnSaveApi)

        // Разделитель и подпись бренда AOR Systems + Версия 3.0
        val divider = View(this).apply {
            setBackgroundColor(p.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(22)
                bottomMargin = dp(12)
            }
        }
        col.addView(divider)

        col.addView(TextView(this).apply {
            text = "AOR Systems"
            textSize = spSize(15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.textPrimary)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "Версия 3.0"
            textSize = spSize(12f)
            setTextColor(p.textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })

        drawerPanel.addView(col)
    }

    private fun openDrawer() {
        isDrawerOpen = true
        drawerScrim.visibility = View.VISIBLE
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        isDrawerOpen = false
        drawerScrim.visibility = View.GONE
        drawerPanel.animate().translationX(-dp(305).toFloat()).setDuration(180).withEndAction {
            if (!isDrawerOpen) drawerPanel.visibility = View.GONE
        }.start()
    }

    private fun makeDrawerSectionHeader(title: String, p: ThemePalette): TextView {
        return TextView(this).apply {
            text = title.uppercase(Locale.getDefault())
            textSize = spSize(11f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(0, dp(14), 0, dp(4))
        }
    }

    private fun makeDrawerSwitch(
        title: String,
        subtitle: String,
        checked: Boolean,
        p: ThemePalette,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = spSize(14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.textPrimary)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = spSize(11f)
            setTextColor(p.textSecondary)
        })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        row.addView(textCol)
        row.addView(sw)
        return row
    }

    private fun makeHeaderChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(7), dp(9), dp(7))
            setOnClickListener { onClick() }
        }
    }

    private fun makeStatChip(onClick: () -> Unit): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun marginRightParams(dpRight: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(dpRight)
        }
    }

    private fun statWeightParams(dpRight: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (dpRight > 0) marginEnd = dp(dpRight)
        }
    }

    // ==================== ОБНОВЛЕНИЕ СОСТОЯНИЯ ЭКРАНА ====================

    private fun thresholdMillis(): Long = addDaysToTodayMillis(storage.offsetDays)

    private fun isItemExpired(item: ScannedItem): Boolean {
        if (item.status == "RETIRED") return true
        val expMs = parseIsoToMillis(item.expDate) ?: return false
        return expMs <= thresholdMillis()
    }

    private fun refreshAllUi() {
        val p = palette()
        val tsd = storage.tsdMode

        rootFrame.setBackgroundColor(p.bg)
        headerContainer.setBackgroundColor(p.surface)

        // Шапка
        val dateStr = formatShortDayMonth(thresholdMillis())
        tvThresholdBadge.text = if (tsd) "ТСД • До $dateStr" else "До $dateStr"
        tvThresholdBadge.textSize = spSize(13f)
        tvThresholdBadge.setTextColor(p.textPrimary)
        tvThresholdBadge.background = roundedBg(p.surfaceAlt, 8)

        btnZoom.visibility = if (tsd) View.GONE else View.VISIBLE
        btnScanArea.visibility = if (tsd) View.GONE else View.VISIBLE
        btnTorch.visibility = if (tsd) View.GONE else View.VISIBLE

        btnZoom.text = when (zoomPresetIndex) {
            0 -> "0.6x"
            1 -> "1x"
            2 -> "2x"
            else -> "3x"
        }
        styleChip(btnZoom, false, p)

        btnScanArea.text = if (fullFrameScan) "Весь" else "Центр"
        styleChip(btnScanArea, fullFrameScan, p)
        reticleView.visibility = if (!tsd && !fullFrameScan) View.VISIBLE else View.GONE

        btnTorch.background = roundedBg(if (torchEnabled) Color.parseColor("#F59E0B") else p.surfaceAlt, 8)
        btnTorch.setTextColor(if (torchEnabled) Color.BLACK else p.textPrimary)

        styleChip(btnLock, false, p)
        styleChip(btnReset, false, p)

        // Пресеты дат
        for ((days, tv) in presetViews) {
            val active = storage.offsetDays == days
            tv.textSize = spSize(12f)
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            tv.background = roundedBg(if (active) p.primary else p.surfaceAlt, 8)
            tv.setTextColor(if (active) Color.WHITE else p.textPrimary)
        }

        // Переключение режимов Камера / ТСД / Список
        if (tsd) {
            cameraSection.visibility = View.GONE
            listSection.visibility = View.VISIBLE
            phoneListTopBar.visibility = View.GONE
            bannerSlotTsd.visibility = View.VISIBLE
            attachBannerCardTo(bannerSlotTsd)
        } else if (showListScreen) {
            cameraSection.visibility = View.GONE
            listSection.visibility = View.VISIBLE
            phoneListTopBar.visibility = View.VISIBLE
            bannerSlotTsd.visibility = View.GONE
            tvPhoneListTitle.text = "Отсканировано: ${items.size}"
            tvPhoneListTitle.textSize = spSize(15f)
            tvPhoneListTitle.setTextColor(p.textPrimary)
        } else {
            cameraSection.visibility = View.VISIBLE
            listSection.visibility = View.GONE
            bottomHud.setBackgroundColor(Color.argb(235, Color.red(p.bg), Color.green(p.bg), Color.blue(p.bg)))
            attachBannerCardTo(bannerSlotCamera)
        }

        // Счётчики
        val expiredCount = items.count { isItemExpired(it) }
        val noDateCount = items.count { it.expDate == null && !it.isLoading }

        tvStatAll.text = "${items.size}\nВсего ↗"
        tvStatAll.textSize = spSize(12f)
        tvStatAll.setTextColor(p.textPrimary)
        tvStatAll.background = roundedBg(p.surface, 10, p.border, 1)

        tvStatExpired.text = "$expiredCount\nПросрочка ↗"
        tvStatExpired.textSize = spSize(12f)
        tvStatExpired.setTextColor(Color.parseColor("#EF4444"))
        tvStatExpired.background = roundedBg(p.surface, 10, p.border, 1)

        tvStatNoDate.text = "$noDateCount\nБез даты ↗"
        tvStatNoDate.textSize = spSize(12f)
        tvStatNoDate.setTextColor(Color.parseColor("#F59E0B"))
        tvStatNoDate.background = roundedBg(p.surface, 10, p.border, 1)

        // Вкладки списка
        for ((tab, tv) in tabViews) {
            styleChip(tv, selectedTab == tab, p)
        }
        styleChip(btnGroupToggle, storage.groupByName, p)

        etSearch.textSize = spSize(13f)
        etSearch.setTextColor(p.textPrimary)
        etSearch.setHintTextColor(p.textSecondary)
        etSearch.background = roundedBg(p.surface, 10, p.border, 1)

        btnCopySummary.background = roundedBg(p.surface, 10, p.border, 1)
        btnCopySummary.setTextColor(p.textPrimary)
        btnCopySummary.textSize = spSize(12f)

        btnExportAll.background = roundedBg(p.surface, 10, p.border, 1)
        btnExportAll.setTextColor(p.textPrimary)
        btnExportAll.textSize = spSize(12f)
        btnExportExpired.textSize = spSize(12f)

        updateActiveBannerCard(p)
        refreshListOnly()
    }

    private fun styleChip(tv: TextView, active: Boolean, p: ThemePalette) {
        tv.textSize = spSize(12f)
        tv.background = roundedBg(if (active) p.primary else p.surfaceAlt, 8)
        tv.setTextColor(if (active) Color.WHITE else p.textPrimary)
    }

    private fun attachBannerCardTo(target: FrameLayout) {
        val currentParent = activeBannerCard.parent as? ViewGroup
        if (currentParent !== target) {
            currentParent?.removeView(activeBannerCard)
            target.addView(activeBannerCard)
        }
    }

    private fun updateActiveBannerCard(p: ThemePalette) {
        val item = items.find { it.uniqueKey == activeBannerKey } ?: items.firstOrNull()
        val large = storage.tsdMode

        if (item == null) {
            activeBannerCard.background = roundedBg(p.surface, 12, p.border, 1)
            tvBannerStatus.text = "Наведите сканер на DataMatrix «Честного ЗНАКа»"
            tvBannerStatus.setTextColor(p.textSecondary)
            tvBannerStatus.textSize = spSize(if (large) 15f else 13f)
            tvBannerDuplicateBadge.visibility = View.GONE
            tvBannerName.visibility = View.GONE
            tvBannerDate.visibility = View.GONE
            fifoContainerBanner.visibility = View.GONE
            return
        }

        tvBannerName.visibility = View.VISIBLE
        tvBannerDate.visibility = View.VISIBLE

        val expired = isItemExpired(item)
        val borderColor = when {
            item.isLoading -> Color.parseColor("#3B82F6")
            expired -> Color.parseColor("#EF4444")
            item.expDate != null -> Color.parseColor("#10B981")
            else -> Color.parseColor("#F59E0B")
        }

        activeBannerCard.background = roundedBg(p.surface, 12, borderColor, 2)

        tvBannerStatus.text = when {
            item.isLoading -> "⏳ Запрос в Честный ЗНАК..."
            item.status == "RETIRED" -> "⛔ ВЫБЫЛ ИЗ ОБОРОТА!"
            expired -> "🚨 ПРОСРОЧКА / СПИСАТЬ"
            item.expDate != null -> "✅ ГОДЕН"
            else -> "⚠️ НЕТ ДАТЫ В ЧЗ"
        }
        tvBannerStatus.setTextColor(borderColor)
        tvBannerStatus.textSize = spSize(if (large) 15f else 13f)

        tvBannerDuplicateBadge.visibility = if (isDuplicateBanner) View.VISIBLE else View.GONE
        tvBannerDuplicateBadge.background = roundedBg(p.surfaceAlt, 6)
        tvBannerDuplicateBadge.setTextColor(p.textSecondary)
        tvBannerDuplicateBadge.textSize = spSize(11f)

        tvBannerName.text = item.name ?: "GTIN: ${item.gtin}"
        tvBannerName.setTextColor(p.textPrimary)
        tvBannerName.textSize = spSize(if (large) 16f else 14f)

        tvBannerDate.text = when {
            item.expDate != null -> "Срок годности: до ${formatRuDate(item.expDate)}"
            !item.infoMsg.isNullOrBlank() -> item.infoMsg
            else -> "GTIN ${item.gtin} • SN: ${item.sn}"
        }
        tvBannerDate.setTextColor(if (expired) Color.parseColor("#EF4444") else p.textPrimary)
        tvBannerDate.setTypeface(null, if (item.expDate != null) Typeface.BOLD else Typeface.NORMAL)
        tvBannerDate.textSize = spSize(if (large) 15f else 13f)

        if (storage.fifoBarEnabled && item.expDate != null) {
            val expMs = parseIsoToMillis(item.expDate)
            if (expMs != null) {
                fifoContainerBanner.visibility = View.VISIBLE
                val daysLeft = ((expMs - todayZeroMillis()) / 86_400_000L).toInt()
                val (barColor, widthDp, label) = when {
                    expMs <= thresholdMillis() -> Triple(Color.parseColor("#EF4444"), 180, "Списать")
                    daysLeft <= 3 -> Triple(Color.parseColor("#F59E0B"), 115, "Ротация ($daysLeft дн.)")
                    else -> Triple(Color.parseColor("#10B981"), 65, "Запас ($daysLeft дн.)")
                }
                fifoFillBanner.background = roundedBg(barColor, 4)
                fifoFillBanner.layoutParams = FrameLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.MATCH_PARENT)
                tvFifoLabelBanner.text = label
                tvFifoLabelBanner.setTextColor(barColor)
                tvFifoLabelBanner.textSize = spSize(10f)
            } else {
                fifoContainerBanner.visibility = View.GONE
            }
        } else {
            fifoContainerBanner.visibility = View.GONE
        }
    }

    private fun refreshListOnly() {
        listAdapter.rebuildData()
        listAdapter.notifyDataSetChanged()
    }

    // ==================== АДАПТЕР СПИСКА (ОБЫЧНЫЙ + АККОРДЕОН ГРУППЫ) ====================

    private inner class ScanListAdapter : BaseAdapter() {
        private sealed class RowEntry {
            data class Single(val item: ScannedItem) : RowEntry()
            data class Group(val title: String, val units: List<ScannedItem>) : RowEntry()
        }

        private var rows: List<RowEntry> = emptyList()

        fun rebuildData() {
            val q = searchQuery.trim().lowercase(Locale.getDefault())
            val filtered = items.filter { item ->
                val tabMatch = when (selectedTab) {
                    FilterTab.ALL -> true
                    FilterTab.EXPIRED -> isItemExpired(item)
                    FilterTab.VALID -> item.expDate != null && !isItemExpired(item)
                    FilterTab.NO_DATE -> item.expDate == null && !item.isLoading
                }
                val queryMatch = q.isEmpty() ||
                    (item.name?.lowercase(Locale.getDefault())?.contains(q) == true) ||
                    item.gtin.contains(q) ||
                    item.sn.lowercase(Locale.getDefault()).contains(q)
                tabMatch && queryMatch
            }

            rows = if (storage.groupByName) {
                filtered
                    .groupBy { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } ?: "GTIN: ${it.gtin}" }
                    .map { (title, list) -> RowEntry.Group(title, list) }
            } else {
                filtered.map { RowEntry.Single(it) }
            }
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = palette()
            return when (val entry = rows[position]) {
                is RowEntry.Single -> buildSingleCard(entry.item, p, compact = false)
                is RowEntry.Group -> buildGroupCard(entry.title, entry.units, p)
            }
        }

        private fun buildGroupCard(title: String, units: List<ScannedItem>, p: ThemePalette): View {
            val isExpanded = title in expandedGroups
            val expCount = units.count { isItemExpired(it) }
            val borderColor = if (expCount > 0) Color.parseColor("#EF4444") else p.border

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(11), dp(10))
                background = roundedBg(p.surface, 10, borderColor, if (expCount > 0) 2 else 1)
            }

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (isExpanded) expandedGroups.remove(title) else expandedGroups.add(title)
                    notifyDataSetChanged()
                }
            }

            val leftCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            leftCol.addView(TextView(this@MainActivity).apply {
                text = title
                setTypeface(null, Typeface.BOLD)
                textSize = spSize(14f)
                setTextColor(p.textPrimary)
            })

            val summaryLine = if (expCount > 0) {
                "Всего: ${units.size} шт.  •  Просрочка: $expCount"
            } else {
                "Всего: ${units.size} шт."
            }
            leftCol.addView(TextView(this@MainActivity).apply {
                text = summaryLine
                textSize = spSize(12f)
                setTypeface(null, if (expCount > 0) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (expCount > 0) Color.parseColor("#EF4444") else p.textSecondary)
                setPadding(0, dp(3), 0, 0)
            })

            val arrow = TextView(this@MainActivity).apply {
                text = if (isExpanded) "▲" else "▼"
                textSize = spSize(14f)
                setTextColor(p.textSecondary)
                setPadding(dp(8), 0, dp(4), 0)
            }

            headerRow.addView(leftCol)
            headerRow.addView(arrow)
            card.addView(headerRow)

            if (isExpanded) {
                val sep = View(this@MainActivity).apply {
                    setBackgroundColor(p.border)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(6)
                    }
                }
                card.addView(sep)

                for (sub in units) {
                    val subRow = buildSingleCard(sub, p, compact = true)
                    (subRow.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(5)
                    card.addView(subRow)
                }
            }

            return card
        }

        private fun buildSingleCard(item: ScannedItem, p: ThemePalette, compact: Boolean): View {
            val expired = isItemExpired(item)
            val borderColor = when {
                item.isLoading -> p.primary
                expired -> Color.parseColor("#EF4444")
                item.expDate != null -> Color.parseColor("#10B981")
                else -> Color.parseColor("#F59E0B")
            }

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(8), dp(8))
                background = roundedBg(if (compact) p.surfaceAlt else p.surface, 10, borderColor, 1)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val infoCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            if (!compact) {
                infoCol.addView(TextView(this@MainActivity).apply {
                    text = item.name ?: "Загрузка названия..."
                    setTypeface(null, Typeface.BOLD)
                    textSize = spSize(14f)
                    setTextColor(p.textPrimary)
                })
            }

            infoCol.addView(TextView(this@MainActivity).apply {
                text = "GTIN: ${item.gtin} • SN: ${item.sn}"
                textSize = spSize(11f)
                setTextColor(p.textSecondary)
                setPadding(0, dp(2), 0, dp(2))
            })

            val statusText = when {
                item.isLoading -> "Проверка в Честном ЗНАКе..."
                item.status == "RETIRED" -> "ВЫБЫЛ (до ${formatRuDate(item.expDate)})"
                expired && item.expDate != null -> "ПРОСРОЧКА: до ${formatRuDate(item.expDate)}"
                item.expDate != null -> "Годен: до ${formatRuDate(item.expDate)}"
                else -> item.infoMsg ?: "Срок не указан"
            }
            infoCol.addView(TextView(this@MainActivity).apply {
                text = statusText
                setTypeface(null, Typeface.BOLD)
                textSize = spSize(13f)
                setTextColor(borderColor)
            })

            val btnDel = TextView(this@MainActivity).apply {
                text = "✕"
                textSize = spSize(15f)
                setTextColor(p.textSecondary)
                setPadding(dp(10), dp(6), dp(8), dp(6))
                setOnClickListener {
                    items.removeAll { it.uniqueKey == item.uniqueKey }
                    storage.saveItems(items)
                    refreshAllUi()
                }
            }

            row.addView(infoCol)
            row.addView(btnDel)
            return row
        }
    }

    // ==================== ОБРАБОТКА СКАНОВ И ОЧЕРЕДЬ API ====================

    private fun onRawBarcodeDetected(rawText: String) {
        val parsed = CrptParser.parse(rawText) ?: return
        val now = System.currentTimeMillis()
        if (lastScanKey == parsed.uniqueKey && now - lastScanTime < 1100L) return
        lastScanKey = parsed.uniqueKey
        lastScanTime = now

        mainHandler.post {
            val existing = items.find { it.uniqueKey == parsed.uniqueKey }
            if (existing != null) {
                // Повторное наведение: показываем срок годности уже отсканированного товара!
                activeBannerKey = existing.uniqueKey
                isDuplicateBanner = true
                triggerFeedback(expired = isItemExpired(existing), isDuplicate = true)
                refreshAllUi()
                return@post
            }

            isDuplicateBanner = false
            activeBannerKey = parsed.uniqueKey
            items.add(
                0,
                ScannedItem(
                    uniqueKey = parsed.uniqueKey,
                    gtin = parsed.gtin,
                    sn = parsed.sn,
                    raw = parsed.raw,
                    isLoading = true
                )
            )
            storage.saveItems(items)
            refreshAllUi()
            apiQueue.trySend(parsed)
        }
    }

    private fun startApiWorker() {
        uiScope.launch {
            for (task in apiQueue) {
                delay(350L + Random.nextLong(50L, 140L))
                var attempt = 0
                var done = false

                while (attempt < 3 && !done) {
                    attempt++
                    if (attempt > 1) {
                        updateSingleItem(task.uniqueKey) {
                            it.copy(infoMsg = "Повтор запроса ($attempt/3)...")
                        }
                        delay(2200L * (attempt - 1))
                    }

                    val res = fetchFromGateway(
                        endpoint = storage.apiUrl,
                        mode = storage.apiMode,
                        token = storage.apiToken,
                        parsed = task
                    )

                    if (res.shouldRetry && attempt < 3) continue

                    done = true
                    updateSingleItem(task.uniqueKey) {
                        it.copy(
                            name = res.name ?: it.name,
                            expDate = res.expDate,
                            status = res.status,
                            infoMsg = res.error,
                            isLoading = false
                        )
                    }

                    val updated = items.find { it.uniqueKey == task.uniqueKey }
                    if (updated != null && (updated.expDate != null || updated.status == "RETIRED")) {
                        triggerFeedback(expired = isItemExpired(updated), isDuplicate = false)
                    }
                }
            }
        }
    }

    private fun updateSingleItem(key: String, transform: (ScannedItem) -> ScannedItem) {
        val idx = items.indexOfFirst { it.uniqueKey == key }
        if (idx != -1) {
            items[idx] = transform(items[idx])
            storage.saveItems(items)
            refreshAllUi()
        }
    }

    private fun triggerFeedback(expired: Boolean, isDuplicate: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
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
            if (storage.soundEnabled) {
                toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 350)
            }
            if (storage.flashEnabled) {
                showScreenFlash(Color.parseColor("#EF4444"), 460L)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(38, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(38)
            }
            if (storage.soundEnabled) {
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
            }
            if (storage.flashEnabled) {
                showScreenFlash(Color.parseColor("#10B981"), 320L)
            }
        }
    }

    private fun showScreenFlash(color: Int, durationMs: Long) {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(10), color)
        }
        flashOverlay.background = gd
        flashOverlay.visibility = View.VISIBLE
        mainHandler.removeCallbacksAndMessages("flash_hide")
        mainHandler.postDelayed({ flashOverlay.visibility = View.GONE }, durationMs)
    }

    // ==================== КАМЕРА CAMERAX С МАКРО 0.6X ====================

    private fun ensureCameraStarted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            tvPermissionHint.visibility = View.GONE
            startCameraIfNeeded()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCameraIfNeeded() {
        if (storage.tsdMode) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

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
                    if (!fullFrameScan) {
                        val w = mediaImage.width
                        val h = mediaImage.height
                        val cropSize = (minOf(w, h) * 0.56f).toInt()
                        val left = (w - cropSize) / 2
                        val top = (h - cropSize) / 2
                        imageProxy.setCropRect(Rect(left, top, left + cropSize, top + cropSize))
                    }
                    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            for (b in barcodes) {
                                val raw = b.rawValue
                                if (!raw.isNullOrBlank()) {
                                    onRawBarcodeDetected(raw)
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
                boundCamera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                applyCameraZoom()
                boundCamera?.cameraControl?.enableTorch(torchEnabled)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyCameraZoom() {
        val cam = boundCamera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val target = when (zoomPresetIndex) {
            0 -> zoomState.minZoomRatio.coerceAtLeast(0.5f) // Ширик / Макро 0.6x
            1 -> 1.0f.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            2 -> 2.0f.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            else -> 3.0f.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        }
        cam.cameraControl.setZoomRatio(target)
    }

    // ==================== ДИАЛОГИ ====================

    private fun confirmResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить смену?")
            .setMessage("Будут удалены все отсканированные позиции (${items.size} шт.). Это действие нельзя отменить.")
            .setPositiveButton("Очистить") { _, _ ->
                items.clear()
                activeBannerKey = null
                storage.saveItems(items)
                refreshAllUi()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openManualAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val etName = EditText(this).apply {
            hint = "Наименование товара"
            isSingleLine = true
        }
        val etDate = EditText(this).apply {
            hint = "Срок годности (ДД.ММ.ГГГГ)"
            setText(SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date()))
            isSingleLine = true
        }
        val etQty = EditText(this).apply {
            hint = "Количество (шт.)"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        box.addView(etName)
        box.addView(etDate)
        box.addView(etQty)

        AlertDialog.Builder(this)
            .setTitle("Добавить вручную")
            .setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val name = etName.text.toString().trim().ifBlank { "Товар без штрихкода" }
                val iso = parseRuToIso(etDate.text.toString())
                val qty = (etQty.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99)
                val now = System.currentTimeMillis()
                for (idx in 1..qty) {
                    items.add(
                        0,
                        ScannedItem(
                            uniqueKey = "MANUAL_${now}_$idx",
                            gtin = "РУЧНОЙ_ВВОД",
                            sn = "№$idx",
                            raw = "",
                            name = name,
                            expDate = iso,
                            status = "MANUAL",
                            infoMsg = "Добавлено вручную",
                            isLoading = false,
                            isManual = true,
                            scannedAt = now + idx
                        )
                    )
                }
                activeBannerKey = items.firstOrNull()?.uniqueKey
                isDuplicateBanner = false
                storage.saveItems(items)
                refreshAllUi()
                Toast.makeText(this, "Добавлено: $qty шт.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
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

    // ==================== ДАТЫ И ОТЧЁТЫ ====================

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

    private fun formatShortDayMonth(millis: Long): String =
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(millis))

    private fun formatIsoFileDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

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
        return if (y.length == 4) "$y-$m-$d" else null
    }

    private fun buildCsv(list: List<ScannedItem>): String {
        val sb = StringBuilder()
        sb.appendLine("Наименование;GTIN;Серийный номер;Срок годности;Статус;Порог проверки")
        val thrStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(thresholdMillis()))
        for (it in list) {
            val cleanName = (it.name ?: "Не определено").replace(";", ",")
            val expStr = formatRuDate(it.expDate)
            val statusStr = when {
                it.status == "RETIRED" -> "ВЫБЫЛ"
                isItemExpired(it) -> "ПРОСРОЧКА"
                it.expDate != null -> "ГОДЕН"
                else -> it.infoMsg ?: "БЕЗ ДАТЫ"
            }
            sb.appendLine("$cleanName;\"${it.gtin}\";\"${it.sn}\";$expStr;$statusStr;$thrStr")
        }
        return sb.toString()
    }

    private fun buildMessengerSummary(): String {
        val nowStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        val thrStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(thresholdMillis()))
        val expired = items.filter { isItemExpired(it) }

        val sb = StringBuilder()
        sb.appendLine("📋 Сводка ревизии (AOR Systems)")
        sb.appendLine("Дата: $nowStr | Порог: до $thrStr")
        sb.appendLine("Всего проверено: ${items.size} шт. | На списание: ${expired.size} шт.")
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
}
