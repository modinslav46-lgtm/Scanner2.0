package com.revizor.scanner

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    data class ScanRecord(
        val uniqueKey: String,
        val raw: String,
        val gtin: String?,
        val serial: String?,
        val cis: String,
        var status: String, // "ok" | "bad" | "nodate" | "pending"
        var expMillis: Long?,
        var name: String?,
        var apiStatus: String?,
        var error: String?,
        val scannedAt: Long
    )

    private val items = ConcurrentHashMap<String, ScanRecord>()
    private val apiQueue = mutableListOf<ScanRecord>()
    private val queueLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // Главный экран камеры
    private lateinit var topBar: LinearLayout
    private lateinit var expandTopBtn: Button
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ReticleOverlayView
    private lateinit var thresholdBtn: Button
    private lateinit var dpmBtn: Button
    private lateinit var zoomBtn: Button
    private lateinit var torchBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var apiBtn: Button
    private lateinit var queueBadge: TextView

    private lateinit var statTotalVal: TextView
    private lateinit var statBadVal: TextView
    private lateinit var statPendingVal: TextView
    private lateinit var lastScanCard: LinearLayout
    private lateinit var lastScanTitle: TextView
    private lateinit var lastScanSub: TextView
    private lateinit var badLogContainer: LinearLayout

    // Отдельная полноэкранная страница списка
    private lateinit var listPage: LinearLayout
    private lateinit var listPageTitle: TextView
    private lateinit var listSearchInput: EditText
    private lateinit var fullListContainer: LinearLayout
    private val listTabButtons = mutableListOf<Button>()
    private var currentListFilter = "all"

    // Отдельная полноэкранная страница настроек API
    private lateinit var apiPage: LinearLayout
    private lateinit var workerUrlInput: EditText
    private lateinit var apiModeSpinner: Spinner
    private lateinit var apiTokenInput: EditText
    private val apiModes = arrayOf("mobile_api", "true_api", "mock")

    private val pillButtons = mutableListOf<Button>()

    private var camera: Camera? = null
    private lateinit var scanner: BarcodeScanner
    private var toneGen: ToneGenerator? = null

    private var thresholdMillis: Long = 0L
    private var dpmMode = true
    private var zoomLevel = 1.0f
    private var torchOn = false
    private var muted = false
    private var frameIndex = 0
    private var simIndex = 0
    private var inFlightCount = 0
    private var flushScheduled = false

    private var workerUrl = ""
    private var apiMode = "mobile_api"
    private var apiToken = ""

    private val dateRuFormat = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))
    private val dateShortFormat = SimpleDateFormat("dd.MM", Locale("ru"))
    private val dateIsoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Нужен доступ к камере для сканирования", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        } catch (_: Exception) {}

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .build()
        scanner = BarcodeScanning.getClient(options)

        loadConfig()
        buildUi()
        syncApiButtonStyle()
        setThresholdOffset(0)
        loadSession()
        updateStatsUi()

        // Системная кнопка «Назад» закрывает открытый список или настройки вместо выхода из приложения
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    listPage.visibility == View.VISIBLE -> listPage.visibility = View.GONE
                    apiPage.visibility == View.VISIBLE -> apiPage.visibility = View.GONE
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        scanner.close()
        toneGen?.release()
    }

    // --- Построение всего интерфейса ---
    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
        }

        // 1. Основной экран сканера
        val mainScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val lbl = TextView(this).apply {
            text = "До: "
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
        }

        thresholdBtn = makeChipButton("", "#0F1115", "#F3F4F6").apply {
            setOnClickListener { openDatePicker() }
        }

        dpmBtn = makeChipButton("🔬 Крышки", "#06B6D4", "#000000").apply {
            setOnClickListener {
                dpmMode = !dpmMode
                styleChip(this, if (dpmMode) "#06B6D4" else "#232834", if (dpmMode) "#000000" else "#F3F4F6")
                overlayView.showReticle = dpmMode
                overlayView.invalidate()
            }
        }

        zoomBtn = makeChipButton("🔍 1x", "#232834", "#F3F4F6").apply {
            setOnClickListener {
                zoomLevel = when (zoomLevel) {
                    1.0f -> 2.0f
                    2.0f -> 3.0f
                    else -> 1.0f
                }
                text = "🔍 ${zoomLevel.toInt()}x"
                styleChip(this, if (zoomLevel > 1f) "#EAB308" else "#232834", if (zoomLevel > 1f) "#000000" else "#F3F4F6")
                camera?.cameraControl?.setZoomRatio(zoomLevel)
            }
        }

        torchBtn = makeChipButton("🔦", "#232834", "#F3F4F6").apply {
            setOnClickListener {
                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                if (!hasFlash) {
                    Toast.makeText(this@MainActivity, "Вспышка недоступна", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                styleChip(this, if (torchOn) "#EAB308" else "#232834", if (torchOn) "#000000" else "#F3F4F6")
            }
        }

        muteBtn = makeChipButton("🔊", "#232834", "#F3F4F6").apply {
            setOnClickListener {
                muted = !muted
                text = if (muted) "🔇" else "🔊"
            }
        }

        val collapseTopBtn = makeChipButton("▲", "#232834", "#F3F4F6").apply {
            setOnClickListener {
                topBar.visibility = View.GONE
                expandTopBtn.visibility = View.VISIBLE
            }
        }

        row1.addView(lbl)
        row1.addView(thresholdBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        row1.addView(dpmBtn, marginEnd(4))
        row1.addView(zoomBtn, marginEnd(4))
        row1.addView(torchBtn, marginEnd(4))
        row1.addView(muteBtn, marginEnd(4))
        row1.addView(collapseTopBtn)

        // Вторая строка верхнего меню
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val presets = listOf("Сегодня" to 0, "+1 дн" to 1, "+7 дн" to 7, "+14 дн" to 14)
        for ((title, days) in presets) {
            val btn = makeChipButton(title, if (days == 0) "#3B82F6" else "#232834", "#FFFFFF").apply {
                tag = days
                setOnClickListener {
                    setThresholdOffset(days)
                    highlightPreset(days)
                }
            }
            pillButtons.add(btn)
            row2.addView(btn, marginEnd(4))
        }

        row2.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val simBtn = makeChipButton("Тест", "#232834", "#F3F4F6").apply {
            setOnClickListener { runTestSimulation() }
        }
        val listBtn = makeChipButton("📋 Список", "#232834", "#F3F4F6").apply {
            setOnClickListener { openListPage("all") }
        }
        apiBtn = makeChipButton("⚙️ API", "#232834", "#F3F4F6").apply {
            setOnClickListener { openApiPage() }
        }
        val resetBtn = makeChipButton("Сброс", "#232834", "#F3F4F6").apply {
            setOnClickListener { confirmReset() }
        }

        row2.addView(simBtn, marginEnd(4))
        row2.addView(listBtn, marginEnd(4))
        row2.addView(apiBtn, marginEnd(4))
        row2.addView(resetBtn)

        topBar.addView(row1)
        topBar.addView(row2)
        mainScreen.addView(topBar)

        // Видоискатель камеры
        val viewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlayView = ReticleOverlayView(this)

        expandTopBtn = makeChipButton("▼ Меню", "#E6181B22", "#F3F4F6").apply {
            visibility = View.GONE
            setOnClickListener {
                topBar.visibility = View.VISIBLE
                visibility = View.GONE
            }
        }

        queueBadge = TextView(this).apply {
            text = "⏳ API: 0"
            setTextColor(Color.parseColor("#06B6D4"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundBg("#D90F1115", "#06B6D4", 20)
            visibility = View.GONE
        }

        viewport.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        viewport.addView(overlayView, FrameLayout.LayoutParams(-1, -1))
        viewport.addView(expandTopBtn, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        viewport.addView(queueBadge, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })

        mainScreen.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))

        // Нижняя панель статистики и просрочки
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val box1 = makeStatBox("Уникальных ↗", "#F3F4F6") { openListPage("all") }
        statTotalVal = box1.second
        val box2 = makeStatBox("Просрочка ↗", "#EF4444") { openListPage("bad") }
        statBadVal = box2.second
        val box3 = makeStatBox("Без даты / API ↗", "#06B6D4") { openListPage("nodate") }
        statPendingVal = box3.second

        statsRow.addView(box1.first, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        statsRow.addView(box2.first, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        statsRow.addView(box3.first, LinearLayout.LayoutParams(0, -2, 1f))
        bottomPanel.addView(statsRow)

        lastScanCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundBg("#11141A", "#2A2F3D", 8)
        }
        lastScanTitle = TextView(this).apply {
            text = "Ожидание сканирования..."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        lastScanSub = TextView(this).apply {
            text = "Наведите камеру на крышку банки или этикетку"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11f
        }
        lastScanCard.addView(lastScanTitle)
        lastScanCard.addView(lastScanSub)
        bottomPanel.addView(lastScanCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val logScroll = ScrollView(this)
        badLogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        logScroll.addView(badLogContainer)
        bottomPanel.addView(logScroll, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(6) })

        mainScreen.addView(bottomPanel)
        rootFrame.addView(mainScreen, FrameLayout.LayoutParams(-1, -1))

        // 2. Отдельная полноэкранная страница списка
        listPage = buildFullListPage()
        rootFrame.addView(listPage, FrameLayout.LayoutParams(-1, -1))

        // 3. Отдельная полноэкранная страница настроек API
        apiPage = buildFullApiPage()
        rootFrame.addView(apiPage, FrameLayout.LayoutParams(-1, -1))

        setContentView(rootFrame)
    }

    // --- Отдельная страница списка отсканированного ---
    private fun buildFullListPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        // Шапка страницы списка
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val backBtn = makeChipButton("← К камере", "#232834", "#F3F4F6").apply {
            setOnClickListener { page.visibility = View.GONE }
        }
        listPageTitle = TextView(this).apply {
            text = "Отсканировано: 0"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val clearBtn = makeChipButton("Очистить", "#232834", "#EF4444").apply {
            setOnClickListener { confirmReset() }
        }

        header.addView(backBtn)
        header.addView(listPageTitle, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(clearBtn)
        page.addView(header)

        // Вкладки фильтра и строка поиска
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tabs = listOf("Все" to "all", "Просрочка" to "bad", "Годные" to "ok", "Без даты" to "nodate")
        for ((label, key) in tabs) {
            val b = makeChipButton(label, if (key == "all") "#3B82F6" else "#232834", "#FFFFFF").apply {
                tag = key
                setOnClickListener {
                    currentListFilter = key
                    syncListTabStyles()
                    renderFullList()
                }
            }
            listTabButtons.add(b)
            tabsRow.addView(b, marginEnd(6))
        }

        listSearchInput = EditText(this).apply {
            hint = "Поиск по названию, GTIN или серийнику..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBg("#181B22", "#2A2F3D", 10)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = renderFullList()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        controls.addView(tabsRow)
        controls.addView(listSearchInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        page.addView(controls)

        // Прокручиваемый список карточек
        val scroll = ScrollView(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        fullListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(fullListContainer)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Нижняя панель экспорта CSV
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val exportBadBtn = makeChipButton("📥 CSV Просрочка", "#EF4444", "#FFFFFF").apply {
            textSize = 13f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { shareCsv(onlyBad = true) }
        }
        val exportAllBtn = makeChipButton("📥 CSV Всё", "#232834", "#F3F4F6").apply {
            textSize = 13f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { shareCsv(onlyBad = false) }
        }

        footer.addView(exportBadBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        footer.addView(exportAllBtn)
        page.addView(footer)

        return page
    }

    private fun openListPage(filter: String) {
        currentListFilter = filter
        syncListTabStyles()
        renderFullList()
        listPage.visibility = View.VISIBLE
    }

    private fun syncListTabStyles() {
        for (tb in listTabButtons) {
            val active = (tb.tag as String) == currentListFilter
            styleChip(tb, if (active) "#3B82F6" else "#232834", "#FFFFFF")
        }
    }

    private fun renderFullList() {
        fullListContainer.removeAllViews()
        listPageTitle.text = "Отсканировано: ${items.size}"
        val q = listSearchInput.text.toString().trim().lowercase()

        val filtered = items.values
            .sortedByDescending { it.scannedAt }
            .filter { rec ->
                val passTab = when (currentListFilter) {
                    "bad" -> rec.status == "bad"
                    "ok" -> rec.status == "ok"
                    "nodate" -> rec.status == "nodate" || rec.status == "pending"
                    else -> true
                }
                val hay = "${rec.name ?: ""} ${rec.gtin ?: ""} ${rec.serial ?: ""}".lowercase()
                passTab && (q.isEmpty() || hay.contains(q))
            }

        if (filtered.isEmpty()) {
            fullListContainer.addView(TextView(this).apply {
                text = "Список пуст"
                setTextColor(Color.parseColor("#9CA3AF"))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(40), dp(12), dp(40))
            })
            return
        }

        for (rec in filtered) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val border = when (rec.status) {
                    "bad" -> "#EF4444"
                    "ok" -> "#22C55E"
                    "pending" -> "#06B6D4"
                    else -> "#F59E0B"
                }
                val fill = if (rec.status == "bad") "#221317" else "#181B22"
                background = roundBg(fill, border, 10)
            }

            val infoCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val titleTv = TextView(this).apply {
                text = rec.name ?: (rec.gtin?.let { "GTIN $it" } ?: rec.raw.take(22))
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }

            val idDetails = buildString {
                if (rec.name != null && rec.gtin != null) append("GTIN: ${rec.gtin} • ")
                append("SN: ${rec.serial ?: "—"}")
            }
            val idTv = TextView(this).apply {
                text = idDetails
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 11f
                setPadding(0, dp(2), 0, dp(3))
            }

            val (statusStr, statusColor) = when (rec.status) {
                "bad" -> {
                    val txt = rec.expMillis?.let { "Списать: до ${dateRuFormat.format(Date(it))}" } ?: "Выбыл / Риск"
                    txt to "#EF4444"
                }
                "ok" -> {
                    val txt = "Годен: до ${rec.expMillis?.let { dateRuFormat.format(Date(it)) } ?: "—"}"
                    txt to "#22C55E"
                }
                "pending" -> "⏳ Запрос срока в ЧЗ..." to "#06B6D4"
                else -> (rec.error ?: "Без даты (17)") to "#F59E0B"
            }

            val statusTv = TextView(this).apply {
                text = statusStr
                setTextColor(Color.parseColor(statusColor))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }

            infoCol.addView(titleTv)
            infoCol.addView(idTv)
            infoCol.addView(statusTv)

            val delBtn = makeChipButton("✕", "#232834", "#9CA3AF").apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    items.remove(rec.uniqueKey)
                    saveSession()
                    updateStatsUi()
                    rebuildMiniBadLog()
                    renderFullList()
                }
            }

            card.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            card.addView(delBtn)
            fullListContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    // --- Отдельная страница настроек Cloudflare Worker ---
    private fun buildFullApiPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val backBtn = makeChipButton("← Назад", "#232834", "#F3F4F6").apply {
            setOnClickListener { page.visibility = View.GONE }
        }
        val titleTv = TextView(this).apply {
            text = "Настройка Cloudflare Worker"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        header.addView(backBtn)
        header.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(64), 1))
        page.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val hintTv = TextView(this).apply {
            text = "Для реальных товаров выберите «Mobile / Шлюз проверки» или «True API ГИС МТ». В режиме Mock воркер возвращает тестовые заглушки."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        }

        workerUrlInput = EditText(this).apply {
            hint = "https://chz-proxy.ваш-ник.workers.dev"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBg("#181B22", "#2A2F3D", 10)
        }

        val modeLabels = arrayOf(
            "Mobile / Шлюз проверки (реальный)",
            "True API ГИС МТ (официальный, нужен токен)",
            "Mock (тестовые заглушки без сети)"
        )
        apiModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modeLabels)
        }

        apiTokenInput = EditText(this).apply {
            hint = "Bearer-токен ЧЗ (при необходимости)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBg("#181B22", "#2A2F3D", 10)
        }

        body.addView(hintTv)
        body.addView(makeFieldLabel("URL Cloudflare Worker"))
        body.addView(workerUrlInput)
        body.addView(makeFieldLabel("Режим работы воркера", topMarginDp = 12))
        body.addView(apiModeSpinner)
        body.addView(makeFieldLabel("Токен (если не сохранён в Cloudflare Secrets)", topMarginDp = 12))
        body.addView(apiTokenInput)
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val saveBtn = makeChipButton("Сохранить настройки", "#3B82F6", "#FFFFFF").apply {
            textSize = 14f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener {
                var u = workerUrlInput.text.toString().trim().trimEnd('/')
                if (u.isNotEmpty() && !u.startsWith("http")) u = "https://$u"
                workerUrl = u
                apiMode = apiModes[apiModeSpinner.selectedItemPosition]
                apiToken = apiTokenInput.text.toString().trim()
                getSharedPreferences("revizor_cfg", MODE_PRIVATE).edit()
                    .putString("workerUrl", workerUrl)
                    .putString("apiMode", apiMode)
                    .putString("apiToken", apiToken)
                    .apply()
                syncApiButtonStyle()
                page.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
        }
        footer.addView(saveBtn, LinearLayout.LayoutParams(-1, -2))
        page.addView(footer)

        return page
    }

    private fun openApiPage() {
        workerUrlInput.setText(workerUrl)
        apiModeSpinner.setSelection(apiModes.indexOf(apiMode).coerceAtLeast(0))
        apiTokenInput.setText(apiToken)
        apiPage.visibility = View.VISIBLE
    }

    private fun syncApiButtonStyle() {
        when {
            workerUrl.isEmpty() -> {
                apiBtn.text = "⚙️ API"
                apiBtn.setTextColor(Color.parseColor("#F3F4F6"))
                apiBtn.background = roundBg("#232834", "#2A2F3D", 8)
            }
            apiMode == "mock" -> {
                apiBtn.text = "⚙️ Mock"
                apiBtn.setTextColor(Color.parseColor("#F59E0B"))
                apiBtn.background = roundBg("#232834", "#F59E0B", 8)
            }
            else -> {
                apiBtn.text = "⚙️ API"
                apiBtn.setTextColor(Color.parseColor("#06B6D4"))
                apiBtn.background = roundBg("#232834", "#06B6D4", 8)
            }
        }
    }

    // --- Кнопка Тест ---
    private fun runTestSimulation() {
        val stamp = System.currentTimeMillis()
        val samples = listOf(
            // 1. Код без тега 17 (уйдёт в Cloudflare Worker)
            "010460068253201921SN_TEST_${stamp}_$simIndex\u001D93ABCD",
            // 2. Просроченный код с тегом 17
            "010460700123456721SN_EXP_${stamp}_$simIndex\u001D17250115\u001D93WXYZ",
            // 3. Свежий код с тегом 17
            "010460700987654321SN_OK_${stamp}_$simIndex\u001D17281231\u001D93QWER"
        )
        val code = samples[simIndex % samples.size]
        simIndex++
        handleScannedCode(code)
    }

    // --- Запуск CameraX + анализатора кадров ---
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                if (zoomLevel > 1f) camera?.cameraControl?.setZoomRatio(zoomLevel)
                if (torchOn) camera?.cameraControl?.enableTorch(true)
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка камеры: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // --- Обработка кадра: обычный проход + инверсия/склейка точек для чёрных ключей ---
    private fun processFrame(imageProxy: ImageProxy) {
        val rotation = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        frameIndex++
        val useInvertedDpm = dpmMode && (frameIndex % 2 == 1)

        val inputImage: InputImage = if (!useInvertedDpm) {
            val nv21 = ByteArray(width * height * 3 / 2)
            var outIdx = 0
            for (r in 0 until height) {
                val rowStart = r * rowStride
                for (c in 0 until width) {
                    nv21[outIdx++] = yBuffer.get(rowStart + c * pixelStride)
                }
            }
            java.util.Arrays.fill(nv21, width * height, nv21.size, 0x80.toByte())
            InputImage.fromByteArray(nv21, width, height, rotation, InputImage.IMAGE_FORMAT_NV21)
        } else {
            val cropSize = (minOf(width, height) * 0.50).toInt() and 0xFFFE
            val startX = ((width - cropSize) / 2) and 0xFFFE
            val startY = ((height - cropSize) / 2) and 0xFFFE

            val rawY = IntArray(cropSize * cropSize)
            var idx = 0
            for (r in 0 until cropSize) {
                val rowStart = (startY + r) * rowStride
                for (c in 0 until cropSize) {
                    rawY[idx++] = yBuffer.get(rowStart + (startX + c) * pixelStride).toInt() and 0xFF
                }
            }

            val nv21 = ByteArray(cropSize * cropSize * 3 / 2)
            for (r in 0 until cropSize) {
                for (c in 0 until cropSize) {
                    var sum = 0
                    var count = 0
                    for (dr in -1..1) {
                        val nr = r + dr
                        if (nr < 0 || nr >= cropSize) continue
                        val rowOff = nr * cropSize
                        for (dc in -1..1) {
                            val nc = c + dc
                            if (nc < 0 || nc >= cropSize) continue
                            sum += rawY[rowOff + nc]
                            count++
                        }
                    }
                    val blurred = sum / count
                    val inverted = 255 - blurred
                    val contrasted = (((inverted - 128) * 2.4f) + 128f).toInt().coerceIn(0, 255)
                    nv21[r * cropSize + c] = contrasted.toByte()
                }
            }
            java.util.Arrays.fill(nv21, cropSize * cropSize, nv21.size, 0x80.toByte())
            InputImage.fromByteArray(nv21, cropSize, cropSize, rotation, InputImage.IMAGE_FORMAT_NV21)
        }

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (b in barcodes) {
                    val raw = b.rawValue ?: continue
                    if (raw.isNotEmpty()) handleScannedCode(raw)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    // --- Парсер GS1 DataMatrix ---
    private fun parseGs1(raw: String): ScanRecord {
        var str = raw.replace(Regex("^\\][a-zA-Z0-9]{2}"), "").trimStart('\u001D')
        val gs = '\u001D'
        var gtin: String? = null
        var serial: String? = null
        var expMillis: Long? = null
        var i = 0

        while (i < str.length) {
            if (str[i] == gs) {
                i++
                continue
            }
            val ai2 = if (i + 2 <= str.length) str.substring(i, i + 2) else ""
            if (ai2 == "01" && i + 16 <= str.length) {
                gtin = str.substring(i + 2, i + 16)
                i += 16
            } else if ((ai2 == "17" || ai2 == "11" || ai2 == "15") && i + 8 <= str.length) {
                if (ai2 == "17") expMillis = parseYyMmDd(str.substring(i + 2, i + 8))
                i += 8
            } else if (ai2 in listOf("21", "10", "91", "92", "93")) {
                val nextGs = str.indexOf(gs, i + 2)
                val end = if (nextGs == -1) str.length else nextGs
                if (ai2 == "21") serial = str.substring(i + 2, end)
                i = if (nextGs == -1) end else end + 1
            } else {
                val nextGs = str.indexOf(gs, i)
                if (nextGs == -1) break
                i = nextGs + 1
            }
        }

        if (expMillis == null) {
            val m = Regex("\u001D17(\\d{6})").find(str)
            if (m != null) expMillis = parseYyMmDd(m.groupValues[1])
        }

        val uniqueKey = if (gtin != null && serial != null) "${gtin}_$serial" else raw
        val cis = if (gtin != null && serial != null) "01${gtin}21$serial"
        else str.replace(Regex("\u001D9[123].*$"), "")

        val status = when {
            expMillis != null -> if (expMillis <= thresholdMillis) "bad" else "ok"
            workerUrl.isNotEmpty() -> "pending"
            else -> "nodate"
        }

        return ScanRecord(
            uniqueKey = uniqueKey,
            raw = raw,
            gtin = gtin,
            serial = serial,
            cis = cis,
            status = status,
            expMillis = expMillis,
            name = null,
            apiStatus = null,
            error = null,
            scannedAt = System.currentTimeMillis()
        )
    }

    private fun parseYyMmDd(s: String): Long? {
        if (!s.matches(Regex("\\d{6}"))) return null
        val yy = s.substring(0, 2).toInt()
        val mm = s.substring(2, 4).toInt()
        val dd = s.substring(4, 6).toInt()
        if (mm < 1 || mm > 12 || dd > 31) return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2000 + yy)
            set(Calendar.MONTH, mm - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            if (dd == 0) {
                set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            } else {
                set(Calendar.DAY_OF_MONTH, dd)
            }
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun handleScannedCode(raw: String) {
        val candidate = parseGs1(raw)
        val existing = items.putIfAbsent(candidate.uniqueKey, candidate)
        if (existing != null) return

        mainHandler.post {
            saveSession()
            updateStatsUi()
            updateLastScanCard(candidate)
            if (listPage.visibility == View.VISIBLE) renderFullList()
            when (candidate.status) {
                "bad" -> {
                    playAlarm("bad")
                    overlayView.flashBorder(Color.parseColor("#EF4444"))
                    rebuildMiniBadLog()
                }
                "ok" -> {
                    playAlarm("ok")
                    overlayView.flashBorder(Color.parseColor("#22C55E"))
                }
                "pending" -> {
                    playAlarm("queued")
                    enqueueForWorker(candidate)
                }
                else -> {
                    playAlarm("nodate")
                    overlayView.flashBorder(Color.parseColor("#F59E0B"))
                }
            }
        }
    }

    // --- Фоновая очередь в Cloudflare Worker ---
    private fun enqueueForWorker(record: ScanRecord) {
        if (workerUrl.isEmpty()) return
        synchronized(queueLock) {
            apiQueue.add(record)
            updateQueueBadgeUi()
            if (!flushScheduled) {
                flushScheduled = true
                mainHandler.postDelayed({ flushApiQueue() }, 150)
            }
        }
    }

    private fun flushApiQueue() {
        val batch: List<ScanRecord>
        synchronized(queueLock) {
            flushScheduled = false
            if (apiQueue.isEmpty()) return
            val takeCount = minOf(25, apiQueue.size)
            batch = apiQueue.take(takeCount).toList()
            repeat(takeCount) { apiQueue.removeAt(0) }
            inFlightCount += batch.size
            updateQueueBadgeUi()
        }

        networkExecutor.execute {
            try {
                val payload = JSONObject()
                val arr = JSONArray()
                for (rec in batch) {
                    arr.put(JSONObject().apply {
                        put("uniqueKey", rec.uniqueKey)
                        put("cis", rec.cis)
                        put("raw", rec.raw)
                    })
                }
                payload.put("items", arr)

                val conn = (URL(workerUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Api-Mode", apiMode)
                    if (apiToken.isNotEmpty()) setRequestProperty("X-Api-Token", apiToken)
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(payload.toString())
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val respText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(respText)

                if (code !in 200..299) {
                    throw Exception(json.optString("error", "HTTP $code"))
                }

                val results = json.optJSONArray("results") ?: JSONArray()
                var anyBad = false
                var lastUpdated: ScanRecord? = null

                for (idx in 0 until results.length()) {
                    val obj = results.getJSONObject(idx)
                    val key = obj.optString("uniqueKey")
                    val rec = items[key] ?: continue

                    if (!obj.isNull("name")) rec.name = obj.optString("name")
                    if (!obj.isNull("status")) rec.apiStatus = obj.optString("status")
                    if (!obj.isNull("error")) rec.error = obj.optString("error")

                    val expStr = if (obj.isNull("expDate")) "" else obj.optString("expDate")
                    if (expStr.length >= 10) {
                        val d = dateIsoFormat.parse(expStr.substring(0, 10))
                        if (d != null) rec.expMillis = d.time
                    }

                    val isRetired = rec.apiStatus == "RETIRED"
                    val isBadDate = rec.expMillis != null && rec.expMillis!! <= thresholdMillis
                    rec.status = when {
                        isBadDate || isRetired -> "bad"
                        rec.expMillis != null -> "ok"
                        else -> "nodate"
                    }
                    if (rec.status == "bad") anyBad = true
                    lastUpdated = rec
                }

                for (rec in batch) {
                    if (rec.status == "pending") {
                        rec.status = "nodate"
                        rec.error = "Нет данных в ответе"
                    }
                }

                mainHandler.post {
                    if (anyBad) {
                        playAlarm("bad")
                        overlayView.flashBorder(Color.parseColor("#EF4444"))
                    } else if (lastUpdated?.status == "ok") {
                        playAlarm("ok")
                        overlayView.flashBorder(Color.parseColor("#22C55E"))
                    }
                    lastUpdated?.let { updateLastScanCard(it) }
                }
            } catch (e: Exception) {
                for (rec in batch) {
                    if (rec.status == "pending") {
                        rec.status = "nodate"
                        rec.error = e.message ?: "Ошибка сети"
                    }
                }
                mainHandler.post {
                    batch.lastOrNull()?.let { updateLastScanCard(it) }
                }
            } finally {
                synchronized(queueLock) {
                    inFlightCount = maxOf(0, inFlightCount - batch.size)
                }
                mainHandler.post {
                    updateQueueBadgeUi()
                    saveSession()
                    updateStatsUi()
                    rebuildMiniBadLog()
                    if (listPage.visibility == View.VISIBLE) renderFullList()
                }
                synchronized(queueLock) {
                    if (apiQueue.isNotEmpty() && !flushScheduled) {
                        flushScheduled = true
                        mainHandler.postDelayed({ flushApiQueue() }, 150)
                    }
                }
            }
        }
    }

    // --- Звук, вибрация, UI обновления ---
    private fun playAlarm(type: String) {
        val vib = @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (type == "bad") {
                    vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 90, 220), -1))
                } else if (type == "ok") {
                    vib.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } else {
                @Suppress("DEPRECATION")
                if (type == "bad") vib.vibrate(longArrayOf(0, 220, 90, 220), -1)
                else if (type == "ok") vib.vibrate(35)
            }
        }

        if (muted) return
        try {
            when (type) {
                "ok" -> toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                "bad" -> toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 320)
                else -> toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 40)
            }
        } catch (_: Exception) {}
    }

    private fun updateQueueBadgeUi() {
        val count = synchronized(queueLock) { apiQueue.size + inFlightCount }
        if (count > 0) {
            queueBadge.visibility = View.VISIBLE
            queueBadge.text = "⏳ API: $count"
        } else {
            queueBadge.visibility = View.GONE
        }
    }

    private fun updateStatsUi() {
        var total = 0
        var bad = 0
        var nodate = 0
        for (rec in items.values) {
            total++
            if (rec.status == "bad") bad++
            else if (rec.status == "nodate" || rec.status == "pending") nodate++
        }
        statTotalVal.text = total.toString()
        statBadVal.text = bad.toString()
        statPendingVal.text = nodate.toString()
        listPageTitle.text = "Отсканировано: $total"
    }

    private fun updateLastScanCard(rec: ScanRecord) {
        val idLine = buildString {
            if (rec.name != null) append("${rec.name} • ")
            if (rec.gtin != null) append("GTIN ${rec.gtin}") else append(rec.raw.take(18))
            if (rec.serial != null) append(" • SN: ${rec.serial}")
        }
        lastScanSub.text = idLine

        when (rec.status) {
            "bad" -> {
                val dateTxt = rec.expMillis?.let { "ДО ${dateRuFormat.format(Date(it))}" } ?: "УЖЕ ПРОДАН (ВЫБЫЛ)"
                lastScanTitle.text = "ПРОСРОЧКА / СПИСАТЬ: $dateTxt"
                lastScanTitle.setTextColor(Color.parseColor("#EF4444"))
                lastScanCard.background = roundBg("#2A1215", "#EF4444", 8)
            }
            "ok" -> {
                val dateTxt = rec.expMillis?.let { dateRuFormat.format(Date(it)) } ?: "—"
                lastScanTitle.text = "ГОДЕН: до $dateTxt"
                lastScanTitle.setTextColor(Color.parseColor("#22C55E"))
                lastScanCard.background = roundBg("#11141A", "#22C55E", 8)
            }
            "pending" -> {
                lastScanTitle.text = "⏳ Запрос срока в ЧЗ..."
                lastScanTitle.setTextColor(Color.parseColor("#06B6D4"))
                lastScanCard.background = roundBg("#11141A", "#06B6D4", 8)
            }
            else -> {
                lastScanTitle.text = rec.error ?: "Нет даты (настройте Worker в ⚙️ API)"
                lastScanTitle.setTextColor(Color.parseColor("#F59E0B"))
                lastScanCard.background = roundBg("#11141A", "#F59E0B", 8)
            }
        }
    }

    private fun rebuildMiniBadLog() {
        badLogContainer.removeAllViews()
        val badList = items.values.filter { it.status == "bad" }.sortedByDescending { it.scannedAt }
        for (rec in badList) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = roundBg("#2A1215", "#7F1D1D", 6)
            }
            val nameTv = TextView(this).apply {
                text = rec.name ?: rec.gtin ?: rec.raw.take(18)
                setTextColor(Color.parseColor("#F3F4F6"))
                textSize = 11f
                maxLines = 1
            }
            val dateTv = TextView(this).apply {
                text = rec.expMillis?.let { "до ${dateRuFormat.format(Date(it))}" } ?: (rec.apiStatus ?: "Риск")
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
            }
            row.addView(nameTv, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(dateTv)
            badLogContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
    }

    // --- Даты и пресеты ---
    private fun syncThresholdLabels() {
        val d = Date(thresholdMillis)
        thresholdBtn.text = dateRuFormat.format(d)
        expandTopBtn.text = "▼ Меню • до ${dateShortFormat.format(d)}"
    }

    private fun setThresholdOffset(days: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, days)
        }
        thresholdMillis = cal.timeInMillis
        syncThresholdLabels()
        reevaluateAll()
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = thresholdMillis }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val picked = Calendar.getInstance().apply {
                    set(y, m, d, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                thresholdMillis = picked.timeInMillis
                syncThresholdLabels()
                highlightPreset(-1)
                reevaluateAll()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun highlightPreset(activeDays: Int) {
        for (b in pillButtons) {
            val active = (b.tag as Int) == activeDays
            styleChip(b, if (active) "#3B82F6" else "#232834", "#FFFFFF")
        }
    }

    private fun reevaluateAll() {
        for (rec in items.values) {
            if (rec.expMillis != null) {
                val isBad = rec.expMillis!! <= thresholdMillis || rec.apiStatus == "RETIRED"
                rec.status = if (isBad) "bad" else "ok"
            }
        }
        saveSession()
        updateStatsUi()
        rebuildMiniBadLog()
        if (listPage.visibility == View.VISIBLE) renderFullList()
    }

    // --- Экспорт CSV и сброс ---
    private fun shareCsv(onlyBad: Boolean) {
        val list = items.values.filter { !onlyBad || it.status == "bad" }
        if (list.isEmpty()) {
            Toast.makeText(this, "Список пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("\uFEFFСтатус;Наименование;GTIN;Серийный номер;Срок годности;Статус ЧЗ\n")
        for (r in list) {
            val st = when (r.status) {
                "bad" -> "СПИСАТЬ"
                "ok" -> "ГОДЕН"
                else -> "БЕЗ ДАТЫ"
            }
            val dt = r.expMillis?.let { dateRuFormat.format(Date(it)) } ?: ""
            sb.append("\"$st\";\"${r.name ?: ""}\";\"${r.gtin ?: ""}\";\"${r.serial ?: ""}\";\"$dt\";\"${r.apiStatus ?: ""}\"\n")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Ревизия сроков ${dateIsoFormat.format(Date())}")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "Отправить отчёт ревизии"))
    }

    private fun confirmReset() {
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Очистить список смены?")
            .setMessage("Будут удалены все ${items.size} отсканированных позиций.")
            .setPositiveButton("Очистить") { _, _ ->
                items.clear()
                synchronized(queueLock) { apiQueue.clear() }
                getSharedPreferences("revizor_session", MODE_PRIVATE).edit().clear().apply()
                updateQueueBadgeUi()
                updateStatsUi()
                rebuildMiniBadLog()
                if (listPage.visibility == View.VISIBLE) renderFullList()
                lastScanTitle.text = "Счётчик сброшен"
                lastScanTitle.setTextColor(Color.parseColor("#9CA3AF"))
                lastScanCard.background = roundBg("#11141A", "#2A2F3D", 8)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // --- Сохранение конфигурации и смены ---
    private fun loadConfig() {
        val p = getSharedPreferences("revizor_cfg", MODE_PRIVATE)
        workerUrl = p.getString("workerUrl", "") ?: ""
        apiMode = p.getString("apiMode", "mobile_api") ?: "mobile_api"
        apiToken = p.getString("apiToken", "") ?: ""
    }

    private fun saveSession() {
        val arr = JSONArray()
        for (r in items.values) {
            arr.put(JSONObject().apply {
                put("uniqueKey", r.uniqueKey)
                put("raw", r.raw)
                put("gtin", r.gtin ?: JSONObject.NULL)
                put("serial", r.serial ?: JSONObject.NULL)
                put("cis", r.cis)
                put("status", r.status)
                put("expMillis", r.expMillis ?: JSONObject.NULL)
                put("name", r.name ?: JSONObject.NULL)
                put("apiStatus", r.apiStatus ?: JSONObject.NULL)
                put("error", r.error ?: JSONObject.NULL)
                put("scannedAt", r.scannedAt)
            })
        }
        getSharedPreferences("revizor_session", MODE_PRIVATE).edit()
            .putString("items", arr.toString())
            .apply()
    }

    private fun loadSession() {
        val raw = getSharedPreferences("revizor_session", MODE_PRIVATE).getString("items", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rec = ScanRecord(
                    uniqueKey = o.getString("uniqueKey"),
                    raw = o.getString("raw"),
                    gtin = if (o.isNull("gtin")) null else o.getString("gtin"),
                    serial = if (o.isNull("serial")) null else o.getString("serial"),
                    cis = o.getString("cis"),
                    status = o.getString("status"),
                    expMillis = if (o.isNull("expMillis")) null else o.getLong("expMillis"),
                    name = if (o.isNull("name")) null else o.getString("name"),
                    apiStatus = if (o.isNull("apiStatus")) null else o.getString("apiStatus"),
                    error = if (o.isNull("error")) null else o.getString("error"),
                    scannedAt = o.optLong("scannedAt", System.currentTimeMillis())
                )
                items[rec.uniqueKey] = rec
                if (rec.status == "pending" && workerUrl.isNotEmpty()) enqueueForWorker(rec)
            }
            rebuildMiniBadLog()
        } catch (_: Exception) {}
    }

    // --- Вспомогательные UI-методы ---
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun marginEnd(dpVal: Int) = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(dpVal) }

    private fun makeFieldLabel(text: String, topMarginDp: Int = 0): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(topMarginDp), 0, dp(4))
        }
    }

    private fun roundBg(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun makeChipButton(label: String, bgHex: String, textHex: String): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(6), dp(8), dp(6))
            styleChip(this, bgHex, textHex)
        }
    }

    private fun styleChip(btn: Button, bgHex: String, textHex: String) {
        btn.setTextColor(Color.parseColor(textHex))
        btn.background = roundBg(bgHex, "#2A2F3D", 8)
    }

    private fun makeStatBox(label: String, colorHex: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = roundBg("#11141A", "#2A2F3D", 8)
            setOnClickListener { onClick() }
        }
        val valTv = TextView(this).apply {
            text = "0"
            setTextColor(Color.parseColor(colorHex))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val lblTv = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        box.addView(valTv)
        box.addView(lblTv)
        return box to valTv
    }

    // --- Кастомный слой прицела для крышек и вспышки рамки ---
    inner class ReticleOverlayView(ctx: Context) : View(ctx) {
        var showReticle = true
        private var flashColor: Int? = null

        private val reticlePaint = Paint().apply {
            color = Color.parseColor("#B306B6D4")
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(dp(10).toFloat(), dp(7).toFloat()), 0f)
        }

        private val flashPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(8).toFloat()
        }

        fun flashBorder(color: Int) {
            flashColor = color
            invalidate()
            mainHandler.postDelayed({
                flashColor = null
                invalidate()
            }, 350)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (showReticle && width > 0 && height > 0) {
                val size = minOf(width, height) * 0.50f
                val left = (width - size) / 2f
                val top = (height - size) / 2f
                canvas.drawRect(left, top, left + size, top + size, reticlePaint)
            }
            flashColor?.let { c ->
                flashPaint.color = c
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
            }
        }
    }
}
