package com.example.batteryalert

import java.util.Calendar
import org.json.JSONArray
import kotlin.math.ceil
import kotlin.math.roundToInt

// ───── 가정값 (설계서 6장 기준, 실차 데이터로 보정 예정) ─────
const val BATTERY_AH = 60.0            // 12V 배터리 정격 용량 기본값 (설정에서 변경)
const val ALTERNATOR_SPARE_A = 20.0    // 내연기관: 발전기 여유 전류 (주행 중)
const val IDLE_SPARE_RATIO = 0.5       // 내연기관 P 공회전: 발전기 여유 절반으로 가정
const val LDC_SPARE_A = 40.0           // 전기차: LDC 여유 전류
const val CHARGE_ACCEPT_A = 10.0       // 12V 배터리 충전 수용 한계 전류
const val AVG_SPEED_KMH = 30.0         // 주행시간 없을 때 추정용 평균 속도
const val START_COST_PERCENT = 1.0     // 내연기관 시동 1회 소모 SoC
const val START_LIMIT_SOC = 25.0       // 시동 필요 SoC
const val DARK_CURRENT_A = 0.03        // 기본 암전류 30mA
const val DASHCAM_A = 0.35             // 블랙박스 상시녹화 기본값 350mA (설정에서 변경)
const val UNDERGROUND_BONUS_C = 5.0    // 지하·실내 주차: 바깥보다 약 5°C 따뜻하다고 가정
const val HEADLIGHT_PARKED_A = 6.0     // 전조등 켜둔 채 주차
const val DOOR_OPEN_A = 0.6            // 트렁크 열림 → 실내등
const val STEERING_HEAT_A = 4.0        // 운전대 열선
const val EV_SAVER_MIN_HV_SOC = 20     // 전기차 자동충전 가능 고전압 SoC (설정 ON일 때만)
const val LONG_PARKING_HOURS = 48.0    // 장기 주차 기준
const val SOH_LOSS_PER_YEAR = 0.08     // 노후: 1년당 용량 −8%
const val CAUTION_MARGIN = 1.5         // 시동 한계 < 주차시간 × 1.5 → 주의

enum class Risk { OK, CAUTION, DANGER }

// ───── 배터리 노후 ─────
fun batteryHealth(s: VehicleSnapshot): Double =
    (1.0 - s.batteryAgeYears * SOH_LOSS_PER_YEAR).coerceIn(0.5, 1.0)

fun usableAh(s: VehicleSnapshot): Double = s.batteryAh * batteryHealth(s)

// ① 지금 켜져 있는 전기 부하 목록 (큰 순서) — F2 코칭에서 "뭘 끄면 되는지" 보여줄 때 사용
fun loadBreakdown(s: VehicleSnapshot): List<Pair<String, Double>> = buildList {
    if (s.rearDefrostOn) add("뒷유리 열선" to 12.0)
    if (s.seatHeaterOn) add("시트 열선" to s.seatHeatA)
    if (s.headlightOn) add("전조등" to 6.0)
    if (s.steeringHeaterOn) add("운전대 열선" to STEERING_HEAT_A)
    if (s.mirrorHeaterOn) add("미러 열선" to 2.0)
    val fan = s.hvacFanLevel.toDouble() / s.fanMax.coerceAtLeast(1) * 15.0
    if (fan > 0) add("공조 팬" to fan)
}.sortedByDescending { it.second }

// ① 전기 부하 합계 (A)
fun electricLoadA(s: VehicleSnapshot): Double = loadBreakdown(s).sumOf { it.second }

// F2: 지금 충전 여유 전류 (A)
fun chargeSpareA(s: VehicleSnapshot): Double = when {
    s.isEv -> LDC_SPARE_A
    s.driveMode == "P" && s.engineOffWait -> 0.0      // Y2: 내연기관 시동 끄고 대기 → 발전기 충전 없음
    s.driveMode == "P" -> ALTERNATOR_SPARE_A * IDLE_SPARE_RATIO
    else -> ALTERNATOR_SPARE_A
}

// F2: 지금 12V에 실제로 들어가는 전류 (+면 충전, −면 방전)
fun chargeNetA(s: VehicleSnapshot): Double =
    (chargeSpareA(s) - electricLoadA(s)).coerceAtMost(CHARGE_ACCEPT_A)

// ① 이번 주행으로 12V SoC가 얼마나 변했나 (%) — 장부 기록이 없을 때 쓰는 추정값
fun tripSocChange(s: VehicleSnapshot): Double {
    val hours = s.tripMinutes?.let { it / 60.0 } ?: (s.tripKm / AVG_SPEED_KMH)
    val spareA = if (s.isEv) LDC_SPARE_A else ALTERNATOR_SPARE_A
    val netA = (spareA - electricLoadA(s)).coerceAtMost(CHARGE_ACCEPT_A)
    val startCost = if (s.isEv) 0.0 else START_COST_PERCENT
    return netA * hours / usableAh(s) * 100 - startCost
}

// ② 하차 전 체크
fun parkingWarnings(s: VehicleSnapshot): List<String> = buildList {
    if (s.headlightOn) add("전조등이 켜져 있어요")
    if (s.trunkOpen) add("트렁크가 열려 있어요")
    if (s.windowOpen) add("창문이 열려 있어요")
    if (!s.passengerOccupied && s.passengerHeaterOn) add("동승자 좌석 열선이 켜져 있어요")   // X4
}

// ③ 기온에 따른 가용 용량 비율
fun tempFactor(t: Double): Double = when {
    t >= 25 -> 1.0
    t >= 0 -> 0.8 + (t / 25.0) * 0.2
    t >= -20 -> 0.5 + ((t + 20) / 20.0) * 0.3
    else -> 0.5
}

// ③ 주차 중 소모 전류 목록 (큰 순서) — F8 원인별 기여도에 사용
fun parkedBreakdown(s: VehicleSnapshot): List<Pair<String, Double>> = buildList {
    add("기본 암전류" to DARK_CURRENT_A)
    if (s.dashcamParkingOn) add("블랙박스" to s.dashcamMa / 1000.0)
    if (s.headlightOn) add("전조등" to HEADLIGHT_PARKED_A)
    if (s.trunkOpen) add("트렁크 실내등" to DOOR_OPEN_A)
}.sortedByDescending { it.second }

// ③ 주차 중 소모 전류 합계 (A)
// W1: 이 차에 맞춘 보정계수(drainK)를 곱함
fun parkedDrainA(s: VehicleSnapshot): Double = parkedBreakdown(s).sumOf { it.second } * s.drainK

// F8: "전조등 94% · 블랙박스 5% · 기본 암전류 1%"
fun drainShareText(s: VehicleSnapshot): String {
    val total = parkedDrainA(s)
    return parkedBreakdown(s).joinToString(" · ") { "${it.first} ${(it.second / total * 100).roundToInt()}%" }
}

// F8: 추위 때문에 못 쓰게 되는 용량 (%)
fun coldLossPercent(tempC: Double): Int = ((1 - tempFactor(tempC)) * 100).roundToInt()

// ③ 시동 가능 시간 (시간)
fun predictHours(s: VehicleSnapshot, soc: Double, tempC: Double): Double {
    val effectiveSoc = soc * tempFactor(tempC)
    val marginAh = (effectiveSoc - START_LIMIT_SOC) / 100.0 * usableAh(s)
    return maxOf(0.0, marginAh / parkedDrainA(s))
}

// ③ 전기차 12V 자동충전: 설정에서 켰을 때만 반영 (기본 OFF = 보수적)
fun evSaverAvailable(s: VehicleSnapshot): Boolean =
    s.isEv && s.evSaverEnabled && s.hvSoc >= EV_SAVER_MIN_HV_SOC

// ③ 지금부터 출발까지 남은 시간 (시간)
fun hoursUntilDeparture(s: VehicleSnapshot, now: Calendar = Calendar.getInstance()): Double {
    val depart = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, s.departDaysLater)
        set(Calendar.HOUR_OF_DAY, s.departHour)
        set(Calendar.MINUTE, s.departMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return ((depart.timeInMillis - now.timeInMillis) / 3_600_000.0).coerceAtLeast(0.0)
}

fun departLabel(s: VehicleSnapshot): String {
    val day = when (s.departDaysLater) {
        0 -> "오늘"
        1 -> "내일"
        2 -> "모레"
        else -> "${s.departDaysLater}일 뒤"
    }
    return "%s %02d:%02d".format(day, s.departHour, s.departMinute)
}

