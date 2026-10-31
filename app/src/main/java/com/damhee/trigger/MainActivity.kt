package com.damhee.trigger

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * 2단계:
 *  ① 언제 알려드릴까요? — 날짜 박스(요일 표시) → 시각 박스(오전/오후/저녁 + ＋다른시각). 시각을 누르면 ②로
 *  ② 그때 무엇을 알려드릴까요? — 🎙 말로 하기 / ⌨ 글자로 쓰기. [확인]을 누르면 바로 저장
 *  저장 화면은 5초 동안 보여주고 소리로도 읽어줌
 */
class MainActivity : Activity() {
    private val INK = 0xFF1F2328.toInt()
    private val SUB = 0xFF6B7280.toInt()
    private val FAINT = 0xFFC9CDD3.toInt()
    private val LINE = 0xFFDDE0E4.toInt()
    private val ACCENT = 0xFF2563EB.toInt()
    private val ACCENT_BG = 0xFFEAF1FF.toInt()
    private val BG = 0xFFEEF0F3.toInt()
    private val CARD = 0xFFFFFFFF.toInt()
    private val BTN = 0xFFF4F6F9.toInt()
    private val RED = 0xFFD93025.toInt()
    private val BLUE = 0xFF2563EB.toInt()
    private val GREEN = 0xFF16A34A.toInt()
    private val TODAY_BG = 0xFFFFF1F0.toInt()
    private val AMBER = 0xFFF59E0B.toInt()

    private var step = 1          // 1: 언제, 2: 무엇
    private var mode = 0          // step 2 안에서 0: 고르기, 1: 말로, 2: 글자로
    private var selDate: LocalDate? = null
    private var remindAt: LocalDateTime? = null

    private var askedNotif = false
    private var saving = false
    private val ui = Handler(Looper.getMainLooper())
    private var newVersion = 0
    private var downloadId = -1L

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var topBar: LinearLayout
    private lateinit var banner: TextView
    private lateinit var updateBar: TextView
    private lateinit var content: LinearLayout
    private lateinit var overlay: FrameLayout

    // 말로 하기 상태
    private val WAIT_MS = 15_000L      // 말하기 전 기다려 주는 시간
    private val SILENCE_MS = 3_000L    // 말이 멈춘 뒤 자동으로 넘어가기까지
    private var recognizer: SpeechRecognizer? = null
    private var gen = 0
    private var vState = 0             // 0 듣는 중, 1 결과, 2 말이 없었음
    private var accum = ""
    private var partial = ""
    private var heardFinal = ""
    private var spoke = false
    private var listenStart = 0L
    private var lastVoiceAt = 0L
    private var fillColor = 0
    private var vFill: View? = null
    private var vSecs: TextView? = null
    private var vText: TextView? = null
    private var vHint: TextView? = null
    private var vMic: TextView? = null
    private var textInput: EditText? = null

