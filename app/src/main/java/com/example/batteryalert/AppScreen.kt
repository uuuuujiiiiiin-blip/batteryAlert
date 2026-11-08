package com.example.batteryalert

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ai.pleos.playground.navi.constants.RouteOption
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import android.util.Log

val RED = Color(0xFFFFE0E0)
val GREEN = Color(0xFFE0F7E9)
val YELLOW = Color(0xFFFFF4D6)
val BLUE = Color(0xFFE3F0FF)

// ───── 긴급출동 ─────
const val EMERGENCY_TEL = "0806006000"   // 현대차 긴급출동 지원센터 (승용)

fun callEmergency(context: Context, tel: String = EMERGENCY_TEL) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "전화 앱을 열 수 없어요: $tel", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun AppScreen(source: VehicleSource, modifier: Modifier = Modifier) {
    val raw by source.state.collectAsState()
    val context = LocalContext.current

    // ── 사용자 설정: 앱 켤 때 불러오고, 바뀔 때마다 저장 ──
    var settingsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        (source as? FakeVehicleSource)?.update { UserSettings.load(context, it).copy(drainK = SelfCal.k(context)) }
        settingsLoaded = true
    }
    val settingsKey = UserSettings.key(raw)
    LaunchedEffect(settingsKey, settingsLoaded) {
        if (settingsLoaded) UserSettings.save(context, raw)
    }

    // ── 음성 안내: Pleos TextToSpeech SDK ──
    val voice = remember { PleosVoice(context) }
    DisposableEffect(Unit) {
        voice.start()
        onDispose { voice.stop() }
    }

    // ── F5 출발 시각 학습: 자동 학습이 켜져 있고 학습되면 학습값으로 출발 시각을 채움 ──
    var departures by remember { mutableStateOf(DepartureLog.load(context)) }
    val learned = learnNextDeparture(departures)
    // Y2 차박·대기 모드 상태 (계산에 들어가야 해서 맨 위에 둠)
    var waitMode by remember { mutableStateOf(false) }
    var waitEngineOff by remember { mutableStateOf(true) }   // 내연기관: 시동 끄고 대기(기본) / 시동 켜고 대기
    val base = if (raw.autoDepart && learned != null)
        raw.copy(departDaysLater = learned.daysLater, departHour = learned.hour, departMinute = learned.minute)
    else raw
    val evNow = base.isEv && !base.demoForceIce          // [시연] 전기차여도 내연기관으로 계산
    val s = base.copy(isEv = evNow, engineOffWait = waitMode && waitEngineOff && !evNow,
        tireLow = if (base.demoTireLow) true else base.tireLow,          // [시연] 가짜 경고 신호
        washerLow = if (base.demoWasherLow) true else base.washerLow)
    val departInfo = when {
        !raw.autoDepart -> "직접 입력"
        learned != null -> "자동 학습: ${learned.basis} (기록 ${learned.samples}회)"
        else -> "학습 중 — 출발 기록 ${departures.size}회 (같은 요일 2회 이상 필요)"
    }
    val scope = rememberCoroutineScope()
    // Y5: 주차 절전 중이면 차량 읽기·내비 요청·날씨 요청·장부 계산을 모두 멈춤
    var sleeping by remember { mutableStateOf(false) }
    var sleepSince by remember { mutableStateOf(0L) }
    var showDialog by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(AlertHistory.load(context)) }

    // ── 내비 연동 (현재 위치 + 길 안내) ──
    val navi = remember { NaviBridge(context) }
    DisposableEffect(Unit) {
        navi.start()
        onDispose { navi.stop() }
    }
    var nearby by remember { mutableStateOf<List<Place>>(emptyList()) }
    var bhStatus by remember { mutableStateOf("") }

    fun findBluehands() {
        scope.launch {
            bhStatus = "현재 위치 확인 중..."
            val loc = navi.awaitLocation()
            val lat = loc?.latitude ?: WEATHER_LAT
            val lon = loc?.longitude ?: WEATHER_LON
            val where = loc?.address?.ifBlank { null } ?: if (loc != null) "차량 위치" else "기본 위치(울산)"
            bhStatus = "$where 기준으로 블루핸즈 검색 중..."
            val res = searchBluehands(lat, lon)
            when {
                res == null -> bhStatus = "검색 실패 — API 키 / 카카오맵 사용 설정 확인"
                res.isEmpty() -> bhStatus = "반경 20km 안에 블루핸즈가 없어요"
                else -> {
                    nearby = res
                    val p = res.first()
                    val km = "%.1f".format(p.distanceM / 1000.0)
                    bhStatus = if (navi.routeTo(p)) "내비 길 안내 시작: ${p.name} (${km}km)"
                    else "가장 가까운 곳: ${p.name} (${km}km) — 내비 연결 안 됨"
                }
            }
        }
    }

    // ── 날씨 예보 (차 위치 기준, 없으면 울산) ──
    var forecast by remember { mutableStateOf<List<HourTemp>?>(null) }
    var weatherStatus by remember { mutableStateOf("날씨 받는 중...") }
    LaunchedEffect(Unit) {
        delay(3_000)   // 내비에서 위치 받을 시간
        while (true) {
            if (sleeping) snapshotFlow { sleeping }.first { !it }   // Y5: 절전 중에는 네트워크 요청 안 함
            val loc = navi.lastLocation
            val f = fetchHourlyForecast(loc?.latitude ?: WEATHER_LAT, loc?.longitude ?: WEATHER_LON)
            if (f != null) {
                forecast = f
                weatherStatus = "예보 수신 완료 (${if (loc != null) "차량 위치" else "울산 기본값"})"
            } else if (forecast == null) {
                weatherStatus = "날씨 받기 실패 → 수동값 사용"
            }
            delay(30 * 60 * 1000L)
        }
    }

    val parkHours = hoursUntilDeparture(s)
    val forecastMin = minTempUntilDeparture(forecast, parkHours)
    val useForecast = s.autoWeather && forecastMin != null
    val outsideC = if (useForecast) forecastMin!! else s.outsideTempC
    // Y4 장소 프로필: P단에서 아는 장소에 있으면 그 장소의 지하/지상·실내 보정을 씀 (설정의 지하주차보다 우선)
    var places by remember { mutableStateOf(PlaceLog.load(context)) }
    var curPlaceId by remember { mutableStateOf(PlaceLog.currentId(context)) }
    val curPlace = if (s.driveMode == "P") places.firstOrNull { it.id == curPlaceId } else null
    val placeBonus = when {
        curPlace != null -> if (curPlace.underground) curPlace.bonusC else 0.0
        s.undergroundParking -> UNDERGROUND_BONUS_C        // 지하주차 +5°C
        else -> 0.0
    }
    val tempC = outsideC + placeBonus

    // ── F1 에너지 장부: 앱 켜질 때 꺼져 있던 시간만큼 소급 → 그 뒤 2초마다 적분 ──
    var ledger by remember { mutableStateOf(EnergyLedger.load(context)) }
    val latestS by rememberUpdatedState(s)
    // Y3 주차 기록 (영수증·주간 리포트)
    var parkLog by remember { mutableStateOf(ParkLog.load(context)) }
    var receiptSeen by remember { mutableStateOf(ParkLog.seenEndTime(context)) }
    fun recordParking(hours: Double, drainA: Double) {
        if (hours < PARK_RECORD_MIN_H) return
        parkLog = ParkLog.add(context, makeParkRecord(latestS, hours, drainA, tempC, ledger.soc))
        PlaceLog.onParkEnded(context, hours)                    // Y4: 이 장소의 평균 주차 시간·이름 추천
        places = PlaceLog.load(context)
    }
    // Z4 점수 기록이 바뀌면 다시 계산
    var scoreVer by remember { mutableStateOf(0) }
    fun userActed(what: String) {                              // Z4 대응 점수: 경고 30분 안에 사용자가 조치
        ScoreLog.markActed(context, what)
        scoreVer++
    }
    var askStart by remember { mutableStateOf(false) }      // W1 "어제 시동 괜찮았나요?" 질문
    var gapHours by remember { mutableStateOf(0.0) }
    // Z2 겨울 출발 브리핑: (주차 시간, 빠진 12V) — 앱 시작 소급 때 1번 정해짐
    var briefStart by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var briefLeft by remember { mutableStateOf<Int?>(null) }   // 브리핑 표시 중이면 남은 초

    // W1 보정 결과를 화면 상태와 장부(신뢰도 +3점/회)에 반영
    fun refreshSelfCal() {
        (source as? FakeVehicleSource)?.update { it.copy(drainK = SelfCal.k(context)) }
        ledger = EnergyLedger.setSelfCalCount(context, ledger, SelfCal.count(context))
    }

    // G3: 실측 보정할 때 장부 예측과 비교해서 기록 → 치우침이 있으면 보정계수 자동 수정
    fun measureAndCalibrate(newSoc: Double, src: String, note: String = ""): String {
        val msg = SelfCal.recordMeasurement(context, ledger.soc, newSoc, ledger.resetSource != EnergyLedger.SRC_NONE)
        ledger = EnergyLedger.calibrate(context, ledger, newSoc, src, note)
        refreshSelfCal()
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        return msg
    }

    LaunchedEffect(Unit) {
        delay(3_000)   // SDK 값(전기차 여부, 기어 등) 받을 시간
        gapHours = (System.currentTimeMillis() - EnergyLedger.load(context).lastTick) / 3_600_000.0
        val socBefore = ledger.soc
        val drainBefore = ledger.lastDrainA
        ledger = EnergyLedger.catchUp(context, latestS)
        recordParking(gapHours, drainBefore)                    // Y3: 꺼져 있던 동안을 주차 기록으로
        briefStart = gapHours to (socBefore - ledger.soc)      // Z2: 어젯밤 주차 시간 + 빠진 양
        refreshSelfCal()
        // W1: 오래 꺼져 있다가 켜졌고 지난 주차 예측이 남아 있으면 (예측 결과와 상관없이) 시동 결과를 물어봄
        if (gapHours >= SelfCal.ASK_GAP_HOURS && SelfCal.pending(context) != null) askStart = true
        while (true) {
            delay(2_000)
            if (sleeping) { snapshotFlow { sleeping }.first { !it }; continue }   // Y5: 절전 중에는 계산도 멈춤
            ledger = EnergyLedger.tick(context, ledger, latestS)
        }
    }
    val socNow = ledger.soc
    val unc = ledger.uncertainty()

    // W3 신뢰도: 낮으면 오차 하한값으로 보수적으로 판정
    val confScore = ledger.confidence()
    val conf = confidenceLevel(confScore)
    val judgeSoc = if (conf == Confidence.LOW) (socNow - unc).coerceAtLeast(0.0) else socNow
    val confText = "추정 신뢰도: ${conf.label} (" +
            (if (ledger.resetSource == EnergyLedger.SRC_NONE) "실측 기록 없음, 설치 후"
            else "${ledger.resetSource} 실측 후") + " ${ledger.daysSinceReset().toInt()}일)"
    val netA = chargeNetA(s)

    // ① 이번 주행 변화: 장부 기록이 있으면 장부 기준, 없으면 추정
    val tripChange: Double? = ledger.tripStartSoc?.let { start -> (ledger.tripEndSoc ?: socNow) - start }
    val change = tripChange ?: tripSocChange(s)

    // G3: 날씨 예보가 있으면 시간대별 기온으로 계산 (없으면 최저기온 하나로)
    val bonusC = placeBonus                         // Y4 장소 보정까지 반영 (기존: 설정의 지하주차만)
    val fcForCurve = if (useForecast) forecast else null
    val curveMax = (Math.round(parkHours * 1.6 * 10) / 10.0).coerceIn(12.0, 24.0 * 7)
    // 속도 개선: 시간대별 곡선 계산(예보 192시간 × 15분 간격)은 무거워서, 입력값이 바뀔 때만 다시 계산
    // (12V는 0.1% 단위로 반올림해서 비교)
    val socKey = Math.round(judgeSoc * 10)
    val uncKey = Math.round(unc * 10)
    val curves = remember(s, fcForCurve, socKey, uncKey, tempC, bonusC, curveMax) {
        fun curveFor(x: VehicleSnapshot, soc: Double) = startCurve(x, soc, fcForCurve, tempC, bonusC, curveMax)
        fun hoursFor(x: VehicleSnapshot, soc: Double): Double =
            if (fcForCurve == null) predictHours(x, soc, tempC) else (crossingHours(curveFor(x, soc)) ?: curveMax)
        val c = curveFor(s, judgeSoc)
        val cOff = if (s.dashcamParkingOn) curveFor(s.copy(dashcamParkingOn = false), judgeSoc) else null
        val h = if (fcForCurve == null) predictHours(s, judgeSoc, tempC) else (crossingHours(c) ?: curveMax)
        CurveSet(c, cOff, h, hoursFor(s, (socNow - unc).coerceAtLeast(0.0)), hoursFor(s, (socNow + unc).coerceAtMost(100.0)))
    }
    val curve = curves.curve
    val curveDashOff = curves.curveDashOff
    val hours = curves.hours
    val hoursLow = curves.hoursLow
    val hoursHigh = curves.hoursHigh
    val saver = evSaverAvailable(s)
    val risk = riskLevel(s, hours, parkHours)
    val warnings = parkingWarnings(s)
    val advice = parkingAdvice(s, parkHours, hours, judgeSoc, tempC)
    val longParking = isLongParking(parkHours)
    val dashAdvice = dashcamAdvice(s, judgeSoc, tempC)
    val report = batteryReport(history, s)

    // ── X1 도착 전 선제 예측: 내비 경로(또는 시연용 가상 내비)로 "집 도착 N분 전" 판단 ──
    val driving = s.driveMode != "P"   // 기어 SDK가 없으면 기본값 D → 주행 중으로 봄
    var naviDest by remember { mutableStateOf<ai.pleos.playground.navi.data.DestinationInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (sleeping) snapshotFlow { sleeping }.first { !it }   // Y5: 절전 중에는 내비에 묻지 않음
            navi.requestRouteInfo()
            delay(1_000)
            naviDest = navi.activeDestination
            delay(4_000)
        }
    }
    val home = navi.homeBookmark
    val realHome = naviDest?.let { d -> isHomeDestination(d.name, d.latitude, d.longitude, home?.latitude, home?.longitude) } == true
    val arriveMin: Double? = when {
        realHome -> naviDest!!.remainTime / 60.0
        s.fakeNaviHome -> s.fakeArriveMin.toDouble()
        else -> null
    }
    val plan = arriveMin?.let { arrivalPlan(s, judgeSoc, tempC, it, parkHours) }
    // G1 처방 엔진: 팝업·배너·④ 카드·음성이 모두 이 목록을 씀
    val rx = prescriptions(s, judgeSoc, tempC, parkHours, plan, driving)
    val actions = rx.map { it.line }

    // ── Y1 12V 보호 하한선: 지금 부하가 이어지면 몇 분 뒤 하한선에 닿는지 (2초마다 다시 계산) ──
    val protect = protectPlan(s, socNow, tempC, conf)
    var y1Keep by remember { mutableStateOf(false) }        // "유지" 선택 → 이번 주차 동안 자동 OFF 안 함
    var y1Voiced by remember { mutableStateOf(false) }      // 10분 전 음성 1회
    var y1Warned by remember { mutableStateOf(false) }      // 자동으로 못 끌 때 경고 1회
    var y1Countdown by remember { mutableStateOf<Int?>(null) }   // 자동 OFF 예고 남은 초
    var y1Done by remember { mutableStateOf(false) }        // 자동 보호로 껐음 → "시동 가능 상태 유지 중"
    // 앱을 켠 직후에는 기본값(열선 ON·팬 3단 등)으로 계산돼서 잘못 울릴 수 있음
    // → 장부 소급과 차량 실제값이 다 들어올 때까지 8초 기다린 뒤부터 Y1 동작
    var y1Ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(8_000); y1Ready = true; Log.d("BatteryAlert", "Y1 준비 완료: 12V ${"%.1f".format(ledger.soc)}%") }
    var y1HeatersOff by remember { mutableStateOf(false) }  // Y2 대기 모드: 1단계(열선만 끔) 완료

    // ── Y2 차박·대기 모드: 남은 시간(12V·고전압 중 짧은 쪽) + what-if ──
    val wait = waitPlan(s, socNow, tempC, conf)
    val whatIfs = if (waitMode) waitWhatIfs(s, socNow, tempC, conf) else emptyList()
    var waitSuggest by remember { mutableStateOf(false) }    // "대기 모드로 볼까요?" 제안 표시
    var waitDismissed by remember { mutableStateOf(false) }  // 이번 주차에서 "괜찮아요" 누름
    var preArrivalLatched by remember { mutableStateOf(false) }   // 이번 주행에서 경고를 띄웠나
    var x2Applied by remember { mutableStateOf(false) }           // 이번 주행에서 충전 경로를 받아들였나
    var x2Dismissed by remember { mutableStateOf(false) }
    var x2RouteNote by remember { mutableStateOf("") }
    var etaBeforeChange by remember { mutableStateOf<Int?>(null) }

    fun speak(text: String) {
        if (!s.voiceOn) return
        voice.speak(text)
    }

    // ── F3 주차 감지: (P 10초 유지) 또는 (P에서 운전석 문 열림) → 주차 1회만 ──
    val gearLinked = "gear" in s.sdkFields
    val isP = !gearLinked || s.driveMode == "P"
    var alertedThisParking by remember { mutableStateOf(false) }
    var lastMode by remember { mutableStateOf<String?>(null) }

    val parkingAlert by rememberUpdatedState { reason: String ->
        if (gearLinked && alertedThisParking) return@rememberUpdatedState
        alertedThisParking = true
        Log.d("BatteryAlert", "주차 감지($reason) → 위험도 $risk")
        if (x2Applied) {
            val need = plan?.needSoc
            val ok = risk == Risk.OK
            Toast.makeText(context,
                if (ok) "충전 경로 회복 완료: 12V 약 ${socNow.roundToInt()}%" else "충전 경로로 12V 약 ${socNow.roundToInt()}%까지 회복 (아직 부족)",
                Toast.LENGTH_LONG).show()
            Log.d("BatteryAlert", "X2 결과: 12V ${socNow.roundToInt()}%, 필요 ${need?.roundToInt()}%")
        }
        if (risk != Risk.OK || warnings.isNotEmpty() || advice.isNotEmpty()) {
            showDialog = true
            val say = buildList {
                if (risk == Risk.DANGER) add("배터리 방전 위험이 있어요.")
                else if (risk == Risk.CAUTION) add("배터리 여유가 많지 않아요.")
                addAll(warnings.take(2))
                if (longParking) add("장기 주차 체크리스트를 확인해 주세요.")
                add("내리기 전에 화면을 확인해 주세요.")
            }
            speak(say.joinToString(" "))
        } else {
            Toast.makeText(context, "주차 확인 완료: 출발(${departLabel(s)})까지 시동 문제없어요", Toast.LENGTH_LONG).show()
        }
        if (risk != Risk.OK) {
            AlertHistory.add(context, AlertHistory.Event(System.currentTimeMillis(), risk.name, hours, tempC))
            history = AlertHistory.load(context)
            PlaceLog.addRisk(context); places = PlaceLog.load(context)     // Y4: 장소별 위험 횟수
            ScoreLog.addWarn(context, "주차 위험 ${risk.name}"); scoreVer++   // Z4: 경고 기록
        }
        SelfCal.savePrediction(context, risk.name, hours)   // W1: 다음 출발 결과와 비교할 예측
    }

    // ── Y5 앱 자체 소모 0: 하차 확정 → 2분 뒤 주차 절전 ──
    var parkedAt by remember { mutableStateOf<Long?>(null) }   // 하차 확정 시각 (절전 타이머 시작점)
    fun markParked(reason: String) {
        if (waitMode && reason.contains("하차")) {
            waitMode = false
            Log.d("BatteryAlert", "Y2: 운전석에서 내려서 대기 모드 종료")
        }
        parkedAt = System.currentTimeMillis()
        parkingAlert(reason)
    }
    fun goSleep(reason: String) {
        if (sleeping) return
        ledger = EnergyLedger.tick(context, ledger, latestS)     // 멈추기 직전 장부 저장 (주차 소모 전류 기록)
        (source as? PleosVehicleSource)?.pausePolling(reason)
        sleepSince = System.currentTimeMillis()
        sleeping = true
        Log.d("BatteryAlert", "Y5: 주차 절전 시작 ($reason) — 차량 읽기·내비·날씨 요청 모두 중지")
    }
    fun wake(reason: String) {
        if (!sleeping) return
        val drainBefore = ledger.lastDrainA
        val sleptH = (System.currentTimeMillis() - sleepSince) / 3_600_000.0
        ledger = EnergyLedger.resumeAfterSleep(context, ledger, latestS)   // 멈춰 있던 동안의 소모를 한 번에 반영
        recordParking(sleptH, drainBefore)                                 // Y3: 절전 구간도 주차 기록으로
        (source as? PleosVehicleSource)?.resumePolling(reason)
        sleeping = false
        parkedAt = null
        Log.d("BatteryAlert", "Y5: 주차 절전 해제 ($reason)")
    }

    // X1: 출발하면 초기화, 주행 중 "집 도착 10분 전 + 주의·위험"이면 배너 1회 + 음성 1회
    LaunchedEffect(driving) {
        if (driving) { preArrivalLatched = false; x2Applied = false; x2Dismissed = false; x2RouteNote = "" }
    }
    val preArrivalNow = driving && plan != null && plan.arriveMin in 0.1..PRE_ARRIVAL_MIN && plan.risk != Risk.OK
    LaunchedEffect(preArrivalNow) {
        if (!preArrivalNow || preArrivalLatched || plan == null) return@LaunchedEffect
        preArrivalLatched = true
        Log.d("BatteryAlert", "도착 전 경고: ${plan.arriveMin.roundToInt()}분 전, 도착 시 12V ${plan.socArrive.roundToInt()}%, ${plan.risk}")
        val tip = when {
            plan.heaterGain >= 0.5 -> "지금 열선과 공조를 끄면 도착할 때 ${"%.1f".format(plan.heaterGain)}퍼센트 더 충전돼요."
            plan.extraMin != null -> "${plan.extraMin}분 정도 더 달리면 회복할 수 있어요."
            else -> "화면에서 조치를 확인해 주세요."
        }
        speak("집 도착 ${plan.arriveMin.roundToInt()}분 전입니다. 내일 아침 시동이 어려울 수 있어요. $tip")
    }
    // X2: 경로 옵션 변경 후 도착 예정 시간이 얼마나 늘었는지 확인
    LaunchedEffect(naviDest?.remainTime, x2Applied) {
        val before = etaBeforeChange ?: return@LaunchedEffect
        val now = naviDest?.remainTime ?: return@LaunchedEffect
        if (x2Applied && now != before) {
            x2RouteNote = "경로 변경 후 도착 ${"%+d".format((now - before) / 60)}분"
            etaBeforeChange = null
        }
    }

    // X4: 좌석 점유 센서가 연결되면 "P 10초"보다 "운전석에서 일어남"을 우선 사용
    val occLinked = "occ" in s.sdkFields

    // 기어가 P로 바뀐 뒤 10초 유지되면 주차로 판단 (신호 대기 P는 무시)
    LaunchedEffect(s.driveMode, gearLinked) {
        if (!gearLinked) return@LaunchedEffect
        val prev = lastMode
        lastMode = s.driveMode
        if (s.driveMode != "P") {
            alertedThisParking = false       // 출발하면 다음 주차를 위해 초기화
            parkedAt = null                  // Y5 절전 타이머 취소
            if (prev == "P" && DepartureLog.recordDeparture(context)) {   // F5: 출발 시각 기록
                departures = DepartureLog.load(context)
                SelfCal.onStartSuccess(context)                             // W1: 2시간 넘게 세웠다가 정상 출발 = 시동 성공
                refreshSelfCal()
                askStart = false
            }
            return@LaunchedEffect
        }
        if (prev != null && prev != "P") DepartureLog.markPark(context)  // F5: 주차 시작 시각 기록
        if (prev != null && prev != "P" && !occLinked) {
            delay(10_000)
            markParked("P 10초 유지")
        }
    }

    // X4: P에서 운전석 점유가 풀리면(사람이 일어나면) 하차 확정
    var prevOcc by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(s.driverSeatOccupied, occLinked) {
        if (!occLinked) { prevOcc = null; return@LaunchedEffect }   // 센서 값이 들어오기 전 기본값은 무시
        val prev = prevOcc
        prevOcc = s.driverSeatOccupied
        if (prev == true && !s.driverSeatOccupied && isP) markParked("운전석 하차 (좌석 센서)")
    }
    // X4: P에서 안전벨트를 풀면 하차 준비로 보고 기록만 (경고는 하차 확정 때)
    LaunchedEffect(s.driverBelted) {
        if (!s.driverBelted && isP && gearLinked) Log.d("BatteryAlert", "안전벨트 해제 → 하차 준비")
    }
    // P 상태에서 운전석 문이 열리면 바로 판단 (내리는 순간)
    LaunchedEffect(s.driverDoorOpen) {
        if (s.driverDoorOpen && sleeping) wake("운전석 문 열림")   // Y5: 문 신호는 절전 중에도 들어옴
        if (s.driverDoorOpen && isP) markParked("운전석 문 열림")
    }
    // Y5: 기어가 바뀌면 절전 해제 (기어 신호도 절전 중에 들어옴)
    LaunchedEffect(s.driveMode) {
        if (sleeping) wake("기어 ${s.driveMode}")
    }
    // Y4: P단이 되면 내비 현재 위치로 장소 찾기 (앱 시작 직후에는 장부 소급이 먼저 끝나도록 기다림)
    var placeChecked by remember { mutableStateOf(false) }
    LaunchedEffect(s.driveMode) {
        if (s.driveMode != "P") {
            if (curPlaceId >= 0) { PlaceLog.clearCurrent(context); curPlaceId = -1 }
            return@LaunchedEffect
        }
        delay(if (placeChecked) 1_500 else 6_000)
        placeChecked = true
        repeat(5) {
            val loc = navi.lastLocation
            if (loc != null) {
                val pl = PlaceLog.onParked(context, loc.latitude, loc.longitude)
                places = PlaceLog.load(context); curPlaceId = pl.id
                return@LaunchedEffect
            }
            delay(2_000)
        }
        Log.d("BatteryAlert", "Y4: 내비 위치를 못 받아서 장소 판별 건너뜀")
    }
    // Z4 회복 점수용: 주행 시간 기록
    var driveStartAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(driving) {
        if (driving) driveStartAt = System.currentTimeMillis()
        else driveStartAt?.let { st ->
            ScoreLog.addDrive(context, (System.currentTimeMillis() - st) / 60_000.0); scoreVer++
            driveStartAt = null
        }
    }
    // Y5: 하차 확정 2분 뒤 절전. 운전석에 사람이 있거나(차박·대기) 12V 보호가 진행 중이면 미룸
    val canSleepNow by rememberUpdatedState(
        isP && !(occLinked && s.driverSeatOccupied) &&
                (protect.stage == ProtectStage.NONE || y1Done) && y1Countdown == null && !waitMode
    )
    LaunchedEffect(parkedAt) {
        if (parkedAt == null || sleeping) return@LaunchedEffect
        delay(SLEEP_AFTER_PARK_MS)
        while (!canSleepNow) {
            Log.d("BatteryAlert", "Y5: 절전 보류 (운전석에 사람이 있거나 12V 보호 중) → 30초 뒤 다시 확인")
            delay(30_000)
        }
        goSleep("하차 확정 2분 경과")
    }

    // F2 음성 코칭: 쓰는 전기가 충전보다 많은 상태가 30초 이어지면 1번 안내
    val draining = netA < 0 && !waitMode     // Y2 대기 모드에서는 대기 카드가 대신 알려줌
    LaunchedEffect(draining) {
        if (!draining) return@LaunchedEffect
        delay(30_000)
        val top = loadBreakdown(latestS).firstOrNull()?.first ?: "전기 장치"
        speak("지금 쓰는 전기가 충전보다 많아요. $top 부터 줄여 주세요.")
    }

    val linkedNames = listOf(
        "trip" to "주행거리", "tripTime" to "주행시간", "seat" to "시트열선", "mirror" to "미러열선",
        "defrost" to "뒷유리열선", "light" to "전조등", "fan" to "공조팬", "ev" to "고전압배터리",
        "occ" to "좌석점유", "belt" to "안전벨트"
    ).filter { it.first in s.sdkFields }.map { it.second }

    // ── X5: 차대번호 연식으로 배터리 사용 연수 기본값을 처음 한 번만 채움 ──
    LaunchedEffect(s.carModelYear, settingsLoaded) {
        val y = s.carModelYear ?: return@LaunchedEffect
        if (!settingsLoaded || UserSettings.vinApplied(context)) return@LaunchedEffect
        (source as? FakeVehicleSource)?.update { it.copy(batteryAgeYears = ageFromModelYear(y)) }
        UserSettings.markVinApplied(context)
        Log.d("BatteryAlert", "X5: ${y}년식 → 배터리 사용 연수 ${ageFromModelYear(y)}년으로 자동 설정")
    }
    val carText = when {
        s.carModelYear != null -> "차량: ${s.carMaker ?: ""} ${s.carModelYear}년식 (${s.vinStatus}) → 배터리 연수 기본값 ${ageFromModelYear(s.carModelYear)}년"
        s.vinStatus.isNotBlank() -> "차량 정보: ${s.vinStatus} → 연수를 직접 입력해 주세요"
        else -> "차량 정보 확인 중..."
    }

    // ── X6 음성 문답·제어: "내일 아침 시동 괜찮아?" / "열선 다 꺼줘" / "왜 위험해?" ──
    val listener = remember { PleosListener(context) }
    var voiceState by remember { mutableStateOf("") }
    var voiceQ by remember { mutableStateOf("") }
    var voiceA by remember { mutableStateOf("") }

    var leftPane by remember { mutableStateOf("none") }   // "none", "settings", "demo", "region"
    var tab by remember { mutableStateOf("home") }        // G2 화면 탭: home / health / detail

    // X2 충전 경로 적용 (배너 버튼과 음성 "충전 경로로 바꿔줘"가 같이 씀)
    fun applyChargeRoute(): String {
        val extra = plan?.extraMin ?: return "지금은 경로로 회복할 필요가 없어요."
        x2Applied = true
        ScoreLog.addRecovery(context, extra); userActed("충전 경로 적용")   // Z4
        if (realHome) {
            etaBeforeChange = naviDest?.remainTime
            x2RouteNote = if (navi.changeRouteOption(RouteOption.FREE)) "무료도로 우선 경로로 바꾸는 중..."
            else "내비 경로 변경 실패 → 집 근처를 ${extra}분 더 달려 주세요"
        } else if (source is FakeVehicleSource) {
            source.update { it.copy(fakeArriveMin = it.fakeArriveMin + extra) }
            x2RouteNote = "[가상 내비] 도착 +${extra}분 경로 적용"
        }
        return "${extra}분 더 걸리는 경로로 바꿨어요. 열선과 공조는 꺼 주세요."
    }

    // 차량 끄기 (SDK 차량이면 실제 제어, 가짜 모드면 값만 바꿈)
    fun turnOff(target: String) {
        val p = source as? PleosVehicleSource
        if (p != null) { p.turnOff(target); return }
        (source as? FakeVehicleSource)?.update {
            when (target) {
                "all" -> it.copy(seatHeaterOn = false, steeringHeaterOn = false, mirrorHeaterOn = false, rearDefrostOn = false,
                    hvacFanLevel = 0, passengerHeaterOn = false, windowOpen = false, trunkOpen = false)
                "heat" -> it.copy(seatHeaterOn = false, steeringHeaterOn = false, mirrorHeaterOn = false,
                    rearDefrostOn = false, passengerHeaterOn = false)
                "seat" -> it.copy(seatHeaterOn = false, passengerHeaterOn = false)
                "steer" -> it.copy(steeringHeaterOn = false)
                "defrost" -> it.copy(rearDefrostOn = false)
                "mirror" -> it.copy(mirrorHeaterOn = false)
                "hvac" -> it.copy(hvacFanLevel = 0)
                "window" -> it.copy(windowOpen = false)
                "trunk" -> it.copy(trunkOpen = false)
                else -> it
            }
        }
    }

    // G2 "지금 할 일" 버튼 실행
    fun runRx(r: Rx) {
        val msg = when (r.action) {
            "route" -> applyChargeRoute()
            "heat" -> { turnOff("heat"); "열선을 껐어요." }
            "close" -> if (driving) "정차한 뒤에 닫을 수 있어요." else { turnOff("window"); turnOff("trunk"); "창문과 트렁크를 닫았어요." }
            "dashcam" -> { (source as? FakeVehicleSource)?.update { it.copy(dashcamParkingOn = false) }
                "블랙박스 주차녹화를 끈 걸로 계산할게요." }
            "call" -> { callEmergency(context); "긴급출동 전화를 연결할게요." }
            else -> return
        }
        Log.d("BatteryAlert", "할 일 실행: ${r.title} → $msg")
        userActed("할 일: ${r.title}")
        speak(msg)
    }

    // ── Y1 보호 동작: 끄기 / 유지 ──
    // Y2 대기 모드 자동 보호 1단계: 열선만 끄고 공조는 유지
    fun protectHeatersOff() {
        PROTECT_TARGETS_DRIVING.forEach { turnOff(it) }
        y1Countdown = null
        y1HeatersOff = true
        y1Done = true
        Log.d("BatteryAlert", "Y2 자동 보호 1단계: 열선 OFF, 공조 유지 (12V ${"%.1f".format(socNow)}%)")
        speak("시동을 위해 열선을 껐어요. 공조는 그대로 둘게요.")
    }
    fun protectOff(byUser: Boolean) {
        val targets = if (driving) PROTECT_TARGETS_DRIVING else PROTECT_TARGETS
        targets.forEach { turnOff(it) }
        y1Countdown = null
        if (!driving) y1Done = true
        val what = if (driving) "열선" else "공조와 열선"
        if (byUser) userActed("Y1 지금 끄기")
        Log.d("BatteryAlert", "Y1 ${if (byUser) "지금 끄기" else "자동 보호"}: $what OFF " +
                "(12V ${"%.1f".format(socNow)}%, 하한선 ${"%.1f".format(protect.limitSoc)}%)")
        speak(if (byUser) "${what}을 껐어요."
        else "12볼트 보호를 위해 ${what}을 껐어요. 내일 아침 시동이 걸리는 상태로 지킬게요.")
    }
    fun protectKeep() {
        y1Keep = true
        y1Countdown = null
        y1Warned = true
        Log.d("BatteryAlert", "Y1: 사용자가 유지 선택 → 이번 주차 동안 자동 OFF 안 함")
        speak("알겠어요. 이번 주차 동안은 자동으로 끄지 않을게요. 화면으로 계속 알려드릴게요.")
    }
    // 출발하면 초기화 (다음 주차에서 다시 보호)
    LaunchedEffect(driving) {
        if (driving) {
            y1Keep = false; y1Countdown = null; y1Done = false; y1HeatersOff = false
            waitMode = false; waitSuggest = false; waitDismissed = false
        }
    }
    // Y2: 열선만 끈 뒤에도 하한선 아래에서 계속 줄면 → 1분 예고 후 공조까지 끔
    val protectNow by rememberUpdatedState(protect)
    val autoOkNow by rememberUpdatedState(!driving && s.autoProtect && !y1Keep && !sleeping)
    LaunchedEffect(y1HeatersOff) {
        if (!y1HeatersOff) return@LaunchedEffect
        delay(6_000)   // 열선이 실제로 꺼지고 값이 다시 들어올 시간
        val p = protectNow
        if (p.reached && p.drainA >= 0.05 && autoOkNow && y1Countdown == null) {
            y1Countdown = PROTECT_NOTICE_SEC
            Log.d("BatteryAlert", "Y2 자동 보호 2단계 예고: 열선을 꺼도 ${"%.1f".format(p.drainA)}A씩 줄어서 1분 뒤 공조도 끔")
            speak("열선을 꺼도 12볼트가 계속 줄어요. 1분 뒤 공조도 꺼요. 계속 쓰시려면 화면에서 유지를 눌러 주세요.")
        }
    }
    // 단계가 바뀔 때: 30분 전 배너(화면) → 10분 전 음성 1회 → 1분 전 자동 OFF 예고
    LaunchedEffect(protect.stage, sleeping, driving, s.autoProtect, y1Keep, y1Ready) {
        if (sleeping || !y1Ready) return@LaunchedEffect
        val left = minutesText(protect.minutesLeft ?: 0.0)
        when (protect.stage) {
            ProtectStage.NONE -> { y1Voiced = false; y1Warned = false; y1Countdown = null }
            ProtectStage.BANNER -> {
                if (y1Done) { y1Done = false; y1HeatersOff = false }   // 껐다가 다시 켰으면 처음부터 다시 보호
                y1Countdown = null
                Log.d("BatteryAlert", "Y1: 약 $left 뒤 보호 하한선 ${protect.limitSoc.roundToInt()}% 도달 → 배너")
            }
            ProtectStage.VOICE -> {
                if (y1Done) { y1Done = false; y1HeatersOff = false }
                y1Countdown = null
                if (!y1Voiced) {
                    y1Voiced = true
                    Log.d("BatteryAlert", "Y1: 약 $left 뒤 하한선 도달 → 음성 안내")
                    ScoreLog.addWarn(context, "Y1 하한선 10분 전"); scoreVer++
                    speak("약 $left 뒤 12볼트가 보호 하한선에 닿아요. " +
                            if (driving) "주행 중이니 열선부터 줄여 주세요." else "지금 공조와 열선을 끄면 내일 아침 시동을 지킬 수 있어요.")
                }
            }
            ProtectStage.NOTICE -> {
                if (y1Done) { y1Done = false; y1HeatersOff = false }
                val canAuto = !driving && s.autoProtect && !y1Keep
                if (canAuto) {
                    if (y1Countdown == null) {
                        y1Countdown = PROTECT_NOTICE_SEC
                        if (waitMode) {
                            Log.d("BatteryAlert", "Y2 자동 보호 1단계 예고: 1분 뒤 열선 OFF (공조 유지)")
                            speak("시동을 위해 1분 뒤 열선을 끕니다. 계속 쓰시려면 화면에서 유지를 눌러 주세요.")
                            return@LaunchedEffect
                        }
                        Log.d("BatteryAlert", "Y1: 자동 OFF ${PROTECT_NOTICE_SEC}초 전 예고 " +
                                "(12V ${"%.1f".format(socNow)}%, 하한선 ${"%.1f".format(protect.limitSoc)}%, 빠지는 전류 ${"%.1f".format(protect.drainA)}A)")
                        speak("1분 뒤 12볼트 보호를 위해 공조와 열선을 자동으로 꺼요. 계속 쓰시려면 화면에서 유지를 눌러 주세요.")
                    }
                } else if (!y1Warned) {
                    y1Warned = true
                    y1Countdown = null
                    Log.d("BatteryAlert", "Y1: 하한선 도달 · 자동 OFF 안 함 (주행 중=$driving, 자동 보호=${s.autoProtect})")
                    speak(if (driving) "12볼트가 보호 하한선에 닿았어요. 주행 중이라 자동으로 끄지 않아요. 열선부터 줄여 주세요."
                    else "12볼트가 보호 하한선에 닿았어요. 자동 보호가 꺼져 있어서, 공조와 열선을 직접 꺼 주세요.")
                }
            }
        }
    }
    // 예고 카운트다운: 1초마다 줄이고 0초가 되면 마지막으로 조건 확인 후 자동 OFF
    LaunchedEffect(y1Countdown) {
        val c = y1Countdown ?: return@LaunchedEffect
        if (c > 0) { delay(1_000); y1Countdown = c - 1; return@LaunchedEffect }
        if (!driving && s.autoProtect && !y1Keep && !sleeping) {
            if (waitMode && !y1HeatersOff) protectHeatersOff() else protectOff(byUser = false)
        } else y1Countdown = null
    }

    // ── Z2 겨울 출발 브리핑 ──
    val tempNowC = if (s.autoWeather && forecast != null) tempAtHour(forecast, 0.0, s.outsideTempC, 0.0) else s.outsideTempC
    val nightMinC = if (s.autoWeather) (minTempUntilDeparture(forecast, 24.0) ?: s.outsideTempC) else s.outsideTempC
    val brief = briefStart?.let { (h, d) -> winterBriefing(s, h, d, socNow, tempNowC, nightMinC) }
    fun showBriefing(reason: String) {
        val b = brief ?: return
        briefLeft = BRIEF_SHOW_SEC
        b.driveMin?.let { if (it > 0) { ScoreLog.addRecovery(context, it); scoreVer++ } }   // Z4 회복 처방
        Log.d("BatteryAlert", "Z2 출발 브리핑 표시 ($reason): 주차 ${"%.1f".format(b.parkH)}시간, −${"%.1f".format(b.drop)}%, " +
                "지금 ${"%.1f".format(b.tempNow)}°C, 회복 주행 ${b.driveMin ?: "어려움"}분, 타이어=${s.tireLow}, 워셔액=${s.washerLow}")
        speak(when {
            b.driveMin == null -> "출발 브리핑이에요. 오늘은 주행만으로 12볼트를 채우기 어려워요. 충전이나 점검을 권해요."
            b.driveMin > 0 -> "출발 브리핑이에요. 내일 아침 시동을 위해 오늘 총 ${b.driveMin}분 이상 달려 주세요."
            else -> "출발 브리핑이에요. 오늘은 따로 더 달리지 않아도 괜찮아요."
        } + (if (s.tireLow == true) " 타이어 공기압도 확인해 주세요." else "") +
                (if (s.washerLow == true) " 워셔액이 부족해요." else ""))
    }
    // 앱 시작 8초 뒤(장부 소급·날씨·차량값 수신 후) 조건 맞으면 1회 표시
    LaunchedEffect(briefStart, y1Ready) {
        val st = briefStart ?: return@LaunchedEffect
        if (!y1Ready || briefLeft != null) return@LaunchedEffect
        if (st.first >= BRIEF_MIN_PARK_H && tempNowC <= BRIEF_MAX_TEMP_C && !driving) showBriefing("앱 시작")
        else Log.d("BatteryAlert", "Z2 출발 브리핑 안 띄움: 주차 ${"%.1f".format(st.first)}시간, 기온 ${"%.1f".format(tempNowC)}°C " +
                "(조건: ${BRIEF_MIN_PARK_H.toInt()}시간 이상 & ${BRIEF_MAX_TEMP_C.toInt()}°C 이하)")
    }
    LaunchedEffect(briefLeft) {
        val c = briefLeft ?: return@LaunchedEffect
        delay(1_000)
        briefLeft = if (c > 1) c - 1 else null
    }

    // ── Z4 12V 관리 점수 ──
    val weekIn = remember(scoreVer) { ScoreLog.weekInputs(context) }
    val demoMargin = remember(scoreVer) { ScoreLog.demoMargin(context) }
    val weekMinSoc = ledger.samples.filter { it.time >= System.currentTimeMillis() - 7L * 24 * 3_600_000L }.minOfOrNull { it.soc }
    val minMargin = demoMargin ?: weekMinSoc?.let { it - startNeedSoc(tempC) }
    val score = careScore(s, minMargin, weekIn.third, weekIn.first, weekIn.second)
    var scoreWeeks by remember { mutableStateOf(ScoreLog.weeks(context)) }
    LaunchedEffect(score.total, y1Ready) {
        if (!y1Ready) return@LaunchedEffect
        ScoreLog.saveWeek(context, weekStart(), score.total)
        scoreWeeks = ScoreLog.weeks(context)
    }
    val lastWeekScore = scoreWeeks.firstOrNull { it.first == weekStart() - 7L * 24 * 3_600_000L }?.second
    // 블랙박스 설정을 바꾸면 "조치"로 인정 (Z4 대응)
    LaunchedEffect(s.dashcamParkingOn, s.dashcamMa, s.dashcamCutoffSet) {
        if (y1Ready) userActed("블랙박스 설정 변경")
    }

    // ── Y2 대기 모드 시작·끝 ──
    fun startWait(reason: String) {
        waitMode = true
        waitSuggest = false
        y1HeatersOff = false
        Log.d("BatteryAlert", "Y2: 대기 모드 시작 ($reason) → 남은 ${wait.minutes?.let { minutesText(it) } ?: "제한 없음"} (${wait.basis})")
    }
    fun stopWait(reason: String) {
        if (!waitMode) return
        waitMode = false
        Log.d("BatteryAlert", "Y2: 대기 모드 종료 ($reason)")
    }
    // P + 운전석 착석 + 공조·열선 켬이 5분 이어지면 "대기 모드로 볼까요?" 제안 (주차마다 1번)
    val waitCandidate = !driving && occLinked && s.driverSeatOccupied && !waitMode && !waitDismissed && !sleeping &&
            (s.hvacFanLevel > 0 || s.seatHeaterOn || s.steeringHeaterOn || s.rearDefrostOn || s.mirrorHeaterOn)
    LaunchedEffect(waitCandidate) {
        if (!waitCandidate) return@LaunchedEffect
        delay(WAIT_SUGGEST_MS)
        waitSuggest = true
        Log.d("BatteryAlert", "Y2: P·착석·공조/열선 5분 → 대기 모드 제안")
        speak("차 안에서 기다리시는 중이면 대기 모드로 볼까요? 이대로 얼마나 버틸 수 있는지 알려드릴게요.")
    }

    // ── G1 음성 전체 제어: 말한 문장 → 명령 해석(parseVoice) → 실행 → 음성 답변 ──
    val handleCommand by rememberUpdatedState { text: String ->
        voiceQ = text
        val cmd = parseVoice(text)
        val riskWord = when (risk) { Risk.OK -> "괜찮아요"; Risk.CAUTION -> "여유가 빠듯해요"; Risk.DANGER -> "위험해요" }
        val fake = source as? FakeVehicleSource
        val answer: String = when (cmd) {
            is VoiceCmd.Help -> VOICE_HELP
            is VoiceCmd.Status ->
                "지금 12볼트 배터리는 약 ${socNow.roundToInt()}퍼센트예요. " +
                        (if (netA >= 0) "충전 중이에요." else "쓰는 전기가 더 많아서 줄고 있어요.") +
                        " 추정 신뢰도는 ${conf.label}이에요." +
                        (protect.minutesLeft?.let { m ->
                            if (m <= 0.0) " 보호 하한선 ${protect.limitSoc.roundToInt()}퍼센트에 이미 닿았어요."
                            else " 이대로면 약 ${minutesText(m)} 뒤 보호 하한선 ${protect.limitSoc.roundToInt()}퍼센트에 닿아요."
                        } ?: "")
            is VoiceCmd.Forecast -> when {
                saver -> "12볼트 자동충전을 쓸 수 있어서 시동은 괜찮아요."
                hours <= 0.0 -> "지금 12볼트 배터리로는 주차하면 바로 시동이 어려울 수 있어요. 충전한 뒤 주차해 주세요."
                else -> "${departLabel(s)} 출발까지 약 ${parkHours.roundToInt()}시간 주차해요. 시동 한계는 약 ${hours.roundToInt()}시간이라 $riskWord." +
                        (if (risk != Risk.OK) rx.firstOrNull()?.let { " ${it.spoken}." } ?: "" else "")
            }
            is VoiceCmd.Why -> {
                val total = parkedDrainA(s)
                val top = parkedBreakdown(s).firstOrNull()
                if (top == null || risk == Risk.OK) "지금은 위험하지 않아요. 주차 중에는 ${drainShareText(s)} 비율로 전기를 써요."
                else "가장 큰 원인은 ${top.first}예요. 주차 중 쓰는 전기의 ${(top.second * s.drainK / total * 100).roundToInt()}퍼센트를 차지해요." +
                        (if (coldLossPercent(tempC) > 0) " 추위 때문에 쓸 수 있는 용량도 ${coldLossPercent(tempC)}퍼센트 줄어요." else "")
            }
            is VoiceCmd.HowTo ->
                if (rx.isEmpty()) "지금은 따로 할 일이 없어요. 출발까지 괜찮아요."
                else "가장 효과가 큰 순서로 말씀드릴게요. " + rx.take(2).mapIndexed { i, r -> "${i + 1}번, ${r.spoken}." }.joinToString(" ")
            is VoiceCmd.Off -> when {
                driving && !allowedWhileDriving(cmd.target) -> "주행 중에는 안전을 위해 열선만 끌 수 있어요. 정차한 뒤 다시 말씀해 주세요."
                driving && cmd.target == "all" -> { turnOff("heat"); "주행 중이라 열선만 껐어요." }
                else -> { turnOff(cmd.target); userActed("음성 끄기: ${cmd.target}")
                    "${offLabel(cmd.target)}을 껐어요." + if (cmd.target == "all") " 전조등은 직접 꺼 주세요." else "" }
            }
            is VoiceCmd.Dashcam -> { fake?.update { it.copy(dashcamParkingOn = cmd.on) }
                if (cmd.on) "블랙박스 주차녹화를 켠 걸로 계산할게요." else "블랙박스 주차녹화를 끈 걸로 계산할게요." }
            is VoiceCmd.Underground -> { fake?.update { it.copy(undergroundParking = cmd.on) }
                curPlace?.let { pl -> PlaceLog.setUnderground(context, pl.id, cmd.on); places = PlaceLog.load(context) }   // Y4
                if (cmd.on) "지하 주차로 계산할게요. 바깥보다 5도 따뜻하게 봐요." else "지상 주차로 계산할게요." }
            is VoiceCmd.Depart -> {
                fake?.update { it.copy(autoDepart = false, departHour = cmd.hour, departMinute = cmd.minute,
                    departDaysLater = cmd.days ?: it.departDaysLater) }
                val dayWord = when (cmd.days) { 0 -> "오늘"; 1 -> "내일"; 2 -> "모레"; null -> ""; else -> "${cmd.days}일 뒤" }
                "$dayWord ${cmd.hour}시 ${cmd.minute}분 출발로 바꿨어요."
            }
            is VoiceCmd.Voltage -> {
                val soc = voltageToSoc(cmd.volts)
                ParkLog.addVoltage(context, cmd.volts)
                measureAndCalibrate(soc, EnergyLedger.SRC_DASHCAM, " (${"%.2f".format(cmd.volts)}V, 음성)")
                "${cmd.volts}볼트로 보정했어요. 약 ${soc.roundToInt()}퍼센트예요."
            }
            is VoiceCmd.StartResult -> {
                if (cmd.ok) SelfCal.onStartSuccess(context) else SelfCal.onStartFailed(context)
                refreshSelfCal(); askStart = false
                if (!cmd.ok && !driving) leftPane = "guide"     // Z3: 시동 실패 직후 대처 가이드
                if (cmd.ok) "알려주셔서 고마워요. 예측에 반영했어요." else "그랬군요. 이 차는 방전이 더 빠른 걸로 보정했어요. 방전 대처 가이드를 열었어요."
            }
            is VoiceCmd.Bluehands -> { findBluehands(); "가까운 블루핸즈를 찾아서 길 안내를 시작할게요." }
            is VoiceCmd.Emergency -> { callEmergency(context); "긴급출동 080 600 6000으로 전화를 연결할게요." }
            is VoiceCmd.Route -> if (driving) applyChargeRoute() else "충전 경로는 주행 중에 바꿀 수 있어요."
            is VoiceCmd.Show -> if (driving) "주행 중에는 화면을 열 수 없어요." else {
                leftPane = cmd.pane
                when (cmd.pane) {
                    "region" -> "지역별 방전 위험 지수를 열었어요."
                    "receipt" -> "주차 영수증과 주간 리포트를 열었어요."
                    else -> "설정을 열었어요."
                }
            }
            is VoiceCmd.Wait -> when {
                !cmd.on -> { stopWait("음성"); "대기 모드를 끝냈어요." }
                driving -> "대기 모드는 P단에서 쓸 수 있어요."
                else -> {
                    if (!waitMode) startWait("음성")
                    val w = waitPlan(s, socNow, tempC, conf)
                    val best = waitWhatIfs(s, socNow, tempC, conf).firstOrNull()
                    if (w.minutes == null) "대기 모드를 켰어요. 지금은 12볼트가 줄지 않아서 걱정 없어요."
                    else "대기 모드를 켰어요. 이대로면 약 ${minutesText(w.minutes)} 버틸 수 있어요." +
                            (best?.let { b -> " ${b.label.removeSuffix("기")}면 " + (b.gainMin?.let { "${minutesText(it)} 더 버텨요." } ?: "더 줄지 않아요.") } ?: "")
                }
            }
            is VoiceCmd.Unknown -> "잘 못 알아들었어요. 뭐 할 수 있어? 라고 물어보시면 알려드릴게요."
        }
        voiceA = answer
        Log.d("BatteryAlert", "음성 명령: \"$text\" → $cmd → $answer")
        speak(answer)
    }

    DisposableEffect(Unit) {
        listener.onResult = { t -> handleCommand(t) }
        listener.onState = { st -> voiceState = st }
        listener.start()
        onDispose { listener.stop() }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Log.w("BatteryAlert", "마이크 권한 거부 → Pleos 음성 인식만으로 시도")
        listener.listen()
    }
    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listener.listen()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── 화면 구성: 운전자 화면(카드 2열) ↔ 왼쪽에 설정 / 시연 패널 열기 ──

    // 주행 중 화면 제한: 기어가 P가 아니면 간단 화면만 (시연 패널에서 테스트용으로 풀 수 있음)
    val restricted = driving && gearLinked && !s.demoUnrestricted
    LaunchedEffect(restricted) { if (restricted) leftPane = "none" }

    // [시연용] 가상 내비: 주행 중이면 3초마다 도착 시간 1분씩 줄이기 (손 안 대고 "도착 10분 전" 만들기)
    LaunchedEffect(driving, s.fakeNaviHome) {
        if (!driving || !s.fakeNaviHome) return@LaunchedEffect
        while (true) {
            delay(3_000)
            (source as? FakeVehicleSource)?.update { if (it.fakeArriveMin > 1) it.copy(fakeArriveMin = it.fakeArriveMin - 1) else it }
        }
    }

    // 주행 중에도 보여주는 카드: 도착 전 배너(X1·X2) / 집 도착 예정
    val driveTopCards: @Composable () -> Unit = {
        // Z2 겨울 출발 브리핑 (10초 뒤 자동으로 닫힘)
        if (briefLeft != null && brief != null) {
            BriefingCard(brief, s, briefLeft ?: 0,
                onReceipt = { briefLeft = null; leftPane = "receipt" },
                onClose = { briefLeft = null })
        }
        // Y3 아침 영수증: 3시간 이상 주차가 끝난 지 18시간 안 · 주차 1건당 1번 · 출발 전(P)에만
        val lastPark = parkLog.lastOrNull()
        if (briefLeft == null && !driving && !sleeping && lastPark != null && lastPark.endTime != receiptSeen &&
            lastPark.hours >= RECEIPT_MIN_PARK_H &&
            System.currentTimeMillis() - lastPark.endTime <= (RECEIPT_SHOW_WITHIN_H * 3_600_000).toLong()) {
            ReceiptCard(lastPark, s, socNow, compact = true,
                trust = "추정 신뢰도 ${conf.label} · ${lastMeasureText(ledger)}",
                onWeekly = { leftPane = "receipt" },
                onClose = { receiptSeen = lastPark.endTime; ParkLog.markSeen(context, lastPark.endTime) })
        }
        // Y4 지금 장소 프로필
        if (curPlace != null && !driving && !sleeping) {
            val pa = placeAdvice(s, socNow, outsideC, curPlace.avgH.takeIf { it > 0 } ?: parkHours, curPlace.underground, curPlace.bonusC)
            InfoCard("📍 지금 장소: ${curPlace.label}",
                listOf(
                    (if (curPlace.visits > 0) "평균 ${"%.0f".format(curPlace.avgH)}시간 주차" else "처음 온 장소 (출발 시각 기준)") +
                            " · " + (if (curPlace.underground) "실내 보정 +${"%.0f".format(curPlace.bonusC)}°C → ${"%.0f".format(pa.tempC)}°C"
                    else "${"%.0f".format(pa.tempC)}°C 그대로"),
                    "이 장소 위험도 ${placeRiskWord(pa.risk)} → ${pa.dashcam}" +
                            if (curPlace.risks30() > 0) " · 최근 30일 위험 ${curPlace.risks30()}번" else ""
                ),
                when (pa.risk) { Risk.OK -> GREEN; Risk.CAUTION -> YELLOW; Risk.DANGER -> RED })
        }
        // Y5 주차 절전 상태
        if (sleeping) {
            SleepCard(sleepSince) { wake("화면에서 다시 켜기") }
        } else if (parkedAt != null && isP) {
            val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(parkedAt!! + SLEEP_AFTER_PARK_MS))
            InfoCard("🌙 하차 확인 · $at 에 주차 절전으로 바뀌어요",
                listOf("주차 중에는 차량 값을 읽지 않아서 이 앱이 차를 깨우지 않아요"), BLUE)
        }
        // Y1 보호 하한선 배너 (주행 중에도 표시, 주행 중에는 경고만)
        if (!sleeping && y1Ready && (protect.stage != ProtectStage.NONE || y1Countdown != null || y1Done)) {
            ProtectBanner(
                p = protect, soc = socNow, countdown = y1Countdown, done = y1Done, keep = y1Keep,
                driving = driving, autoProtect = s.autoProtect, loads = loadBreakdown(s).take(2),
                onOffNow = { protectOff(byUser = true) },
                onKeep = { protectKeep() },
                onHideDone = { y1Done = false },
                heatOnly = waitMode && !y1HeatersOff
            )
        }
        // Y2 대기 모드 제안
        if (!sleeping && waitSuggest && !waitMode && !driving) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = YELLOW, contentColor = Color.Black)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("🛏 차 안에서 기다리시는 중인가요?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("대기 모드로 보면 이대로 얼마나 버틸 수 있는지, 뭘 줄이면 얼마나 늘어나는지 알려드려요")
                    Row(Modifier.padding(top = 6.dp)) {
                        Button(onClick = { startWait("제안 수락") }, modifier = Modifier.padding(end = 8.dp)) { Text("대기 모드로 보기") }
                        OutlinedButton(onClick = { waitSuggest = false; waitDismissed = true }) { Text("괜찮아요") }
                    }
                }
            }
        }
        // Y2 대기 모드 카드
        if (!sleeping && waitMode && !driving) {
            WaitCard(
                w = wait, s = s, soc = socNow, limitSoc = protect.limitSoc, loads = loadBreakdown(s),
                whatIfs = whatIfs, engineOff = waitEngineOff, isEv = s.isEv,
                onEngineOff = { waitEngineOff = it },
                onApply = { target -> turnOff(target); userActed("대기 모드 끄기: $target"); speak("${offLabel(target)}을 껐어요.") },
                onStop = { stopWait("화면 버튼") }
            )
        }
        // X1·X2 도착 전 배너 (주행 중 팝업 금지 → 배너 + 음성)
        if (driving && preArrivalLatched && plan != null) {
            PreArrivalBanner(
                plan = plan, s = s, tempC = tempC, firstAction = actions.firstOrNull(),
                x2Applied = x2Applied, x2Dismissed = x2Dismissed, x2RouteNote = x2RouteNote,
                onRoute = { speak(applyChargeRoute()) },
                onDismiss = { x2Dismissed = true }
            )
        }
        // ③-2 도착 전 예측 (집으로 가는 중이면 항상 표시)
        if (driving && plan != null && !preArrivalLatched) {
            InfoCard(
                "🏠 집 도착 예정 (약 ${plan.arriveMin.roundToInt()}분 뒤)",
                listOf(
                    "도착 시 12V 약 ${plan.socArrive.roundToInt()}% · 도착 후 약 ${plan.parkHours.roundToInt()}시간 주차",
                    "도착 후 시동 한계 약 ${"%.1f".format(plan.limitHours)}시간 → " + when (plan.risk) {
                        Risk.OK -> "OK"; Risk.CAUTION -> "주의"; Risk.DANGER -> "위험" }
                ),
                when (plan.risk) { Risk.OK -> GREEN; Risk.CAUTION -> YELLOW; Risk.DANGER -> RED }
            )
        }
    }

    val leftCards: @Composable () -> Unit = {
        driveTopCards()
        // F2 실시간 코칭: 쓰는 전기가 충전보다 많으면 바로 알려주기
        if (netA < 0) {
            val top = loadBreakdown(s).take(2).joinToString(", ") { "${it.first} ${it.second.roundToInt()}A" }
            InfoCard(
                "🔋 지금은 충전보다 쓰는 전기가 더 많아요",
                listOf(
                    "전기 부하 ${electricLoadA(s).roundToInt()}A > 충전 여유 ${chargeSpareA(s).roundToInt()}A",
                    "이대로면 1시간에 약 ${"%.1f".format(-netA / usableAh(s) * 100)}%씩 줄어요",
                    "큰 부하: $top → 이것부터 줄여보세요"
                ),
                RED
            )
        }

        InfoCard(
            "① 12V 배터리 수지 (${if (s.isEv) "전기차·LDC 충전" else "내연기관·발전기 충전"})",
            buildList {
                add("현재 12V 약 ${socNow.roundToInt()}% (±${unc.roundToInt()}%) · 에너지 장부 추정")
                add(if (netA >= 0) "지금 충전 중 +${"%.1f".format(netA)}A" else "지금 방전 중 ${"%.1f".format(netA)}A")
                add("전기 부하 약 ${electricLoadA(s).roundToInt()}A / 충전 여유 ${chargeSpareA(s).roundToInt()}A")
                add("주행 ${"%.1f".format(s.tripKm)} km · " +
                        (s.tripMinutes?.let { "${it}분 (실측)" } ?: "${(s.tripKm / AVG_SPEED_KMH * 60).toInt()}분 (추정)"))
                add("이번 주행 SoC 변화 ${"%+.1f".format(change)}% " + if (tripChange != null) "(장부 기준)" else "(추정)")
                if (ledger.lastEvent.isNotBlank()) add(ledger.lastEvent)
                if (conf == Confidence.LOW) add("추정 오차가 커졌어요 → 블랙박스 전압이나 정비소 측정값으로 보정 권장")
                if (linkedNames.isNotEmpty()) add("실제 차량값 반영: ${linkedNames.joinToString(", ")}")
            },
            if (netA < 0) RED else GREEN
        )
        InfoCard(
            "② 하차 전 체크",
            if (warnings.isEmpty()) listOf("이상 없음") else warnings,
            if (warnings.isEmpty()) GREEN else RED
        )
        InfoCard(
            "③ 다음 출발 예측",
            buildList {
                add(when {
                    saver -> "✓ OK — 12V 자동충전 가능"
                    risk == Risk.DANGER -> "⚠ 위험 — 출발 전 시동이 어려울 수 있어요"
                    risk == Risk.CAUTION -> "△ 주의 — 여유가 많지 않아요"
                    else -> "✓ OK — 시동 문제없어요"
                })
                add("출발 ${departLabel(s)} · 주차 약 ${parkHours.toInt()}시간")
                add("출발 시각: $departInfo")
                add("현재 12V 약 ${socNow.roundToInt()}% (±${unc.roundToInt()}%)")
                add(confText)
                if (conf == Confidence.LOW) add("신뢰도가 낮아 하한값 ${judgeSoc.roundToInt()}% 기준으로 판정했어요")
                if (conf == Confidence.LOW && risk != Risk.OK) add("→ ⚙ 설정에서 블랙박스 전압을 한 번 입력하면 더 정확해져요")
                add("출발 전 최저 ${"%.1f".format(tempC)}°C (${if (useForecast) "날씨 예보" else "수동 입력"}${if (s.undergroundParking) " + 지하주차 5°C" else ""})")
                add("배터리 상태 ${(batteryHealth(s) * 100).toInt()}% (${s.batteryAgeYears}년차, 실용량 ${usableAh(s).toInt()}Ah)")
                add("주차 중 소모 ${"%.2f".format(parkedDrainA(s))}A — ${drainShareText(s)}")
                if (coldLossPercent(tempC) > 0) add("추위로 쓸 수 있는 용량 −${coldLossPercent(tempC)}%")
                if (s.isEv) add("고전압 배터리 ${s.hvSoc}%")
                if (s.isEv && !s.evSaverEnabled) add("12V 자동충전 미반영 (보수적 계산)")
                if (!saver) {
                    if (hours <= 0.0) {
                        add("⚠ 지금 12V(약 ${socNow.roundToInt()}%)로는 주차하면 바로 시동이 어려울 수 있어요")
                        add("→ 시동 켠 채로 충전한 뒤 주차하거나, 긴급출동을 불러주세요")
                    } else {
                        add("예상 시동 한계 약 ${"%.1f".format(hours)}시간 (오차 고려 ${"%.1f".format(hoursLow)}~${"%.1f".format(hoursHigh)}시간)")
                        add("이대로면 ${timeLabelAfter(hours)}쯤 시동 한계 도달 (출발 ${departLabel(s)})")
                    }
                }
            },
            when (risk) {
                Risk.DANGER -> RED
                Risk.CAUTION -> YELLOW
                Risk.OK -> GREEN
            }
        )
    }
    val rightCards: @Composable () -> Unit = {
        VoiceCard(voiceState, voiceQ, voiceA, big = false) { startListening() }
        if (actions.isNotEmpty()) {
            InfoCard("④ 지금 할 일 (효과 큰 순서)", rx.mapIndexed { i, r -> "${i + 1}. ${r.line} · ${r.kind.tag}" }, BLUE)
        }
        if (advice.isNotEmpty()) {
            InfoCard(if (longParking) "⑤ 🅿 장기 주차 모드" else "⑤ 주차 안내", advice, YELLOW)
        }
        ReportCard(
            report, bhStatus, nearby,
            onBluehands = { findBluehands() },
            onEmergency = { callEmergency(context) }
        )
        TrendCard(ledger.samples, socNow, unc,
            if (SelfCal.count(context) > 0) "이 차에 맞춰 보정 중 (보정 ${SelfCal.count(context)}회, 주차 소모 ×${"%.2f".format(s.drainK)})" else "")
        if (s.dashcamParkingOn) {
            InfoCard("⑧ 블랙박스 설정 추천", dashAdvice.lines, BLUE)
        }
    }

    // ── G2 홈 화면: 결론(두괄식) → 지금 할 일 → 원인 ──
    val verdictColor = when {
        saver || risk == Risk.OK -> GREEN
        risk == Risk.CAUTION -> YELLOW
        else -> RED
    }
    val verdict = when {
        saver -> "✓ ${departLabel(s)} 출발 · 시동 괜찮아요"
        hours <= 0.0 -> "⚠ 지금 주차하면 바로 시동이 어려워요"
        risk == Risk.DANGER -> "⚠ ${departLabel(s)} 출발 · 시동 위험"
        risk == Risk.CAUTION -> "△ ${departLabel(s)} 출발 · 여유가 빠듯해요"
        else -> "✓ ${departLabel(s)} 출발 · 시동 괜찮아요"
    }
    val topCause = parkedBreakdown(s).firstOrNull()?.first ?: "-"
    val heroSub = when {
        saver -> "고전압 배터리가 12V를 자동으로 충전해요"
        hours <= 0.0 -> "시동을 켠 채로 충전한 뒤 주차하세요"
        risk != Risk.OK -> "이대로면 ${timeLabelAfter(hours)}쯤 시동 한계 · 가장 큰 원인: $topCause"
        else -> "출발까지 ${parkHours.roundToInt()}시간, 시동 한계까지 ${hours.roundToInt()}시간 여유"
    }
    val heroStats = listOf(
        Triple("12V 배터리", "${socNow.roundToInt()}%", "±${unc.roundToInt()}% · 신뢰도 ${conf.label}"),
        Triple("시동 한계", if (saver) "자동충전" else "${hours.roundToInt()}시간", if (saver) "" else timeLabelAfter(hours)),
        Triple("출발까지", "${parkHours.roundToInt()}시간", if (raw.autoDepart && learned != null) "평소 출발 시각" else "직접 입력"),
        Triple("출발 전 최저", "${"%.0f".format(tempC)}°C", if (useForecast) "날씨 예보" else "수동 입력")
    )

    val homeContent: @Composable (Boolean) -> Unit = { wide ->
        HeroCard(verdict, heroSub, verdictColor, heroStats)
        if (risk == Risk.DANGER && !driving) {
            OutlinedButton(onClick = { leftPane = "guide" }, modifier = Modifier.padding(bottom = 8.dp)) { Text("🔧 방전되면? 대처 순서 보기") }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1.3f).padding(end = 12.dp)) {
                    driveTopCards()
                    RxActionList(rx, warnings, driving) { runRx(it) }
                }
                Column(Modifier.weight(1f)) {
                    ForecastCard(curve, curveDashOff, parkHours, curveMax, departLabel(s), saver)
                    DrainCard(s, tempC)
                    VoiceCard(voiceState, voiceQ, voiceA, big = false) { startListening() }
                }
            }
        } else {
            driveTopCards()
            RxActionList(rx, warnings, driving) { runRx(it) }
            ForecastCard(curve, curveDashOff, parkHours, curveMax, departLabel(s), saver)
            DrainCard(s, tempC)
            VoiceCard(voiceState, voiceQ, voiceA, big = false) { startListening() }
        }
    }
    val healthContent: @Composable (Boolean) -> Unit = { wide ->
        val calNote = if (SelfCal.count(context) > 0)
            "이 차에 맞춰 보정 중 (보정 ${SelfCal.count(context)}회, 주차 소모 ×${"%.2f".format(s.drainK)})" else ""
        val confCard: @Composable () -> Unit = {
            InfoCard("추정 신뢰도: ${conf.label} (${confScore}점)", buildList {
                add(confText)
                add("오차 ±${unc.roundToInt()}% · 실측할수록 정확해져요")
                if (calNote.isNotBlank()) add(calNote)
                add(SelfCal.accuracyText(context))
                if (conf == Confidence.LOW) add("→ 설정에서 블랙박스 전압을 입력해 주세요")
            }, if (conf == Confidence.LOW) YELLOW else BLUE)
        }
        if (wide) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    TrendCard(ledger.samples, socNow, unc, calNote)
                    confCard()
                }
                Column(Modifier.weight(1f)) {
                    ScoreCard(score, lastWeekScore, scoreWeeks) { leftPane = "qr" }
                    ReportCard(report, bhStatus, nearby, onBluehands = { findBluehands() }, onEmergency = { callEmergency(context) })
                    OutlinedButton(onClick = { leftPane = "qr" }, modifier = Modifier.padding(bottom = 12.dp)) { Text("📱 정비소에 기록 보여주기 (QR)") }
                    if (s.dashcamParkingOn) InfoCard("블랙박스 설정 추천", dashAdvice.lines, BLUE)
                }
            }
        } else {
            TrendCard(ledger.samples, socNow, unc, calNote)
            confCard()
            ScoreCard(score, lastWeekScore, scoreWeeks) { leftPane = "qr" }
            ReportCard(report, bhStatus, nearby, onBluehands = { findBluehands() }, onEmergency = { callEmergency(context) })
            OutlinedButton(onClick = { leftPane = "qr" }, modifier = Modifier.padding(bottom = 12.dp)) { Text("📱 정비소에 기록 보여주기 (QR)") }
            if (s.dashcamParkingOn) InfoCard("블랙박스 설정 추천", dashAdvice.lines, BLUE)
        }
    }
    val detailContent: @Composable (Boolean) -> Unit = { wide ->
        if (wide) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) { leftCards() }
                Column(Modifier.weight(1f)) { rightCards() }
            }
        } else {
            leftCards()
            rightCards()
        }
    }
    val tabContent: @Composable (Boolean) -> Unit = { wide ->
        when (tab) {
            "health" -> healthContent(wide)
            "detail" -> detailContent(wide)
            else -> homeContent(wide)
        }
    }

    // 화면 전체(상단 바·탭·패널·팝업)를 별도 함수로 분리 → AppScreen 본문이 작아져서 안드로이드가 빠르게 실행(JIT)할 수 있음
    val screenBody: @Composable () -> Unit = {
        Column(modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("방전 알리미", fontWeight = FontWeight.Bold, fontSize = 28.sp, modifier = Modifier.padding(end = 20.dp))
                if (!restricted) {
                    TabButton("홈", tab == "home") { tab = "home" }
                    TabButton("배터리 건강", tab == "health") { tab = "health" }
                    TabButton("자세히", tab == "detail") { tab = "detail" }
                }
                Spacer(Modifier.weight(1f))
                if (restricted) {
                    Text("주행 중 · 간단 화면", fontSize = 16.sp)
                } else if (source is FakeVehicleSource) {
                    if (!driving) OutlinedButton(
                        onClick = { if (waitMode) stopWait("상단 버튼") else startWait("상단 버튼") },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(if (waitMode) "대기 모드 끝" else "🛏 대기 모드") }
                    OutlinedButton(
                        onClick = { leftPane = if (leftPane == "receipt") "none" else "receipt" },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(if (leftPane == "receipt") "영수증 닫기" else "🧾 영수증") }
                    OutlinedButton(
                        onClick = { leftPane = if (leftPane == "settings") "none" else "settings" },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(if (leftPane == "settings") "설정 닫기" else "⚙ 설정") }
                    OutlinedButton(
                        onClick = { leftPane = if (leftPane == "region") "none" else "region" },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(if (leftPane == "region") "지역 지수 닫기" else "📊 지역 위험 지수") }
                    OutlinedButton(onClick = { leftPane = if (leftPane == "demo") "none" else "demo" }) {
                        Text(if (leftPane == "demo") "시연 패널 닫기" else "🧪 시연 패널")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (restricted) {
                // ── 주행 중 간단 화면: 큰 숫자 + 꼭 필요한 안내만 ──
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    driveTopCards()
                    Row(Modifier.fillMaxWidth()) {
                        BigTile("12V 배터리", "${socNow.roundToInt()}%", "오차 ±${unc.roundToInt()}%",
                            if (socNow < START_LIMIT_SOC + 15) RED else GREEN, Modifier.weight(1f).padding(end = 12.dp))
                        BigTile(if (netA >= 0) "충전 중" else "방전 중", "${"%+.0f".format(netA)}A",
                            "부하 ${electricLoadA(s).roundToInt()}A / 여유 ${chargeSpareA(s).roundToInt()}A",
                            if (netA >= 0) GREEN else RED, Modifier.weight(1f))
                    }
                    if (netA < 0) {
                        val top = loadBreakdown(s).firstOrNull()
                        InfoCard("🔋 ${top?.first ?: "전기 장치"}부터 줄여 주세요",
                            listOf("쓰는 전기가 충전보다 많아서 12V가 줄고 있어요"), RED)
                    }
                    VoiceCard(voiceState, voiceQ, voiceA, big = true) { startListening() }
                }
            } else Row(Modifier.fillMaxSize()) {
                if (leftPane != "none" && source is FakeVehicleSource) {
                    // 패널 열림: 왼쪽 패널 + 오른쪽 카드 1열
                    if (leftPane == "guide") {
                        GuidePanel(
                            g = dischargeGuide(s.demoVin ?: s.vinKey, s.carMaker, s.isEv),
                            onCall = { tel -> callEmergency(context, tel) },
                            onBluehands = { findBluehands() },
                            onQr = { leftPane = "qr" },
                            modifier = Modifier.weight(1f)
                        )
                    } else if (leftPane == "qr") {
                        val now = System.currentTimeMillis()
                        val day = 24L * 3_600_000
                        val socs = ledger.samples.map { it.soc } + parkLog.filter { it.endTime >= now - 30 * day }.map { it.socAfter }
                        val days30 = history.filter { it.time >= now - 30 * day }
                        fun dayCount(r: String) = days30.filter { it.risk == r }.map { it.time / day }.distinct().size
                        QrPanel(
                            text = qrSummary(
                                s, s.vinMasked, socs.minOrNull(), socs.takeIf { it.isNotEmpty() }?.average(),
                                dayCount("CAUTION"), dayCount("DANGER"), SelfCal.k(context), SelfCal.count(context),
                                ParkLog.voltages(context), score, topCause30(parkLog)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    } else if (leftPane == "receipt") {
                        ReceiptPanel(
                            last = parkLog.lastOrNull(), week = weeklyReport(s, parkLog), s = s, socNow = socNow,
                            trust = "추정 신뢰도 ${conf.label} · ${lastMeasureText(ledger)}",
                            modifier = Modifier.weight(1f)
                        )
                    } else if (leftPane == "region") {
                        RegionPanel(
                            s = s, myArea = navi.lastLocation?.address?.ifBlank { null },
                            myRisk = risk, tempC = tempC,
                            onConsent = { v -> source.update { it.copy(shareConsent = v) } },
                            modifier = Modifier.weight(1f)
                        )
                    } else if (leftPane == "settings") {
                        SettingsPanel(
                            source, s, socNow, unc, confText, departInfo, dashAdvice, carText, protect,
                            places = places, curPlaceId = curPlaceId, outsideC = outsideC, parkHours = parkHours,
                            onRenamePlace = { id, name -> PlaceLog.rename(context, id, name); places = PlaceLog.load(context) },
                            onPlaceUnderground = { id, on -> PlaceLog.setUnderground(context, id, on); places = PlaceLog.load(context) },
                            onCalibrate = { measureAndCalibrate(s.battery12vSoc.toDouble(), EnergyLedger.SRC_SHOP) },
                            onVoltage = { v -> ParkLog.addVoltage(context, v); measureAndCalibrate(voltageToSoc(v), EnergyLedger.SRC_DASHCAM, " (${"%.2f".format(v)}V)") },
                            onGuide = { leftPane = "guide" },
                            onClearDepart = { DepartureLog.clear(context); departures = DepartureLog.load(context) },
                            onTestVoice = { speak("방전 알리미 음성 안내 테스트입니다.") },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        DemoPanel(
                            source, s, weatherStatus, forecastMin,
                            onDemoHistory = { AlertHistory.addDemo(context); history = AlertHistory.load(context) },
                            onClearHistory = { AlertHistory.clear(context); history = AlertHistory.load(context) },
                            onSimPark = {
                                ledger = EnergyLedger.simulateParking(context, ledger, 12.0, parkedDrainA(s), usableAh(s))
                                recordParking(12.0, parkedDrainA(s))
                            },
                            onDemoTrend = { ledger = EnergyLedger.addDemo(context, ledger, usableAh(s)) },
                            onDemoDepart = { DepartureLog.addDemo(context); departures = DepartureLog.load(context) },
                            onShiftReset = { d -> ledger = EnergyLedger.shiftResetDays(context, ledger, d) },
                            onW1Success = {
                                SelfCal.savePrediction(context, "DANGER", hours)
                                SelfCal.onStartSuccess(context); refreshSelfCal()
                            },
                            onW1Ask = {
                                if (SelfCal.pending(context) == null) SelfCal.savePrediction(context, risk.name, hours)
                                gapHours = 10.0; askStart = true
                            },
                            onW1Reset = { SelfCal.reset(context); refreshSelfCal() },
                            onVoiceText = { text -> handleCommand(text) },
                            onDemoMeasure = { measureAndCalibrate((ledger.soc - 8).coerceAtLeast(0.0), EnergyLedger.SRC_DASHCAM, " (시연: 실측이 8% 낮음)") },
                            onY1Minutes = { m ->
                                if (protect.drainA < 0.05) {
                                    Toast.makeText(context, "지금은 12V가 충전 중이라 하한선에 닿지 않아요. " +
                                            "콘솔에서 시트 열선과 공조 팬을 켜서 부하를 충전 여유(${chargeSpareA(s).roundToInt()}A)보다 크게 해 주세요",
                                        Toast.LENGTH_LONG).show()
                                } else {
                                    val target = socForMinutesLeft(s, protect, m, tempC)
                                    ledger = EnergyLedger.demoSetSoc(context, ledger, target,
                                        if (m > 0) "Y1 보호 하한선 ${m.roundToInt()}분 전" else "Y1 보호 하한선 도달")
                                }
                            },
                            onY5Sleep = { goSleep("시연 버튼") },
                            onZ2Brief = { hours ->
                                val drop0 = ledger.soc
                                ledger = EnergyLedger.simulateParking(context, ledger, hours, parkedDrainA(s), usableAh(s))
                                briefStart = hours to (drop0 - ledger.soc)
                                recordParking(hours, parkedDrainA(s))
                                briefLeft = null
                                if (hours >= BRIEF_MIN_PARK_H && tempNowC <= BRIEF_MAX_TEMP_C) {
                                    tab = "home"
                                    scope.launch { delay(300); showBriefing("시연 버튼") }
                                } else {
                                    Toast.makeText(context, "주차 ${hours.toInt()}시간 · 지금 ${"%.1f".format(tempNowC)}°C → 조건(6시간 이상, 5°C 이하)이 안 맞아서 브리핑을 띄우지 않아요",
                                        Toast.LENGTH_LONG).show()
                                }
                            },
                            onY3Receipt = {
                                ledger = EnergyLedger.simulateParking(context, ledger, 14.0, parkedDrainA(s), usableAh(s))
                                recordParking(14.0, parkedDrainA(s))
                                leftPane = "none"; tab = "home"
                                Toast.makeText(context, "어젯밤 14시간 주차 기록을 만들었어요. 홈 탭 맨 위 영수증을 보세요", Toast.LENGTH_SHORT).show()
                            },
                            onY3Week = {
                                parkLog = ParkLog.addDemoWeek(context, s, tempC)
                                leftPane = "receipt"
                            },
                            onY3Clear = { ParkLog.clear(context); parkLog = emptyList(); receiptSeen = 0L },
                            onY4Demo = {
                                val lat = navi.lastLocation?.latitude ?: WEATHER_LAT
                                val lon = navi.lastLocation?.longitude ?: WEATHER_LON
                                places = PlaceLog.addDemo(context, lat, lon)
                                val soc = t6DemoSoc(s, outsideC, 13.0, PLACE_UNDERGROUND_BONUS_C, 9.0)
                                ledger = EnergyLedger.demoSetSoc(context, ledger, soc, "T6 집·회사 비교용 12V")
                                Toast.makeText(context, "집(지하)·회사(지상) 프로필을 만들고 12V를 ${"%.1f".format(soc)}%로 맞췄어요", Toast.LENGTH_LONG).show()
                            },
                            onY4Park = { id -> PlaceLog.setCurrentDemo(context, id); curPlaceId = id; tab = "home"; leftPane = "none" },
                            onZ3Vin = { v -> source.update { it.copy(demoVin = v) }; leftPane = "guide" },
                            onZ4Demo = { ScoreLog.addDemoWeek(context); scoreVer++; scoreWeeks = ScoreLog.weeks(context); tab = "health"; leftPane = "none" },
                            onZ4Clear = { ScoreLog.clear(context); scoreVer++; scoreWeeks = ScoreLog.weeks(context) },
                            onY2Suggest = {
                                stopWait("제안 시연")          // 대기 모드가 켜져 있으면 제안 카드가 안 보여서 먼저 끔
                                waitDismissed = false; waitSuggest = true
                                Toast.makeText(context, "홈 탭 맨 위에 대기 모드 제안 카드를 띄웠어요", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        tabContent(false)
                    }
                } else {
                    // G2 운전자 화면: 탭 내용 (홈 / 배터리 건강 / 자세히)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        tabContent(true)
                    }
                }
            }
        }

        // W1: 지난 주차 후 시동 결과 질문 (P일 때만)
        if (askStart && !driving && !showDialog) {
            AlertDialog(
                onDismissRequest = { askStart = false },
                title = { Text("지난번 주차 후 시동은 괜찮았나요?") },
                text = {
                    Text("앱이 약 ${gapHours.roundToInt()}시간 동안 꺼져 있었어요.\n" +
                            "알려주시면 이 차에 맞춰 방전 예측이 더 정확해져요.")
                },
                confirmButton = {
                    Button(onClick = { SelfCal.onStartSuccess(context); refreshSelfCal(); askStart = false }) {
                        Text("괜찮았어요")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { SelfCal.onStartFailed(context); refreshSelfCal(); askStart = false; leftPane = "guide" }) {
                            Text("방전됐어요")
                        }
                        TextButton(onClick = { askStart = false }) { Text("나중에") }
                    }
                }
            )
        }

        if (showDialog) {
            val lines = warnings.toMutableList()
            when (risk) {
                Risk.DANGER -> lines.add("출발(${departLabel(s)})까지 약 ${parkHours.toInt()}시간인데 " +
                        "약 ${hours.toInt()}시간 뒤 시동이 어려울 수 있어요")
                Risk.CAUTION -> lines.add("시동 여유가 약 ${hours.toInt()}시간으로 빠듯해요")
                Risk.OK -> {}
            }
            if (risk != Risk.OK) lines.add("방전 원인: ${drainShareText(s)}")
            if (s.headlightOn) lines.add("전조등은 안전상 앱이 끄지 않아요. 직접 꺼주세요")
            rx.take(2).forEachIndexed { i, r -> lines.add("${i + 1}순위: ${r.line}") }
            if (longParking) lines.add("장기 주차 체크리스트는 '자세히' 탭에서 확인하세요")
            if (report.recommendCheck) lines.add("최근 위험 알림이 반복돼요. 배터리 점검을 권장해요")

            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(if (risk == Risk.DANGER) "⚠ 내리기 전에 확인하세요" else "내리기 전에 확인하세요") },
                text = { Text(lines.joinToString("\n") { "• $it" }) },
                confirmButton = {
                    Button(onClick = {
                        userActed("주차 팝업 조치")
                        if (source is PleosVehicleSource) {
                            source.applyAllFixes()
                        } else if (source is FakeVehicleSource) {
                            source.update {
                                it.copy(seatHeaterOn = false, steeringHeaterOn = false, mirrorHeaterOn = false,
                                    rearDefrostOn = false, hvacFanLevel = 0, trunkOpen = false, windowOpen = false)
                            }
                        }
                        showDialog = false
                    }) { Text("공조·열선·창문 끄기") }
                },
                dismissButton = {
                    Row {
                        if (risk == Risk.DANGER) {
                            TextButton(onClick = { callEmergency(context); showDialog = false }) {
                                Text("긴급출동 전화")
                            }
                        }
                        TextButton(onClick = { showDialog = false }) { Text("괜찮아요") }
                    }
                }
            )
        }
    }
    screenBody()
}

// Y1 보호 하한선 배너: 30분 전 / 10분 전(지금 끄기) / 1분 전 예고(유지) / 도달 / 보호 완료
@Composable
fun ProtectBanner(
    p: ProtectPlan,
    soc: Double,
    countdown: Int?,
    done: Boolean,
    keep: Boolean,
    driving: Boolean,
    autoProtect: Boolean,
    loads: List<Pair<String, Double>>,
    onOffNow: () -> Unit,
    onKeep: () -> Unit,
    onHideDone: () -> Unit,
    heatOnly: Boolean = false      // Y2 대기 모드 1단계: 열선만 끄고 공조 유지
) {
    val safe = done && p.stage == ProtectStage.NONE
    val color = when {
        safe -> GREEN
        countdown != null || p.stage == ProtectStage.NOTICE || p.stage == ProtectStage.VOICE -> RED
        else -> YELLOW
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            val title = when {
                safe -> "🛡 시동 가능 상태 유지 중"
                countdown != null -> if (heatOnly) "⏱ ${countdown}초 뒤 열선 자동 OFF (공조는 유지)"
                else "⏱ ${countdown}초 뒤 공조·열선 자동 OFF (12V 보호)"
                p.reached -> "⚠ 12V 보호 하한선 도달"
                else -> "🔋 약 ${minutesText(p.minutesLeft ?: 0.0)} 뒤 12V 보호 하한선 도달"
            }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("지금 12V 약 ${soc.roundToInt()}% · 보호 하한선 ${p.limitSoc.roundToInt()}% " +
                    "(내일 시동 필요 ${p.needSoc.roundToInt()}%에 여유 ${p.margin}%p, 추위 반영)", fontSize = 15.sp)
            val note = when {
                safe -> "자동 보호로 공조·열선을 껐어요. 지금은 12V가 줄지 않아요"
                countdown != null -> "계속 쓰시려면 '유지'를 눌러 주세요 · 전조등은 안전상 끄지 않아요"
                p.reached && driving -> "주행 중이라 자동으로 끄지 않아요 · 열선부터 줄여 주세요"
                p.reached && !autoProtect -> "자동 보호가 꺼져 있어요 · 공조와 열선을 직접 꺼 주세요"
                p.reached && keep -> "'유지'를 선택하셔서 자동으로 끄지 않아요 · 내일 아침 시동이 어려울 수 있어요"
                else -> "지금 12V에서 ${"%.1f".format(p.drainA)}A씩 빠져나가요" +
                        if (loads.isNotEmpty()) " · 큰 부하: " + loads.joinToString(", ") { "${it.first} ${it.second.roundToInt()}A" } else ""
            }
            Text(note, fontSize = 15.sp)
            if (safe) {
                OutlinedButton(onClick = onHideDone, modifier = Modifier.padding(top = 6.dp)) { Text("확인") }
            } else if (countdown != null || p.stage == ProtectStage.VOICE || p.stage == ProtectStage.NOTICE) {
                Row(Modifier.padding(top = 6.dp)) {
                    Button(onClick = onOffNow, modifier = Modifier.padding(end = 8.dp)) {
                        Text(if (driving) "지금 열선 끄기" else "지금 끄기")
                    }
                    if (countdown != null) OutlinedButton(onClick = onKeep) { Text("유지") }
                }
            }
        }
    }
}

// Y2 차박·대기 모드 카드: 큰 숫자 + 게이지 + 부하 목록 + what-if
@Composable
fun WaitCard(
    w: WaitPlan,
    s: VehicleSnapshot,
    soc: Double,
    limitSoc: Double,
    loads: List<Pair<String, Double>>,
    whatIfs: List<WhatIf>,
    engineOff: Boolean,
    isEv: Boolean,
    onEngineOff: (Boolean) -> Unit,
    onApply: (String) -> Unit,
    onStop: () -> Unit
) {
    val m = w.minutes
    val color = when { m == null -> GREEN; m <= PROTECT_BANNER_MIN -> RED; m <= 120 -> YELLOW; else -> GREEN }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛏 차박·대기 모드", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onStop) { Text("끝내기") }
            }
            Text(if (m == null) "남은 시간 걱정 없어요" else "남은 시간 ${minutesText(m)}",
                fontWeight = FontWeight.Bold, fontSize = 40.sp)
            Text(when {
                m == null -> "지금은 12V가 줄지 않아요" + if (isEv) " (고전압 배터리가 12V를 채워줘요)" else ""
                w.basis == "고전압" -> "고전압 배터리 ${s.hvSoc}% → ${EV_WAIT_MIN_HV.toInt()}%까지 기준 (공조 약 ${"%.1f".format(hvLoadKw(s))}kW)"
                else -> "12V가 보호 하한선 ${limitSoc.roundToInt()}%에 닿을 때까지 · 그 뒤에는 열선부터 자동으로 꺼요"
            }, fontSize = 14.sp)
            if (isEv) {
                Text("12V 기준 ${w.minutes12v?.let { minutesText(it) } ?: "줄지 않음"} · 고전압 기준 ${w.minutesHv?.let { minutesText(it) } ?: "-"} → 짧은 쪽 표시",
                    fontSize = 12.sp)
            }
            // 게이지: 12V 지금 위치와 보호 하한선
            Canvas(Modifier.fillMaxWidth().height(22.dp).padding(vertical = 4.dp)) {
                val wd = size.width
                val ht = size.height
                drawRect(Color(0xFFE6EBF0), size = size)
                drawRect(if (soc <= limitSoc) Color(0xFFD32F2F) else Color(0xFF1565C0),
                    size = androidx.compose.ui.geometry.Size(wd * (soc / 100.0).toFloat().coerceIn(0f, 1f), ht))
                val lx = wd * (limitSoc / 100.0).toFloat().coerceIn(0f, 1f)
                drawLine(Color.Black, Offset(lx, 0f), Offset(lx, ht), strokeWidth = 4f)
            }
            Text("12V 약 ${soc.roundToInt()}% · 검은 선 = 보호 하한선 ${limitSoc.roundToInt()}%", fontSize = 12.sp)
            if (!isEv) {
                ToggleRow("시동 끄고 대기 중 (끄면 발전기 충전 없음으로 계산)", engineOff, onEngineOff)
            }
            Spacer(Modifier.height(6.dp))
            if (loads.isNotEmpty()) {
                Text("지금 쓰는 전기: " + loads.joinToString(" · ") { "${it.first} ${"%.1f".format(it.second)}A" }, fontSize = 14.sp)
            }
            if (whatIfs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("이렇게 하면 더 버텨요", fontWeight = FontWeight.Bold)
                whatIfs.take(4).forEach { wi ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${wi.label} → " + (wi.gainMin?.let { "+${minutesText(it)}" } ?: "12V가 더 줄지 않아요"),
                            Modifier.weight(1f), fontSize = 15.sp)
                        wi.target?.let { tg -> OutlinedButton(onClick = { onApply(tg) }) { Text("끄기") } }
                    }
                }
                if (whatIfs.any { it.target == null }) Text("단계 낮추기는 직접 조절해 주세요 (1단 = 약 절반 전류로 가정)", fontSize = 12.sp)
            }
            Text("하한선에 닿으면 1분 전에 알려드리고 열선부터 끄고, 그래도 부족하면 공조까지 꺼요", fontSize = 12.sp)
        }
    }
}

