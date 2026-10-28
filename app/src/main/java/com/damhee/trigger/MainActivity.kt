package com.damhee.trigger

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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

/**
 * 2단계:
 *  ① 언제 알려줄까?  — 날짜 박스 → (밝아지는) 시각 박스. 시각을 누르면 ②로
 *  ② 무슨 일 알려줄까? — 🎙 음성메모(말하면 글자로) + 큰 타일. 타일을 누르면 저장
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
    private val STAR_BG = 0xFFFFF4D6.toInt()
    private val STAR = 0xFFB45309.toInt()

    private var step = 1
    private var selDate: LocalDate? = null
    private var remindAt: LocalDateTime? = null
    private var memoText = ""

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

    private fun title(s: String) {
        content.addView(tv(s, 28f, INK, true).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 2, 0, 10))
    }

    private fun dayColor(d: LocalDate) = when (d.dayOfWeek) {
        DayOfWeek.SUNDAY -> RED
        DayOfWeek.SATURDAY -> BLUE
        else -> INK
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
            if (selDate?.isBefore(LocalDate.now()) == true) { selDate = null; step = 1 }
            val r = remindAt
            if (step == 2 && r != null && !r.isAfter(LocalDateTime.now())) step = 1
            render()
        }
        checkPermissions()
        Updater.check(this) { v -> runOnUiThread { showUpdate(v) } }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun render() {
        renderTop()
        content.removeAllViews()
        if (step == 1) renderStep1() else renderStep2()
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
            step = 1
            render()
        } else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    // ---------- ① 언제 알려줄까? ----------
    private fun quickDates(): List<Pair<String, LocalDate>> {
        val t = LocalDate.now()
        val base = listOf("오늘" to t, "내일" to t.plusDays(1), "모레" to t.plusDays(2))
        fun weekend(day: DayOfWeek, name: String): Pair<String, LocalDate> {
            val d = t.with(TemporalAdjusters.nextOrSame(day))
            return if (d.isAfter(t.plusDays(2))) name to d else "다음 $name" to d.plusWeeks(1)
        }
        val ends = listOf(weekend(DayOfWeek.SATURDAY, "토요일"), weekend(DayOfWeek.SUNDAY, "일요일"))
            .sortedBy { it.second }
        return base + ends
    }

    private fun renderStep1() {
        title("언제 알려줄까?")

        // ── 날짜 박스 ──
        val dateBox = col().apply {
            background = box(CARD, if (selDate == null) ACCENT else LINE, 20, if (selDate == null) 3 else 2)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        dateBox.addView(tv("① 날짜", 17f, ACCENT, true).apply { gravity = Gravity.START }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 0, 0, 6))
        val q = quickDates()
        val custom = selDate != null && q.none { it.second == selDate }
        val r1 = row()
        val r2 = row()
        q.forEachIndexed { i, (name, d) ->
            val on = d == selDate
            val b = tv("$name\n${d.monthValue}/${d.dayOfMonth}", 19f, if (on) Color.WHITE else dayColor(d), true).apply {
                background = box(if (on) ACCENT else BTN, 0, 14)
                setLineSpacing(0f, 0.95f)
                setOnClickListener { selDate = d; render() }
            }
            val target = if (i < 3) r1 else r2
            target.addView(b, lp(0, MATCH_PARENT, 1f, 3, 0, 3))
        }
        val calLabel = if (custom) "📅 ${selDate!!.monthValue}/${selDate!!.dayOfMonth}(${Fmt.dow(selDate!!)})" else "📅 달력"
        r2.addView(tv(calLabel, 19f, if (custom) Color.WHITE else ACCENT, true).apply {
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
        val head = if (d == null) "② 시각  (날짜를 먼저 눌러주세요)"
        else "② 시각 — ${Fmt.rel(d).let { if (it.isEmpty()) "" else "$it " }}${Fmt.dateLong(d)}"
        timeBox.addView(tv(head, 17f, if (active) ACCENT else SUB, true).apply { gravity = Gravity.START },
            lp(MATCH_PARENT, WRAP_CONTENT, 0f, 4, 0, 0, 6))

        val last = AlarmStore.lastTime(this)
        val now = LocalDateTime.now()
        val rows = listOf(
            "아침" to listOf(6, 7, 8),
            "오후" to listOf(12, 15, 17),
            "저녁" to listOf(20, 21, 22, 23),
        )
        for ((label, hours) in rows) {
            val rr = row().apply { gravity = Gravity.CENTER_VERTICAL }
            rr.addView(tv(label, 17f, SUB, true), lp(dp(48), MATCH_PARENT))
            for (h in hours) {
                val t = LocalTime.of(h, 0)
                val past = d != null && !LocalDateTime.of(d, t).isAfter(now)
                val star = t == last && !past
                val txt = "${if (h > 12) h - 12 else h}시"
                val b = col().apply {
                    gravity = Gravity.CENTER
                    background = when {
                        past -> box(0xFFF0F0F0.toInt(), 0, 14)
                        star -> box(STAR_BG, STAR, 14)
                        else -> box(BTN, 0, 14)
                    }
                    if (active && !past) setOnClickListener { pickTime(d!!, t) }
                }
                b.addView(tv(txt, 22f, if (past) FAINT else INK, true))
                if (star) b.addView(tv("⭐ 지난번", 11f, STAR, true))
                rr.addView(b, lp(0, MATCH_PARENT, 1f, 3, 0, 3))
            }
            for (k in hours.size until 4) rr.addView(View(this), lp(0, MATCH_PARENT, 1f, 3, 0, 3))
            timeBox.addView(rr, lp(MATCH_PARENT, 0, 1f, 0, 4, 0, 4))
        }
        timeBox.addView(tv("⏰ 다른 시각 직접 정하기", 18f, ACCENT, true).apply {
            background = box(ACCENT_BG, 0, 14)
            if (active) setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    val x = LocalDateTime.of(d!!, LocalTime.of(h, m))
                    if (!x.isAfter(LocalDateTime.now())) Toast.makeText(this@MainActivity, "이미 지난 시각이에요", Toast.LENGTH_SHORT).show()
                    else pickTime(d, LocalTime.of(h, m))
                }, last.hour, last.minute, false).show()
            }
        }, lp(MATCH_PARENT, dp(54), 0f, 0, 6))
        content.addView(timeBox, lp(MATCH_PARENT, 0, 1f, 0, 10))
    }

    private fun pickTime(d: LocalDate, t: LocalTime) {
        remindAt = LocalDateTime.of(d, t)
        AlarmStore.setLastTime(this, t)
        step = 2
        render()
    }

    /** 큰 달력 (◀ ▶ 로 달 넘기기) */
    private fun showCalendar() {
        var month = YearMonth.from(selDate ?: LocalDate.now())
        if (month.isBefore(YearMonth.now())) month = YearMonth.now()
        val dlg = Dialog(this)
        val c0 = col().apply { setPadding(dp(10), dp(12), dp(10), dp(10)) }

        fun draw() {
            c0.removeAllViews()
            val today = LocalDate.now()
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
            for (r in 0 until 6) {
                val rr = row()
                for (c in 0 until 7) {
                    val day = r * 7 + c - offset + 1
                    val cell = tv("", 21f, INK, true)
                    if (day in 1..days) {
                        val d = month.atDay(day)
                        val past = d.isBefore(today)
                        cell.text = day.toString()
                        cell.setTextColor(when { past -> FAINT; c == 0 -> RED; c == 6 -> BLUE; else -> INK })
                        if (past) cell.typeface = Typeface.DEFAULT
                        cell.background = when {
                            d == selDate -> box(ACCENT, 0, 12).also { cell.setTextColor(Color.WHITE) }
                            d == today -> box(Color.TRANSPARENT, ACCENT, 12)
                            else -> null
                        }
                        if (!past) cell.setOnClickListener { selDate = d; dlg.dismiss(); render() }
                    }
                    rr.addView(cell, lp(0, MATCH_PARENT, 1f, 2, 2, 2, 2))
                }
                c0.addView(rr, lp(MATCH_PARENT, dp(52)))
            }
            c0.addView(tv("닫기", 18f, ACCENT, true).apply { setOnClickListener { dlg.dismiss() } },
                lp(MATCH_PARENT, dp(50), 0f, 0, 6))
        }
        draw()
        dlg.setContentView(c0)
        dlg.window?.setBackgroundDrawable(box(CARD, 0, 22))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), WRAP_CONTENT)
    }

    // ---------- ② 무슨 일 알려줄까? ----------
    private fun renderStep2() {
        val r = remindAt ?: run { step = 1; render(); return }
        title("무슨 일 알려줄까?")
        val rel = Fmt.rel(r.toLocalDate())
        content.addView(tv("🔔 ${if (rel.isNotEmpty()) "$rel " else ""}${Fmt.date(r.toLocalDate())} ${Fmt.kor(r.toLocalTime())}", 19f, ACCENT, true).apply {
            background = box(ACCENT_BG, 0, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, lp(MATCH_PARENT, WRAP_CONTENT, 0f, 0, 0, 0, 10))

        // 🎙 음성메모 (말하면 글자로)
        if (memoText.isBlank()) {
            content.addView(tv("🎙 음성메모", 21f, Color.WHITE, true).apply {
                background = box(ACCENT, 0, 16)
                setOnClickListener { startVoice() }
            }, lp(MATCH_PARENT, dp(64), 0f, 0, 0, 0, 10))
        } else {
            val mr = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = box(CARD, ACCENT, 16)
                setPadding(dp(14), dp(6), dp(8), dp(6))
            }
            mr.addView(tv("🎙 “$memoText”", 19f, INK, true).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, lp(0, MATCH_PARENT, 1f))
            mr.addView(tv("다시", 16f, ACCENT, true).apply {
                background = box(ACCENT_BG, 0, 12)
                setOnClickListener { startVoice() }
            }, lp(dp(58), dp(46), 0f, 6))
            mr.addView(tv("지우기", 16f, RED, true).apply {
                background = box(TODAY_BG, 0, 12)
                setOnClickListener { memoText = ""; render() }
            }, lp(dp(66), dp(46), 0f, 6))
            content.addView(mr, lp(MATCH_PARENT, dp(70), 0f, 0, 0, 0, 10))
        }

        // 큰 타일 2칸 × 4줄 — 누르면 저장
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
                t.addView(tv(c.emoji, 32f))
                t.addView(tv(c.label, 21f, Color.WHITE, true))
                rr.addView(t, lp(0, MATCH_PARENT, 1f, if (j == 0) 0 else 5, 0, if (j == 0) 5 else 0))
            }
            content.addView(rr, lp(MATCH_PARENT, 0, 1f, 0, 5, 0, 5))
        }
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
            Toast.makeText(this, "이 폰에서는 음성 인식을 쓸 수 없어요", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            memoText = said.trim()
            render()
        }
    }

    // ---------- 저장 ----------
    private fun save(cat: Cat) {
        if (saving) return
        val r = remindAt ?: return
        val ms = Fmt.toMs(r)
        if (ms <= System.currentTimeMillis() + 10_000L) {
            Toast.makeText(this, "알림 시각이 이미 지났어요. 다시 골라주세요", Toast.LENGTH_LONG).show()
            step = 1; render()
            return
        }
        saving = true
        val a = Alarm(AlarmStore.nextId(this), ms, cat.key, memoText, System.currentTimeMillis())
        AlarmStore.put(this, a)
        AlarmScheduler.schedule(this, a)
        showSaved(a, cat)
    }

    /** 크게 "저장되었습니다!!" 5초 + 소리로 읽어줌. [확인]을 누르면 바로 닫힘 */
    private fun showSaved(a: Alarm, cat: Cat) {
        val x = Fmt.ldt(a.time)
        val rel = Fmt.rel(x.toLocalDate())
        val whenTxt = "${if (rel.isNotEmpty()) "$rel " else ""}${Fmt.dateLong(x.toLocalDate())}"

        val card = col().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = box(CARD, 0, 28)
            setPadding(dp(22), dp(28), dp(22), dp(20))
        }
        card.addView(tv("✓", 54f, Color.WHITE, true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
        }, LinearLayout.LayoutParams(dp(100), dp(100)))
        card.addView(tv("저장되었습니다!!", 32f, INK, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 18))
        card.addView(tv("🔔 $whenTxt\n${Fmt.kor(x.toLocalTime())}에", 23f, ACCENT, true).apply {
            setLineSpacing(0f, 1.15f)
        }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 16))
        card.addView(tv("${cat.emoji} ${cat.label}", 25f, cat.color, true), lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 8))
        if (a.memo.isNotBlank()) {
            card.addView(tv("“${a.memo}”", 19f, INK).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }, lp(WRAP_CONTENT, WRAP_CONTENT, 0f, 0, 8))
        }
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

        // 소리로 읽어주기
        if (ttsReady) {
            val say = "$whenTxt ${Fmt.kor(x.toLocalTime())}에 ${cat.label} 알려드릴게요"
            tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "saved")
        }
        // 5초 카운트다운
        for (s in 4 downTo 1) ui.postDelayed({ ok.text = "확인 ($s)" }, (5 - s) * 1000L)
        ui.postDelayed({ if (!isFinishing) finish() }, 5000)
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
            right.addView(tv("${c.emoji} ${c.label}" + if (past) "  (지남)" else "", 19f, c.color, true).apply {
                gravity = Gravity.START
            }, lp(MATCH_PARENT, WRAP_CONTENT))
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

        val dlg = Dialog(this)
        dlg.setContentView(ScrollView(this).apply { addView(c0) })
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