// F7: 지금부터 ○시간 뒤가 "몇 월 며칠 몇 시"인지 ("내일 04:10")
fun timeLabelAfter(hours: Double, now: Calendar = Calendar.getInstance()): String {
    if (hours > 24 * 30) return "30일 이상 뒤"
    val t = (now.clone() as Calendar).apply { add(Calendar.MINUTE, (hours * 60).toInt()) }
    fun dayStart(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((dayStart(t) - dayStart(now)) / (24L * 3_600_000)).toInt()
    val day = when (days) {
        0 -> "오늘"
        1 -> "내일"
        2 -> "모레"
        else -> "${days}일 뒤"
    }
    return "%s %02d:%02d".format(day, t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
}

// ③ 위험도 3단계
fun riskLevel(s: VehicleSnapshot, startableHours: Double, parkHours: Double): Risk = when {
    evSaverAvailable(s) -> Risk.OK
    startableHours < parkHours -> Risk.DANGER
    startableHours < parkHours * CAUTION_MARGIN -> Risk.CAUTION
    else -> Risk.OK
}

// ④ 추천 조치: 조치별로 시동 한계가 얼마나 늘어나는지
fun recommendedActions(
    s: VehicleSnapshot, soc: Double, tempC: Double, parkHours: Double
): List<String> = buildList {
    if (evSaverAvailable(s)) {
        if (s.headlightOn) add("전조등 끄기")
        if (s.trunkOpen) add("트렁크 닫기")
        return@buildList
    }
    val now = predictHours(s, soc, tempC)
    val fixes = listOf(
        Triple(s.headlightOn, "전조등 끄기", s.copy(headlightOn = false)),
        Triple(s.trunkOpen, "트렁크 닫기", s.copy(trunkOpen = false)),
        Triple(s.dashcamParkingOn, "블랙박스 상시녹화 끄기", s.copy(dashcamParkingOn = false))
    ).filter { it.first }
        .map { (_, label, fixed) -> label to predictHours(fixed, soc, tempC) }
        .filter { it.second > now + 0.5 }
        .sortedByDescending { it.second }

    for ((label, h) in fixes) {
        val ok = if (h >= parkHours) " ✓ 출발까지 충분" else ""
        add("$label → 시동 한계 약 ${now.toInt()} → ${h.toInt()}시간 (+${(h - now).toInt()}h)$ok")
    }
    if (fixes.size >= 2) {
        val all = s.copy(headlightOn = false, trunkOpen = false, dashcamParkingOn = false)
        add("전부 조치 시 → 약 ${predictHours(all, soc, tempC).toInt()}시간")
    }
    if (riskLevel(s, now, parkHours) != Risk.OK) {
        if (s.isEv) {
            if (s.hvSoc < EV_SAVER_MIN_HV_SOC) add("고전압 배터리 충분히 충전 후 주차")
            else add("차량 설정에서 12V 자동충전(보조배터리 세이버) 켜져 있는지 확인")
        }
        else add("배터리 충전기 연결 또는 출발 전 중간 시동")
    }
}

// ⑤ 주차 안내 + F12 장기 주차 모드 (48시간 이상)
fun isLongParking(parkHours: Double) = parkHours >= LONG_PARKING_HOURS

fun parkingAdvice(
    s: VehicleSnapshot, parkHours: Double, startableHours: Double, soc: Double, tempC: Double
): List<String> = buildList {
    if (s.isEv && s.evSaverEnabled && s.hvSoc < EV_SAVER_MIN_HV_SOC) {
        add("고전압 배터리가 ${s.hvSoc}%라 12V 자동충전이 안 될 수 있어요. 충전 후 주차하세요")
    }
    if (!isLongParking(parkHours)) return@buildList

    add("약 ${"%.1f".format(parkHours / 24)}일 주차 예정이에요 (출발 ${departLabel(s)})")
    if (!evSaverAvailable(s)) {
        if (startableHours <= 0.0) {
            add("지금 잔량으로는 이미 시동 한계 아래예요 → 충전 후 주차가 필요해요")
        } else if (startableHours >= parkHours) {
            add("지금 상태로도 버틸 수 있어요 (시동 한계 약 ${"%.1f".format(startableHours / 24)}일)")
        } else {
            add("이대로면 약 ${"%.1f".format(startableHours / 24)}일 뒤(${timeLabelAfter(startableHours)}) 시동 한계 — 출발보다 먼저 와요")
        }
    }
    add("체크리스트")
    if (s.dashcamParkingOn) {
        val off = predictHours(s.copy(dashcamParkingOn = false), soc, tempC)
        add(if (off > 0.0) "☐ 블랙박스 주차녹화 끄기 → 시동 한계 약 ${"%.1f".format(off / 24)}일"
        else "☐ 블랙박스 주차녹화 끄기")
    }
    if (s.headlightOn) add("☐ 전조등 끄기")
    if (s.trunkOpen || s.windowOpen) add("☐ 트렁크·창문 닫기")
    add("☐ 공조·열선 끄고 내리기")
    if (s.isEv) {
        add("☐ 고전압 배터리 충분히 충전해 두기")
    } else if (startableHours < parkHours) {
        add("☐ ${timeLabelAfter(startableHours * 0.7)} 전에 30분 이상 주행, 또는 충전기 연결")
    }
}

// 지금부터 출발까지 예보 중 최저기온 (예보 없으면 null)
fun minTempUntilDeparture(forecast: List<HourTemp>?, parkHours: Double): Double? {
    val now = System.currentTimeMillis()
    val until = now + (parkHours * 3_600_000).toLong()
    return forecast
        ?.filter { it.epochMillis in (now - 3_600_000)..until }
        ?.minOfOrNull { it.tempC }
}

// ⑥ 배터리 리포트 (F11): 최근 30일 위험 알림 기록 분석
const val REPORT_DAYS = 30
const val REPLACE_AGE_YEARS = 4        // 4년 이상 +
const val REPLACE_DANGER_COUNT = 2     // 30일 위험 2회 이상 → 교체 권고
const val REPEAT_DANGER_COUNT = 3      // 연수 상관없이 위험 3회 이상 → 점검 권고

data class BatteryReport(val recommendCheck: Boolean, val lines: List<String>)

fun batteryReport(events: List<AlertHistory.Event>, s: VehicleSnapshot): BatteryReport {
    val cutoff = System.currentTimeMillis() - REPORT_DAYS * 24L * 3_600_000
    val recent = events.filter { it.time >= cutoff }
    val danger = recent.count { it.risk == "DANGER" }
    val caution = recent.count { it.risk == "CAUTION" }
    val cold = recent.count { it.tempC < 0 }
    val health = batteryHealth(s)
    val replace = s.batteryAgeYears >= REPLACE_AGE_YEARS && danger >= REPLACE_DANGER_COUNT
    val batteryCheck = replace || danger >= REPEAT_DANGER_COUNT
    val recommend = batteryCheck || s.powerDownWarning   // 경고등 켜져도 블루핸즈 안내

    val lines = buildList {
        if (s.powerDownWarning) add("⚠ 계기판 출력 제한 경고 켜짐 → 정비 점검이 필요해요")
        add("최근 ${REPORT_DAYS}일 위험 ${danger}회 · 주의 ${caution}회")
        if (recent.isNotEmpty()) {
            add("알림 시 평균 기온 ${"%.1f".format(recent.map { it.tempC }.average())}°C · 영하 비율 ${cold * 100 / recent.size}%")
            add("알림 시 평균 시동 한계 ${"%.0f".format(recent.map { it.hours }.average())}시간")
        }
        add("12V 배터리 ${s.batteryAgeYears}년차 · 상태 ${(health * 100).toInt()}%")
        if (batteryCheck) {
            val coldNote = if (recent.isNotEmpty() && cold * 2 >= recent.size)
                " (저온에서 집중 발생 → 저온 성능 저하 의심)" else ""
            add(if (replace) "→ 배터리 교체 권장$coldNote" else "→ 배터리 점검 권장$coldNote")
        }
    }
    return BatteryReport(recommend, lines)
}

// ───── F5 출발 시각 학습: 요일별 평소 출발 시각(중앙값)으로 다음 출발 예측 ─────
const val LEARN_DAYS = 56                // 최근 8주 기록만 사용
const val SAME_DOW_MIN = 2               // 같은 요일 기록 2회 이상이면 요일 기준
const val GROUP_MIN = 3                  // 아니면 평일/주말 묶음 3회 이상
const val DEPART_MIN_GAP_MIN = 30        // 지금부터 30분 이후 출발만 "다음 출발"로 봄

data class LearnedDeparture(
    val daysLater: Int, val hour: Int, val minute: Int,
    val basis: String,      // "화요일 평소 출발 08:05~08:15"
    val samples: Int
)

private val DOW_NAMES = arrayOf("일", "월", "화", "수", "목", "금", "토")

private fun isWeekend(dow: Int) = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

fun learnNextDeparture(events: List<Long>, now: Calendar = Calendar.getInstance()): LearnedDeparture? {
    val cutoff = now.timeInMillis - LEARN_DAYS * 24L * 3_600_000
    // (요일, 하루 중 몇 분) 목록
    val recent = events.filter { it >= cutoff }.map { t ->
        val c = Calendar.getInstance().apply { timeInMillis = t }
        c.get(Calendar.DAY_OF_WEEK) to (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }
    if (recent.isEmpty()) return null

    for (d in 0..7) {
        val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, d) }
        val dow = day.get(Calendar.DAY_OF_WEEK)
        val sameDow = recent.filter { it.first == dow }.map { it.second }
        val group = recent.filter { isWeekend(it.first) == isWeekend(dow) }.map { it.second }
        val (mins, label) = when {
            sameDow.size >= SAME_DOW_MIN -> sameDow to "${DOW_NAMES[dow - 1]}요일 평소 출발"
            group.size >= GROUP_MIN -> group to (if (isWeekend(dow)) "주말 평소 출발" else "평일 평소 출발")
            else -> continue
        }
        val sorted = mins.sorted()
        val median = sorted[sorted.size / 2]
        val cand = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, median / 60); set(Calendar.MINUTE, median % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cand.timeInMillis < now.timeInMillis + DEPART_MIN_GAP_MIN * 60_000L) continue
        val range = if (sorted.first() == sorted.last()) hm(median) else "${hm(sorted.first())}~${hm(sorted.last())}"
        return LearnedDeparture(d, median / 60, median % 60, "$label $range", sorted.size)
    }
    return null
}

