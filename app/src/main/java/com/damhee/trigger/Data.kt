package com.damhee.trigger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 분류 버튼 — 알람이 울릴 때 이것만 크게 보여주고, 무엇이었는지는 스스로 떠올리게 함 */
data class Cat(val key: String, val emoji: String, val label: String, val color: Int)

object Cats {
    val all = listOf(
        Cat("todo", "✅", "할일", 0xFF2E7D32.toInt()),
        Cat("item", "📦", "물건", 0xFFE65100.toInt()),
        Cat("talk", "💬", "말하기", 0xFF1565C0.toInt()),
        Cat("event", "🎉", "이벤트", 0xFFAD1457.toInt()),
        Cat("call", "📞", "연락", 0xFF00838F.toInt()),
        Cat("buy", "🛒", "구매", 0xFF6A1B9A.toInt()),
    )

    fun of(key: String): Cat = all.firstOrNull { it.key == key } ?: all[0]
}

data class Alarm(
    val id: Int,
    val time: Long,      // 울릴 시각 (epoch ms)
    val cat: String,
    val memo: String,    // 선택 입력 — 힌트로만 보여줌
    val created: Long,   // 맞춘 시각
)

object AlarmStore {
    private const val PREF = "trigger"
    private const val KEY = "alarms"
    private const val KEY_ID = "next_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): MutableList<Alarm> {
        val out = mutableListOf<Alarm>()
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Alarm(
                    o.getInt("id"), o.getLong("time"), o.optString("cat", "todo"),
                    o.optString("memo", ""), o.optLong("created", 0L)
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("time", it.time).put("cat", it.cat)
                    .put("memo", it.memo).put("created", it.created)
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun nextId(ctx: Context): Int {
        val id = prefs(ctx).getInt(KEY_ID, 1)
        prefs(ctx).edit().putInt(KEY_ID, if (id >= 90000) 1 else id + 1).apply()
        return id
    }

    @Synchronized
    fun get(ctx: Context, id: Int): Alarm? = load(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun put(ctx: Context, a: Alarm) {
        val list = load(ctx)
        list.removeAll { it.id == a.id }
        list += a
        save(ctx, list)
    }

    @Synchronized
    fun remove(ctx: Context, id: Int) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        save(ctx, list)
    }

    /** 이틀 넘게 지난 알람은 정리 */
    @Synchronized
    fun prune(ctx: Context) {
        val cut = System.currentTimeMillis() - 2 * 24 * 3600_000L
        val list = load(ctx)
        if (list.removeAll { it.time < cut }) save(ctx, list)
    }

    fun lastTime(ctx: Context): LocalTime {
        val m = prefs(ctx).getInt("last_time", 22 * 60)
        return LocalTime.of(m / 60, m % 60)
    }

    fun setLastTime(ctx: Context, t: LocalTime) {
        prefs(ctx).edit().putInt("last_time", t.hour * 60 + t.minute).apply()
    }
}

object Fmt {
    private val dows = arrayOf("월", "화", "수", "목", "금", "토", "일")
    fun dow(d: LocalDate) = dows[d.dayOfWeek.value - 1]
    fun date(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${dow(d)})"
    fun time(t: LocalTime) = String.format("%02d:%02d", t.hour, t.minute)
    fun ldt(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    fun dateTime(ms: Long): String {
        val x = ldt(ms)
        return "${date(x.toLocalDate())} ${time(x.toLocalTime())}"
    }

    fun toMs(d: LocalDate, t: LocalTime): Long =
        LocalDateTime.of(d, t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
