package com.damhee.trigger

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import kotlin.math.abs

/** 좌우로 밀면 onSwipe(+1: 다음, -1: 이전). 안쪽 버튼 누르기는 그대로 됨 */
class SwipeLayout(ctx: Context, private val onSwipe: (Int) -> Unit) : LinearLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var sx = 0f
    private var sy = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - sx
                val dy = e.y - sy
                if (abs(dx) > slop * 3 && abs(dx) > abs(dy) * 1.5f) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - sx
                if (abs(dx) > width * 0.15f && abs(dx) > abs(e.y - sy)) onSwipe(if (dx < 0) 1 else -1)
            }
        }
        return true
    }
}

/**
 * 3단계:
 *  1) 언제 하는 일이에요?  (오늘·내일은 오전/오후/저녁도)
 *  2) 언제 알려드릴까요?  (전날 저녁 8~11시, 전날 오후, 전날 오전, 당일 아침, 며칠 전)
 *  3) 어떤 일이에요?      (큰 타일을 누르면 바로 저장)
 */
class MainActivity : Activity() {
    private val INK = 0xFF1F2328.toInt()
    private val SUB = 0xFF6B7280.toInt()
    private val FAINT = 0xFFC9CDD3.toInt()
    private val LINE = 0xFFDDE0E4.toInt()
    private val ACCENT = 0xFF2563EB.toInt()
    private val ACCENT_BG = 0xFFEAF1FF.toInt()
    private val BG = 0xFFF5F5F2.toInt()
    private val RED = 0xFFD93025.toInt()
    private val BLUE = 0xFF2563EB.toInt()
    private val GREEN = 0xFF16A34A.toInt()
    private val TODAY_BG = 0xFFFFF1F0.toInt()
    private val STAR_BG = 0xFFFFF4D6.toInt()
    private val STAR = 0xFFB45309.toInt()

    // 선택한 것들
    private var step = 1
    private var showCal = false
    private var askPart = false
    private var evDate: LocalDate? = null
    private var evPart = ""
    private var remindAt: LocalDateTime? = null
    private var memoText = ""
    private var month: YearMonth = YearMonth.now()

    private var askedNotif = false
    private var saving = false
    private val ui = Handler(Looper.getMainLooper())
    private var newVersion = 0
    private var downloadId = -1L

    private lateinit var topBar: LinearLayout
    private lateinit var banner: TextView
    private lateinit var updateBar: TextView
    private lateinit var content: LinearLayout
    private lateinit var overlay: FrameLayout
    private var memo: EditText? = null
    private var calGrid: LinearLayout? = null

    // ---------- 작은 도우미 ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int = 0, radius: Int = 14) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
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