// ───── F10 블랙박스 저전압 차단 전압 추천 ─────
// 12V 납축전지 휴지 전압 ↔ 잔량(SoC) 관계 (상온 기준 근사값)
private val OCV_TABLE = listOf(0.0 to 11.8, 25.0 to 12.0, 50.0 to 12.2, 75.0 to 12.4, 100.0 to 12.7)

fun socToVoltage(soc: Double): Double {
    val x = soc.coerceIn(0.0, 100.0)
    for (i in 0 until OCV_TABLE.size - 1) {
        val (s0, v0) = OCV_TABLE[i]
        val (s1, v1) = OCV_TABLE[i + 1]
        if (x <= s1) return v0 + (v1 - v0) * (x - s0) / (s1 - s0)
    }
    return OCV_TABLE.last().second
}

const val CUTOFF_MARGIN_SOC = 5.0      // 시동 필요량 위에 여유 5%

data class DashcamAdvice(val cutoffV: Double, val targetSoc: Double, val recordHours: Double, val lines: List<String>)

fun dashcamAdvice(s: VehicleSnapshot, socNow: Double, tempC: Double): DashcamAdvice {
    // 추우면 쓸 수 있는 용량이 줄어드니, 그만큼 더 남겨두고 꺼져야 시동이 걸림
    val target = (START_LIMIT_SOC / tempFactor(tempC) + CUTOFF_MARGIN_SOC).coerceAtMost(90.0)
    val agedBonus = if (s.batteryAgeYears >= REPLACE_AGE_YEARS) 0.1 else 0.0   // 노후 배터리는 전압이 더 빨리 떨어짐
    val v = (ceil((socToVoltage(target) + agedBonus) * 10) / 10).coerceIn(11.8, 12.6)
    val drainA = s.dashcamMa / 1000.0 + DARK_CURRENT_A
    val recordHours = ((socNow - target) / 100 * usableAh(s) / drainA).coerceAtLeast(0.0)

    val lines = buildList {
        add("블랙박스 저전압 차단을 ${"%.1f".format(v)}V 이상으로 설정하세요")
        add("근거: 출발 전 최저 ${"%.1f".format(tempC)}°C · ${s.batteryAgeYears}년차 → 꺼질 때 잔량 약 ${target.roundToInt()}%는 남아야 시동 가능")
        if (recordHours > 0) {
            add("지금(약 ${socNow.roundToInt()}%)부터 차단까지 녹화 약 ${"%.0f".format(recordHours)}시간")
        } else {
            add("지금 잔량이 이미 차단 기준 아래예요 → 블랙박스가 곧 꺼지거나 시동이 어려울 수 있어요")
        }
        add("※ 전압-잔량 관계는 상온 기준 근사값이에요")
    }
    return DashcamAdvice(v, target, recordHours, lines)
}

// ───── W2 블랙박스 표시 전압 → 잔량 (F10과 같은 변환표 하나만 사용) ─────
fun voltageToSoc(v: Double): Double {
    val table = OCV_TABLE
    if (v <= table.first().second) return 0.0
    for (i in 0 until table.size - 1) {
        val (s0, v0) = table[i]
        val (s1, v1) = table[i + 1]
        if (v <= v1) return s0 + (s1 - s0) * (v - v0) / (v1 - v0)
    }
    return 100.0
}

// ───── W3 추정 신뢰도 ─────
const val CONF_HIGH = 70               // 70 이상 높음
const val CONF_MID = 40                // 40~69 보통, 40 미만 낮음

enum class Confidence(val label: String) { HIGH("높음"), MID("보통"), LOW("낮음") }

fun confidenceLevel(score: Int): Confidence = when {
    score >= CONF_HIGH -> Confidence.HIGH
    score >= CONF_MID -> Confidence.MID
    else -> Confidence.LOW
}

// ───── X1 도착 전 선제 예측 + X2 충전 경로 처방 ─────
const val HOME_RADIUS_KM = 2.0          // 목적지가 집 즐겨찾기 반경 2km 안이면 "집"
const val PRE_ARRIVAL_MIN = 10.0        // 도착 10분 전부터 경고
const val MAX_EXTRA_DRIVE_MIN = 30      // 추가 주행 30분 넘게 필요하면 경로 처방 대신 다른 조치

fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.asin(Math.sqrt(a))
}

// 집 판단: 내비 "집" 즐겨찾기 반경 2km 안, 즐겨찾기가 없으면 목적지 이름에 "집"·"home"
fun isHomeDestination(name: String, lat: Double, lon: Double, homeLat: Double?, homeLon: Double?): Boolean {
    if (homeLat != null && homeLon != null) return distanceKm(lat, lon, homeLat, homeLon) <= HOME_RADIUS_KM
    val n = name.lowercase()
    return "집" in n || "home" in n
}

// 열선·공조를 전부 끈 상태
fun heatersOff(s: VehicleSnapshot): VehicleSnapshot = s.copy(
    seatHeaterOn = false, steeringHeaterOn = false, mirrorHeaterOn = false,
    rearDefrostOn = false, hvacFanLevel = 0, passengerHeaterOn = false
)

data class ArrivalPlan(
    val arriveMin: Double,       // 도착까지 남은 시간 (분)
    val parkHours: Double,       // 도착 후 다음 출발까지 주차 시간
    val socArrive: Double,       // 지금 상태 그대로 도착했을 때 12V
    val heaterGain: Double,      // 지금 열선·공조를 끄면 도착 시 늘어나는 12V (%p)
    val limitHours: Double,      // 도착 시 12V로 버틸 수 있는 시간
    val risk: Risk,
    val needSoc: Double,         // 출발까지 버티려면 도착 때 필요한 12V
    val shortage: Double,        // 부족분 (열선 끈 상태 기준)
    val extraMin: Int?           // 부족분을 채우는 추가 주행 시간 (못 채우면 null)
)

fun arrivalPlan(s: VehicleSnapshot, socNow: Double, tempC: Double, arriveMin: Double, hoursToDepart: Double): ArrivalPlan {
    val h = arriveMin / 60.0
    val ah = usableAh(s)
    val parkH = (hoursToDepart - h).coerceAtLeast(0.0)
    val socArrive = (socNow + chargeNetA(s) * h / ah * 100).coerceIn(0.0, 100.0)
    val off = heatersOff(s)
    val socArriveOff = (socNow + chargeNetA(off) * h / ah * 100).coerceIn(0.0, 100.0)
    val limit = predictHours(s, socArrive, tempC)
    // 필요 12V = (시동 필요 SoC + 주차 소모 × 주차 시간 ÷ 용량) ÷ 저온 계수
    val need = ((START_LIMIT_SOC + parkedDrainA(s) * parkH / ah * 100) / tempFactor(tempC)).coerceAtMost(100.0)
    val shortage = need - socArriveOff
    val ratePerMin = chargeNetA(off).coerceAtLeast(0.0) / 60.0 / ah * 100     // 열선 끄고 달릴 때 분당 충전 %
    val extra = when {
        shortage <= 0 -> null
        ratePerMin <= 0 -> null
        else -> Math.ceil(shortage / ratePerMin).toInt().takeIf { it <= MAX_EXTRA_DRIVE_MIN }
    }
    return ArrivalPlan(arriveMin, parkH, socArrive, socArriveOff - socArrive, limit,
        riskLevel(s, limit, parkH), need, shortage, extra)
}

// ───── X5 차대번호(VIN) 해석: 제조사(앞 3자리) + 연식(10번째 자리) ─────
private val WMI_MAKERS = mapOf("KMH" to "현대", "KMF" to "현대", "KMJ" to "현대", "KMT" to "제네시스",
    "KNA" to "기아", "KNC" to "기아", "KND" to "기아", "KNE" to "기아")
