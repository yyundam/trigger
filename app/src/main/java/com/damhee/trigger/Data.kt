package com.damhee.trigger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 일 종류 — phrase는 "내일은 ___이에요" 로 읽어줄 말 */
data class Cat(val key: String, val emoji: String, val label: String, val color: Int, val phrase: String)

object Cats {
    val all = listOf(
        Cat("item", "📦", "물건 챙기기", 0xFFE65100.toInt(), "물건 챙기는 날"),
        Cat("meet", "👥", "약속·모임", 0xFF1565C0.toInt(), "약속 있는 날"),
        Cat("hospital", "🏥", "병원", 0xFF00897B.toInt(), "병원 가는 날"),
        Cat("pay", "💳", "납부·결제", 0xFF6A1B9A.toInt(), "돈 내는 날"),
        Cat("apply", "🎁", "응모·결과", 0xFF558B2F.toInt(), "응모나 결과 확인하는 날"),
        Cat("call", "📞", "연락하기", 0xFF00838F.toInt(), "연락하는 날"),
        Cat("event", "🎂", "기념일·행사", 0xFFC2185B.toInt(), "기념일이나 행사 날"),
        Cat("todo", "✏️", "기타 할 일", 0xFF455A64.toInt(), "할 일 있는 날"),
    )

    fun of(key: String): Cat = all.firstOrNull { it.key == key } ?: all.last()
}

data class Alarm(
    val id: Int,
    val time: Long,      // 울릴 시각 (epoch ms)
    val cat: String,
    val memo: String,
    val created: Long,   // 맞춘 시각
    val event: Long = -1L, // 일이 있는 날 (epochDay), 없으면 -1
    val part: String = "", // 오전/오후/저녁 (없으면 "")
) {
    fun eventDate(): LocalDate? = if (event >= 0) LocalDate.ofEpochDay(event) else null
}

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
                    o.optString("memo", ""), o.optLong("created", 0L),
                    o.optLong("event", -1L), o.optString("part", "")
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
                    .put("event", it.event).put("part", it.part)
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
}

object Fmt {
    private val dows = arrayOf("월", "화", "수", "목", "금", "토", "일")
    fun dow(d: LocalDate) = dows[d.dayOfWeek.value - 1]

    /** 10/31(금) */
    fun date(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${dow(d)})"

    /** 10월 31일 금요일 */
    fun dateLong(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일 ${dow(d)}요일"

    /** 22:00 → 밤 10시, 07:30 → 아침 7시 30분 */
    fun kor(t: LocalTime): String {
        val h = t.hour
        val (w, hh) = when (h) {
            in 0..4 -> "새벽" to (if (h == 0) 12 else h)
            in 5..8 -> "아침" to h
            in 9..11 -> "오전" to h
            12 -> "낮" to 12
            in 13..17 -> "오후" to h - 12
            in 18..20 -> "저녁" to h - 12
            else -> "밤" to h - 12
        }
        return "$w ${hh}시" + if (t.minute > 0) " ${t.minute}분" else ""
    }

    /** 오늘/내일/모레, 그 외엔 "" */
    fun rel(d: LocalDate, from: LocalDate = LocalDate.now()): String =
        when (ChronoUnit.DAYS.between(from, d)) {
            0L -> "오늘"
            1L -> "내일"
            2L -> "모레"
            else -> ""
        }

    fun ldt(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    fun dateTime(ms: Long): String {
        val x = ldt(ms)
        return "${date(x.toLocalDate())} ${kor(x.toLocalTime())}"
    }

    fun toMs(x: LocalDateTime): Long = x.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 알람이 울릴 때 보여주고 읽어줄 문장: "내일은 물건 챙기는 날이에요" */
    fun sentence(a: Alarm): String {
        val c = Cats.of(a.cat)
        val ev = a.eventDate() ?: return "${c.label} 알림이에요"
        val ring = ldt(a.time).toLocalDate()
        val who = when (ChronoUnit.DAYS.between(ring, ev)) {
            0L -> "오늘은"
            1L -> "내일은"
            2L -> "모레는"
            else -> "${ev.monthValue}월 ${ev.dayOfMonth}일은"
        }
        return "$who ${c.phrase}이에요"
    }
}