// Z3 방전 대처 가이드 패널: 3단계 카드 + 전화 걸기
@Composable
fun GuidePanel(g: DischargeGuide, onCall: (String) -> Unit, onBluehands: () -> Unit, onQr: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 24.dp)) {
        Text("🔧 방전되면? · ${g.title}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("${groupLabel(g.group)} 기준 대처 순서", fontSize = 14.sp)
        if (!g.verified) Text("차종별 버튼·단자 위치는 취급설명서로 확인된 것만 보여드려요", fontSize = 12.sp, color = Color.DarkGray)
        Spacer(Modifier.height(8.dp))
        g.steps.forEachIndexed { i, st ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(containerColor = when (i) { 0 -> BLUE; 1 -> YELLOW; else -> RED }, contentColor = Color.Black)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(st.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    st.lines.forEach { Text("• $it", fontSize = 15.sp) }
                    if (i == 2) {
                        Row(Modifier.padding(top = 6.dp)) {
                            Button(onClick = { onCall(g.tel) }, modifier = Modifier.padding(end = 8.dp)) { Text("📞 전화 걸기") }
                            OutlinedButton(onClick = onBluehands, modifier = Modifier.padding(end = 8.dp)) { Text("가까운 블루핸즈") }
                        }
                        OutlinedButton(onClick = onQr, modifier = Modifier.padding(top = 4.dp)) { Text("📱 정비소에 기록 보여주기") }
                    }
                }
            }
        }
    }
}