// 연식 코드: 2010=A … (I, O, Q, U, Z 제외) … 2023=P, 2024=R, 2025=S, 2026=T, 2027=V
private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY"

fun decodeVin(vin: String?): Pair<String, Int>? {
    val v = vin?.trim()?.uppercase() ?: return null
    if (v.length != 17) return null
    val maker = WMI_MAKERS[v.substring(0, 3)] ?: "기타 제조사"
    val idx = YEAR_CODES.indexOf(v[9])
    if (idx < 0) return null
    return maker to (2010 + idx)
}

fun ageFromModelYear(year: Int): Int =
    (Calendar.getInstance().get(Calendar.YEAR) - year).coerceIn(0, 8)

// ───── X3 지역별 방전 위험 지수 (서비스 미리보기용 가상 데이터) ─────
data class RegionRisk(val name: String, val cars: Int, val minTempC: Double, val riskPct: Int) {
    // 위험 차량 중 실제 출동으로 이어지는 비율 15% 가정
    val expectedCalls: Int get() = (cars * riskPct / 100.0 * 0.15).toInt()
}

val DEMO_REGIONS = listOf(
    RegionRisk("울산 울주군", 980, -11.2, 24),
    RegionRisk("경북 경주시", 720, -10.1, 21),
    RegionRisk("울산 북구", 1240, -9.5, 18),
    RegionRisk("울산 동구", 640, -8.4, 14),
    RegionRisk("울산 중구", 860, -7.8, 12),
    RegionRisk("울산 남구", 1530, -6.9, 9)
)

// ───── G1 처방 엔진: 지금 할 수 있는 조치를 효과 큰 순서로 하나의 목록으로 ─────
// 팝업·배너·④ 카드·음성("어떻게 해?")이 전부 이 목록 하나를 같이 씀
enum class RxKind(val tag: String) { CONTROL("앱이 끌 수 있음"), USER("직접"), ROUTE("내비"), SERVICE("서비스") }

data class Rx(
    val title: String,
    val gainHours: Double,     // 이 조치로 늘어나는 시동 한계 (시간)
    val kind: RxKind,
    val enough: Boolean,       // 이것만 해도 출발까지 버틸 수 있나
    val note: String = "",
    val action: String = ""    // 화면 버튼으로 바로 실행: route / heat / close / dashcam / call (빈칸 = 직접)
) {
    val line: String get() = buildString {
        append(title)
        if (gainHours >= 0.5) append(" → 시동 한계 +${gainHours.roundToInt()}시간")
        if (note.isNotBlank()) append(" ($note)")
        if (enough) append(" ✓ 출발까지 충분")
    }
    // 음성으로 읽을 짧은 문장
    val spoken: String get() = title + (if (gainHours >= 0.5) ", 시동 한계가 ${gainHours.roundToInt()}시간 늘어요" else "")
}

fun prescriptions(
    s: VehicleSnapshot, soc: Double, tempC: Double, parkHours: Double,
    plan: ArrivalPlan?, driving: Boolean
): List<Rx> {
    val list = mutableListOf<Rx>()
    if (evSaverAvailable(s)) {
        if (s.headlightOn) list += Rx("전조등 끄기", 0.0, RxKind.USER, true)
        if (s.trunkOpen || s.windowOpen) list += Rx("트렁크·창문 닫기", 0.0, RxKind.CONTROL, true, action = "close")
        return list
    }
    val base = predictHours(s, soc, tempC)
    val needed = riskLevel(s, base, parkHours) != Risk.OK || isLongParking(parkHours)
    fun after(fixed: VehicleSnapshot) = predictHours(fixed, soc, tempC)
    fun gain(fixed: VehicleSnapshot) = (after(fixed) - base).coerceAtLeast(0.0)

    // 주행 중: 집 도착 전에 할 수 있는 것 (X1·X2)
    if (driving && plan != null && plan.risk != Risk.OK) {
        plan.extraMin?.let {
            list += Rx("${it}분 더 걸리는 경로로 회복", 0.0, RxKind.ROUTE, true, "필요 ${plan.needSoc.roundToInt()}%", "route")
        }
        if (plan.heaterGain >= 0.5) {
            list += Rx("지금 열선 끄기", 0.0, RxKind.CONTROL, false, "도착 시 12V +${"%.1f".format(plan.heaterGain)}%", "heat")
        }
    }
    // 주차 쪽 조치
    if (s.headlightOn) {
        val f = s.copy(headlightOn = false)
        list += Rx("전조등 끄기", gain(f), RxKind.USER, after(f) >= parkHours)
    }
    if (s.trunkOpen || s.windowOpen) {
        val f = s.copy(trunkOpen = false, windowOpen = false)
        list += Rx("트렁크·창문 닫기", gain(f), RxKind.CONTROL, after(f) >= parkHours, action = "close")
    }
    if (s.dashcamParkingOn && needed) {
        val f = s.copy(dashcamParkingOn = false)
        list += Rx(if (driving) "도착하면 블랙박스 주차녹화 끄기" else "블랙박스 주차녹화 끄기",
            gain(f), RxKind.USER, after(f) >= parkHours, action = "dashcam")
    }
    // 위 조치를 다 해도 부족하면
    val all = s.copy(headlightOn = false, trunkOpen = false, windowOpen = false, dashcamParkingOn = false)
    if (needed && after(all) < parkHours && plan?.extraMin == null) {
        if (s.isEv) {
            list += Rx(if (s.hvSoc < EV_SAVER_MIN_HV_SOC) "고전압 배터리 충전 후 주차"
            else "차량 설정에서 12V 자동충전(보조배터리 세이버) 켜기", 0.0, RxKind.USER, false)
        } else {
            list += Rx("출발 전 30분 이상 주행 또는 충전기 연결", 0.0, RxKind.USER, false)
        }
        list += Rx("그래도 시동이 안 걸리면 긴급출동 080-600-6000", 0.0, RxKind.SERVICE, false, action = "call")
    }
    // "이것만으로 충분" → 효과 큰 순서. 경로·서비스 같은 고정 항목은 자기 자리 유지
    return list.sortedWith(compareByDescending<Rx> { it.enough }.thenByDescending { it.gainHours })
}

// ───── G1 음성 명령 해석: 말한 문장 → 명령 ─────
sealed class VoiceCmd {
    object Help : VoiceCmd()
    object Status : VoiceCmd()          // 배터리 몇 %?
    object Forecast : VoiceCmd()        // 내일 아침 시동 괜찮아?
    object Why : VoiceCmd()             // 왜 위험해?
    object HowTo : VoiceCmd()           // 어떻게 해?
    data class Off(val target: String) : VoiceCmd()     // 끄기·닫기
    data class Dashcam(val on: Boolean) : VoiceCmd()
    data class Underground(val on: Boolean) : VoiceCmd()
    data class Depart(val days: Int?, val hour: Int, val minute: Int) : VoiceCmd()
    data class Voltage(val volts: Double) : VoiceCmd()
    data class StartResult(val ok: Boolean) : VoiceCmd()
    object Bluehands : VoiceCmd()
    object Emergency : VoiceCmd()
    object Route : VoiceCmd()
    data class Show(val pane: String) : VoiceCmd()
    data class Wait(val on: Boolean) : VoiceCmd()     // Y2 차박·대기 모드 켜기/끄기, "얼마나 버텨?"
    object Unknown : VoiceCmd()
}

