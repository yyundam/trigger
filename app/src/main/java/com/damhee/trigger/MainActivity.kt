package com.damhee.trigger

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

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

    private var selDate: LocalDate? = null
    private var selTime: LocalTime = LocalTime.of(22, 0)
    private var month: YearMonth = YearMonth.now()
    private var askedNotif = false

    private lateinit var banner: TextView
    private lateinit var listBtn: TextView
    private lateinit var quickRow: LinearLayout
    private lateinit var monthTitle: TextView
    private lateinit var prevBtn: TextView
    private lateinit var grid: LinearLayout
    private lateinit var timeRow: LinearLayout
    private lateinit var memo: EditText
    private lateinit var summary: TextView

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

        // ① 날짜 빠른 버튼
        quickRow = row()
        root.addView(quickRow, lp(MATCH_PARENT, dp(58), 0f, 0, 8))

        // ① 큰 달력
        val cal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Color.WHITE, 0, 16)
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val mh = row().apply { gravity = Gravity.CENTER_VERTICAL }
        prevBtn = tv("‹", 28f, INK).apply { setOnClickListener { moveMonth(-1) } }
        monthTitle = tv("", 17f, INK, true)
        val nextBtn = tv("›", 28f, INK).apply { setOnClickListener { moveMonth(1) } }
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

        setContentView(root)
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

    private fun moveMonth(delta: Long) {
        val n = month.plusMonths(delta)
        if (n.isBefore(YearMonth.now())) return
        month = n
        renderCalendar()
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
        val a = Alarm(AlarmStore.nextId(this), ms, cat.key, memo.text.toString().trim(), System.currentTimeMillis())
        AlarmStore.put(this, a)
        AlarmScheduler.schedule(this, a)
        hideKeyboard()
        Toast.makeText(this, "✓ ${Fmt.date(d)} ${Fmt.time(selTime)}  ${cat.emoji} ${cat.label}", Toast.LENGTH_LONG).show()
        finish()
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

    private fun showList() {
        val list = AlarmStore.load(this).sortedBy { it.time }
        if (list.isEmpty()) {
            Toast.makeText(this, "예정된 알람이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val items = list.map {
            val c = Cats.of(it.cat)
            val m = if (it.memo.isBlank()) "" else "  · ${it.memo}"
            val p = if (it.time <= now) "  (지남)" else ""
            "${Fmt.dateTime(it.time)}   ${c.emoji} ${c.label}$m$p"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("예정된 알람 (누르면 삭제)")
            .setItems(items) { _, i -> confirmDelete(list[i]) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun confirmDelete(a: Alarm) {
        val c = Cats.of(a.cat)
        AlertDialog.Builder(this)
            .setMessage("${Fmt.dateTime(a.time)}  ${c.emoji} ${c.label}\n이 알람을 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                AlarmScheduler.cancel(this, a.id)
                AlarmStore.remove(this, a.id)
                refreshCount()
                showList()
            }
            .setNegativeButton("취소", null)
            .show()
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