// Z5 QR 패널: 공유될 내용 미리보기 + QR
@Composable
fun QrPanel(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) {
        try {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
            Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565).apply {
                for (x in 0 until m.width) for (y in 0 until m.height)
                    setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        } catch (e: Exception) {
            Log.w("BatteryAlert", "QR 만들기 실패: ${e.message}")
            null
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 24.dp)) {
        Text("📱 정비소에 기록 보여주기", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("아래 내용이 공유됩니다 (위치·주행 경로는 넣지 않아요 · 서버로 보내지 않아요)", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F6F8), contentColor = Color.Black)
        ) { Text(text, Modifier.padding(12.dp), fontSize = 14.sp) }
        Text("${text.length}자 / ${QR_MAX_CHARS}자", fontSize = 12.sp, color = Color.DarkGray)
        Spacer(Modifier.height(10.dp))
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "12V 기록 QR", modifier = Modifier.size(260.dp))
            Text("정비사님 휴대폰 카메라로 찍으면 위 내용이 글자로 보여요", fontSize = 13.sp)
        } else {
            Text("QR을 만들지 못했어요")
        }
    }
}

// Z4 12V 관리 점수 카드: 큰 숫자 + 지난주 대비 + 8주 추이 + 항목별 점수
@Composable
fun ScoreCard(sc: CareScore, lastWeek: Int?, weeks: List<Pair<Long, Int>>, onQr: () -> Unit) {
    val color = when { sc.total >= 90 -> GREEN; sc.total >= 70 -> BLUE; sc.total >= 50 -> YELLOW; else -> RED }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("🏅 이번 주 12V 관리 점수", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${sc.total}점", fontWeight = FontWeight.Bold, fontSize = 40.sp)
                Text("  ${sc.grade}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                lastWeek?.let { lw ->
                    val d = sc.total - lw
                    Text("   지난주 대비 " + when { d > 0 -> "▲$d"; d < 0 -> "▼${-d}"; else -> "변화 없음" }, fontSize = 15.sp)
                }
            }
            // 8주 추이 막대 (이번 주는 진하게)
            val last8 = weeks.sortedBy { it.first }.takeLast(8)
            if (last8.size >= 2) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                    last8.forEachIndexed { i, (_, v) ->
                        Box(Modifier.weight(1f).padding(horizontal = 3.dp).fillMaxHeight((v / 100f).coerceIn(0.05f, 1f))
                            .background(if (i == last8.lastIndex) Color(0xFF263238) else Color(0x66263238)))
                    }
                }
                Text("최근 ${last8.size}주 추이", fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("여유 ${sc.margin}/40 · ${sc.marginNote}", fontSize = 14.sp)
            Text("회복 ${sc.recovery}/25 · ${sc.recoveryNote}", fontSize = 14.sp)
            Text("설정 ${sc.setting}/15 · ${sc.settingNote}", fontSize = 14.sp)
            Text("대응 ${sc.response}/20 · ${sc.responseNote}", fontSize = 14.sp)
            if (sc.total < 50) {
                Text("점검 필요: 블루핸즈에서 배터리 점검을 받아 보세요", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Button(onClick = onQr, modifier = Modifier.padding(top = 6.dp)) { Text("📱 정비소에 기록 보여주기") }
            }
        }
    }
}