fun parseVoice(text: String): VoiceCmd {
    val q = text.replace(" ", "").lowercase()
    fun has(vararg w: String) = w.any { it in q }
    val off = has("꺼", "끄", "끈", "닫")

    if (has("뭐할수있", "도움말", "명령어", "뭘할수")) return VoiceCmd.Help

    Regex("""(1[0-4](?:[.,]\d{1,2})?)(볼트|v)""").find(q)?.let {
        return VoiceCmd.Voltage(it.groupValues[1].replace(',', '.').toDouble())
    }
    if (has("출발")) {
        Regex("""(\d{1,2})시(?:(\d{1,2})분|반)?""").find(q)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = when {
                m.value.endsWith("반") -> 30
                m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
                else -> 0
            }
            if (has("오후", "저녁", "밤") && h < 12) h += 12
            val days = when { has("모레") -> 2; has("내일") -> 1; has("오늘") -> 0; else -> null }
            return VoiceCmd.Depart(days, h.coerceIn(0, 23), min.coerceIn(0, 59))
        }
    }
    if (has("대기모드", "차박", "얼마나버텨", "몇분버텨", "몇시간버텨", "언제까지버텨")) return VoiceCmd.Wait(!(off && has("모드")))
    if (has("방전됐", "시동안걸렸", "시동이안걸렸")) return VoiceCmd.StartResult(false)
    if (has("괜찮았", "잘걸렸", "시동걸렸")) return VoiceCmd.StartResult(true)
    if (has("긴급출동", "견인", "출동")) return VoiceCmd.Emergency
    if (has("블루핸즈", "정비소", "서비스센터")) return VoiceCmd.Bluehands
    if (has("경로") && has("바꿔", "회복", "충전", "돌아")) return VoiceCmd.Route
    if (has("영수증", "리포트", "주간")) return VoiceCmd.Show("receipt")
    if (has("지역")) return VoiceCmd.Show("region")
    if (has("설정")) return VoiceCmd.Show("settings")
    if (has("블랙박스")) {
        if (off) return VoiceCmd.Dashcam(false)
        if (has("켜", "켰")) return VoiceCmd.Dashcam(true)
    }
    if (has("지하")) return VoiceCmd.Underground(true)
    if (has("지상", "야외", "실외")) return VoiceCmd.Underground(false)
    if (off) {
        val target = when {
            has("창문") -> "window"
            has("트렁크") -> "trunk"
            has("운전대", "핸들") -> "steer"
            has("뒷유리", "성에", "김서림") -> "defrost"
            has("미러", "거울") -> "mirror"
            has("시트", "엉따", "좌석") -> "seat"
            has("에어컨", "공조", "히터", "바람", "팬") -> "hvac"
            has("다", "전부", "모두", "싹") && has("열선") -> "heat"
            has("열선") -> "heat"
            has("다", "전부", "모두", "싹") -> "all"
            else -> null
        }
        if (target != null) return VoiceCmd.Off(target)
    }
    if (has("왜", "원인")) return VoiceCmd.Why
    if (has("어떻게", "뭐해야", "뭘해야", "방법", "처방", "해결")) return VoiceCmd.HowTo
    if (has("몇퍼", "퍼센트", "잔량", "충전돼", "충전되", "지금배터리")) return VoiceCmd.Status
    if (has("시동", "내일", "아침", "괜찮", "배터리")) return VoiceCmd.Forecast
    return VoiceCmd.Unknown
}

val VOICE_HELP = "이렇게 말해 보세요. 내일 아침 시동 괜찮아? 왜 위험해? 어떻게 해? 배터리 몇 퍼센트야? " +
        "열선 다 꺼줘, 시트 열선 꺼줘, 창문 닫아줘, 블랙박스 껐어, 내일 8시에 출발해, 블랙박스 전압 12.3볼트, " +
        "블루핸즈 찾아줘, 긴급출동 불러줘, 충전 경로로 바꿔줘, 대기 모드 켜줘, 얼마나 버텨?, 영수증 보여줘."

fun offLabel(target: String): String = when (target) {
    "all" -> "공조, 열선, 창문, 트렁크"
    "heat" -> "열선"
    "seat" -> "시트 열선"
    "steer" -> "운전대 열선"
    "defrost" -> "뒷유리 열선"
    "mirror" -> "미러 열선"
    "hvac" -> "공조"
    "window" -> "창문"
    "trunk" -> "트렁크"
    else -> target
}

// 주행 중에도 끌 수 있는 것: 열선 종류만 (요청서 개발 규칙)
fun allowedWhileDriving(target: String) = target in setOf("heat", "seat", "steer", "defrost", "mirror")

// ───── G3 시간대별 기온으로 "지금 주차하면" 12V 곡선 ─────
// 추위 반영 12V = 남은 잔량 × 저온 계수(지금까지 중 가장 추웠던 기온). 25% 아래로 내려가면 시동 한계
data class CurvePoint(val h: Double, val eff: Double)

fun tempAtHour(forecast: List<HourTemp>?, hoursFromNow: Double, fallback: Double, bonus: Double): Double {
    if (forecast.isNullOrEmpty()) return fallback
    val t = System.currentTimeMillis() + (hoursFromNow * 3_600_000).toLong()
    val nearest = forecast.minByOrNull { Math.abs(it.epochMillis - t) } ?: return fallback
    return nearest.tempC + bonus
}

fun startCurve(
    s: VehicleSnapshot, soc: Double, forecast: List<HourTemp>?, fallbackTemp: Double,
    bonus: Double, maxHours: Double, step: Double = 0.25
): List<CurvePoint> {
    val drain = parkedDrainA(s)
    val ah = usableAh(s)
    var runMin = Double.MAX_VALUE
    val pts = mutableListOf<CurvePoint>()
    var h = 0.0
    while (h <= maxHours + 1e-9) {
        runMin = minOf(runMin, tempAtHour(forecast, h, fallbackTemp, bonus))
        val nominal = (soc - drain * h / ah * 100).coerceAtLeast(0.0)
        pts += CurvePoint(h, nominal * tempFactor(runMin))
        h += step
    }
    return pts
}

fun crossingHours(curve: List<CurvePoint>): Double? = curve.firstOrNull { it.eff < START_LIMIT_SOC }?.h

fun effAt(curve: List<CurvePoint>, hours: Double): Double =
    curve.minByOrNull { Math.abs(it.h - hours) }?.eff ?: 0.0

// ───── Y1 12V 보호 하한선 (자동 보호 엔진) ─────
// 보호 하한선 = 시동 필요 SoC(저온 보정) + 여유 10%p (설정 10/15/20, 신뢰도 낮음이면 +5%p)
// 지금 부하가 이어지면 몇 분 뒤 하한선에 닿는지 계산 → 30분 전 배너 / 10분 전 음성 / 1분 전 예고 → 자동 OFF
const val PROTECT_MARGIN_DEFAULT = 10      // 하한선 여유 기본값 (%p)
val PROTECT_MARGIN_CHOICES = listOf(10, 15, 20)
const val PROTECT_LOW_CONF_EXTRA = 5       // 추정 신뢰도 "낮음"이면 여유 +5%p (10 → 15)
const val PROTECT_BANNER_MIN = 30.0        // 30분 전: 배너
const val PROTECT_VOICE_MIN = 10.0         // 10분 전: 음성 1회 + "지금 끄기"
const val PROTECT_NOTICE_MIN = 1.0         // 1분 전: 자동 OFF 예고 (유지 선택 가능)
const val PROTECT_NOTICE_SEC = 60          // 예고 후 자동 OFF까지 초
// 자동 OFF 대상: 공조, 시트·운전대·미러·뒷유리 열선 (전조등은 안전상 제외, 블랙박스는 앱이 제어 불가)
val PROTECT_TARGETS = listOf("seat", "steer", "mirror", "defrost", "hvac")
// 주행 중 "지금 끄기"를 누르면 열선만 (개발 규칙: 주행 중 제어는 열선만)
val PROTECT_TARGETS_DRIVING = listOf("seat", "steer", "mirror", "defrost")

enum class ProtectStage { NONE, BANNER, VOICE, NOTICE }

data class ProtectPlan(
    val needSoc: Double,        // 내일 아침 시동에 필요한 12V (저온 보정, 화면 12V 숫자와 같은 기준)
    val margin: Int,            // 적용된 여유 (%p)
    val limitSoc: Double,       // 보호 하한선
    val drainA: Double,         // 지금 12V에서 빠져나가는 전류 (충전 중이면 0)
    val minutesLeft: Double?,   // 하한선까지 남은 분 (충전 중이면 null)
    val stage: ProtectStage
) {
    val reached: Boolean get() = minutesLeft != null && minutesLeft <= 0.0
}

// 시동 필요 SoC: 저온 계수를 곱한 값이 25% 이상이어야 함 → 화면 숫자 기준으로는 25 ÷ 저온 계수
fun startNeedSoc(tempC: Double): Double = (START_LIMIT_SOC / tempFactor(tempC)).coerceAtMost(100.0)

// 요청서 공식: 남은 시간 = (12V × 저온 계수 − (25% + 여유)) × 용량 × 건강도 ÷ 부하
// → 추위 반영 기준으로 계산하고, 화면에는 12V 숫자 기준(÷ 저온 계수)으로 바꿔서 보여줌
fun protectPlan(s: VehicleSnapshot, soc: Double, tempC: Double, conf: Confidence): ProtectPlan {
    val tf = tempFactor(tempC)
    val need = startNeedSoc(tempC)
    val margin = s.protectMargin + if (conf == Confidence.LOW) PROTECT_LOW_CONF_EXTRA else 0
    val limitEff = START_LIMIT_SOC + margin
    val limit = (limitEff / tf).coerceAtMost(100.0)
    val drainA = (-chargeNetA(s)).coerceAtLeast(0.0)
    val minutes = if (drainA < 0.05) null
    else ((soc * tf - limitEff) / 100.0 * usableAh(s) / drainA * 60.0).coerceAtLeast(0.0)
    val stage = when {
        minutes == null -> ProtectStage.NONE
        minutes <= PROTECT_NOTICE_MIN -> ProtectStage.NOTICE
        minutes <= PROTECT_VOICE_MIN -> ProtectStage.VOICE
        minutes <= PROTECT_BANNER_MIN -> ProtectStage.BANNER
        else -> ProtectStage.NONE
    }
    return ProtectPlan(need, margin, limit, drainA, minutes, stage)
}

