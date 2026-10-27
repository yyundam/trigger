package com.damhee.trigger

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
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
import android.provider.Settings
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
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
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
                if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.3f) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - sx
                if (abs(dx) > width * 0.12f && abs(dx) > abs(e.y - sy)) onSwipe(if (dx < 0) 1 else -1)
            }
        }
        return true
    }
}

/**
 * 한 화면: 날짜(오늘·내일·모레·토·일 + 큰 달력) → 시각 → (메모 선택) → 분류 버튼을 누르면 바로 저장.
 */
class MainActivity : Activity() {
    private val INK = 0xFF1F2328.toInt()
    private val SUB = 0xFF8A8F98.toInt()
    private val FAINT = 0xFFC9CDD3.toInt()
    private val LINE = 0xFFE3E5E8.toInt()
    private val ACCENT = 0xFF2563EB.toInt()
    private val BG = 0xFFF5F5F2.toInt()
    private val RED = 0xFFD93025.toInt()
    private val BLUE = 0xFF2563EB.toInt()
    private val GREEN = 0xFF16A34A.toInt()
    private val TODAY_BG = 0xFFFFF1F0.toInt()

    private var selDate: LocalDate? = null
    private var selTime: LocalTime = LocalTime.of(22, 0)
    private var month: YearMonth = YearMonth.now()
    private var askedNotif = false
    private var saving = false

    private val ui = Handler(Looper.getMainLooper())
    private var newVersion = 0
    private var downloadId = -1L

    private lateinit var banner: TextView
    private lateinit var updateBar: TextView
    private lateinit var listBtn: TextView
    private lateinit var quickRow: LinearLayout
    private lateinit var monthTitle: TextView
    private lateinit var prevBtn: TextView
    private lateinit var grid: LinearLayout
    private lateinit var timeRow: LinearLayout
    private lateinit var memo: EditText
    private lateinit var summary: TextView
    private lateinit var overlay: FrameLayout

    private val presets = listOf(
        LocalTime.of(7, 0), LocalTime.of(9, 0), LocalTime.of(18, 0), LocalTime.of(22, 0)
    )

    // ---------- 작은 도우미 ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int = 0, radius: Int = 12) = GradientDrawable().apply {
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

    /** 오늘 / 내일 / 모레 / 그 외엔 날짜 */
    private fun relDay(d: LocalDate): String = when (ChronoUnit.DAYS.between(LocalDate.now(), d)) {
        0L -> "오늘"
        1L -> "내일"
        2L -> "모레"
        else -> ""
    }