// Y3 마지막 실측 표시
fun lastMeasureText(st: EnergyLedger.State): String =
    if (st.resetSource == EnergyLedger.SRC_NONE) "실측 기록 없음 (설치 가정값)"
    else "마지막 실측 " + java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA).format(java.util.Date(st.resetTime)) +
            " (${st.resetSource})"

// Y3 주차 영수증 카드 (홈: 간단히 / 패널: 크게)
@Composable
fun ReceiptCard(
    r: ParkRecord, s: VehicleSnapshot, socNow: Double, compact: Boolean, trust: String,
    onWeekly: (() -> Unit)? = null, onClose: (() -> Unit)? = null
) {
    val ah = usableAh(s)
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black),
        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF90A4AE))
    ) {
        Column(Modifier.padding(16.dp)) {
            val day = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA).format(java.util.Date(r.endTime))
            Text("🧾 주차 에너지 영수증", fontWeight = FontWeight.Bold, fontSize = if (compact) 18.sp else 22.sp)
            Text("$day 까지 · ${"%.0f".format(r.hours)}시간 주차", fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("12V 사용 ${"%.1f".format(r.totalAh)}Ah (${"%.1f".format(r.totalAh / ah * 100)}%)",
                fontWeight = FontWeight.Bold, fontSize = if (compact) 22.sp else 28.sp)
            r.causes.filter { it.second > 0 }.forEach { (name, a) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(name, Modifier.weight(1f), fontSize = 15.sp)
                    Text("${"%.1f".format(a)}Ah (${shareText(a, r.totalAh)})", fontSize = 15.sp)
                }
            }
            val loss = coldLossPercent(r.tempC)
            if (loss > 0) Text("저온 영향: 쓸 수 있는 용량 −$loss% (${"%.0f".format(r.tempC)}°C)", fontSize = 15.sp)
            Text("남은 12V 약 ${socNow.roundToInt()}% · ${startRoomText(socNow, r.tempC)}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("- - - - - - - - - - - - - - - -", color = Color.Gray)
            Text(trust, fontSize = 12.sp, color = Color.DarkGray)
            if (onWeekly != null || onClose != null) {
                Row(Modifier.padding(top = 6.dp)) {
                    onWeekly?.let { OutlinedButton(onClick = it, modifier = Modifier.padding(end = 8.dp)) { Text("주간 리포트") } }
                    onClose?.let { Button(onClick = it) { Text("닫기") } }
                }
            }
        }
    }
}

