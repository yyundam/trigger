package com.damhee.trigger

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 알람 화면: 분류를 크게, 메모가 있으면 그 아래에 그대로 */
class AlarmActivity : Activity() {
    private var alarmId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, sp: Float, bold: Boolean = false, alpha: Float = 1f) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(Color.WHITE)
            this.alpha = alpha
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun render(i: Intent) {
        alarmId = i.getIntExtra("id", -1)
        val a = AlarmStore.get(this, alarmId)
        if (a == null) {
            finish()
            return
        }
        val cat = Cats.of(a.cat)
        window.statusBarColor = cat.color
        window.navigationBarColor = cat.color

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cat.color)
            setPadding(dp(24), dp(32), dp(24), dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        center.addView(label(cat.emoji, 110f))
        center.addView(label(cat.label, 40f, bold = true))
        if (a.memo.isNotBlank()) {
            center.addView(label(a.memo, 28f, bold = true).apply { setPadding(0, dp(24), 0, 0) })
        } else {
            center.addView(label("무엇이었을까요?", 24f).apply { setPadding(0, dp(16), 0, 0) })
        }
        center.addView(
            label("${Fmt.dateTime(a.created)}에 맞춤", 14f, alpha = 0.75f)
                .apply { setPadding(0, dp(20), 0, 0) }
        )
        root.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(
            button("끄기", Color.WHITE, cat.color, 26f) {
                Actions.done(this, alarmId)
                finish()
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(84))
        )
        root.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(12)))
        root.addView(
            button("10분 뒤 다시", 0x33FFFFFF, Color.WHITE, 20f) {
                Actions.snooze(this, alarmId, 10)
                finish()
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(64))
        )
        setContentView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun button(t: String, fill: Int, textColor: Int, sp: Float, onClick: () -> Unit) =
        TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(18).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 뒤로가기로는 안 꺼짐 — 끄기나 10분 뒤를 눌러야 함
    }
}