    // ---------- 화면 만들기 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        Notif.ensureChannels(this)
        AlarmStore.prune(this)
        selTime = AlarmStore.lastTime(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        // 맨 위: 이름 + 예정된 알람 목록
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv("⚡ 트리거", 20f, INK, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
            lp(0, dp(40), 1f))
        listBtn = tv("예정 0 ›", 15f, ACCENT, true).apply {
            background = box(Color.WHITE, LINE, 20)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { showList() }
        }
        header.addView(listBtn, lp(-2, dp(36)))
        root.addView(header)

        banner = tv("", 14f, Color.WHITE, true).apply {
            background = box(RED, 0, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        root.addView(banner, lp(MATCH_PARENT, -2, 0f, 0, 4))

        updateBar = tv("", 15f, Color.WHITE, true).apply {
            background = box(GREEN, 0, 10)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            visibility = View.GONE
            setOnClickListener { onUpdateTap() }
        }
        root.addView(updateBar, lp(MATCH_PARENT, -2, 0f, 0, 4))

        // ① 날짜 빠른 버튼
        quickRow = row()
        root.addView(quickRow, lp(MATCH_PARENT, dp(58), 0f, 0, 8))

        // ① 큰 달력 (좌우로 밀면 달이 넘어감)
        val cal = SwipeLayout(this) { dir -> swipeMonth(dir) }.apply {
            orientation = LinearLayout.VERTICAL
            background = box(Color.WHITE, 0, 16)
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val mh = row().apply { gravity = Gravity.CENTER_VERTICAL }
        prevBtn = tv("‹", 28f, INK).apply { setOnClickListener { swipeMonth(-1) } }
        monthTitle = tv("", 17f, INK, true)
        val nextBtn = tv("›", 28f, INK).apply { setOnClickListener { swipeMonth(1) } }
        mh.addView(prevBtn, lp(dp(56), MATCH_PARENT))
        mh.addView(monthTitle, lp(0, MATCH_PARENT, 1f))
        mh.addView(nextBtn, lp(dp(56), MATCH_PARENT))
        cal.addView(mh, lp(MATCH_PARENT, dp(38)))
        val wk = row()
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, s ->
            wk.addView(tv(s, 12f, when (i) { 0 -> RED; 6 -> BLUE; else -> SUB }), lp(0, MATCH_PARENT, 1f))
        }
        cal.addView(wk, lp(MATCH_PARENT, dp(20)))
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cal.addView(grid, lp(MATCH_PARENT, 0, 1f))
        root.addView(cal, lp(MATCH_PARENT, 0, 1f, 0, 8))

        // ② 시각
        timeRow = row()
        root.addView(timeRow, lp(MATCH_PARENT, dp(46), 0f, 0, 8))

        // 메모 (선택)
        memo = EditText(this).apply {
            hint = "메모 (선택) — 안 적어도 됨"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(INK)
            setHintTextColor(SUB)
            background = box(Color.WHITE, LINE, 12)
            setPadding(dp(14), 0, dp(14), 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }
        root.addView(memo, lp(MATCH_PARENT, dp(48), 0f, 0, 8))

        summary = tv("", 14f, SUB)
        root.addView(summary, lp(MATCH_PARENT, dp(26), 0f, 0, 4))

        // ③ 분류 버튼 = 저장
        for (r in 0 until 2) {
            val rr = row()
            for (c in 0 until 3) {
                val cat = Cats.all[r * 3 + c]
                val b = tv("${cat.emoji}  ${cat.label}", 18f, Color.WHITE, true).apply {
                    background = box(cat.color, 0, 14)
                    setOnClickListener { save(cat) }
                }
                rr.addView(b, lp(0, MATCH_PARENT, 1f, if (c == 0) 0 else 4, 0, if (c == 2) 0 else 4))
            }
            root.addView(rr, lp(MATCH_PARENT, dp(60), 0f, 0, if (r == 0) 2 else 8))
        }

        // 저장 확인 화면 (평소엔 숨김)
        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frame)
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        // 날짜가 바뀌었을 수 있으니 매번 새로 그림
        if (selDate?.isBefore(LocalDate.now()) == true) selDate = null
        if (month.isBefore(YearMonth.now())) month = YearMonth.now()
        renderAll()
        refreshCount()
        checkPermissions()
        Updater.check(this) { v -> runOnUiThread { showUpdate(v) } }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun renderAll() {
        renderQuick()
        renderCalendar()
        renderTimes()
        renderSummary()
    }

    // ---------- 날짜 ----------
    private fun quickDates(): List<Pair<String, LocalDate>> {
        val t = LocalDate.now()
        val base = listOf("오늘" to t, "내일" to t.plusDays(1), "모레" to t.plusDays(2))
        fun weekend(day: DayOfWeek, name: String): Pair<String, LocalDate> {
            val d = t.with(TemporalAdjusters.nextOrSame(day))
            return if (d.isAfter(t.plusDays(2))) name to d else "다음$name" to d.plusWeeks(1)
        }
        val ends = listOf(weekend(DayOfWeek.SATURDAY, "토"), weekend(DayOfWeek.SUNDAY, "일"))
            .sortedBy { it.second }
        return base + ends
    }

    private fun renderQuick() {
        quickRow.removeAllViews()
        quickDates().forEachIndexed { i, (name, d) ->
            val on = d == selDate
            val color = when (d.dayOfWeek) {
                DayOfWeek.SUNDAY -> RED
                DayOfWeek.SATURDAY -> BLUE
                else -> INK
            }
            val b = tv("$name\n${d.monthValue}/${d.dayOfMonth}", 15f, if (on) Color.WHITE else color, true).apply {
                background = box(if (on) ACCENT else Color.WHITE, if (on) 0 else LINE, 14)
                setLineSpacing(0f, 0.95f)
                setOnClickListener { select(d) }
            }
            quickRow.addView(b, lp(0, MATCH_PARENT, 1f, if (i == 0) 0 else 3, 0, if (i == 4) 0 else 3))
        }
    }

    /** 달 넘기기 (버튼·스와이프 공용), 넘어가는 방향으로 살짝 밀려 들어오는 효과 */
    private fun swipeMonth(dir: Int) {
        val n = month.plusMonths(dir.toLong())
        if (n.isBefore(YearMonth.now())) {
            grid.animate().cancel()
            grid.translationX = dp(18).toFloat()
            grid.animate().translationX(0f).setDuration(160).start()
            return
        }
        month = n
        renderCalendar()
        grid.animate().cancel()
        grid.translationX = dir * grid.width * 0.35f
        grid.alpha = 0.2f
        grid.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    private fun renderCalendar() {
        grid.removeAllViews()
        monthTitle.text = "${month.year}년 ${month.monthValue}월"
        prevBtn.setTextColor(if (month == YearMonth.now()) FAINT else INK)
        val today = LocalDate.now()
        val offset = month.atDay(1).dayOfWeek.value % 7 // 일요일 = 0
        val days = month.lengthOfMonth()
        val rows = (offset + days + 6) / 7
        for (r in 0 until rows) {
            val rr = row()
            for (c in 0 until 7) {
                val day = r * 7 + c - offset + 1
                val cell = tv("", 18f)
                if (day in 1..days) {
                    val d = month.atDay(day)
                    val past = d.isBefore(today)
                    val on = d == selDate
                    cell.text = day.toString()
                    cell.setTextColor(
                        when {
                            on -> Color.WHITE
                            past -> FAINT
                            c == 0 -> RED
                            c == 6 -> BLUE
                            else -> INK
                        }
                    )
                    if (on) cell.typeface = Typeface.DEFAULT_BOLD
                    cell.background = when {
                        on -> box(ACCENT, 0, 12)
                        d == today -> box(Color.TRANSPARENT, ACCENT, 12)
                        else -> null
                    }
                    if (!past) cell.setOnClickListener { select(d) }
                }
                rr.addView(cell, lp(0, MATCH_PARENT, 1f, 2, 2, 2, 2))
            }
            grid.addView(rr, lp(MATCH_PARENT, 0, 1f))
        }
    }

    private fun select(d: LocalDate) {
        selDate = d
        month = YearMonth.from(d)
        renderQuick()
        renderCalendar()
        renderSummary()
    }

    // ---------- 시각 ----------
    private fun renderTimes() {
        timeRow.removeAllViews()
        val custom = selTime !in presets
        presets.forEachIndexed { i, t ->
            val on = t == selTime
            val b = tv(Fmt.time(t), 16f, if (on) Color.WHITE else INK, true).apply {
                background = box(if (on) INK else Color.WHITE, if (on) 0 else LINE, 12)
                setOnClickListener { setTime(t) }
            }
            timeRow.addView(b, lp(0, MATCH_PARENT, 1f, if (i == 0) 0 else 3, 0, 3))
        }
        val cb = tv(if (custom) "⏰ ${Fmt.time(selTime)}" else "⏰ 직접", 16f, if (custom) Color.WHITE else INK, true).apply {
            background = box(if (custom) INK else Color.WHITE, if (custom) 0 else LINE, 12)
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m -> setTime(LocalTime.of(h, m)) },
                    selTime.hour, selTime.minute, true).show()
            }
        }
        timeRow.addView(cb, lp(0, MATCH_PARENT, 1.25f, 3, 0, 0))
    }

    private fun setTime(t: LocalTime) {
        selTime = t
        AlarmStore.setLastTime(this, t)
        renderTimes()
        renderSummary()
    }

    private fun renderSummary() {
        val d = selDate
        if (d == null) {
            summary.text = "날짜 → 시각 → 아래 버튼 누르면 저장"
            summary.setTextColor(SUB)
            return
        }
        val past = Fmt.toMs(d, selTime) <= System.currentTimeMillis()
        summary.text = "${Fmt.date(d)} ${Fmt.time(selTime)}에 울림" + if (past) " — 이미 지난 시각" else ""
        summary.setTextColor(if (past) RED else ACCENT)
    }

    // ---------- 저장 ----------
    private fun save(cat: Cat) {
        if (saving) return
        val d = selDate
        if (d == null) {
            Toast.makeText(this, "날짜를 먼저 눌러주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val ms = Fmt.toMs(d, selTime)
        if (ms <= System.currentTimeMillis() + 10_000L) {
            Toast.makeText(this, "이미 지난 시각이에요", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        val a = Alarm(AlarmStore.nextId(this), ms, cat.key, memo.text.toString().trim(), System.currentTimeMillis())
        AlarmStore.put(this, a)
        AlarmScheduler.schedule(this, a)
        hideKeyboard()
        showSaved(a, cat)
    }

    /** 크게 "저장되었습니다!!" 를 1.8초 보여주고 앱 닫기. 화면을 누르면 바로 닫힘 */
    private fun showSaved(a: Alarm, cat: Cat) {
        val x = Fmt.ldt(a.time)
        val rel = relDay(x.toLocalDate())

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = box(Color.WHITE, 0, 28)
            setPadding(dp(24), dp(30), dp(24), dp(28))
        }
        card.addView(tv("✓", 52f, Color.WHITE, true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
        }, LinearLayout.LayoutParams(dp(96), dp(96)))
        card.addView(tv("저장되었습니다!!", 30f, INK, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 18))
        val whenTxt = (if (rel.isEmpty()) "" else "$rel ") + "${Fmt.date(x.toLocalDate())}  ${Fmt.time(x.toLocalTime())}"
        card.addView(tv(whenTxt, 21f, ACCENT, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 12))
        card.addView(tv("${cat.emoji} ${cat.label}", 21f, cat.color, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 6))
        if (a.memo.isNotBlank()) {
            card.addView(tv(a.memo, 17f, SUB).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 6))
        }

        overlay.removeAllViews()
        overlay.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(28), 0, dp(28), 0)
        })
        overlay.setOnClickListener { finish() }
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(150).start()
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        ui.postDelayed({ if (!isFinishing) finish() }, 1800)
    }