    private fun question(title: String, sub: String) {
        content.addView(tv(title, 27f, INK, true).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 6))
        if (sub.isNotEmpty()) {
            content.addView(tv(sub, 16f, SUB).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 4, 0, 10))
        }
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
            setBackgroundColor(0xCC000000.toInt())
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
        // 날짜가 지났으면 처음부터
        val d = evDate
        if (!saving && d != null && d.isBefore(LocalDate.now())) reset()
        val r = remindAt
        if (!saving && step == 3 && r != null && Fmt.toMs(r) <= System.currentTimeMillis()) step = 2
        if (month.isBefore(YearMonth.now())) month = YearMonth.now()
        if (!saving) render()
        checkPermissions()
        Updater.check(this) { v -> runOnUiThread { showUpdate(v) } }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reset() {
        step = 1; showCal = false; askPart = false
        evDate = null; evPart = ""; remindAt = null; memoText = ""
    }

    private fun render() {
        memo?.let { memoText = it.text.toString() }
        memo = null
        calGrid = null
        renderTop()
        content.removeAllViews()
        when (step) {
            1 -> when {
                askPart -> renderPart()
                showCal -> renderCalendar()
                else -> renderStep1()
            }
            2 -> renderStep2()
            3 -> renderStep3()
        }
    }

    private fun renderTop() {
        topBar.removeAllViews()
        val atStart = step == 1 && !showCal && !askPart
        if (atStart) {
            topBar.addView(tv("🔔 잊지마", 23f, INK, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
                lp(0, MATCH_PARENT, 1f))
        } else {
            topBar.addView(tv("◀ 뒤로", 19f, INK, true).apply {
                background = box(Color.WHITE, LINE, 24)
                setPadding(dp(18), 0, dp(20), 0)
                setOnClickListener { goBack() }
            }, lp(WRAP_CONTENT, dp(48)))
            topBar.addView(View(this), lp(0, 1, 1f))
        }
        // 단계 표시 ● ○ ○
        val dots = (1..3).joinToString(" ") { if (it == step) "●" else "○" }
        topBar.addView(tv("$dots  $step/3", 15f, SUB, true), lp(WRAP_CONTENT, MATCH_PARENT, 0f, 0, 0, 10))
        if (atStart) {
            val now = System.currentTimeMillis()
            val n = AlarmStore.load(this).count { it.time > now }
            topBar.addView(tv("📋 예정 $n", 17f, ACCENT, true).apply {
                background = box(Color.WHITE, LINE, 24)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { showList() }
            }, lp(WRAP_CONTENT, dp(48)))
        }
    }

    private fun goBack() {
        when {
            overlay.visibility == View.VISIBLE -> return
            step == 3 -> step = 2
            step == 2 -> { step = 1; askPart = false; showCal = false }
            askPart -> askPart = false
            showCal -> showCal = false
            else -> { finish(); return }
        }
        hideKeyboard()
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    // ---------- 1단계: 언제 하는 일? ----------
    private fun quickDates(): List<Pair<String, LocalDate>> {
        val t = LocalDate.now()
        val base = listOf("오늘" to t, "내일" to t.plusDays(1), "모레" to t.plusDays(2))
        fun weekend(day: DayOfWeek, name: String): Pair<String, LocalDate> {
            val d = t.with(TemporalAdjusters.nextOrSame(day))
            return if (d.isAfter(t.plusDays(2))) "이번 주 $name" to d else "다음 주 $name" to d.plusWeeks(1)
        }
        val ends = listOf(weekend(DayOfWeek.SATURDAY, "토요일"), weekend(DayOfWeek.SUNDAY, "일요일"))
            .sortedBy { it.second }
        return base + ends
    }

    private fun dayColor(d: LocalDate) = when (d.dayOfWeek) {
        DayOfWeek.SUNDAY -> RED
        DayOfWeek.SATURDAY -> BLUE
        else -> INK
    }

    private fun renderStep1() {
        question("언제 하는 일이에요?", "물건 가져갈 날, 약속 날, 준비할 날")
        for ((name, d) in quickDates()) {
            val b = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = box(Color.WHITE, LINE, 16)
                setPadding(dp(20), 0, dp(20), 0)
                setOnClickListener { pickDate(d) }
            }
            b.addView(tv(name, 24f, dayColor(d), true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
                lp(0, MATCH_PARENT, 1f))
            b.addView(tv("${d.monthValue}월 ${d.dayOfMonth}일 ${Fmt.dow(d)}", 18f, SUB), lp(WRAP_CONTENT, MATCH_PARENT))
            content.addView(b, lp(MATCH_PARENT, 0, 1f, 0, 5, 0, 5))
        }
        content.addView(tv("📅  다른 날 (달력에서 고르기)", 21f, ACCENT, true).apply {
            background = box(ACCENT_BG, ACCENT, 16)
            setOnClickListener { showCal = true; render() }
        }, lp(MATCH_PARENT, 0, 1f, 0, 5, 0, 2))
    }

    private fun pickDate(d: LocalDate) {
        evDate = d
        evPart = ""
        remindAt = null
        val today = LocalDate.now()
        if (d == today || d == today.plusDays(1)) {
            askPart = true
        } else {
            step = 2
        }
        render()
    }

    /** 오늘·내일만: 몇 시쯤인지 */
    private fun renderPart() {
        val d = evDate ?: return
        val rel = Fmt.rel(d)
        question("$rel 몇 시쯤이에요?", "${Fmt.dateLong(d)}")
        val now = LocalTime.now()
        val isToday = d == LocalDate.now()
        val parts = listOf(
            Triple("오전", "☀️", "아침 ~ 낮 12시"),
            Triple("오후", "🌤", "낮 12시 ~ 저녁 6시"),
            Triple("저녁", "🌙", "저녁 6시 이후"),
        )
        for ((p, icon, hint) in parts) {
            val over = isToday && when (p) {
                "오전" -> now.hour >= 11
                "오후" -> now.hour >= 17
                else -> now.hour >= 22
            }
            val b = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = box(if (over) 0xFFEFEFEF.toInt() else Color.WHITE, LINE, 16)
                setPadding(dp(20), 0, dp(20), 0)
                if (!over) setOnClickListener { evPart = p; askPart = false; step = 2; render() }
            }
            b.addView(tv("$icon  $rel $p", 26f, if (over) FAINT else INK, true).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, lp(0, MATCH_PARENT, 1f))
            b.addView(tv(if (over) "지났어요" else hint, 16f, if (over) FAINT else SUB), lp(WRAP_CONTENT, MATCH_PARENT))
            content.addView(b, lp(MATCH_PARENT, 0, 1f, 0, 6, 0, 6))
        }
        content.addView(tv("잘 모르겠어요 / 상관없어요", 19f, SUB, true).apply {
            background = box(Color.WHITE, LINE, 16)
            setOnClickListener { evPart = ""; askPart = false; step = 2; render() }
        }, lp(MATCH_PARENT, dp(64), 0f, 0, 6))
    }

    private fun renderCalendar() {
        question("날짜를 눌러주세요", "")
        val cal = SwipeLayout(this) { dir -> moveMonth(dir) }.apply {
            orientation = LinearLayout.VERTICAL
            background = box(Color.WHITE, 0, 18)
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        val mh = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val canPrev = month.isAfter(YearMonth.now())
        mh.addView(tv("◀", 24f, if (canPrev) INK else FAINT, true).apply {
            background = box(if (canPrev) BG else Color.WHITE, 0, 14)
            setOnClickListener { moveMonth(-1) }
        }, lp(dp(70), MATCH_PARENT))
        mh.addView(tv("${month.year}년 ${month.monthValue}월", 22f, INK, true), lp(0, MATCH_PARENT, 1f))
        mh.addView(tv("▶", 24f, INK, true).apply {
            background = box(BG, 0, 14)
            setOnClickListener { moveMonth(1) }
        }, lp(dp(70), MATCH_PARENT))
        cal.addView(mh, lp(MATCH_PARENT, dp(56)))
        val wk = row()
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, s ->
            wk.addView(tv(s, 15f, when (i) { 0 -> RED; 6 -> BLUE; else -> SUB }, true), lp(0, MATCH_PARENT, 1f))
        }
        cal.addView(wk, lp(MATCH_PARENT, dp(30), 0f, 0, 4))
        val grid = col()
        calGrid = grid
        fillGrid(grid)
        cal.addView(grid, lp(MATCH_PARENT, 0, 1f))
        content.addView(cal, lp(MATCH_PARENT, 0, 1f))
    }

    private fun fillGrid(grid: LinearLayout) {
        grid.removeAllViews()
        val today = LocalDate.now()
        val offset = month.atDay(1).dayOfWeek.value % 7 // 일요일 = 0
        val days = month.lengthOfMonth()
        val rows = (offset + days + 6) / 7
        for (r in 0 until rows) {
            val rr = row()
            for (c in 0 until 7) {
                val day = r * 7 + c - offset + 1
                val cell = tv("", 22f, INK, true)
                if (day in 1..days) {
                    val d = month.atDay(day)
                    val past = d.isBefore(today)
                    cell.text = day.toString()
                    cell.setTextColor(
                        when {
                            past -> FAINT
                            c == 0 -> RED
                            c == 6 -> BLUE
                            else -> INK
                        }
                    )
                    if (!past) cell.typeface = Typeface.DEFAULT_BOLD else cell.typeface = Typeface.DEFAULT
                    if (d == today) cell.background = box(Color.TRANSPARENT, ACCENT, 14)
                    if (!past) cell.setOnClickListener { showCal = false; pickDate(d) }
                }
                rr.addView(cell, lp(0, MATCH_PARENT, 1f, 2, 2, 2, 2))
            }
            grid.addView(rr, lp(MATCH_PARENT, 0, 1f))
        }
    }

    private fun moveMonth(dir: Int) {
        val n = month.plusMonths(dir.toLong())
        if (n.isBefore(YearMonth.now())) return
        month = n
        render()
        calGrid?.let { g ->
            g.post {
                g.translationX = dir * g.width * 0.3f
                g.alpha = 0.2f
                g.animate().translationX(0f).alpha(1f).setDuration(180).start()
            }
        }
    }

    // ---------- 2단계: 언제 알려줄까? ----------
    private data class Opt(val label: String, val at: LocalDateTime, val star: Boolean = false)
    private data class OptRow(val title: String, val opts: List<Opt>)

    private fun options(): List<OptRow> {
        val e = evDate ?: return emptyList()
        val now = LocalDateTime.now().plusMinutes(1)
        val today = LocalDate.now()
        val rows = mutableListOf<OptRow>()
        fun at(d: LocalDate, h: Int) = LocalDateTime.of(d, LocalTime.of(h, 0))

        if (e == today) {
            // 오늘 일: 일 시작 전까지
            val limit = when (evPart) {
                "오전" -> 12
                "오후" -> 18
                else -> 24
            }
            val n = LocalDateTime.now()
            val soon = listOf(
                Opt("30분 뒤", n.plusMinutes(30).withSecond(0).withNano(0)),
                Opt("1시간 뒤", n.plusHours(1).withSecond(0).withNano(0)),
                Opt("2시간 뒤", n.plusHours(2).withSecond(0).withNano(0)),
            ).filter { it.at.toLocalDate() == today && it.at.hour < limit }
            rows += OptRow("곧", soon)
            val hours = ((n.hour + 1) until minOf(limit, 24)).map { h ->
                Opt(Fmt.kor(LocalTime.of(h, 0)), at(today, h))
            }
            hours.chunked(3).forEachIndexed { i, ch -> rows += OptRow(if (i == 0) "오늘 정각" else "", ch) }
        } else {
            val d1 = e.minusDays(1)
            val prev = if (d1 == today) "오늘" else "전날"
            rows += OptRow("$prev 저녁 · ${Fmt.date(d1)}", listOf(
                Opt("8시", at(d1, 20)), Opt("9시", at(d1, 21), star = true), Opt("10시", at(d1, 22)), Opt("11시", at(d1, 23))
            ))
            rows += OptRow("$prev 오후", listOf(Opt("1시", at(d1, 13)), Opt("3시", at(d1, 15)), Opt("5시", at(d1, 17))))
            rows += OptRow("$prev 오전", listOf(Opt("7시", at(d1, 7)), Opt("9시", at(d1, 9)), Opt("11시", at(d1, 11))))
            val morning = when (evPart) {
                "오전" -> listOf(5, 6, 7)
                else -> listOf(6, 7, 8)
            }
            rows += OptRow("당일 아침 · ${Fmt.date(e)}", morning.map { Opt("${it}시", at(e, it)) })
            val ahead = listOf(2 to "2일 전", 3 to "3일 전", 7 to "1주 전")
                .filter { e.minusDays(it.first.toLong()).isAfter(today) || e.minusDays(it.first.toLong()) == today }
                .map { Opt(it.second, at(e.minusDays(it.first.toLong()), 21)) }
            rows += OptRow("더 미리 (밤 9시에)", ahead)
        }
        return rows
            .map { r -> r.copy(opts = r.opts.filter { it.at.isAfter(now) }) }
            .filter { it.opts.isNotEmpty() }
    }

    private fun renderStep2() {
        val e = evDate ?: run { step = 1; render(); return }
        val rel = Fmt.rel(e)
        val part = if (evPart.isNotEmpty()) " $evPart" else ""
        question("언제 알려드릴까요?", "할 일: ${if (rel.isNotEmpty()) "$rel " else ""}${Fmt.dateLong(e)}$part")

        val list = col()
        for (r in options()) {
            if (r.title.isNotEmpty()) {
                list.addView(tv(r.title, 17f, SUB, true).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 10, 0, 4))
            }
            val rr = row()
            r.opts.forEachIndexed { i, o ->
                val b = col().apply {
                    gravity = Gravity.CENTER
                    background = if (o.star) box(STAR_BG, STAR, 14) else box(Color.WHITE, LINE, 14)
                    setOnClickListener { remindAt = o.at; step = 3; render() }
                }
                b.addView(tv(o.label, 22f, INK, true))
                if (o.star) b.addView(tv("⭐ 추천", 12f, STAR, true))
                rr.addView(b, lp(0, dp(64), 1f, if (i == 0) 0 else 4, 0, if (i == r.opts.size - 1) 0 else 4))
            }
            // 버튼 수가 적은 줄도 크기를 맞춤
            for (k in r.opts.size until 4) if (r.opts.size >= 2) rr.addView(View(this), lp(0, dp(64), 1f, 4))
            list.addView(rr, lp(MATCH_PARENT, WRAP_CONTENT))
        }
        list.addView(tv("⏰  다른 시각으로 직접 정하기", 18f, ACCENT, true).apply {
            background = box(ACCENT_BG, 0, 14)
            setOnClickListener { pickCustom() }
        }, lp(MATCH_PARENT, dp(58), 0f, 0, 16, 0, 8))
        content.addView(ScrollView(this).apply { addView(list) }, lp(MATCH_PARENT, 0, 1f))
    }

    private fun pickCustom() {
        val e = evDate ?: return
        val start = if (e.isAfter(LocalDate.now())) e.minusDays(1) else e
        DatePickerDialog(this, { _, y, m, d ->
            val day = LocalDate.of(y, m + 1, d)
            TimePickerDialog(this, { _, h, mi ->
                val x = LocalDateTime.of(day, LocalTime.of(h, mi))
                if (!x.isAfter(LocalDateTime.now())) {
                    Toast.makeText(this, "이미 지난 시각이에요", Toast.LENGTH_SHORT).show()
                } else {
                    remindAt = x; step = 3; render()
                }
            }, 21, 0, false).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    // ---------- 3단계: 어떤 일? ----------
    private fun renderStep3() {
        val e = evDate ?: run { step = 1; render(); return }
        val r = remindAt ?: run { step = 2; render(); return }
        val rel = Fmt.rel(r.toLocalDate())
        question("어떤 일이에요?", "${if (rel.isNotEmpty()) "$rel " else ""}${Fmt.date(r.toLocalDate())} ${Fmt.kor(r.toLocalTime())}에 알려드려요")

        // 메모 (선택) + 말로 하기
        val mr = row()
        val m = EditText(this).apply {
            hint = "메모 (안 적어도 돼요)"
            setText(memoText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(INK)
            setHintTextColor(SUB)
            background = box(Color.WHITE, LINE, 14)
            setPadding(dp(14), 0, dp(14), 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }
        memo = m
        mr.addView(m, lp(0, MATCH_PARENT, 1f))
        mr.addView(tv("🎤 말로", 17f, Color.WHITE, true).apply {
            background = box(ACCENT, 0, 14)
            setOnClickListener { startVoice() }
        }, lp(dp(96), MATCH_PARENT, 0f, 8))
        content.addView(mr, lp(MATCH_PARENT, dp(58), 0f, 0, 0, 0, 10))

        // 큰 타일 2칸 × 4줄
        val cats = Cats.all
        for (i in cats.indices step 2) {
            val rr = row()
            for (j in 0..1) {
                val c = cats.getOrNull(i + j) ?: continue
                val t = col().apply {
                    gravity = Gravity.CENTER
                    background = box(c.color, 0, 18)
                    setOnClickListener { save(c) }
                }
                t.addView(tv(c.emoji, 34f))
                t.addView(tv(c.label, 21f, Color.WHITE, true))
                rr.addView(t, lp(0, MATCH_PARENT, 1f, if (j == 0) 0 else 5, 0, if (j == 0) 5 else 0))
            }
            content.addView(rr, lp(MATCH_PARENT, 0, 1f, 0, 5, 0, 5))
        }
        if (e.isBefore(LocalDate.now())) { reset(); render() }
    }

    private val VOICE = 77

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "메모를 말씀해 주세요")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, VOICE)
        } catch (_: Exception) {
            Toast.makeText(this, "키보드의 🎤 마이크 버튼을 눌러 말씀해 주세요", Toast.LENGTH_LONG).show()
            memo?.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(memo, 0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            memoText = said
            memo?.setText(said)
        }
    }

    // ---------- 저장 ----------
    private fun save(cat: Cat) {
        if (saving) return
        val e = evDate ?: return
        val r = remindAt ?: return
        val ms = Fmt.toMs(r)
        if (ms <= System.currentTimeMillis() + 10_000L) {
            Toast.makeText(this, "알림 시각이 이미 지났어요. 다시 골라주세요", Toast.LENGTH_LONG).show()
            step = 2; render()
            return
        }
        saving = true
        val text = (memo?.text?.toString() ?: memoText).trim()
        val a = Alarm(AlarmStore.nextId(this), ms, cat.key, text, System.currentTimeMillis(), e.toEpochDay(), evPart)
        AlarmStore.put(this, a)
        AlarmScheduler.schedule(this, a)
        hideKeyboard()
        showSaved(a, cat)
    }

    /** 크게 "저장되었습니다!!" — 2.5초 뒤 앱 닫힘, 화면을 누르면 바로 닫힘 */
    private fun showSaved(a: Alarm, cat: Cat) {
        val ring = Fmt.ldt(a.time)
        val ev = a.eventDate()!!
        val card = col().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = box(Color.WHITE, 0, 28)
            setPadding(dp(22), dp(30), dp(22), dp(28))
        }
        card.addView(tv("✓", 54f, Color.WHITE, true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
        }, LinearLayout.LayoutParams(dp(100), dp(100)))
        card.addView(tv("저장되었습니다!!", 32f, INK, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 18))
        val part = if (a.part.isNotEmpty()) " ${a.part}" else ""
        card.addView(tv("${cat.emoji} ${Fmt.dateLong(ev)}$part\n${cat.label}", 20f, cat.color, true).apply {
            setLineSpacing(0f, 1.15f)
        }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 14))
        val rel = Fmt.rel(ring.toLocalDate())
        card.addView(tv("🔔 ${if (rel.isNotEmpty()) "$rel " else ""}${Fmt.dateLong(ring.toLocalDate())}\n${Fmt.kor(ring.toLocalTime())}에 알려드릴게요", 21f, ACCENT, true).apply {
            setLineSpacing(0f, 1.15f)
        }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 14))
        if (a.memo.isNotBlank()) {
            card.addView(tv("메모: ${a.memo}", 17f, SUB).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 10))
        }

        overlay.removeAllViews()
        overlay.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(22), 0, dp(22), 0)
        })
        overlay.setOnClickListener { finish() }
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(150).start()
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        ui.postDelayed({ if (!isFinishing) finish() }, 2500)
    }

    private fun hideKeyboard() {
        val m = memo ?: return
        m.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(m.windowToken, 0)
    }

    // ---------- 예정 목록 ----------
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
                background = box(if (isToday) TODAY_BG else Color.WHITE, if (isToday) RED else LINE, 14)
                setPadding(dp(10), dp(12), dp(12), dp(12))
                setOnClickListener { confirmDelete(a) }
                if (past) alpha = 0.5f
            }
            // 왼쪽: 언제 울리나
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

            // 오른쪽: 무슨 일
            val right = col()
            right.addView(tv("${c.emoji} ${c.label}" + if (past) "  (지남)" else "", 19f, c.color, true).apply {
                gravity = Gravity.START
            }, lp(MATCH_PARENT, WRAP_CONTENT))
            a.eventDate()?.let { ev ->
                val p = if (a.part.isNotEmpty()) " ${a.part}" else ""
                right.addView(tv("할 일: ${Fmt.date(ev)}$p", 15f, SUB).apply { gravity = Gravity.START },
                    lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 2))
            }
            if (a.memo.isNotBlank()) {
                right.addView(tv(a.memo, 16f, INK).apply {
                    gravity = Gravity.START
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 2))
            }
            r.addView(right, lp(0, WRAP_CONTENT, 1f, 8))
            c0.addView(r, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 4, 0, 4))
        }
        c0.addView(tv("닫기", 18f, ACCENT, true).apply {
            setOnClickListener { listDialog?.dismiss() }
        }, lp(MATCH_PARENT, dp(52), 0f, 0, 8))

        val scroll = ScrollView(this).apply { addView(c0) }
        val dlg = Dialog(this)
        dlg.setContentView(scroll)
        dlg.window?.setBackgroundDrawable(box(BG, 0, 20))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), WRAP_CONTENT)
        listDialog = dlg
    }

    private fun confirmDelete(a: Alarm) {
        val c = Cats.of(a.cat)
        val memoLine = if (a.memo.isBlank()) "" else "\n${a.memo}"
        AlertDialog.Builder(this)
            .setMessage("${Fmt.dateTime(a.time)}\n${c.emoji} ${c.label}$memoLine\n\n이 알림을 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                AlarmScheduler.cancel(this, a.id)
                AlarmStore.remove(this, a.id)
                render()
                showList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------- 업데이트 ----------
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

    // ---------- 권한 ----------
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
        checkPermissions()
    }
}