    // ---------- 작은 도우미 ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int = 0, radius: Int = 14, sw: Int = 2) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(sw), stroke)
    }

    private fun tv(s: String, sp: Float, color: Int = INK, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun title(s: String, sub: String = "") {
        content.addView(tv(s, 28f, INK, true).apply { gravity = Gravity.START },
            lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 2, 0, if (sub.isEmpty()) 10 else 2))
        if (sub.isNotEmpty()) {
            content.addView(tv(sub, 16f, SUB).apply { gravity = Gravity.START },
                lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 0, 0, 10))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dayColor(d: LocalDate) = when (d.dayOfWeek) {
        DayOfWeek.SUNDAY -> RED
        DayOfWeek.SATURDAY -> BLUE
        else -> INK
    }

    private fun h12(h: Int) = if (h > 12) h - 12 else h

    private fun join(a: String, b: String) = (a + " " + b).trim()

    /** 내일(일) 11/1  또는  11/6(금) */
    private fun dateText(d: LocalDate): String {
        val rel = Fmt.rel(d)
        return if (rel.isNotEmpty()) "$rel(${Fmt.dow(d)}) ${d.monthValue}/${d.dayOfMonth}"
        else "${d.monthValue}/${d.dayOfMonth}(${Fmt.dow(d)})"
    }

    // ---------- 화면 틀 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        Notif.ensureChannels(this)
        AlarmStore.prune(this)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.KOREAN)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(0.9f)
            }
        }

        val root = col().apply {
            setBackgroundColor(BG)
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        topBar = row().apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(topBar, lp(MATCH_PARENT, dp(52)))

        banner = tv("", 15f, Color.WHITE, true).apply {
            background = box(RED, 0, 10)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            visibility = View.GONE
        }
        root.addView(banner, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 4))
        updateBar = tv("", 16f, Color.WHITE, true).apply {
            background = box(GREEN, 0, 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            setOnClickListener { onUpdateTap() }
        }
        root.addView(updateBar, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 4))

        content = col()
        root.addView(content, lp(MATCH_PARENT, 0, 1f, 0, 6))

        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xDD000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frame)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!saving) {
            if (selDate?.isBefore(LocalDate.now()) == true) selDate = null
            val r = remindAt
            if (step == 2 && (r == null || !r.isAfter(LocalDateTime.now()))) { step = 1; mode = 0 }
            render()
        }
        checkPermissions()
        Updater.check(this) { v -> runOnUiThread { showUpdate(v) } }
    }

    override fun onPause() {
        super.onPause()
        if (mode == 1) { stopVoice(); mode = 0 }
    }

    override fun onDestroy() {
        stopRecognizer()
        ui.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun render() {
        if (step == 2 && remindAt == null) step = 1
        renderTop()
        content.removeAllViews()
        vFill = null; vSecs = null; vText = null; vHint = null; vMic = null; textInput = null
        if (step == 1) renderWhen()
        else when (mode) {
            1 -> renderVoice()
            2 -> renderTextMode()
            else -> renderWhat()
        }
    }

    private fun renderTop() {
        topBar.removeAllViews()
        if (step == 1) {
            topBar.addView(tv("🔔 잊지마", 23f, INK, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
                lp(0, MATCH_PARENT, 1f))
            val now = System.currentTimeMillis()
            val n = AlarmStore.load(this).count { it.time > now }
            topBar.addView(tv("📋 예정 $n", 17f, ACCENT, true).apply {
                background = box(CARD, LINE, 24)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { showList() }
            }, lp(WRAP_CONTENT, dp(48)))
        } else {
            topBar.addView(tv("◀ 뒤로", 19f, INK, true).apply {
                background = box(CARD, LINE, 24)
                setPadding(dp(18), 0, dp(20), 0)
                setOnClickListener { goBack() }
            }, lp(WRAP_CONTENT, dp(48)))
            topBar.addView(View(this), lp(0, 1, 1f))
        }
    }

    private fun goBack() {
        if (overlay.visibility == View.VISIBLE) return
        if (step == 2) {
            if (mode != 0) { stopVoice(); hideKeyboard(); mode = 0 } else step = 1
            render()
        } else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    // =====================================================
    // ① 언제 알려드릴까요?
    // =====================================================

    /** 오늘·내일·모레 + 금·토(이번 주에 남았으면) 또는 그 다음 날들 */
    private fun quickDates(): List<Pair<String, LocalDate>> {
        val t = LocalDate.now()
        val out = mutableListOf<Pair<String, LocalDate>>()
        fun lab(p: String, d: LocalDate) = "$p(${Fmt.dow(d)})"
        out += lab("오늘", t) to t
        out += lab("내일", t.plusDays(1)) to t.plusDays(1)
        out += lab("모레", t.plusDays(2)) to t.plusDays(2)

        val weekEnd = t.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val picks = mutableListOf<LocalDate>()
        for (dw in listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)) {
            val d = t.with(TemporalAdjusters.nextOrSame(dw))
            if (d.isAfter(t.plusDays(2)) && !d.isAfter(weekEnd)) picks += d
        }
        var k = 3L
        while (picks.size < 2) {
            val d = t.plusDays(k)
            if (d !in picks) picks += d
            k++
        }
        for (d in picks.sorted()) {
            out += ((if (d.isAfter(weekEnd)) "다음주 " else "") + Fmt.dow(d)) to d
        }
        return out
    }

    private fun dateBtn(label: String, d: LocalDate): View {
        val on = d == selDate
        val b = col().apply {
            gravity = Gravity.CENTER
            background = box(if (on) ACCENT else BTN, 0, 14)
            setOnClickListener { selDate = d; render() }
        }
        b.addView(tv(label, 17f, if (on) Color.WHITE else dayColor(d), true))
        b.addView(tv("${d.monthValue}/${d.dayOfMonth}", 14f, if (on) Color.WHITE else SUB))
        return b
    }

    private fun renderWhen() {
        title("언제 알려드릴까요?", "알람이 울릴 날짜와 시각을 골라주세요")

        // ── 날짜 박스 ──
        val dateBox = col().apply {
            background = box(CARD, if (selDate == null) ACCENT else LINE, 20, if (selDate == null) 3 else 2)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        dateBox.addView(tv("① 날짜", 17f, ACCENT, true).apply { gravity = Gravity.START },
            lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 0, 0, 6))
        val q = quickDates()
        val r1 = row()
        val r2 = row()
        q.forEachIndexed { i, (label, d) ->
            val target = if (i < 3) r1 else r2
            target.addView(dateBtn(label, d), lp(0, MATCH_PARENT, 1f, 3, 0, 3))
        }
        val custom = selDate != null && q.none { it.second == selDate }
        r2.addView(tv(if (custom) "📅 ${Fmt.date(selDate!!)}" else "📅 달력", 18f,
            if (custom) Color.WHITE else ACCENT, true).apply {
            background = box(if (custom) ACCENT else ACCENT_BG, 0, 14)
            setOnClickListener { showCalendar() }
        }, lp(0, MATCH_PARENT, 1f, 3, 0, 3))
        dateBox.addView(r1, lp(MATCH_PARENT, dp(66)))
        dateBox.addView(r2, lp(MATCH_PARENT, dp(66), 0f, 0, 8))
        content.addView(dateBox, lp(MATCH_PARENT, WRAP_CONTENT))

        // ── 시각 박스 (날짜를 고르면 밝아짐) ──
        val d = selDate
        val active = d != null
        val timeBox = col().apply {
            background = box(CARD, if (active) ACCENT else LINE, 20, if (active) 3 else 2)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            alpha = if (active) 1f else 0.4f
        }
        val head = if (d == null) "② 시각  (날짜를 먼저 눌러주세요)" else "② 시각 — ${dateText(d)}"
        timeBox.addView(tv(head, 17f, if (active) ACCENT else SUB, true).apply { gravity = Gravity.START },
            lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 0, 0, 6))

        val now = LocalDateTime.now()
        for ((name, period) in listOf("오전" to "am", "오후" to "pm", "저녁" to "ev")) {
            val rr = row().apply { gravity = Gravity.CENTER_VERTICAL }
            rr.addView(tv(name, 17f, SUB, true), lp(dp(46), MATCH_PARENT))
            for (h in AlarmStore.row(this, period)) {
                val past = d != null && !LocalDateTime.of(d, LocalTime.of(h, 0)).isAfter(now)
                val b = tv("${h12(h)}시", 22f, if (past) FAINT else INK, true).apply {
                    background = box(if (past) 0xFFF0F0F0.toInt() else BTN, 0, 14)
                    if (active && !past) setOnClickListener { pickTime(d!!, LocalTime.of(h, 0)) }
                }
                rr.addView(b, lp(0, MATCH_PARENT, 1f, 3, 0, 3))
            }
            rr.addView(tv("＋다른\n시각", 14f, ACCENT, true).apply {
                background = box(ACCENT_BG, 0, 14)
                if (active) setOnClickListener { showHours(period, d!!) }
            }, lp(0, MATCH_PARENT, 1f, 3, 0, 3))
            timeBox.addView(rr, lp(MATCH_PARENT, 0, 1f, 0, 4, 0, 4))
        }
        content.addView(timeBox, lp(MATCH_PARENT, 0, 1f, 0, 10))
    }

    private fun pickTime(d: LocalDate, t: LocalTime) {
        val x = LocalDateTime.of(d, t)
        if (!x.isAfter(LocalDateTime.now())) {
            toast("이미 지난 시각이에요")
            return
        }
        remindAt = x
        step = 2
        mode = 0
        render()
    }

    /** ＋다른시각: 화면 전체에 시각이 쭉 펼쳐짐. 누르면 그 시각으로 정하고, 줄에서는 가장 가까운 시각이 빠지고 이 시각이 들어감 */
    private fun showHours(period: String, d: LocalDate) {
        val (name, hours) = when (period) {
            "am" -> "오전" to (4..12).toList()
            "pm" -> "오후" to (12..18).toList()
            else -> "저녁" to (18..23).toList()
        }
        val dlg = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        val root = col().apply {
            setBackgroundColor(BG)
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        root.addView(tv("◀ 뒤로", 19f, INK, true).apply {
            background = box(CARD, LINE, 24)
            setPadding(dp(18), 0, dp(20), 0)
            setOnClickListener { dlg.dismiss() }
        }, lp(WRAP_CONTENT, dp(48)))
        root.addView(tv("$name 몇 시에 알려드릴까요?", 28f, INK, true).apply { gravity = Gravity.START },
            lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 14, 0, 4))
        root.addView(tv(dateText(d), 18f, ACCENT, true).apply { gravity = Gravity.START },
            lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 0, 0, 12))
        val now = LocalDateTime.now()
        for (r in 0 until 3) {
            val rr = row()
            for (c in 0 until 3) {
                val h = hours.getOrNull(r * 3 + c)
                if (h == null) {
                    rr.addView(View(this), lp(0, MATCH_PARENT, 1f, 5, 5, 5, 5))
                    continue
                }
                val past = !LocalDateTime.of(d, LocalTime.of(h, 0)).isAfter(now)
                val b = tv("${h12(h)}시", 36f, if (past) FAINT else INK, true).apply {
                    background = box(if (past) 0xFFF0F0F0.toInt() else CARD, if (past) 0 else LINE, 20)
                    if (!past) setOnClickListener {
                        dlg.dismiss()
                        AlarmStore.pushHour(this@MainActivity, period, h)
                        pickTime(d, LocalTime.of(h, 0))
                    }
                }
                rr.addView(b, lp(0, MATCH_PARENT, 1f, 5, 5, 5, 5))
            }
            root.addView(rr, lp(MATCH_PARENT, 0, 1f))
        }
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(ColorDrawable(BG))
        dlg.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        dlg.show()
    }

    /** 큰 달력 — 오늘은 빨간 칸 + "오늘" 글자, 맨 위에도 오늘 날짜 안내 */
    private fun showCalendar() {
        var month = YearMonth.from(selDate ?: LocalDate.now())
        if (month.isBefore(YearMonth.now())) month = YearMonth.now()
        val dlg = Dialog(this)
        val c0 = col().apply { setPadding(dp(10), dp(12), dp(10), dp(10)) }

        fun draw() {
            c0.removeAllViews()
            val today = LocalDate.now()
            c0.addView(tv("오늘은 ${Fmt.dateLong(today)}", 19f, RED, true).apply {
                background = box(TODAY_BG, RED, 12)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 8))

            val mh = row().apply { gravity = Gravity.CENTER_VERTICAL }
            val canPrev = month.isAfter(YearMonth.now())
            mh.addView(tv("◀", 24f, if (canPrev) INK else FAINT, true).apply {
                background = box(BTN, 0, 14)
                setOnClickListener { if (canPrev) { month = month.minusMonths(1); draw() } }
            }, lp(dp(68), MATCH_PARENT))
            mh.addView(tv("${month.year}년 ${month.monthValue}월", 22f, INK, true), lp(0, MATCH_PARENT, 1f))
            mh.addView(tv("▶", 24f, INK, true).apply {
                background = box(BTN, 0, 14)
                setOnClickListener { month = month.plusMonths(1); draw() }
            }, lp(dp(68), MATCH_PARENT))
            c0.addView(mh, lp(MATCH_PARENT, dp(56)))
            val wk = row()
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, s ->
                wk.addView(tv(s, 15f, when (i) { 0 -> RED; 6 -> BLUE; else -> SUB }, true), lp(0, MATCH_PARENT, 1f))
            }
            c0.addView(wk, lp(MATCH_PARENT, dp(32), 0f, 0, 6))

            val offset = month.atDay(1).dayOfWeek.value % 7
            val days = month.lengthOfMonth()
            val rowsNeeded = (offset + days + 6) / 7
            for (r in 0 until rowsNeeded) {
                val rr = row()
                for (c in 0 until 7) {
                    val day = r * 7 + c - offset + 1
                    val cell = col().apply { gravity = Gravity.CENTER }
                    if (day in 1..days) {
                        val d = month.atDay(day)
                        val past = d.isBefore(today)
                        val isToday = d == today
                        val selected = d == selDate
                        val numColor = when {
                            selected || isToday -> Color.WHITE
                            past -> FAINT
                            c == 0 -> RED
                            c == 6 -> BLUE
                            else -> INK
                        }
                        cell.addView(tv(day.toString(), 21f, numColor, !past))
                        if (isToday) cell.addView(tv("오늘", 11f, Color.WHITE, true))
                        cell.background = when {
                            selected -> box(ACCENT, 0, 12)
                            isToday -> box(RED, 0, 12)
                            else -> null
                        }
                        if (!past) cell.setOnClickListener { selDate = d; dlg.dismiss(); render() }
                    }
                    rr.addView(cell, lp(0, MATCH_PARENT, 1f, 2, 2, 2, 2))
                }
                c0.addView(rr, lp(MATCH_PARENT, dp(54)))
            }
            c0.addView(tv("닫기", 18f, ACCENT, true).apply { setOnClickListener { dlg.dismiss() } },
                lp(MATCH_PARENT, dp(50), 0f, 0, 6))
        }
        draw()
        dlg.setContentView(ScrollView(this).apply { addView(c0) })
        dlg.window?.setBackgroundDrawable(box(CARD, 0, 22))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), WRAP_CONTENT)
    }

    // =====================================================
    // ② 그때 무엇을 알려드릴까요?
    // =====================================================

    private fun timeCard(): View {
        val r = remindAt!!
        val rw = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = box(CARD, ACCENT, 16, 2)
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        rw.addView(tv("🔔 ${dateText(r.toLocalDate())} · ${Fmt.kor(r.toLocalTime())}", 19f, ACCENT, true).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }, lp(0, WRAP_CONTENT, 1f))
        rw.addView(tv("바꾸기", 16f, ACCENT, true).apply {
            background = box(ACCENT_BG, 0, 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { stopVoice(); hideKeyboard(); mode = 0; step = 1; render() }
        }, lp(WRAP_CONTENT, WRAP_CONTENT))
        return rw
    }

    private fun renderWhat() {
        title("그때 무엇을 알려드릴까요?")
        content.addView(timeCard(), lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 12))

        val voice = col().apply {
            gravity = Gravity.CENTER
            background = box(ACCENT, 0, 28)
            setOnClickListener { startVoiceFlow() }
        }
        voice.addView(tv("🎙", 64f))
        voice.addView(tv("말로 하기", 32f, Color.WHITE, true))
        voice.addView(tv("누르고 말씀하세요", 16f, 0xCCFFFFFF.toInt()))
        content.addView(voice, lp(MATCH_PARENT, 0, 1.3f))

        val text = col().apply {
            gravity = Gravity.CENTER
            background = box(CARD, LINE, 28)
            setOnClickListener { mode = 2; render() }
        }
        text.addView(tv("⌨", 48f))
        text.addView(tv("글자로 쓰기", 28f, INK, true))
        content.addView(text, lp(MATCH_PARENT, 0, 1f, 0, 12))
    }

    // ---------- 글자로 쓰기 ----------
    private fun renderTextMode() {
        title("그때 무엇을 알려드릴까요?")
        content.addView(timeCard(), lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 12))
        val et = EditText(this).apply {
            hint = "예) 우산 챙기기"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(INK)
            setHintTextColor(FAINT)
            background = box(CARD, ACCENT, 16, 3)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.TOP or Gravity.START
            setRawInputType(InputType.TYPE_CLASS_TEXT)
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 4
            setOnEditorActionListener { _, _, _ -> submitText(); true }
        }
        textInput = et
        content.addView(et, lp(MATCH_PARENT, 0, 1f, 0, 0, 0, 10))
        content.addView(tv("✓ 확인", 26f, Color.WHITE, true).apply {
            background = box(GREEN, 0, 18)
            setOnClickListener { submitText() }
        }, lp(MATCH_PARENT, dp(70)))
        et.postDelayed({
            et.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(et, 0)
        }, 200)
    }

    private fun submitText() {
        val t = textInput?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) {
            toast("알려드릴 내용을 적어주세요")
            return
        }
        hideKeyboard()
        saveAlarm(t)
    }

    private fun hideKeyboard() {
        val e = textInput ?: return
        e.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(e.windowToken, 0)
    }

    // ---------- 말로 하기 ----------
    private fun startVoiceFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        beginVoice()
    }

    private fun beginVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("이 폰에서는 음성 인식을 쓸 수 없어요. 글자로 써 주세요")
            return
        }
        stopRecognizer()
        mode = 1
        vState = 0
        accum = ""; partial = ""; heardFinal = ""
        spoke = false
        fillColor = 0
        listenStart = SystemClock.elapsedRealtime()
        lastVoiceAt = listenStart
        render()
        startRecognizer()
        ui.removeCallbacks(voiceTick)
        ui.post(voiceTick)
    }

    private fun startRecognizer() {
        recognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        val myGen = ++gen
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (myGen != gen || vState != 0) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (t.isNotBlank() && t != partial) {
                    partial = t
                    spoke = true
                    lastVoiceAt = SystemClock.elapsedRealtime()
                    updateLiveText()
                }
            }

            override fun onResults(results: Bundle?) {
                if (myGen != gen || vState != 0) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (t.isNotBlank()) {
                    accum = join(accum, t)
                    partial = ""
                    spoke = true
                } else mergePartial()
                updateLiveText()
                restartRecognizer(150)   // 폰이 먼저 끊어도 타이머가 끝날 때까지 계속 들음
            }

            override fun onError(error: Int) {
                if (myGen != gen || vState != 0) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        mergePartial(); restartRecognizer(200)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                        mergePartial(); restartRecognizer(600)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        stopVoice(); mode = 0; render()
                        toast("마이크 허용이 필요해요. 글자로 쓰기는 마이크 없이 돼요")
                    }
                    else -> {
                        if (join(accum, partial).isNotBlank()) finishVoice()
                        else {
                            stopVoice(); mode = 0; render()
                            toast("음성 인식이 안 돼요. 인터넷을 확인하거나 글자로 써 주세요")
                        }
                    }
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        recognizer = r
        try {
            r.startListening(i)
        } catch (_: Exception) {
            restartRecognizer(600)
        }
    }

    private fun mergePartial() {
        if (partial.isNotBlank()) {
            accum = join(accum, partial)
            partial = ""
        }
    }

    private fun restartRecognizer(delay: Long) {
        ui.postDelayed({
            if (mode == 1 && vState == 0 && !isFinishing) startRecognizer()
        }, delay)
    }

    private fun updateLiveText() {
        val t = join(accum, partial)
        vText?.apply {
            if (t.isNotBlank()) {
                text = "“$t”"
                setTextColor(INK)
            }
        }
    }

    private fun stopRecognizer() {
        gen++
        ui.removeCallbacks(voiceTick)
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }

    private fun stopVoice() {
        stopRecognizer()
        vState = 0
    }

    private fun finishVoice() {
        stopRecognizer()
        heardFinal = join(accum, partial)
        vState = if (heardFinal.isBlank()) 2 else 1
        render()
    }

    /** 0.1초마다: 남은 시간 막대·숫자를 줄이고, 시간이 다 되면 자동으로 넘어감 */
    private val voiceTick = object : Runnable {
        override fun run() {
            if (mode != 1 || vState != 0) return
            val now = SystemClock.elapsedRealtime()
            val total = if (!spoke) WAIT_MS else SILENCE_MS
            val remaining = total - (now - (if (!spoke) listenStart else lastVoiceAt))
            if (remaining <= 0) {
                if (join(accum, partial).isNotBlank()) finishVoice()
                else { stopRecognizer(); vState = 2; render() }
                return
            }
            vFill?.scaleX = (remaining.toFloat() / total).coerceIn(0f, 1f)
            vSecs?.text = ceil(remaining / 1000.0).toInt().toString()
            vHint?.text = if (spoke) "곧 넘어가요…  (말씀을 이어가면 계속 들어요)" else "말씀해 주세요"
            val want = when {
                spoke -> AMBER
                remaining < 3000 -> RED
                else -> ACCENT
            }
            if (want != fillColor) {
                fillColor = want
                vFill?.background = box(want, 0, 7)
            }
            vMic?.alpha = (0.65f + 0.35f * abs(sin(now / 300.0)).toFloat())
            ui.postDelayed(this, 100)
        }
    }

    private fun renderVoice() {
        when (vState) {
            0 -> renderListening()
            1 -> renderHeard()
            else -> renderNoSpeech()
        }
    }

    private fun renderListening() {
        title("말씀해 주세요")
        content.addView(timeCard(), lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 8))

        val mic = tv("🎙", 60f, Color.WHITE).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED) }
        }
        vMic = mic
        content.addView(mic, LinearLayout.LayoutParams(dp(120), dp(120)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val live = tv("듣고 있어요…", 32f, SUB, true).apply {
            background = box(CARD, 0, 20)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        vText = live
        content.addView(live, lp(MATCH_PARENT, 0, 1f, 0, 10, 0, 10))
        updateLiveText()

        val hint = tv("말씀해 주세요", 18f, SUB, true)
        vHint = hint
        content.addView(hint, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 6))

        val tr = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val bar = FrameLayout(this).apply { background = box(LINE, 0, 7) }
        val fill = View(this).apply {
            background = box(ACCENT, 0, 7)
            pivotX = 0f
        }
        fillColor = ACCENT
        vFill = fill
        bar.addView(fill, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tr.addView(bar, lp(0, dp(14), 1f))
        val secs = tv("15", 24f, INK, true)
        vSecs = secs
        tr.addView(secs, lp(dp(52), WRAP_CONTENT))
        content.addView(tr, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 12))

        content.addView(tv("바로 넘어가기", 20f, ACCENT, true).apply {
            background = box(CARD, ACCENT, 16)
            setOnClickListener {
                if (join(accum, partial).isBlank()) toast("아직 말씀이 없어요")
                else finishVoice()
            }
        }, lp(MATCH_PARENT, dp(60)))
    }

    private fun renderHeard() {
        title("이렇게 들었어요")
        content.addView(timeCard(), lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 12))
        content.addView(tv("“$heardFinal”", 34f, INK, true).apply {
            background = box(CARD, GREEN, 20, 3)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }, lp(MATCH_PARENT, 0, 1f, 0, 0, 0, 12))
        val br = row()
        br.addView(tv("✓ 확인", 30f, Color.WHITE, true).apply {
            background = box(GREEN, 0, 20)
            setOnClickListener { saveAlarm(heardFinal) }
        }, lp(0, MATCH_PARENT, 2f))
        br.addView(tv("다시", 22f, ACCENT, true).apply {
            background = box(CARD, ACCENT, 20)
            setOnClickListener { beginVoice() }
        }, lp(0, MATCH_PARENT, 1f, 8))
        content.addView(br, lp(MATCH_PARENT, dp(86)))
    }

    private fun renderNoSpeech() {
        title("말씀이 없으셔서 멈췄어요")
        content.addView(timeCard(), lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 12))
        val again = col().apply {
            gravity = Gravity.CENTER
            background = box(ACCENT, 0, 28)
            setOnClickListener { beginVoice() }
        }
        again.addView(tv("🎙", 56f))
        again.addView(tv("다시 듣기", 30f, Color.WHITE, true))
        content.addView(again, lp(MATCH_PARENT, 0, 1.2f))
        val text = col().apply {
            gravity = Gravity.CENTER
            background = box(CARD, LINE, 28)
            setOnClickListener { mode = 2; render() }
        }
        text.addView(tv("⌨", 40f))
        text.addView(tv("글자로 쓰기", 24f, INK, true))
        content.addView(text, lp(MATCH_PARENT, 0, 1f, 0, 12))
    }

    // =====================================================
    // 저장
    // =====================================================
    private fun saveAlarm(textIn: String) {
        if (saving) return
        val r = remindAt ?: return
        val text = textIn.trim()
        if (text.isEmpty()) return
        val ms = Fmt.toMs(r)
        if (ms <= System.currentTimeMillis() + 10_000L) {
            toast("알림 시각이 이미 지났어요. 다시 골라주세요")
            stopVoice(); mode = 0; step = 1; render()
            return
        }
        saving = true
        val cat = Cats.guess(text)
        val a = Alarm(AlarmStore.nextId(this), ms, cat.key, text, System.currentTimeMillis())
        AlarmStore.put(this, a)
        AlarmScheduler.schedule(this, a)
        showSaved(a, cat)
    }

    /** 크게 "저장되었습니다!!" 5초 + 소리로 읽어줌. [확인]을 누르면 바로 닫힘 */
    private fun showSaved(a: Alarm, cat: Cat) {
        val x = Fmt.ldt(a.time)
        val whenTxt = "${dateText(x.toLocalDate())} ${Fmt.kor(x.toLocalTime())}"

        val card = col().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = box(CARD, 0, 28)
            setPadding(dp(22), dp(28), dp(22), dp(20))
        }
        card.addView(tv("✓", 54f, Color.WHITE, true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
        }, LinearLayout.LayoutParams(dp(100), dp(100)))
        card.addView(tv("저장되었습니다!!", 32f, INK, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 18))
        card.addView(tv("🔔 ${Fmt.dateLong(x.toLocalDate())}\n${Fmt.kor(x.toLocalTime())}에", 23f, ACCENT, true).apply {
            setLineSpacing(0f, 1.15f)
        }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 16))
        card.addView(tv("${cat.emoji} “${a.memo}”", 26f, cat.color, true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 8))
        card.addView(tv("알려드릴게요", 21f, INK, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 8))
        val ok = tv("확인 (5)", 21f, Color.WHITE, true).apply {
            background = box(GREEN, 0, 16)
            setOnClickListener { finish() }
        }
        card.addView(ok, lp(MATCH_PARENT, dp(62), 0f, 0, 20))

        overlay.removeAllViews()
        overlay.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@MainActivity).apply {
                addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
                    setMargins(dp(20), dp(20), dp(20), dp(20))
                })
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(150).start()
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()

        if (ttsReady) {
            tts?.speak("$whenTxt 에 ${a.memo} 알려드릴게요", TextToSpeech.QUEUE_FLUSH, null, "saved")
        }
        for (s in 4 downTo 1) ui.postDelayed({ ok.text = "확인 ($s)" }, (5 - s) * 1000L)
        ui.postDelayed({ if (!isFinishing) finish() }, 5000)
    }

    // =====================================================
    // 예정 목록
    // =====================================================
    private var listDialog: Dialog? = null

    private fun showList() {
        listDialog?.dismiss()
        val list = AlarmStore.load(this).sortedBy { it.time }
        if (list.isEmpty()) {
            Toast.makeText(this, "예정된 알림이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val today = LocalDate.now()

        val c0 = col().apply { setPadding(dp(14), dp(16), dp(14), dp(8)) }
        c0.addView(tv("예정된 알림", 22f, INK, true).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT))
        c0.addView(tv("누르면 지울 수 있어요", 14f, SUB).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 2, 0, 8))

        for (a in list) {
            val x = Fmt.ldt(a.time)
            val d = x.toLocalDate()
            val isToday = d == today
            val past = a.time <= now
            val c = Cats.of(a.cat)
            val rel = Fmt.rel(d)

            val r = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = box(if (isToday) TODAY_BG else CARD, if (isToday) RED else LINE, 14)
                setPadding(dp(10), dp(12), dp(12), dp(12))
                setOnClickListener { confirmDelete(a) }
                if (past) alpha = 0.5f
            }
            val left = col().apply { gravity = Gravity.CENTER_HORIZONTAL }
            if (isToday) {
                left.addView(tv("오늘", 17f, Color.WHITE, true).apply {
                    background = box(RED, 0, 8)
                    setPadding(dp(12), dp(1), dp(12), dp(2))
                }, lp(WRAP_CONTENT, WRAP_CONTENT))
            } else if (rel.isNotEmpty()) {
                left.addView(tv(rel, 17f, ACCENT, true), lp(WRAP_CONTENT, WRAP_CONTENT))
            }
            left.addView(tv(Fmt.date(d), 14f, if (isToday) RED else SUB, isToday), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 2))
            left.addView(tv(Fmt.kor(x.toLocalTime()), 17f, if (isToday) RED else INK, true), lp(WRAP_CONTENT, WRAP_CONTENT))
            r.addView(left, lp(dp(100), WRAP_CONTENT))

            val right = col()
            right.addView(tv("${c.emoji} ${Fmt.sentence(a)}" + if (past) "  (지남)" else "", 20f, c.color, true).apply {
                gravity = Gravity.START
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, lp(MATCH_PARENT, WRAP_CONTENT))
            r.addView(right, lp(0, WRAP_CONTENT, 1f, 8))
            c0.addView(r, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 4, 0, 4))
        }
        c0.addView(tv("닫기", 18f, ACCENT, true).apply {
            setOnClickListener { listDialog?.dismiss() }
        }, lp(MATCH_PARENT, dp(52), 0f, 0, 8))

        val dlg = Dialog(this)
        dlg.setContentView(ScrollView(this).apply { addView(c0) })
        dlg.window?.setBackgroundDrawable(box(BG, 0, 20))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), WRAP_CONTENT)
        listDialog = dlg
    }

    private fun confirmDelete(a: Alarm) {
        val c = Cats.of(a.cat)
        AlertDialog.Builder(this)
            .setMessage("${Fmt.dateTime(a.time)}\n${c.emoji} ${Fmt.sentence(a)}\n\n이 알림을 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                AlarmScheduler.cancel(this, a.id)
                AlarmStore.remove(this, a.id)
                render()
                showList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // =====================================================
    // 업데이트
    // =====================================================
    private fun showUpdate(v: Int) {
        if (isFinishing || downloadId >= 0) return
        newVersion = v
        updateBar.text = "🔄 새 버전이 있어요 — 눌러서 업데이트"
        updateBar.visibility = View.VISIBLE
    }

    private fun onUpdateTap() {
        if (downloadId >= 0) return
        try {
            downloadId = Updater.startDownload(this, newVersion)
        } catch (_: Exception) {
            Updater.openInBrowser(this)
            return
        }
        updateBar.text = "⬇ 받는 중… 0%"
        pollDownload()
    }

    private fun pollDownload() {
        val p = Updater.progress(this, downloadId)
        when {
            p == 101 -> {
                updateBar.text = "✓ 다 받았어요 — 설치를 눌러주세요 (안 뜨면 여기 누르기)"
                val id = downloadId
                downloadId = -1L
                updateBar.setOnClickListener { if (!Updater.install(this, id)) Updater.openInBrowser(this) }
                if (!Updater.install(this, id)) Updater.openInBrowser(this)
            }
            p < 0 -> {
                downloadId = -1L
                updateBar.text = "받기 실패 — 눌러서 다시"
                updateBar.setOnClickListener { onUpdateTap() }
            }
            else -> {
                updateBar.text = "⬇ 받는 중… $p%"
                ui.postDelayed({ pollDownload() }, 500)
            }
        }
    }

    // =====================================================
    // 권한
    // =====================================================
    private fun showBanner(msg: String, target: Intent) {
        banner.text = msg
        banner.visibility = View.VISIBLE
        banner.setOnClickListener {
            try {
                startActivity(target)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!askedNotif) {
                askedNotif = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            showBanner(
                "⚠ 알림이 꺼져 있어요 — 눌러서 켜기",
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
            return
        }
        val am = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            showBanner(
                "⚠ 정확한 시각 알람 허용이 필요해요 — 눌러서 허용",
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            showBanner(
                "⚠ 잠금화면에 알람 띄우기 허용이 필요해요 — 눌러서 허용",
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            )
            return
        }
        banner.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginVoice()
            else toast("마이크 허용이 필요해요. 글자로 쓰기는 마이크 없이 돼요")
        } else checkPermissions()
    }
}