// Y3 영수증 + 주간 리포트 패널
@Composable
fun ReceiptPanel(last: ParkRecord?, week: WeeklyReport, s: VehicleSnapshot, socNow: Double, trust: String, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 24.dp)) {
        Text("🧾 주차 영수증 · 주간 리포트", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        if (last == null) {
            Text("아직 주차 기록이 없어요. 1시간 이상 주차하고 다시 켜면 영수증이 생겨요")
        } else {
            ReceiptCard(last, s, socNow, compact = false, trust = trust)
        }
        Spacer(Modifier.height(8.dp))
        Text("최근 7일", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (week.count == 0) {
            Text("최근 7일 주차 기록이 없어요")
        } else {
            Text("주차 ${week.count}번 · 총 ${"%.0f".format(week.hours)}시간 · 12V 사용 ${"%.1f".format(week.totalAh)}Ah " +
                    "(배터리 ${s.batteryAh}Ah의 ${(week.totalAh / s.batteryAh * 100).roundToInt()}%)")
            Spacer(Modifier.height(6.dp))
            val maxAh = week.causes.maxOfOrNull { it.second }?.coerceAtLeast(0.001) ?: 1.0
            week.causes.forEach { (name, a) ->
                Text("$name ${"%.1f".format(a)}Ah (${shareText(a, week.totalAh)})", fontSize = 14.sp)
                Box(Modifier.fillMaxWidth().height(14.dp).padding(bottom = 4.dp).background(Color(0xFFE6EBF0))) {
                    Box(Modifier.fillMaxWidth((a / maxAh).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                        .background(if (name == week.causes.first().first) Color(0xFFD32F2F) else Color(0xFF90A4AE)))
                }
            }
            week.causes.firstOrNull()?.let { (name, a) ->
                Text("가장 큰 원인: $name ${shareText(a, week.totalAh)}", fontWeight = FontWeight.Bold)
            }
            week.saving?.let {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = YELLOW, contentColor = Color.Black)
                ) { Text("💡 $it", Modifier.padding(12.dp), fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(trust, fontSize = 12.sp, color = Color.DarkGray)
        Text("주차 기록에는 위치를 저장하지 않아요", fontSize = 12.sp, color = Color.DarkGray)
    }
}

// Z2 겨울 출발 브리핑 카드
@Composable
fun BriefingCard(b: Briefing, s: VehicleSnapshot, left: Int, onReceipt: () -> Unit, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = BLUE, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("❄ 출발 브리핑", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Text("${left}초 뒤 닫혀요", fontSize = 12.sp)
            }
            Text("어젯밤 ${"%.0f".format(b.parkH)}시간 주차 · 지금 ${"%.0f".format(b.tempNow)}°C", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("① 밤사이 12V −${"%.1f".format(b.drop)}%" + (if (b.cause.isNotEmpty()) " (${b.cause})" else "") +
                    " → 지금 약 ${b.socNow.roundToInt()}%", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("② " + when {
                b.driveMin == null -> "오늘 밤 ${"%.0f".format(b.nightMin)}°C 예보 · 주행만으로는 채우기 어려워요 → 충전·점검 권장"
                b.driveMin > 0 -> "오늘 밤 ${"%.0f".format(b.nightMin)}°C 예보 · 내일 아침까지 버티려면 오늘 총 ${b.driveMin}분 이상 주행"
                else -> "오늘 밤 ${"%.0f".format(b.nightMin)}°C 예보 · 추가 주행 필요 없어요"
            }, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("(오늘 밤 ${EVENING_ARRIVE_HOUR}시 도착 → 내일 출발까지 ${"%.0f".format(b.nightParkH)}시간 주차 기준, 필요 12V ${b.needSoc.roundToInt()}%)",
                fontSize = 12.sp)
            val chips = buildList {
                if (s.tireLow == true) add("타이어 공기압 부족")
                if (s.washerLow == true) add("워셔액 부족")
                if (s.rearDefrostOn) add("뒷유리 열선 켜짐 (12A)")
            }
            Spacer(Modifier.height(6.dp))
            if (chips.isEmpty()) {
                Text("③ 겨울 점검: 이상 없음" + if (s.tireLow == null) " (타이어 공기압은 이 차에서 확인 불가)" else "", fontSize = 14.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("③ ", fontSize = 14.sp)
                    chips.forEach { c ->
                        Card(
                            modifier = Modifier.padding(end = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = YELLOW, contentColor = Color.Black)
                        ) { Text(c, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onReceipt, modifier = Modifier.padding(end = 8.dp)) { Text("영수증 보기") }
                Button(onClick = onClose) { Text("닫기") }
            }
        }
    }
}

// Y5 주차 절전 카드
@Composable
fun SleepCard(since: Long, onWake: () -> Unit) {
    val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(since))
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = BLUE, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("🌙 주차 절전 중 ($at 부터)", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("이 앱은 주차 중 차량을 깨우지 않습니다", fontWeight = FontWeight.Bold)
            Text("차량 값 읽기 · 내비 요청 · 날씨 요청을 모두 멈췄어요")
            Text("문을 열거나 기어를 바꾸면 다시 시작하고, 그동안의 소모는 한 번에 계산해요", fontSize = 13.sp)
            OutlinedButton(onClick = onWake, modifier = Modifier.padding(top = 6.dp)) { Text("다시 켜기") }
        }
    }
}

// X1·X2 도착 전 배너: 경고 + 열선 효과 + 충전 경로 버튼
@Composable
fun PreArrivalBanner(
    plan: ArrivalPlan,
    s: VehicleSnapshot,
    tempC: Double,
    firstAction: String?,
    x2Applied: Boolean,
    x2Dismissed: Boolean,
    x2RouteNote: String,
    onRoute: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (plan.risk == Risk.DANGER) RED else YELLOW, contentColor = Color.Black
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("🏠 집 도착 약 ${plan.arriveMin.roundToInt()}분 전 — 내일 아침 시동 ${if (plan.risk == Risk.DANGER) "위험" else "주의"}",
                fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("도착 후 ${departLabel(s)}까지 약 ${plan.parkHours.roundToInt()}시간 주차 · 출발 전 최저 ${"%.1f".format(tempC)}°C")
            Text("도착 시 12V 약 ${plan.socArrive.roundToInt()}% → 시동 한계 약 ${"%.1f".format(plan.limitHours)}시간")
            if (plan.heaterGain >= 0.5) {
                Text("지금 열선·공조를 끄면 도착 시 12V +${"%.1f".format(plan.heaterGain)}%", fontWeight = FontWeight.Bold)
            } else {
                Text("지금은 이미 최대로 충전 중이라 열선을 꺼도 도착 때 차이가 거의 없어요")
            }
            Spacer(Modifier.height(6.dp))
            when {
                x2Applied -> {
                    Text("✓ 충전 경로 적용: 도착 때 다시 계산해서 알려드릴게요", fontWeight = FontWeight.Bold)
                    if (x2RouteNote.isNotBlank()) Text(x2RouteNote)
                }
                x2Dismissed -> {
                    firstAction?.let { Text("대신 이렇게 해보세요: $it") }
                }
                plan.extraMin != null -> {
                    Text("열선 끄고 약 ${plan.extraMin}분 더 달리면 회복 가능 (필요 ${plan.needSoc.roundToInt()}%, 부족 ${"%.1f".format(plan.shortage)}%p)")
                    Row(Modifier.padding(top = 6.dp)) {
                        Button(onClick = onRoute, modifier = Modifier.padding(end = 8.dp)) {
                            Text("${plan.extraMin}분 더 걸리는 경로로 회복")
                        }
                        OutlinedButton(onClick = onDismiss) { Text("괜찮아요") }
                    }
                }
                plan.shortage > 0 -> {
                    Text("추가 주행만으로는 회복이 어려워요 (필요 ${plan.needSoc.roundToInt()}%)")
                    if (s.isEv && s.hvSoc < EV_SAVER_MIN_HV_SOC) Text("고전압 배터리도 부족해요 → 충전소 경유를 추천해요")
                    firstAction?.let { Text("대신: $it") }
                }
                else -> firstAction?.let { Text("추천: $it") }
            }
        }
    }
}

// ── X3 지역별 방전 위험 지수 (서비스 미리보기, 가상 데이터) ──
@Composable
fun RegionPanel(
    s: VehicleSnapshot,
    myArea: String?,
    myRisk: Risk,
    tempC: Double,
    onConsent: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val regions = DEMO_REGIONS
    val top = regions.maxByOrNull { it.riskPct }
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 24.dp)) {
        Text("📊 지역별 방전 위험 지수", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("내일 아침 어느 지역에 방전 출동이 몰릴지 미리 봐요 (서비스 미리보기 · 가상 데이터)", fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        top?.let {
            Text("내일 아침 출동이 가장 몰릴 곳: ${it.name} (위험 ${it.riskPct}%, 예상 출동 약 ${it.expectedCalls}건)",
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        regions.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, Modifier.weight(0.28f), fontSize = 14.sp)
                Box(Modifier.weight(0.42f).height(16.dp).background(Color(0xFFE6EBF0))) {
                    Box(Modifier.fillMaxWidth(r.riskPct / 30f).height(16.dp)
                        .background(if (r.riskPct >= 20) Color(0xFFD32F2F) else if (r.riskPct >= 12) Color(0xFFF2A93B) else Color(0xFF4CAF50)))
                }
                Text("  ${r.riskPct}% · ${r.minTempC}°C · 출동 ${r.expectedCalls}건", Modifier.weight(0.30f), fontSize = 12.sp)
            }
        }
        Text("막대 = 참여 차량 중 내일 아침 '위험' 판정 비율", fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        Text("누가 쓰나요", fontWeight = FontWeight.Bold)
        Text("• 보험사 긴급출동: 위험 지역에 기사 미리 배치")
        Text("• 블루핸즈: 지역별 배터리 재고 미리 확보")
        Text("• 현대차 서비스: 겨울철 점검 캠페인 대상 지역 선정")
        Spacer(Modifier.height(12.dp))

        Text("내 차가 보내는 데이터 (동의한 경우만)", fontWeight = FontWeight.Bold)
        ToggleRow("익명 위험 데이터 제공에 동의", s.shareConsent, onConsent)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(java.util.Date())
        Text("지역: ${myArea ?: "위치 확인 중"} (동 단위)")
        Text("날짜: $day · 위험 등급: ${when (myRisk) { Risk.OK -> "OK"; Risk.CAUTION -> "주의"; Risk.DANGER -> "위험" }}")
        Text("예보 최저기온: ${"%.1f".format(tempC)}°C · 차종군: ${if (s.isEv) "전기차" else "내연기관"}")
        Text(if (s.shareConsent) "→ 위 내용만 익명으로 집계에 보내요 (현재는 미리보기라 실제 전송 없음)"
        else "→ 동의하지 않아서 보내지 않아요", fontWeight = FontWeight.Bold)
        Text("보내지 않는 것: 차대번호, 차량번호, 정확한 위치, 개인정보", fontSize = 12.sp)
    }
}

// X6 음성 문답 카드: 마이크 버튼 + 들은 말 + 답
@Composable
fun VoiceCard(state: String, question: String, answer: String, big: Boolean, onMic: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = BLUE, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onMic, modifier = if (big) Modifier.height(64.dp) else Modifier) {
                    Text("🎤 말하기", fontSize = if (big) 22.sp else 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(if (state.isBlank()) "\"내일 아침 시동 괜찮아?\" \"열선 다 꺼줘\" \"왜 위험해?\"" else state,
                    fontSize = if (big) 18.sp else 13.sp)
            }
            if (question.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("🙋 $question", fontSize = if (big) 18.sp else 14.sp)
                Text("🔋 $answer", fontWeight = FontWeight.Bold, fontSize = if (big) 20.sp else 15.sp)
            }
        }
    }
}

// ── G3 "지금 주차하면" 12V 예측 곡선: 지금 → 출발까지, 추위 반영 ──
@Composable
fun ForecastCard(
    curve: List<CurvePoint>, alt: List<CurvePoint>?, departH: Double, maxH: Double,
    departText: String, saver: Boolean
) {
    val cross = crossingHours(curve)
    val altCross = alt?.let { crossingHours(it) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = BLUE, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("지금 주차하면 출발까지 12V는?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            when {
                saver -> Text("12V 자동충전이 켜져 있어서 걱정 없어요", fontSize = 14.sp)
                cross != null && cross < departH ->
                    Text("⚠ ${timeLabelAfter(cross)}에 시동 한계선 아래로 (출발보다 ${(departH - cross).roundToInt()}시간 먼저)",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                else -> Text("✓ 출발할 때 약 ${effAt(curve, departH).roundToInt()}% (시동 한계 ${START_LIMIT_SOC.toInt()}%)", fontSize = 15.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
                val w = size.width
                val h = size.height
                fun x(hr: Double) = (hr / maxH).toFloat() * w
                fun y(v: Double) = h - (v / 100.0).toFloat().coerceIn(0f, 1f) * h
                // 출발 시각 세로선
                drawLine(Color.Gray, Offset(x(departH), 0f), Offset(x(departH), h), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                // 시동 한계 25%
                drawLine(Color(0xFFD32F2F), Offset(0f, y(START_LIMIT_SOC)), Offset(w, y(START_LIMIT_SOC)),
                    strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                fun pathOf(c: List<CurvePoint>) = Path().apply {
                    c.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.h), y(p.eff)) else lineTo(x(p.h), y(p.eff)) }
                }
                alt?.let { drawPath(pathOf(it), Color(0xFF2E7D32), style = Stroke(width = 4f)) }
                drawPath(pathOf(curve), Color(0xFF1565C0), style = Stroke(width = 5f))
                cross?.let { drawCircle(Color(0xFFD32F2F), radius = 10f, center = Offset(x(it), y(START_LIMIT_SOC))) }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("지금", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("점선 세로 = 출발 $departText", fontSize = 12.sp)
            }
            Text("파란선 = 추위 반영 12V · 빨간 점선 = 시동 한계" + if (alt != null) " · 초록선 = 블랙박스 끄면" else "", fontSize = 12.sp)
            if (alt != null && cross != null && cross < departH) {
                Text(if (altCross == null || altCross >= departH) "→ 블랙박스를 끄면 출발까지 버틸 수 있어요"
                else "→ 블랙박스를 꺼도 ${timeLabelAfter(altCross)}에 한계선 아래로", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── G2 화면 부품 ──
@Composable
fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.padding(end = 6.dp)) { Text(label, fontSize = 16.sp) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 6.dp)) { Text(label, fontSize = 16.sp) }
    }
}

// 결론 카드: 한 줄 판정 + 핵심 숫자 4개
@Composable
fun HeroCard(title: String, sub: String, color: Color, stats: List<Triple<String, String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            Text(sub, fontSize = 17.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                stats.forEach { (label, value, small) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 14.sp)
                        Text(value, fontWeight = FontWeight.Bold, fontSize = 36.sp)
                        if (small.isNotBlank()) Text(small, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// 지금 할 일: 효과 큰 순서 + 바로 실행 버튼
@Composable
fun RxActionList(rx: List<Rx>, warnings: List<String>, driving: Boolean, onRun: (Rx) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = BLUE, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("지금 할 일", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("효과가 큰 순서예요", fontSize = 12.sp)
            if (rx.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("✓ 따로 할 일 없어요. 그대로 주차해도 괜찮아요", fontSize = 17.sp)
            }
            rx.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        val effect = buildList {
                            if (r.gainHours >= 0.5) add("시동 한계 +${r.gainHours.roundToInt()}시간")
                            if (r.note.isNotBlank()) add(r.note)
                            if (r.enough) add("✓ 이것만으로 충분")
                        }.joinToString(" · ")
                        if (effect.isNotBlank()) Text(effect, fontSize = 14.sp)
                    }
                    val label = when (r.action) {
                        "route" -> "경로 바꾸기"
                        "heat" -> "열선 끄기"
                        "close" -> if (driving) null else "닫기"
                        "dashcam" -> "껐어요"
                        "call" -> "전화"
                        else -> null
                    }
                    if (label != null) Button(onClick = { onRun(r) }) { Text(label) }
                    else Text("직접", fontSize = 13.sp)
                }
            }
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("하차 전 확인: " + warnings.joinToString(", "), fontSize = 14.sp)
            }
        }
    }
}

// 주차 중 전기가 어디로 새는지: 비율 막대
@Composable
fun DrainCard(s: VehicleSnapshot, tempC: Double) {
    val parts = parkedBreakdown(s)
    val total = parts.sumOf { it.second }.coerceAtLeast(0.001)
    val palette = listOf(Color(0xFFD32F2F), Color(0xFFF2A93B), Color(0xFF1565C0), Color(0xFF7E57C2))
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = BLUE, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("주차 중 전기가 어디로 새나요", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("합계 ${"%.2f".format(parkedDrainA(s))}A", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(22.dp)) {
                parts.forEachIndexed { i, (_, a) ->
                    Box(Modifier.weight((a / total).toFloat().coerceAtLeast(0.01f)).fillMaxHeight()
                        .background(palette[i % palette.size]))
                }
            }
            Spacer(Modifier.height(6.dp))
            parts.forEachIndexed { i, (name, a) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(palette[i % palette.size]))
                    Text("  $name ${(a / total * 100).roundToInt()}% (${"%.2f".format(a)}A)", fontSize = 14.sp)
                }
            }
            if (coldLossPercent(tempC) > 0) {
                Text("+ 추위로 쓸 수 있는 용량 −${coldLossPercent(tempC)}%", fontSize = 14.sp)
            }
        }
    }
}