    private fun hideKeyboard() {
        memo.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(memo.windowToken, 0)
    }

    // ---------- 예정 목록 ----------
    private fun refreshCount() {
        val now = System.currentTimeMillis()
        val n = AlarmStore.load(this).count { it.time > now }
        listBtn.text = "예정 $n ›"
    }

    private var listDialog: Dialog? = null

    private fun showList() {
        listDialog?.dismiss()
        val list = AlarmStore.load(this).sortedBy { it.time }
        if (list.isEmpty()) {
            Toast.makeText(this, "예정된 알람이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val today = LocalDate.now()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(8))
        }
        col.addView(tv("예정된 알람", 19f, INK, true).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT))
        col.addView(tv("누르면 지울 수 있어요", 13f, SUB).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 2, 0, 8))

        for (a in list) {
            val x = Fmt.ldt(a.time)
            val d = x.toLocalDate()
            val isToday = d == today
            val past = a.time <= now
            val c = Cats.of(a.cat)
            val rel = relDay(d)

            val r = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = box(if (isToday) TODAY_BG else Color.WHITE, if (isToday) RED else LINE, 12)
                setPadding(dp(10), dp(10), dp(12), dp(10))
                setOnClickListener { confirmDelete(a) }
                if (past) alpha = 0.5f
            }
            // 왼쪽: 오늘/내일 + 날짜 + 시각
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            if (isToday) {
                left.addView(tv("오늘", 15f, Color.WHITE, true).apply {
                    background = box(RED, 0, 8)
                    setPadding(dp(10), dp(1), dp(10), dp(2))
                }, lp(WRAP_CONTENT, WRAP_CONTENT))
            } else if (rel.isNotEmpty()) {
                left.addView(tv(rel, 15f, ACCENT, true), lp(WRAP_CONTENT, WRAP_CONTENT))
            }
            left.addView(tv(Fmt.date(d), 13f, if (isToday) RED else SUB, isToday), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 2))
            left.addView(tv(Fmt.time(x.toLocalTime()), 20f, if (isToday) RED else INK, true), lp(WRAP_CONTENT, WRAP_CONTENT))
            r.addView(left, lp(dp(92), WRAP_CONTENT))

            // 오른쪽: 분류 + 메모
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(tv("${c.emoji} ${c.label}" + if (past) "  (지남)" else "", 17f, c.color, true).apply {
                gravity = Gravity.START
            }, lp(MATCH_PARENT, WRAP_CONTENT))
            if (a.memo.isNotBlank()) {
                right.addView(tv(a.memo, 15f, INK).apply {
                    gravity = Gravity.START
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 2))
            }
            r.addView(right, lp(0, WRAP_CONTENT, 1f, 8))
            col.addView(r, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 4, 0, 4))
        }
        col.addView(tv("닫기", 16f, ACCENT, true).apply {
            setOnClickListener { listDialog?.dismiss() }
        }, lp(MATCH_PARENT, dp(48), 0f, 0, 8))

        val scroll = ScrollView(this).apply { addView(col) }
        val dlg = Dialog(this)
        dlg.setContentView(scroll)
        dlg.window?.setBackgroundDrawable(box(BG, 0, 20))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WRAP_CONTENT)
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WRAP_CONTENT)
        listDialog = dlg
    }

    private fun confirmDelete(a: Alarm) {
        val c = Cats.of(a.cat)
        val memoLine = if (a.memo.isBlank()) "" else "\n${a.memo}"
        AlertDialog.Builder(this)
            .setMessage("${Fmt.dateTime(a.time)}  ${c.emoji} ${c.label}$memoLine\n\n이 알람을 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                AlarmScheduler.cancel(this, a.id)
                AlarmStore.remove(this, a.id)
                refreshCount()
                showList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------- 업데이트 ----------
    private fun showUpdate(v: Int) {
        if (isFinishing || downloadId >= 0) return
        newVersion = v
        updateBar.text = "🔄 새 버전(v$v)이 있어요 — 눌러서 업데이트"
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