// [시연용] "하한선 ○분 전" 상태를 만들려면 12V를 몇 %로 맞춰야 하나
fun socForMinutesLeft(s: VehicleSnapshot, p: ProtectPlan, minutes: Double, tempC: Double): Double =
    ((START_LIMIT_SOC + p.margin + p.drainA * minutes / 60.0 / usableAh(s) * 100.0) / tempFactor(tempC)).coerceIn(0.0, 100.0)

fun minutesText(m: Double): String {
    val total = m.roundToInt()
    return if (total >= 60) "${total / 60}시간 ${total % 60}분" else "${total.coerceAtLeast(0)}분"
}

// ───── Y5 앱 자체 소모 0 ─────
const val SLEEP_AFTER_PARK_MS = 2 * 60_000L   // 하차 확정 2분 뒤 차량 읽기 중지

// ───── Y2 차박·대기 모드 12V 가드 ─────
// 차 안에서 열선·공조를 켜고 기다릴 때 "이대로 몇 분 버티는지" + "뭘 줄이면 얼마나 늘어나는지"
const val WAIT_SUGGEST_MS = 5 * 60_000L    // P + 운전석 착석 + 공조·열선 5분 → 대기 모드 제안
const val SEAT_HEAT_LOW_A = 4.0             // 시트 열선 1단 가정 (최대 8A의 절반)
const val EV_HV_KWH = 77.4                  // 전기차 고전압 배터리 용량 가정 (요청서 기준)
const val EV_HVAC_KW = 1.5                  // 전기차 공조 소비전력 가정 (요청서 기준)
const val EV_WAIT_MIN_HV = 20.0             // 고전압 배터리는 20%까지만 쓰는 걸로 계산
const val LDC_VOLT = 12.5                   // 12V 부하를 kW로 바꿀 때 쓰는 전압

fun fanLoadA(s: VehicleSnapshot): Double = s.hvacFanLevel.toDouble() / s.fanMax.coerceAtLeast(1) * 15.0

// 전기차 고전압 배터리에서 나가는 전력 (kW): 공조 + 12V 부하(LDC 경유)
fun hvLoadKw(s: VehicleSnapshot): Double {
    val hvac = if (s.hvacFanLevel > 0) EV_HVAC_KW else 0.0
    val other12vA = (electricLoadA(s) - fanLoadA(s)).coerceAtLeast(0.0)
    return hvac + other12vA * LDC_VOLT / 1000.0
}

data class WaitPlan(
    val minutes12v: Double?,   // 12V 기준: 보호 하한선까지 (12V가 안 줄면 null)
    val minutesHv: Double?,    // 전기차 고전압 기준: 20%까지 (내연기관이면 null)
    val minutes: Double?,      // 둘 중 짧은 쪽 (둘 다 null이면 걱정 없음)
    val basis: String          // "12V" / "고전압" / ""
)

fun waitPlan(s: VehicleSnapshot, soc: Double, tempC: Double, conf: Confidence): WaitPlan {
    val m12 = protectPlan(s, soc, tempC, conf).minutesLeft
    val mHv = if (!s.isEv) null else {
        val kw = hvLoadKw(s)
        if (kw < 0.01) null else (s.hvSoc - EV_WAIT_MIN_HV).coerceAtLeast(0.0) / 100.0 * EV_HV_KWH / kw * 60.0
    }
    val m = listOfNotNull(m12, mHv).minOrNull()
    val basis = when {
        m == null -> ""
        m12 != null && (mHv == null || m12 <= mHv) -> "12V"
        else -> "고전압"
    }
    return WaitPlan(m12, mHv, m, basis)
}

// what-if: 하나씩 줄였을 때 남은 시간이 얼마나 늘어나나 (gainMin = null → 더 이상 줄지 않음)
data class WhatIf(val label: String, val gainMin: Double?, val target: String?)

fun waitWhatIfs(s: VehicleSnapshot, soc: Double, tempC: Double, conf: Confidence): List<WhatIf> {
    val base = waitPlan(s, soc, tempC, conf).minutes ?: return emptyList()
    val cands = buildList {
        if (s.seatHeaterOn && s.seatHeatA > SEAT_HEAT_LOW_A)
            add(Triple("시트 열선 1단으로 낮추기", s.copy(seatHeatA = SEAT_HEAT_LOW_A), null as String?))
        if (s.seatHeaterOn) add(Triple("시트 열선 끄기", s.copy(seatHeaterOn = false, passengerHeaterOn = false), "seat"))
        if (s.rearDefrostOn) add(Triple("뒷유리 열선 끄기", s.copy(rearDefrostOn = false), "defrost"))
        if (s.steeringHeaterOn) add(Triple("운전대 열선 끄기", s.copy(steeringHeaterOn = false), "steer"))
        if (s.mirrorHeaterOn) add(Triple("미러 열선 끄기", s.copy(mirrorHeaterOn = false), "mirror"))
        if (s.hvacFanLevel > 1) add(Triple("공조 팬 1단으로 낮추기", s.copy(hvacFanLevel = 1), null as String?))
        if (s.hvacFanLevel > 0) add(Triple("공조 끄기", s.copy(hvacFanLevel = 0), "hvac"))
    }
    return cands.map { (label, s2, target) ->
        val m = waitPlan(s2, soc, tempC, conf).minutes
        WhatIf(label, m?.let { it - base }, target)
    }.filter { it.gainMin == null || it.gainMin >= 1.0 }
        .sortedByDescending { it.gainMin ?: Double.MAX_VALUE }
}

// ───── Z2 겨울 출발 브리핑 ─────
// 시동 켜고 앱이 처음 뜰 때 1회: 어젯밤 12V 변화 + 오늘 회복 주행 처방 + 겨울 점검 (6시간 이상 주차 & 5°C 이하만)
const val BRIEF_MIN_PARK_H = 6.0            // 전날 주차 6시간 이상일 때만
const val BRIEF_MAX_TEMP_C = 5.0            // 출발 전 기온 5°C 이하일 때만
const val BRIEF_SHOW_SEC = 10               // 10초 뒤 자동으로 닫힘
const val EVENING_ARRIVE_HOUR = 19          // 오늘 밤 도착 시각 가정 (19시) → 내일 출발까지가 오늘 밤 주차
const val BRIEF_MAX_DRIVE_MIN = 180         // 이보다 길면 "주행만으로 어려움"

data class Briefing(
    val parkH: Double,         // 어젯밤 주차 시간
    val drop: Double,          // 밤사이 빠진 12V (%p)
    val socNow: Double,        // 지금 12V
    val cause: String,         // 주차 소모 원인 1위 (예: 블랙박스 92%)
    val tempNow: Double,       // 지금 기온
    val nightMin: Double,      // 오늘 밤~내일 아침 최저 기온
    val nightParkH: Double,    // 오늘 밤 주차 시간 (19시 도착 → 내일 출발)
    val needSoc: Double,       // 내일 아침 시동에 필요한 오늘 밤 도착 12V
    val driveMin: Int?         // 회복 주행 분 (0 = 필요 없음, null = 주행만으로 어려움)
)

// 회복 주행 시간 = (오늘 밤 필요 12V − 지금 12V) × 용량 ÷ (주행 중 충전 여유 × 저온 충전 효율)
fun winterBriefing(s: VehicleSnapshot, parkH: Double, drop: Double, socNow: Double,
                   tempNow: Double, nightMin: Double): Briefing {
    val ah = usableAh(s)
    val departH = s.departHour + s.departMinute / 60.0
    val nightParkH = (24 - EVENING_ARRIVE_HOUR + departH).coerceIn(6.0, 20.0)
    val need = ((START_LIMIT_SOC + parkedDrainA(s) * nightParkH / ah * 100) / tempFactor(nightMin)).coerceAtMost(100.0)
    val shortage = need - socNow
    val rateA = chargeNetA(heatersOff(s).copy(driveMode = "D", engineOffWait = false)).coerceAtLeast(0.0) * tempFactor(tempNow)
    val driveMin = when {
        shortage <= 0 -> 0
        rateA <= 0.05 -> null
        else -> Math.ceil(shortage / 100 * ah / rateA * 60).toInt().takeIf { it <= BRIEF_MAX_DRIVE_MIN }
    }
    val parts = parkedBreakdown(s)
    val total = parts.sumOf { it.second }
    val top = parts.maxByOrNull { it.second }
    val cause = if (top == null || total <= 0) "" else "${top.first} ${(top.second / total * 100).roundToInt()}%"
    return Briefing(parkH, drop, socNow, cause, tempNow, nightMin, nightParkH, need, driveMin)
}