// 주행 중 간단 화면용 큰 숫자 칸
@Composable
fun BigTile(label: String, value: String, sub: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 16.sp)
        }
    }
}

@Composable
fun InfoCard(title: String, lines: List<String>, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            lines.forEach { Text(it) }
        }
    }
}

@Composable
fun ReportCard(
    report: BatteryReport,
    status: String,
    places: List<Place>,
    onBluehands: () -> Unit,
    onEmergency: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (report.recommendCheck) RED else BLUE,
            contentColor = Color.Black
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("⑥ 배터리 리포트", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            report.lines.forEach { Text(it) }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onBluehands, modifier = Modifier.padding(end = 8.dp)) {
                    Text("가까운 블루핸즈 안내")
                }
                OutlinedButton(onClick = onEmergency) { Text("긴급출동 080-600-6000") }
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(status, fontWeight = FontWeight.Bold)
            }
            places.take(3).forEachIndexed { i, p ->
                Text("${i + 1}. ${p.name} · ${"%.1f".format(p.distanceM / 1000.0)}km · ${p.phone}", fontSize = 13.sp)
                Text("   ${p.address}", fontSize = 12.sp)
            }
        }
    }
}

// ⑦ 12V 추이 그래프 (최근 7일, 10분마다 기록)
@Composable
fun TrendCard(samples: List<EnergyLedger.Sample>, socNow: Double, unc: Double, calNote: String = "") {
    val now = System.currentTimeMillis()
    val from = now - 7 * 24L * 3_600_000
    val points = samples.filter { it.time >= from }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = BLUE, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("⑦ 12V 배터리 추이 (최근 7일)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (points.size < 2) {
                Text("아직 기록이 적어요. 10분마다 1번씩 기록돼요")
            } else {
                Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp)) {
                    val w = size.width
                    val h = size.height
                    fun x(t: Long) = (t - from).toFloat() / (now - from).toFloat() * w
                    fun y(v: Double) = h - (v / 100.0).toFloat() * h
                    // 하루 구분선
                    for (d in 1..6) {
                        val xx = w * d / 7f
                        drawLine(Color.LightGray, Offset(xx, 0f), Offset(xx, h), strokeWidth = 1f)
                    }
                    // 시동 한계 25% (빨간 점선)
                    drawLine(
                        Color(0xFFD32F2F), Offset(0f, y(START_LIMIT_SOC)), Offset(w, y(START_LIMIT_SOC)),
                        strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    )
                    // 12V SoC 선
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(x(p.time), y(p.soc)) else path.lineTo(x(p.time), y(p.soc))
                    }
                    drawPath(path, Color(0xFF1565C0), style = Stroke(width = 4f))
                    // W2·F1 실측 보정 지점 (초록 점)
                    points.filter { it.measured }.forEach { p ->
                        drawCircle(Color(0xFF2E7D32), radius = 9f, center = Offset(x(p.time), y(p.soc)))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("7일 전", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("지금", fontSize = 12.sp)
                }
                Text("빨간 점선 = 시동 한계 ${START_LIMIT_SOC.toInt()}% · 초록 점 = 실측 보정", fontSize = 12.sp)
                Text("7일 최저 ${points.minOf { it.soc }.roundToInt()}% · 현재 약 ${socNow.roundToInt()}% (±${unc.roundToInt()}%)")
            }
            if (calNote.isNotBlank()) {
                Text(calNote, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ⚙ 설정 패널: 운전자가 직접 바꾸는 값 (앱 껐다 켜도 저장됨)
@Composable
fun SettingsPanel(
    fake: FakeVehicleSource,
    s: VehicleSnapshot,
    ledgerSoc: Double,
    ledgerUnc: Double,
    confText: String,
    departInfo: String,
    dashAdvice: DashcamAdvice,
    carText: String,
    protect: ProtectPlan,
    places: List<ParkPlace>,
    curPlaceId: Int,
    outsideC: Double,
    parkHours: Double,
    onRenamePlace: (Int, String) -> Unit,
    onPlaceUnderground: (Int, Boolean) -> Unit,
    onCalibrate: () -> Unit,
    onVoltage: (Double) -> Unit,
    onClearDepart: () -> Unit,
    onTestVoice: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 24.dp)) {
        Text("⚙ 설정", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        OutlinedButton(onClick = onGuide) { Text("🔧 방전 대처 가이드") }
        Text("바꾼 값은 자동 저장돼요", fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))

        // 다음 출발
        Text("다음 출발: ${departLabel(s)}", fontWeight = FontWeight.Bold)
        Text(departInfo, fontSize = 12.sp)
        ToggleRow("출발 시각 자동 학습", s.autoDepart) { v -> fake.update { it.copy(autoDepart = v) } }
        Text("며칠 뒤: ${s.departDaysLater}일 (0=오늘, 1=내일) — 움직이면 직접 입력으로 바뀌어요", fontSize = 12.sp)
        Slider(value = s.departDaysLater.toFloat(),
            onValueChange = { v -> fake.update { it.copy(departDaysLater = v.roundToInt(), autoDepart = false) } },
            valueRange = 0f..7f, steps = 6)
        Text("시: ${s.departHour}시")
        Slider(value = s.departHour.toFloat(),
            onValueChange = { v -> fake.update { it.copy(departHour = v.roundToInt(), autoDepart = false) } },
            valueRange = 0f..23f, steps = 22)
        Text("분: ${s.departMinute}분")
        Slider(value = s.departMinute.toFloat().coerceIn(0f, 50f),
            onValueChange = { v -> fake.update { it.copy(departMinute = v.roundToInt(), autoDepart = false) } },
            valueRange = 0f..50f, steps = 4)
        OutlinedButton(onClick = onClearDepart) { Text("출발 학습 기록 초기화") }
        Spacer(Modifier.height(12.dp))

        // 12V 배터리
        Text("12V 배터리", fontWeight = FontWeight.Bold)
        Text(carText, fontSize = 12.sp)
        Text("배터리를 교체했다면 사용 연수를 직접 바꿔 주세요 (직접 바꾼 값이 우선)", fontSize = 12.sp)
        Text("용량: ${s.batteryAh}Ah (차량 설명서·배터리 라벨 확인)")
        Slider(value = s.batteryAh.toFloat(),
            onValueChange = { v -> fake.update { it.copy(batteryAh = (v / 5).roundToInt() * 5) } },
            valueRange = 40f..100f, steps = 11)
        Text("사용 연수: ${s.batteryAgeYears}년")
        Slider(value = s.batteryAgeYears.toFloat(),
            onValueChange = { v -> fake.update { it.copy(batteryAgeYears = v.roundToInt()) } },
            valueRange = 0f..8f, steps = 7)
        Text("현재 추정 ${ledgerSoc.roundToInt()}% (±${ledgerUnc.roundToInt()}%)")
        Text(confText, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))

        // W2: 블랙박스 표시 전압 입력
        var volt by remember { mutableStateOf(12.3f) }
        var restedOk by remember { mutableStateOf(false) }
        Text("블랙박스 표시 전압 입력: ${"%.2f".format(volt)}V → 약 ${voltageToSoc(volt.toDouble()).roundToInt()}%",
            fontWeight = FontWeight.Bold)
        Slider(value = volt, onValueChange = { volt = (it * 20).roundToInt() / 20f },
            valueRange = 11.5f..12.9f, steps = 27)
        ToggleRow("시동 끈 뒤 30분 이상 지난 값이에요", restedOk) { restedOk = it }
        Button(onClick = { onVoltage(volt.toDouble()); restedOk = false }, enabled = restedOk) {
            Text("이 전압으로 보정 (±6%)")
        }
        Text("시동을 끈 직후에는 전압이 실제보다 높게 나와서 30분 이상 지난 값만 받아요", fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))

        Text("정비소 측정값으로 보정: ${s.battery12vSoc}%")
        Slider(value = s.battery12vSoc.toFloat(),
            onValueChange = { v -> fake.update { it.copy(battery12vSoc = v.roundToInt()) } },
            valueRange = 0f..100f)
        Button(onClick = onCalibrate) { Text("이 값으로 보정 (±3%)") }
        Spacer(Modifier.height(12.dp))

        // Y1 12V 자동 보호
        Text("12V 자동 보호", fontWeight = FontWeight.Bold)
        ToggleRow("자동 보호 (P단에서 하한선에 닿으면 공조·열선 자동 OFF)", s.autoProtect) { v ->
            fake.update { it.copy(autoProtect = v) }
        }
        Text("하한선 여유: ${s.protectMargin}%p")
        Row {
            PROTECT_MARGIN_CHOICES.forEach { m ->
                TabButton("${m}%p", s.protectMargin == m) { fake.update { it.copy(protectMargin = m) } }
            }
        }
        Text("지금 보호 하한선 ${protect.limitSoc.roundToInt()}% (내일 시동 필요 ${protect.needSoc.roundToInt()}%에 여유 ${protect.margin}%p, 추위 반영)" +
                if (protect.margin != s.protectMargin) " (신뢰도 낮음 +${PROTECT_LOW_CONF_EXTRA}%p)" else "", fontWeight = FontWeight.Bold)
        Text("주행 중에는 끄지 않고 알림만 해요. 끄기 1분 전에 음성으로 먼저 알려드리고 '유지'를 누르면 끄지 않아요. " +
                "전조등은 안전상 끄지 않아요", fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        // 블랙박스
        Text("블랙박스", fontWeight = FontWeight.Bold)
        ToggleRow("주차녹화(상시녹화) 사용", s.dashcamParkingOn) { v -> fake.update { it.copy(dashcamParkingOn = v) } }
        if (s.dashcamParkingOn) {
            ToggleRow("블랙박스 저전압 차단을 권장값(${"%.1f".format(dashAdvice.cutoffV)}V)으로 맞췄어요", s.dashcamCutoffSet) { v ->
                fake.update { it.copy(dashcamCutoffSet = v) }
            }
        }
        if (s.dashcamParkingOn) {
            Text("소모 전류: ${s.dashcamMa}mA (제품 설명서 확인)")
            Slider(value = s.dashcamMa.toFloat(),
                onValueChange = { v -> fake.update { it.copy(dashcamMa = (v / 50).roundToInt() * 50) } },
                valueRange = 100f..800f, steps = 13)
            Text("추천 저전압 차단: ${"%.1f".format(dashAdvice.cutoffV)}V 이상", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        // 기타
        Text("기타", fontWeight = FontWeight.Bold)
        ToggleRow("전기차 12V 자동충전 반영 (기본 OFF = 보수적)", s.evSaverEnabled) { v ->
            fake.update { it.copy(evSaverEnabled = v) }
        }
        ToggleRow("지하·실내 주차 (+5°C로 계산)", s.undergroundParking) { v ->
            fake.update { it.copy(undergroundParking = v) }
        }
        ToggleRow("음성 안내", s.voiceOn) { v -> fake.update { it.copy(voiceOn = v) } }
        OutlinedButton(onClick = onTestVoice) { Text("음성 안내 테스트") }
        Spacer(Modifier.height(12.dp))

        // Y4 장소 프로필
        Text("장소 프로필", fontWeight = FontWeight.Bold)
        if (places.isEmpty()) {
            Text("아직 저장된 장소가 없어요. P단에 서면 내비 위치로 자동으로 만들어져요", fontSize = 12.sp)
        }
        places.forEach { pl ->
            val pa = placeAdvice(s, ledgerSoc, outsideC, pl.avgH.takeIf { it > 0 } ?: parkHours, pl.underground, pl.bonusC)
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = if (pl.id == curPlaceId) BLUE else Color(0xFFF4F6F8), contentColor = Color.Black)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text((if (pl.id == curPlaceId) "📍 " else "") + pl.label + if (!pl.named) " (자동 추천)" else "", fontWeight = FontWeight.Bold)
                    Text("평균 ${"%.0f".format(pl.avgH)}시간 · ${pl.visits}번 주차 · 최근 30일 위험 ${pl.risks30()}번", fontSize = 13.sp)
                    Text("위험도 ${placeRiskWord(pa.risk)} (${"%.0f".format(pa.tempC)}°C 기준) → ${pa.dashcam}", fontSize = 13.sp)
                    Row {
                        listOf("집", "회사", "기타").forEach { nm ->
                            TabButton(nm, pl.name == nm) { onRenamePlace(pl.id, nm) }
                        }
                    }
                    ToggleRow("지하·실내 주차 (+${"%.0f".format(pl.bonusC)}°C)", pl.underground) { v -> onPlaceUnderground(pl.id, v) }
                }
            }
        }
        Text("장소 좌표는 이 기기 안에만 저장돼요. 지역 위험 지수에는 행정동 단위로만 들어가요", fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        // Y5 앱 자체 소모 0
        Text("🔋 이 앱은 주차 중 차량을 깨우지 않습니다", fontWeight = FontWeight.Bold)
        Text("하차를 확인하고 2분이 지나면 차량 값 읽기, 내비 요청, 날씨 요청을 모두 멈춰요. " +
                "원격으로 차를 깨우거나 주기적으로 호출하지 않아요. 문을 열거나 기어를 바꾸면 다시 시작하고, " +
                "멈춰 있던 동안의 소모는 그때 한 번에 계산해요", fontSize = 12.sp)
    }
}

