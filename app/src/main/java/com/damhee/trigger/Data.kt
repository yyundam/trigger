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
import kotlin.math.abs

/** 알림 종류 — 말한 내용에서 단어를 보고 아이콘·색만 자동으로 붙임 (사용자가 고르지 않음) */
data class Cat(val key: String, val emoji: String, val label: String, val color: Int, val phrase: String)

object Cats {
    val all = listOf(
        Cat("item", "📦", "물건 챙기기", 0xFFE65100.toInt(), "물건 챙기는 날"),
        Cat("meet", "👥", "약속·모임", 0xFF1565C0.toInt(), "약속 있는 날"),
        Cat("hospital", "🏥", "병원", 0xFF00897B.toInt(), "병원 가는 날"),
        Cat("med", "💊", "약 먹기", 0xFF2E7D32.toInt(), "약 먹는 날"),
        Cat("pay", "💳", "납부·결제", 0xFF6A1B9A.toInt(), "돈 내는 날"),
        Cat("apply", "🎁", "응모·결과", 0xFF558B2F.toInt(), "응모나 결과 확인하는 날"),
        Cat("call", "📞", "연락하기", 0xFF00838F.toInt(), "연락하는 날"),
        Cat("event", "🎂", "기념일·행사", 0xFFC2185B.toInt(), "기념일이나 행사 날"),
        Cat("buy", "🛒", "사기", 0xFFEF6C00.toInt(), "살 것 있는 날"),
        Cat("todo", "✏️", "기타 할 일", 0xFF455A64.toInt(), "할 일 있는 날"),
    )

    /** 해당하는 단어가 없을 때 */
    val memo = Cat("memo", "🔔", "알림", 0xFF2563EB.toInt(), "알림")

    // 위에서부터 먼저 맞는 것을 씀 ("약속"이 "약"보다 먼저 걸리도록 순서가 중요)
    private val rules = listOf(
        "meet" to listOf("약속", "모임", "만나", "회식", "미팅", "상견례"),
        "hospital" to listOf("병원", "진료", "치과", "검진", "한의원", "내과", "정형외과", "피부과", "안과", "예방접종"),
        "med" to listOf("약 먹", "약먹", "복용", "영양제", "비타민", "약 챙"),
        "pay" to listOf("납부", "결제", "입금", "송금", "세금", "보험료", "요금", "관리비", "이체", "대금", "월세", "카드값"),
        "apply" to listOf("응모", "결과", "신청", "발표", "당첨", "접수", "예매"),
        "call" to listOf("전화", "연락", "문자", "카톡", "통화"),
        "event" to listOf("생일", "기념일", "행사", "결혼", "돌잔치", "제사", "기일", "졸업"),
        "buy" to listOf("사기", "사오", "구매", "주문", "장보", "마트", "사러"),
        "item" to listOf("챙기", "가져", "가지고", "준비물", "우산", "지갑", "열쇠", "서류", "반납", "택배", "도시락"),
    )

    fun guess(text: String): Cat {
        val t = text.replace(" ", "")
        for ((key, words) in rules) {
            if (words.any { t.contains(it.replace(" ", "")) }) return of(key)
        }
        return memo
    }

    fun of(key: String): Cat = all.firstOrNull { it.key == key } ?: memo
}

data class Alarm(
    val id: Int,
    val time: Long,      // 울릴 시각 (epoch ms)
    val cat: String,
    val memo: String,    // 알려줄 내용
    val created: Long,   // 맞춘 시각
    val event: Long = -1L,
    val part: String = "",
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
                    o.getInt("id"), o.getLong("time"), o.optString("cat", "memo"),
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

    // ---------- 오전/오후/저녁 줄에 보이는 시각 3개 (앱을 껐다 켜도 기억) ----------
    private fun defaultRow(period: String) = when (period) {
        "am" -> listOf(7, 8, 9)
        "pm" -> listOf(12, 15, 17)
        else -> listOf(20, 21, 22)
    }

    @Synchronized
    fun row(ctx: Context, period: String): List<Int> {
        val s = prefs(ctx).getString("row_$period", null) ?: return defaultRow(period)
        val l = s.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (l.size == 3) l else defaultRow(period)
    }

    /** 새로 고른 시각이 줄에 없으면, 가장 가까운 시각을 빼고 그 자리에 넣음 */
    @Synchronized
    fun pushHour(ctx: Context, period: String, h: Int) {
        val cur = row(ctx, period)
        if (h in cur) return
        val closest = cur.minByOrNull { abs(it - h) } ?: return
        val next = (cur - closest + h).sorted()
        prefs(ctx).edit().putString("row_$period", next.joinToString(",")).apply()
    }
}

object Fmt {
    private val dows = arrayOf("월", "화", "수", "목", "금", "토", "일")
    fun dow(d: LocalDate) = dows[d.dayOfWeek.value - 1]

    /** 10/31(토) */
    fun date(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${dow(d)})"

    /** 10월 31일 토요일 */
    fun dateLong(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일 ${dow(d)}요일"

    /** 버튼과 같은 말로: 오전 4~11시, 낮 12시, 오후 1~5시, 저녁 6~11시, 새벽 */
    fun kor(t: LocalTime): String {
        val h = t.hour
        val (w, hh) = when (h) {
            in 0..3 -> "새벽" to (if (h == 0) 12 else h)
            in 4..11 -> "오전" to h
            12 -> "낮" to 12
            in 13..17 -> "오후" to h - 12
            else -> "저녁" to h - 12
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

    /** 알람이 울릴 때 크게 보여주고 읽어줄 말 = 사용자가 남긴 내용 */
    fun sentence(a: Alarm): String =
        if (a.memo.isNotBlank()) a.memo else Cats.of(a.cat).label
}