// ───── Y3 주차 에너지 영수증 + 주간 리포트 ─────
// 주차 구간마다 "얼마나, 어디에 썼는지"를 영수증처럼 기록 → 사용자가 스스로 설정을 바꾸게
const val LOW_POWER_DASHCAM_MA = 120        // 블랙박스 저전력(타임랩스) 녹화 가정 0.12A
const val RECEIPT_MIN_PARK_H = 3.0          // 3시간 미만 주차는 아침 영수증 안 띄움
const val RECEIPT_SHOW_WITHIN_H = 18.0      // 주차 끝난 지 18시간 안이면 영수증 표시
const val PARK_RECORD_MIN_H = 1.0           // 1시간 이상 꺼져 있던 구간만 기록

data class ParkRecord(
    val endTime: Long,                      // 주차가 끝난(앱이 다시 켜진) 시각
    val hours: Double,                      // 주차 시간
    val causes: List<Pair<String, Double>>, // 원인별 사용량 (Ah, 보정계수 포함)
    val tempC: Double,                      // 그때 기온 (추위 반영 기온)
    val socAfter: Double                    // 주차 끝났을 때 12V
) {
    val totalAh: Double get() = causes.sumOf { it.second }
}

// 주차 소모 전류(보정 포함) × 시간 → 원인별 비율로 나눠서 기록
fun makeParkRecord(s: VehicleSnapshot, hours: Double, drainA: Double, tempC: Double, socAfter: Double,
                   endTime: Long = System.currentTimeMillis()): ParkRecord {
    val parts = parkedBreakdown(s)
    val sum = parts.sumOf { it.second }.coerceAtLeast(1e-6)
    val total = drainA * hours
    return ParkRecord(endTime, hours, parts.map { it.first to total * it.second / sum }, tempC, socAfter)
}

fun shareText(part: Double, total: Double): String {
    if (total <= 0) return "0%"
    val p = part / total * 100
    return if (p < 1) "1% 미만" else "${p.roundToInt()}%"
}

fun fractionText(r: Double): String = when {
    r >= 1.0 / 2 -> "절반 이상"
    r >= 1.0 / 3 -> "1/3 이상"
    r >= 1.0 / 4 -> "1/4 이상"
    else -> "${(r * 100).roundToInt()}%"
}

data class WeeklyReport(
    val count: Int,                          // 주차 횟수
    val hours: Double,                       // 주차 시간 합계
    val totalAh: Double,
    val causes: List<Pair<String, Double>>,  // 원인별 합계 (큰 순서)
    val saving: String?                      // 가장 큰 원인과 절약 제안
)

fun weeklyReport(s: VehicleSnapshot, records: List<ParkRecord>, now: Long = System.currentTimeMillis()): WeeklyReport {
    val week = records.filter { it.endTime >= now - 7 * 24 * 3_600_000L }
    val causes = week.flatMap { it.causes }.groupBy { it.first }
        .map { (k, v) -> k to v.sumOf { it.second } }.sortedByDescending { it.second }
    val total = causes.sumOf { it.second }
    val dash = causes.firstOrNull { it.first == "블랙박스" }?.second ?: 0.0
    val head = causes.firstOrNull { it.first == "전조등" }?.second ?: 0.0
    val saving = when {
        week.isEmpty() -> null
        dash >= 1.0 && s.dashcamMa > LOW_POWER_DASHCAM_MA -> {
            val save = dash * (1 - LOW_POWER_DASHCAM_MA.toDouble() / s.dashcamMa)
            "블랙박스를 저전력 모드로 바꾸면 주 약 ${save.roundToInt()}Ah 절약 (12V 배터리 ${s.batteryAh}Ah의 ${fractionText(save / s.batteryAh)})"
        }
        head >= 1.0 -> "전조등을 켜둔 채 주차해서 ${"%.1f".format(head)}Ah를 썼어요. 내릴 때 전조등을 꼭 확인해 주세요"
        else -> "주차 중 소모가 적은 편이에요. 지금 설정을 그대로 유지하세요"
    }
    return WeeklyReport(week.size, week.sumOf { it.hours }, total, causes, saving)
}

// 영수증 마지막 줄: 지금 12V로 오늘 시동 여유
fun startRoomText(socNow: Double, tempC: Double): String {
    val eff = socNow * tempFactor(tempC)
    return when {
        eff >= START_LIMIT_SOC + 15 -> "오늘 시동 여유 충분"
        eff >= START_LIMIT_SOC -> "오늘 시동 가능하지만 여유가 적어요"
        else -> "오늘 시동이 어려울 수 있어요"
    }
}

// ───── Y4 장소별 주차 프로필 ─────
// 같은 차라도 집 지하주차장과 회사 지상주차장은 위험이 다름 → 장소마다 평균 주차시간·온도 보정·위험 횟수를 따로 학습
const val PLACE_RADIUS_M = 200.0           // 반경 200m 안이면 같은 장소
const val PLACE_UNDERGROUND_BONUS_C = 6.0  // 장소 프로필 지하 보정 기본값 (요청서 예시 +6°C, 사용자가 바꿀 수 있음)

fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
}

data class PlaceAdvice(
    val tempC: Double,        // 장소 보정 반영 기온
    val startable: Double,    // 지금 12V로 시동 한계 (시간)
    val risk: Risk,           // 이 장소 평균 주차 시간 기준 위험도
    val dashcam: String       // 블랙박스 권장 설정
)

// 장소별 판정: 이 장소의 평균 주차 시간 동안, 이 장소 온도에서 버티는지
fun placeAdvice(s: VehicleSnapshot, socNow: Double, outsideC: Double, avgHours: Double,
                underground: Boolean, bonusC: Double): PlaceAdvice {
    val t = outsideC + if (underground) bonusC else 0.0
    val h = predictHours(s, socNow, t)
    val risk = riskLevel(s, h, avgHours)
    val dash = when {
        !s.dashcamParkingOn -> "블랙박스 주차녹화 꺼짐"
        risk == Risk.OK -> "상시녹화 가능"
        else -> "주차녹화 저전력 권장"
    }
    return PlaceAdvice(t, h, risk, dash)
}

fun placeRiskWord(r: Risk): String = when (r) { Risk.OK -> "낮음"; Risk.CAUTION -> "주의"; Risk.DANGER -> "위험" }

// [시연용] T6: "집(지하) 낮음 / 회사(지상) 주의"가 같이 나오는 12V 값을 찾음 (없으면 회사가 주의가 되는 값)
fun t6DemoSoc(s: VehicleSnapshot, outsideC: Double, homeH: Double, homeBonus: Double, officeH: Double): Double {
    val ok = (300..950).map { it / 10.0 }.filter { soc ->
        placeAdvice(s, soc, outsideC, homeH, true, homeBonus).risk == Risk.OK &&
                placeAdvice(s, soc, outsideC, officeH, false, 0.0).risk == Risk.CAUTION
    }
    if (ok.isNotEmpty()) return (ok.first() + ok.last()) / 2
    val caution = (300..950).map { it / 10.0 }.filter { placeAdvice(s, it, outsideC, officeH, false, 0.0).risk == Risk.CAUTION }
    return caution.lastOrNull() ?: 60.0
}

// ───── Z4 12V 관리 점수 (주 단위, 0~100) ─────
// 여유 40 + 회복 25 + 설정 15 + 대응 20
const val SCORE_MARGIN_FULL = 20.0         // 주간 최저 12V가 시동 필요보다 20%p 이상 높으면 여유 만점
const val SCORE_RESPONSE_MIN = 30          // 경고 받고 30분 안에 조치해야 "대응"으로 인정

data class CareScore(
    val total: Int,
    val margin: Int, val recovery: Int, val setting: Int, val response: Int,
    val marginNote: String, val recoveryNote: String, val settingNote: String, val responseNote: String
) {
    val grade: String get() = when {
        total >= 90 -> "안심"
        total >= 70 -> "양호"
        total >= 50 -> "주의"
        else -> "점검 필요"
    }
}

fun careScore(
    s: VehicleSnapshot,
    minMargin: Double?,                     // 주간 최저 12V − 시동 필요 12V (%p), 기록 없으면 null
    recoveries: List<Pair<Int, Int>>,       // (처방 분, 실제 달린 분)
    warns: Int, acted: Int
): CareScore {
    val m = if (minMargin == null) 40 else (40 * (minMargin / SCORE_MARGIN_FULL).coerceIn(0.0, 1.0)).roundToInt()
    val mNote = if (minMargin == null) "기록 없음 → 만점 처리" else "주간 최저 여유 ${"%.0f".format(minMargin)}%p (20%p 이상 만점)"
    val r = if (recoveries.isEmpty()) 25 else
        (25 * recoveries.map { (need, done) -> (done.toDouble() / need.coerceAtLeast(1)).coerceIn(0.0, 1.0) }.average()).roundToInt()
    val rNote = if (recoveries.isEmpty()) "회복 주행 처방 없음 → 만점"
    else "회복 주행 처방 ${recoveries.size}번 · 달성 ${recoveries.count { it.second >= it.first }}번"
    val st = if (!s.dashcamParkingOn) 15 else
        (if (s.dashcamMa <= LOW_POWER_DASHCAM_MA) 8 else 0) + (if (s.dashcamCutoffSet) 7 else 0)
    val stNote = if (!s.dashcamParkingOn) "주차녹화 꺼짐 → 만점" else
        "저전력 녹화 ${if (s.dashcamMa <= LOW_POWER_DASHCAM_MA) "O" else "X"} · 저전압 차단 설정 ${if (s.dashcamCutoffSet) "O" else "X"}"
    val rs = if (warns == 0) 20 else (20.0 * acted / warns).roundToInt()
    val rsNote = if (warns == 0) "경고 없음 → 만점" else "경고 ${warns}번 중 ${SCORE_RESPONSE_MIN}분 안에 조치 ${acted}번"
    return CareScore(m + r + st + rs, m, r, st, rs, mNote, rNote, stNote, rsNote)
}