// 🧪 시연 패널: SDK 실제값 확인 + 가상값 조작 + 시연용 버튼
@Composable
fun DemoPanel(
    fake: FakeVehicleSource,
    s: VehicleSnapshot,
    weatherStatus: String,
    forecastMin: Double?,
    onDemoHistory: () -> Unit,
    onClearHistory: () -> Unit,
    onSimPark: () -> Unit,
    onDemoTrend: () -> Unit,
    onDemoDepart: () -> Unit,
    onShiftReset: (Int) -> Unit,
    onW1Success: () -> Unit,
    onW1Ask: () -> Unit,
    onW1Reset: () -> Unit,
    onVoiceText: (String) -> Unit,
    onDemoMeasure: () -> Unit,
    onY1Minutes: (Double) -> Unit,
    onY5Sleep: () -> Unit,
    onY2Suggest: () -> Unit,
    onZ2Brief: (Double) -> Unit,
    onY3Receipt: () -> Unit,
    onY3Week: () -> Unit,
    onY3Clear: () -> Unit,
    onY4Demo: () -> Unit,
    onY4Park: (Int) -> Unit,
    onZ3Vin: (String?) -> Unit,
    onZ4Demo: () -> Unit,
    onZ4Clear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 24.dp)) {
        Text("🧪 시연 패널", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("SDK 상태: ${s.sdkStatus}", fontSize = 12.sp)
        Text("날씨 상태: $weatherStatus", fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))

        Text("[SDK 실제값] 운전석 문: ${if (s.driverDoorOpen) "열림" else "닫힘"}",
            fontWeight = FontWeight.Bold)
        ToggleRow("└ 테스트용 수동 변경", s.driverDoorOpen) { v -> fake.update { it.copy(driverDoorOpen = v) } }
        if ("gear" in s.sdkFields) {
            Text("[SDK 실제값] 기어: ${s.driveMode}", fontWeight = FontWeight.Bold)
        } else {
            Text("기어: SDK 미연결 → 문 열림만으로 하차 감지", fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))

        ToggleRow(
            "[외부 API] 날씨 자동" + (forecastMin?.let { " (예보 최저 ${"%.1f".format(it)}°C)" } ?: ""),
            s.autoWeather
        ) { v -> fake.update { it.copy(autoWeather = v) } }
        Text("[수동] 외기온도 ${s.outsideTempC.toInt()}°C ${if (s.autoWeather) "(날씨 자동 끄면 사용)" else ""}")
        Slider(value = s.outsideTempC.toFloat(),
            onValueChange = { v -> fake.update { it.copy(outsideTempC = v.toDouble()) } },
            valueRange = -20f..20f)
        Spacer(Modifier.height(8.dp))

        if ("trip" in s.sdkFields) {
            Text("[SDK 실제값] 이번 주행 거리: ${"%.1f".format(s.tripKm)} km", fontWeight = FontWeight.Bold)
        } else {
            Text("[가상] 주행 거리 ${s.tripKm.toInt()} km")
            Slider(value = s.tripKm.toFloat(),
                onValueChange = { v -> fake.update { it.copy(tripKm = v.toDouble()) } },
                valueRange = 1f..60f)
        }
        if ("tripTime" in s.sdkFields) {
            Text("[SDK 기어 기준] 이번 주행 시간: ${s.tripMinutes ?: 0}분", fontWeight = FontWeight.Bold)
        }

        if ("fan" in s.sdkFields) {
            Text("[SDK 실제값] 공조 팬: ${s.hvacFanLevel} / ${s.fanMax}단", fontWeight = FontWeight.Bold)
        } else {
            Text("[가상] 공조 팬 ${s.hvacFanLevel}단")
            Slider(value = s.hvacFanLevel.toFloat(),
                onValueChange = { v -> fake.update { it.copy(hvacFanLevel = v.roundToInt()) } },
                valueRange = 0f..8f, steps = 7)
        }
        SdkOrToggle("시트 열선", "seat", s.seatHeaterOn, s) { v -> fake.update { it.copy(seatHeaterOn = v) } }
        SdkOrToggle("운전석 착석", "occ", s.driverSeatOccupied, s, "앉아 있음", "비어 있음") { v ->
            fake.update { it.copy(driverSeatOccupied = v) }
        }
        SdkOrToggle("운전석 안전벨트", "belt", s.driverBelted, s, "채움", "풀림") { v -> fake.update { it.copy(driverBelted = v) } }
        SdkOrToggle("조수석 착석", "occP", s.passengerOccupied, s, "앉아 있음", "비어 있음") { v ->
            fake.update { it.copy(passengerOccupied = v) }
        }
        if ("seat" in s.sdkFields) {
            Text("[SDK 실제값] 조수석 열선: ${if (s.passengerHeaterOn) "켜짐" else "꺼짐"}", fontWeight = FontWeight.Bold)
        } else {
            ToggleRow("[가상] 조수석 열선", s.passengerHeaterOn) { v -> fake.update { it.copy(passengerHeaterOn = v) } }
        }
        SdkOrToggle("운전대 열선", "steer", s.steeringHeaterOn, s) { v -> fake.update { it.copy(steeringHeaterOn = v) } }
        SdkOrToggle("출력 제한 경고등", "powerDown", s.powerDownWarning, s) { v -> fake.update { it.copy(powerDownWarning = v) } }
        SdkOrToggle("미러 열선", "mirror", s.mirrorHeaterOn, s) { v -> fake.update { it.copy(mirrorHeaterOn = v) } }
        SdkOrToggle("뒷유리 열선", "defrost", s.rearDefrostOn, s) { v -> fake.update { it.copy(rearDefrostOn = v) } }
        SdkOrToggle("전조등", "light", s.headlightOn, s) { v -> fake.update { it.copy(headlightOn = v) } }
        SdkOrToggle("트렁크", "trunk", s.trunkOpen, s, "열림", "닫힘") { v -> fake.update { it.copy(trunkOpen = v) } }
        SdkOrToggle("창문", "window", s.windowOpen, s, "열림", "닫힘") { v -> fake.update { it.copy(windowOpen = v) } }
        Spacer(Modifier.height(8.dp))

        if ("ev" in s.sdkFields) {
            Text("[SDK 실제값] ${if (s.demoForceIce) "전기차 → [시연] 내연기관으로 계산 중" else "전기차"} · 고전압 배터리 ${s.hvSoc}%", fontWeight = FontWeight.Bold)
        } else {
            ToggleRow("[가상] 전기차", s.isEv) { v -> fake.update { it.copy(isEv = v) } }
            if (s.isEv) {
                Text("[가상] 고전압 배터리 ${s.hvSoc}%")
                Slider(value = s.hvSoc.toFloat(),
                    onValueChange = { v -> fake.update { it.copy(hvSoc = v.roundToInt()) } },
                    valueRange = 0f..100f)
            }
        }
        // Z2 겨울 점검 값 (실제 SDK 값인지, 시연 신호인지, 미지원인지 표시)
        Text("[겨울 점검] 타이어 공기압 부족: ${when (s.tireLow) { true -> if (s.demoTireLow) "있음 (시연 신호)" else "있음"; false -> "없음"; null -> "확인 불가 (에뮬레이터 미지원)" }}" +
                " · 워셔액 부족: ${when (s.washerLow) { true -> if (s.demoWasherLow) "있음 (시연 신호)" else "있음"; false -> "없음"; null -> "확인 불가 (에뮬레이터 미지원)" }}")
        Spacer(Modifier.height(12.dp))

        Text("[시연용] 주행 중 화면", fontWeight = FontWeight.Bold)
        ToggleRow("주행 중 화면 제한 풀기 (테스트용, P에서 미리 켜기)", s.demoUnrestricted) { v ->
            fake.update { it.copy(demoUnrestricted = v) }
        }
        Spacer(Modifier.height(8.dp))
        Text("[시연용] 가상 내비 (X1·X2)", fontWeight = FontWeight.Bold)
        ToggleRow("집으로 주행 중 (실제 내비 경로가 없을 때 사용)", s.fakeNaviHome) { v -> fake.update { it.copy(fakeNaviHome = v) } }
        if (s.fakeNaviHome) {
            Text("도착까지 ${s.fakeArriveMin}분 (주행 중이면 3초마다 1분씩 줄어요, 10분 이하면 도착 전 경고)")
            Slider(value = s.fakeArriveMin.toFloat().coerceIn(1f, 40f),
                onValueChange = { v -> fake.update { it.copy(fakeArriveMin = v.roundToInt()) } },
                valueRange = 1f..40f, steps = 38)
        }
        Spacer(Modifier.height(8.dp))

        Text("[시연용] Y1 보호 하한선", fontWeight = FontWeight.Bold)
        Text("먼저 8088 콘솔에서 기어 P + 시트 열선 + 공조 팬 3단 이상을 켜 주세요 (부하가 충전 여유보다 커야 12V가 줄어요)", fontSize = 12.sp)
        Row {
            OutlinedButton(onClick = { onY1Minutes(29.0) }, modifier = Modifier.padding(end = 6.dp)) { Text("29분 전") }
            OutlinedButton(onClick = { onY1Minutes(9.0) }, modifier = Modifier.padding(end = 6.dp)) { Text("9분 전") }
            OutlinedButton(onClick = { onY1Minutes(2.0) }, modifier = Modifier.padding(end = 6.dp)) { Text("2분 전") }
            OutlinedButton(onClick = { onY1Minutes(-0.5) }) { Text("도달") }
        }
        Text("[시연용] Y2 차박·대기 모드", fontWeight = FontWeight.Bold)
        ToggleRow("내연기관으로 계산 (전기차 에뮬레이터에서 T3 시연용)", s.demoForceIce) { v ->
            fake.update { it.copy(demoForceIce = v) }
        }
        OutlinedButton(onClick = onY2Suggest) { Text("대기 모드 제안 바로 띄우기 (5분 기다리지 않기)") }
        Text("[시연용] Y3 주차 영수증 · 주간 리포트", fontWeight = FontWeight.Bold)
        Text("T5: 설정에서 사용 연수 0년·주차녹화 ON(350mA), 날씨 자동 OFF·외기 −12°C로 맞춘 뒤 누르기", fontSize = 12.sp)
        // 패널 폭이 좁아서 한 줄에 3개를 넣으면 오른쪽 버튼이 화면 밖으로 밀림 → 2줄로
        Row {
            OutlinedButton(onClick = onY3Receipt, modifier = Modifier.padding(end = 6.dp)) { Text("어젯밤 14시간 주차") }
            OutlinedButton(onClick = onY3Week) { Text("최근 7일 기록 채우기") }
        }
        OutlinedButton(onClick = onY3Clear) { Text("주차 기록 지우기") }
        Text("[시연용] Y4 장소 프로필", fontWeight = FontWeight.Bold)
        Text("T6: 날씨 자동 OFF·외기 −12°C로 맞춘 뒤 ① 프로필 만들기 → ②/③ 번갈아 누르기", fontSize = 12.sp)
        OutlinedButton(onClick = onY4Demo) { Text("① 집·회사 프로필 만들기 (12V 자동 맞춤)") }
        Row {
            OutlinedButton(onClick = { onY4Park(901) }, modifier = Modifier.padding(end = 6.dp)) { Text("② 집(지하)에 주차") }
            OutlinedButton(onClick = { onY4Park(902) }) { Text("③ 회사(지상)에 주차") }
        }
        Text("[시연용] Z3 방전 대처 가이드 (에뮬레이터 VIN이 비어 있어서 가짜 VIN으로 시연)", fontWeight = FontWeight.Bold)
        Row {
            OutlinedButton(onClick = { onZ3Vin("DEMO-HEV") }, modifier = Modifier.padding(end = 6.dp)) { Text("하이브리드") }
            OutlinedButton(onClick = { onZ3Vin("DEMO-EV") }, modifier = Modifier.padding(end = 6.dp)) { Text("전기차") }
            OutlinedButton(onClick = { onZ3Vin("DEMO-XXXX") }) { Text("미등록") }
        }
        OutlinedButton(onClick = { onZ3Vin(null) }) { Text("가짜 VIN 해제 (실제 값 사용)") }
        Text("[시연용] Z4 12V 관리 점수", fontWeight = FontWeight.Bold)
        Row {
            OutlinedButton(onClick = onZ4Demo, modifier = Modifier.padding(end = 6.dp)) { Text("가짜 1주 데이터 (T10)") }
            OutlinedButton(onClick = onZ4Clear) { Text("점수 기록 지우기") }
        }
        Text("[시연용] Z2 겨울 출발 브리핑", fontWeight = FontWeight.Bold)
        Text("먼저 날씨 자동을 끄고 외기온도를 5°C 이하로 맞춰 주세요", fontSize = 12.sp)
        Text("에뮬레이터는 타이어 공기압·워셔액 값을 지원하지 않아서(로그: not supported) 아래 가짜 신호로 시연해요. 실제 차에서는 SDK 값을 그대로 써요", fontSize = 12.sp)
        ToggleRow("[시연] 타이어 공기압 부족 신호", s.demoTireLow) { v -> fake.update { it.copy(demoTireLow = v) } }
        ToggleRow("[시연] 워셔액 부족 신호", s.demoWasherLow) { v -> fake.update { it.copy(demoWasherLow = v) } }
        Row {
            OutlinedButton(onClick = { onZ2Brief(8.0) }, modifier = Modifier.padding(end = 6.dp)) { Text("주차 8시간 후 시동") }
            OutlinedButton(onClick = { onZ2Brief(5.0) }) { Text("주차 5시간 (안 뜨는지 확인)") }
        }
        Text("[시연용] Y5 주차 절전", fontWeight = FontWeight.Bold)
        Button(onClick = onY5Sleep) { Text("지금 절전 (2분 기다리지 않기)") }
        Spacer(Modifier.height(8.dp))

        Text("[시연용] 버튼", fontWeight = FontWeight.Bold)
        Button(onClick = onSimPark) { Text("주차 12시간 경과") }
        Row {
            Button(onClick = onDemoHistory, modifier = Modifier.padding(end = 8.dp)) { Text("30일 위험 기록 예시") }
            OutlinedButton(onClick = onClearHistory) { Text("위험 기록 초기화") }
        }
        Button(onClick = onDemoTrend) { Text("7일 12V 추이 예시") }
        Button(onClick = onDemoDepart) { Text("3주 출발 기록 예시") }
        Text("[시연용] 신뢰도 테스트: 마지막 실측 시점 바꾸기", fontWeight = FontWeight.Bold)
        Row {
            OutlinedButton(onClick = { onShiftReset(1) }, modifier = Modifier.padding(end = 8.dp)) { Text("1일 전") }
            OutlinedButton(onClick = { onShiftReset(6) }, modifier = Modifier.padding(end = 8.dp)) { Text("6일 전") }
            OutlinedButton(onClick = { onShiftReset(13) }) { Text("13일 전") }
        }
        Text("[시연용] W1 자가 보정 (주차 소모 보정계수 ×${"%.2f".format(s.drainK)})", fontWeight = FontWeight.Bold)
        Button(onClick = onW1Success) { Text("위험 예측한 밤 → 다음 날 정상 출발") }
        Row {
            Button(onClick = onW1Ask, modifier = Modifier.padding(end = 8.dp)) { Text("어제 시동 질문 띄우기") }
            OutlinedButton(onClick = onW1Reset) { Text("보정 초기화") }
        }
        Text("[시연용] G3 예측→실측 폐루프", fontWeight = FontWeight.Bold)
        Button(onClick = onDemoMeasure) { Text("실측이 장부보다 8% 낮게 나온 경우") }
        Text("[시연용] X6 음성 명령 (마이크 대신 글자로 보내기)", fontWeight = FontWeight.Bold)
        Row {
            OutlinedButton(onClick = { onVoiceText("내일 아침 시동 괜찮아?") }, modifier = Modifier.padding(end = 6.dp)) { Text("시동 괜찮아?") }
            OutlinedButton(onClick = { onVoiceText("열선 다 꺼줘") }, modifier = Modifier.padding(end = 6.dp)) { Text("열선 다 꺼줘") }
            OutlinedButton(onClick = { onVoiceText("왜 위험해?") }) { Text("왜 위험해?") }
        }
        Row {
            OutlinedButton(onClick = { onVoiceText("어떻게 해?") }, modifier = Modifier.padding(end = 6.dp)) { Text("어떻게 해?") }
            OutlinedButton(onClick = { onVoiceText("창문 닫아줘") }, modifier = Modifier.padding(end = 6.dp)) { Text("창문 닫아줘") }
            OutlinedButton(onClick = { onVoiceText("내일 8시에 출발해") }) { Text("내일 8시 출발") }
        }
        Row {
            OutlinedButton(onClick = { onVoiceText("블랙박스 전압 12.3볼트") }, modifier = Modifier.padding(end = 6.dp)) { Text("전압 12.3V") }
            OutlinedButton(onClick = { onVoiceText("블랙박스 껐어") }, modifier = Modifier.padding(end = 6.dp)) { Text("블랙박스 껐어") }
            OutlinedButton(onClick = { onVoiceText("뭐 할 수 있어?") }) { Text("도움말") }
        }
    }
}

@Composable
fun SdkOrToggle(
    label: String,
    key: String,
    value: Boolean,
    s: VehicleSnapshot,
    onText: String = "켜짐",
    offText: String = "꺼짐",
    onChange: (Boolean) -> Unit
) {
    if (key in s.sdkFields) {
        Text("[SDK 실제값] $label: ${if (value) onText else offText}", fontWeight = FontWeight.Bold)
    } else {
        ToggleRow("[가상] $label", value, onChange)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}