// 이번 주 시작 (월요일 0시)
fun weekStart(t: Long = System.currentTimeMillis()): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val dow = c.get(Calendar.DAY_OF_WEEK)               // 일=1 … 토=7
    c.add(Calendar.DAY_OF_YEAR, -((dow + 5) % 7))       // 월요일로
    return c.timeInMillis
}

// ───── Z3 차종별 방전 대처 가이드 ─────
// 차종 데이터는 앱 안 JSON으로 관리. 리셋 버튼·점프 단자 위치는 취급설명서로 확인한 것만 넣는다 (verified=true)
// 확인 전에는 위치를 지어내지 않고 MANUAL_CHECK 문구를 보여준다 (요청서 데이터 주의 규칙)
const val MANUAL_CHECK = "취급설명서의 '배터리 방전 시' 항목을 확인하세요"
const val KIA_EMERGENCY_TEL = "0802002000"     // 기아 고객센터 (긴급출동 24시간, kia.com 확인 11/8)

// match: VIN 앞부분(또는 시연용 가짜 VIN)으로 차종 찾기. 실제 VIN 코드는 확인되면 추가
val CAR_GUIDE_JSON = """
[
  {"id":"grandeur_hev","name":"그랜저 하이브리드","maker":"현대","group":"HEV","match":["DEMO-HEV"],"reset":"","terminal":"","verified":false},
  {"id":"ioniq5","name":"아이오닉 5","maker":"현대","group":"EV","match":["DEMO-EV"],"reset":"","terminal":"","verified":false},
  {"id":"generic_ice","name":"내연기관 승용","maker":"현대","group":"ICE","match":["DEMO-ICE"],"reset":"","terminal":"","verified":false}
]
"""

data class CarGuide(val id: String, val name: String, val maker: String, val group: String,
                    val match: List<String>, val reset: String, val terminal: String, val verified: Boolean)

val CAR_GUIDES: List<CarGuide> by lazy {
    try {
        val arr = JSONArray(CAR_GUIDE_JSON)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.getJSONArray("match")
            CarGuide(o.getString("id"), o.getString("name"), o.getString("maker"), o.getString("group"),
                (0 until m.length()).map { m.getString(it) }, o.getString("reset"), o.getString("terminal"), o.getBoolean("verified"))
        }
    } catch (e: Exception) { emptyList() }
}

fun groupLabel(g: String): String = when (g) { "HEV" -> "하이브리드"; "EV" -> "전기차"; "ICE" -> "내연기관"; else -> "차종 미확인" }

data class GuideStep(val title: String, val lines: List<String>)
data class DischargeGuide(val title: String, val group: String, val verified: Boolean, val steps: List<GuideStep>,
                          val telName: String, val tel: String)

fun dischargeGuide(vinForMatch: String?, maker: String?, isEv: Boolean): DischargeGuide {
    val g = vinForMatch?.let { v -> CAR_GUIDES.firstOrNull { cg -> cg.match.any { v.startsWith(it) } } }
    val group = g?.group ?: if (isEv) "EV" else "UNKNOWN"
    val electric = group == "HEV" || group == "EV"
    val reset = if (g != null && g.verified && g.reset.isNotBlank()) g.reset else "12V 리셋 버튼이 있는 차종인지 $MANUAL_CHECK"
    val terminal = if (g != null && g.verified && g.terminal.isNotBlank()) "점프 단자 위치: ${g.terminal}" else "점프 단자 위치: $MANUAL_CHECK"
    val step1 = GuideStep("1단계 · 스스로 해보기", buildList {
        if (electric) {
            add("고전압 배터리가 남아 있어도 12V가 방전되면 시동(READY)이 걸리지 않아요")
            add(reset)
        } else {
            add("실내등·전조등·블랙박스 등 전기 장치를 모두 끄고 한 번만 다시 시동해 보세요")
            add("시동을 여러 번 반복하면 배터리가 더 약해져요")
            if (group == "UNKNOWN") add("하이브리드·전기차라면 $reset")
        }
    })
    val step2 = GuideStep("2단계 · 점프 시동", buildList {
        add("두 차 모두 시동과 전기 장치를 끈 상태에서 연결하세요")
        add("① 빨간 선: 방전된 차 (+) → 도와주는 차 (+)  ← + 먼저")
        add("② 검은 선: 도와주는 차 (−) → 방전된 차 엔진룸의 금속 부분  ← − 나중")
        add("③ 도와주는 차 시동 → 몇 분 뒤 방전된 차 시동")
        add("④ 뗄 때는 연결한 반대 순서로")
        add(terminal)
        if (electric) add("주황색 고전압 배선과 부품은 절대 만지지 마세요")
        if (group == "EV") add("전기차로 다른 차를 점프해 줘도 되는지는 취급설명서를 먼저 확인하세요")
    })
    val kia = (g?.maker ?: maker) == "기아"
    val telName = if (kia) "기아 고객센터 (긴급출동 24시간)" else "현대 긴급출동"
    val tel = if (kia) KIA_EMERGENCY_TEL else EMERGENCY_TEL
    val step3 = GuideStep("3단계 · 긴급출동", listOf(
        "$telName ${tel.substring(0, 3)}-${tel.substring(3, 6)}-${tel.substring(6)}",
        "가입한 자동차 보험의 긴급출동 서비스도 쓸 수 있어요",
        "시동이 걸린 뒤에는 가까운 블루핸즈에서 배터리 점검을 받아 보세요"
    ))
    val title = g?.name ?: if (isEv) "전기차 일반 가이드" else "일반 가이드"
    return DischargeGuide(title, group, g?.verified ?: false, listOf(step1, step2, step3), telName, tel)
}

// ───── Z5 블루핸즈 QR 공유: 30일 기록 요약 (500자 이내, 위치·경로는 넣지 않음) ─────
const val QR_MAX_CHARS = 500

fun qrSummary(
    s: VehicleSnapshot, vinMasked: String?, socMin: Double?, socAvg: Double?, yellowDays: Int, redDays: Int,
    k: Double, kCount: Int, volts: List<Double>, score: CareScore, topCause: String
): String {
    val lines = listOf(
        "[방전 알리미 12V 기록]",
        "차량: " + listOfNotNull(s.carMaker, s.carModelYear?.let { "${it}년식" }).joinToString(" ").ifBlank { "정보 없음" } +
                " / 차대번호 " + (vinMasked ?: "없음"),
        "배터리: ${s.batteryAgeYears}년차 · ${s.batteryAh}Ah (설정값)",
        "30일 추정 12V: 최저 ${socMin?.roundToInt()?.let { "$it%" } ?: "-"} · 평균 ${socAvg?.roundToInt()?.let { "$it%" } ?: "-"}",
        "위험 일수: 주의 ${yellowDays}일 · 위험 ${redDays}일",
        "시동 보정계수 k ${"%.2f".format(k)} (보정 ${kCount}회)",
        "블랙박스 전압 기록: " + (if (volts.isEmpty()) "없음" else volts.takeLast(3).joinToString(", ") { "%.2fV".format(it) }),
        "12V 관리 점수: ${score.total}점 (${score.grade})",
        "주차 소모 원인 1위: " + topCause.ifBlank { "기록 없음" }
    )
    return lines.joinToString("\n").take(QR_MAX_CHARS)
}

// 30일 주차 기록에서 원인 1위
fun topCause30(records: List<ParkRecord>, now: Long = System.currentTimeMillis()): String {
    val causes = records.filter { it.endTime >= now - 30L * 24 * 3_600_000L }.flatMap { it.causes }
        .groupBy { it.first }.map { (k, v) -> k to v.sumOf { it.second } }.sortedByDescending { it.second }
    val total = causes.sumOf { it.second }
    val top = causes.firstOrNull() ?: return ""
    return "${top.first} ${shareText(top.second, total)}"
}

// 속도 개선용: 곡선 계산 결과 묶음 (AppScreen에서 remember로 재사용)
data class CurveSet(
    val curve: List<CurvePoint>,
    val curveDashOff: List<CurvePoint>?,
    val hours: Double,
    val hoursLow: Double,
    val hoursHigh: Double
)