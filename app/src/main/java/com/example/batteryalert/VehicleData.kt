package com.example.batteryalert

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.listener.ResultListener
import android.os.Handler
import android.os.Looper

data class VehicleSnapshot(
    // [SDK] 운전석 문 (하차 감지)
    val driverDoorOpen: Boolean = false,
    // [SDK] 기어
    val driveMode: String = "D",
    // [SDK] 이번 주행 거리 / 시간
    val tripKm: Double = 5.0,
    val tripMinutes: Int? = null,
    // [SDK] 트렁크 / 창문 / 라이트
    val trunkOpen: Boolean = false,
    val windowOpen: Boolean = false,
    val headlightOn: Boolean = false,
    // [SDK] 좌석 / 미러 / 공조 / 유리
    val seatHeaterOn: Boolean = true,
    // [SDK] X4 좌석 점유·안전벨트 (센서 없으면 기존 F3로 대체)
    val driverSeatOccupied: Boolean = true,
    val driverBelted: Boolean = true,
    val passengerOccupied: Boolean = false,
    val passengerHeaterOn: Boolean = false,
    val steeringHeaterOn: Boolean = false,   // 운전대 열선
    val powerDownWarning: Boolean = false,   // 계기판 출력 제한 경고등
    val mirrorHeaterOn: Boolean = true,
    val rearDefrostOn: Boolean = false,
    val hvacFanLevel: Int = 3,
    val fanMax: Int = 8,
    // [SDK] 전기차
    val isEv: Boolean = false,
    val hvSoc: Int = 60,
    val evSaverEnabled: Boolean = false,  // 전기차 12V 자동충전 반영 (기본 OFF = 보수적)
    // [SDK 없음] 시뮬레이션 / 외부 API / 사용자 설정
    val autoWeather: Boolean = true,
    val outsideTempC: Double = -12.0,
    val battery12vSoc: Int = 50,
    val dashcamParkingOn: Boolean = true,
    val batteryAgeYears: Int = 3,         // 12V 배터리 사용 연수
    val batteryAh: Int = 60,              // [설정] 12V 배터리 용량 (Ah)
    val dashcamMa: Int = 350,             // [설정] 블랙박스 주차녹화 소모 전류 (mA)
    val undergroundParking: Boolean = false, // [설정] 지하·실내 주차 (+5°C)
    val voiceOn: Boolean = true,          // [설정] 음성 안내
    val shareConsent: Boolean = false,    // [설정] X3 익명 위험 데이터 제공 동의
    val autoProtect: Boolean = true,      // [설정] Y1 자동 보호 (하한선 도달 시 P단에서 공조·열선 자동 OFF)
    val protectMargin: Int = PROTECT_MARGIN_DEFAULT,   // [설정] Y1 하한선 여유 10/15/20%p
    val dashcamCutoffSet: Boolean = false,  // [설정] Z4 블랙박스 저전압 차단을 권장값으로 맞췄는지 (사용자 확인)
    // W1 자가 보정계수: 주차 소모 전류 = 기본 계산 × drainK
    val drainK: Double = 1.0,
    // X5 차대번호(VIN)로 읽은 정보 (VIN 자체는 저장하지 않음)
    val carMaker: String? = null,
    val carModelYear: Int? = null,
    val vinStatus: String = "",
    val vinMasked: String? = null,          // Z5: 차대번호 뒤 6자리 가린 값 (메모리에만, 저장 안 함)
    val vinKey: String? = null,             // Z3: 차종 찾기용 VIN 앞 8자리 (메모리에만)
    val demoVin: String? = null,            // [시연용] T9 가짜 VIN (에뮬레이터 VIN이 null이라서)
    // [사용자 입력] 다음 출발 시각 (autoDepart 켜져 있고 학습되면 학습값 사용)
    val autoDepart: Boolean = true,
    val departDaysLater: Int = 1,
    val departHour: Int = 7,
    val departMinute: Int = 30,
    // [시연용] 가상 내비: 내비 경로가 없을 때 "집으로 가는 중"을 흉내 냄 (X1·X2 시연)
    val fakeNaviHome: Boolean = false,
    val fakeArriveMin: Int = 15,
    val demoUnrestricted: Boolean = false,   // [시연용] 주행 중 화면 제한 풀기
    val demoForceIce: Boolean = false,       // [시연용] 전기차여도 내연기관으로 계산 (Y2 T3 시연)
    // Y2 차박·대기 모드
    val engineOffWait: Boolean = false,      // 내연기관 시동 끄고 대기 중 (발전기 충전 없음) — 앱이 정함
    val seatHeatA: Double = 8.0,             // 시트 열선 전류 (what-if 계산용, 기본 최대 8A)
    // Z2 겨울 점검
    val tireLow: Boolean? = null,            // 타이어 공기압 부족 (null = 이 차에서 확인 불가)
    val washerLow: Boolean? = null,          // 워셔액 부족 경고
    // [시연용] 에뮬레이터가 타이어 공기압·워셔액 값을 지원하지 않아서 가짜 경고 신호로 T8 시연
    val demoTireLow: Boolean = false,
    val demoWasherLow: Boolean = false,
    // SDK 상태
    val sdkStatus: String = "가짜 데이터 모드",
    val sdkFields: Set<String> = emptySet()
)

interface VehicleSource {
    val state: StateFlow<VehicleSnapshot>
}

// 가짜 데이터 공급기 (가상 패널로 값 변경)
open class FakeVehicleSource : VehicleSource {
    private val _state = MutableStateFlow(VehicleSnapshot())
    override val state: StateFlow<VehicleSnapshot> = _state

    fun update(change: (VehicleSnapshot) -> VehicleSnapshot) {
        _state.update(change)
    }
}

// 성에 제거(디프로스터) 값이 에뮬레이터에서 반대로 동작함 (좌석 점유와 같은 현상, 8088 콘솔로 확인)
// 실차에서 정상으로 확인되면 false로 바꾸면 됨
const val DEFROST_INVERTED = false   // 뒷유리 영역만 골라 읽게 된 뒤로 반대 처리 불필요 (11/8 확인)
val DEFROST_OFF_VALUE: Boolean get() = DEFROST_INVERTED    // "끄기" 때 보낼 값 (반대면 true를 보내야 꺼짐)

// Pleos SDK 공급기
class PleosVehicleSource(context: Context) : FakeVehicleSource() {

    private val TAG = "BatteryAlert"
    private val vehicle = Vehicle(context.applicationContext)
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val trunkArea = DoorArea(DoorValue.REAR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 차가 알려준 영역
    @Volatile private var fanArea: SeatArea? = null
    @Volatile private var hvacPowerAreas: List<SeatArea> = emptyList()   // 공조 전원 (팬이랑 영역이 다름)
    @Volatile private var heaterSeatAreas: List<SeatArea> = emptyList()
    @Volatile private var mirrorAreas: List<MirrorArea> = emptyList()   // 미러 영역 전부 (왼쪽·오른쪽 등)
    @Volatile private var defrostAreas: List<WindowArea> = emptyList()
    @Volatile private var windowAreas: List<WindowArea> = emptyList()
    @Volatile private var occAreas: List<SeatArea> = emptyList()       // X4 좌석 점유 센서가 있는 좌석
    @Volatile private var beltAreas: List<SeatArea> = emptyList()      // X4 안전벨트 센서가 있는 좌석
    @Volatile private var fanMax: Int = 8

    // 읽어온 값 임시 보관
    @Volatile private var lowBeamOn: Boolean? = null
    @Volatile private var highBeamOn: Boolean? = null
    @Volatile private var hvacPowerOn: Boolean? = null
    @Volatile private var fanRaw: Int = 0
    private val hvacPowerStates = ConcurrentHashMap<String, Boolean>()
    private val seatHeatStates = ConcurrentHashMap<String, Int>()
    private val defrostStates = ConcurrentHashMap<String, Boolean>()
    private val mirrorStates = ConcurrentHashMap<String, Int>()
    // Z2 타이어: 저압 기준값(kPa) / 실제 공기압(kPa, 별도 권한 필요)
    @Volatile private var wheelAreas: List<WheelArea> = emptyList()
    private val tireThreshold = ConcurrentHashMap<String, Float>()
    private val tirePressure = ConcurrentHashMap<String, Float>()
    private val rawLog = ConcurrentHashMap<String, String>()   // 진단 로그: 값이 바뀔 때만 찍기
    private val windowStates = ConcurrentHashMap<String, Boolean>()
    private val occStates = ConcurrentHashMap<String, Boolean>()   // X4 좌석별 점유 (로그용)
    private val failedOnce = ConcurrentHashMap.newKeySet<String>()

    // 주행 측정 (기어 기준)
    @Volatile private var lastMode: String? = null
    @Volatile private var tripStartMillis: Long? = null
    @Volatile private var tripStartOdo: Double? = null
    @Volatile private var odoKm: Double? = null
    @Volatile private var officialTrip = false   // 차가 공식 주행거리/시간을 주면 true

    // ───── 리스너: 운전석 문 ─────
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            Log.d(TAG, "문 상태 변경 감지: $pos")
            update { it.copy(driverDoorOpen = (pos ?: 0) > 0) }
        }
        override fun onFailed(e: Exception) {
            Log.e(TAG, "문 구독 실패", e)
            update { it.copy(sdkStatus = "문 구독 실패: ${e.message}") }
        }
    }

    // ───── 리스너: 기어 ─────
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            Log.d(TAG, "기어 변경 감지: $gear")
            gearToMode(gear)?.let { onGear(it) }
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "기어 구독 실패: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        // 1) SDK 시작 + 운전석 문
        try {
            vehicle.initialize()
            Log.d(TAG, "SDK 초기화 성공")
            update { it.copy(sdkStatus = "SDK 초기화 성공") }

            val door = vehicle.getDoor()
            door.getCurrentDoorPosition(
                area = driverDoor,
                onComplete = { pos ->
                    Log.d(TAG, "처음 읽은 운전석 문 값: $pos")
                    update { it.copy(driverDoorOpen = (pos ?: 0) > 0,
                        sdkStatus = "SDK 연결됨 (운전석 문 = $pos)") }
                },
                onFailed = { e ->
                    Log.e(TAG, "문 읽기 실패", e)
                    update { it.copy(sdkStatus = "문 읽기 실패: ${e.message}") }
                }
            )
            door.registerDoorPosition(driverDoor, doorListener)
        } catch (e: Exception) {
            Log.e(TAG, "SDK 초기화 실패", e)
            update { it.copy(sdkStatus = "SDK 초기화 실패: ${e.message}") }
            return
        }

        // 2) 기어
        safe("gear") {
            vehicle.getGear().getCurrentGearSelectionState(
                onComplete = { g ->
                    Log.d(TAG, "처음 읽은 기어: $g")
                    gearToMode(g)?.let { onGear(it) }
                },
                onFailed = { e -> logFailOnce("기어", e) }
            )
            vehicle.getGear().registerGearSelection(gearListener)
        }

        // X5) 차대번호로 제조사·연식 읽기 (VIN 문자열은 저장하지 않음)
        safe("vin") {
            vehicle.getCarInfo().getVINInfo(
                onComplete = { vin ->
                    val info = decodeVin(vin)
                    val v = vin?.trim()?.uppercase()
                    Log.d(TAG, "차대번호 해석: 제조사=${info?.first}, 연식=${info?.second}")
                    update {
                        it.copy(carMaker = info?.first, carModelYear = info?.second,
                            vinMasked = v?.takeIf { s -> s.length == 17 }?.let { s -> s.take(11) + "******" },
                            vinKey = v?.take(8),
                            vinStatus = if (info == null) "차대번호를 해석하지 못했어요" else "차대번호로 확인")
                    }
                },
                onFailed = { e ->
                    logFailOnce("차대번호", e)
                    update { it.copy(vinStatus = "차대번호를 받지 못했어요") }
                }
            )
        }

        // 3) 지원 영역 확인 → 4) 2초마다 나머지 값 읽기
        findAreas()
        scope.launch {
            delay(1_500)
            while (isActive) {
                // Y5: 주차 절전 중이면 차량 읽기를 완전히 멈추고, 다시 켜질 때까지 기다림 (CPU 0)
                if (paused.value) { paused.first { !it }; continue }
                pollOnce()
                pollSteeringAndSafety()
                updateTrip()
                delay(2_000)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        scope.cancel()
        try { vehicle.getGear().unregisterGearSelection(gearListener) } catch (_: Exception) { }
        try {
            vehicle.getDoor().unregisterDoorPosition(driverDoor, doorListener)
            vehicle.release()
            Log.d(TAG, "SDK 정리 완료")
        } catch (_: Exception) { }
    }

    // ───── Y5 앱 자체 소모 0: 주차 절전 ─────
    // 2초마다 읽던 차량 값을 멈춤. 문·기어는 차가 알려주는 신호(리스너)라서 멈춰도 계속 받음 → 그때 다시 시작
    private val paused = MutableStateFlow(false)

    fun pausePolling(reason: String) {
        if (paused.value) return
        paused.value = true
        Log.d(TAG, "Y5: 차량 읽기 중지 ($reason) — 문·기어 신호가 오면 다시 시작")
    }

    fun resumePolling(reason: String) {
        if (!paused.value) return
        paused.value = false
        Log.d(TAG, "Y5: 차량 읽기 다시 시작 ($reason)")
    }

    // ───── 실제 차 제어: "모두 조치" ─────
    fun applyAllFixes() {
        val s = state.value
        if ("gear" in s.sdkFields && s.driveMode != "P") {
            Log.w(TAG, "P단이 아니라서 차량 제어를 하지 않음")
            return
        }
        Log.d(TAG, "모두 조치 명령 전송 시작")

        for (pa in hvacPowerAreas) safe("set-power") {
            vehicle.getHVAC().setHvacPower(pa, false,
                onFailed = { e -> Log.w(TAG, "공조 끄기 실패: ${e.message}") })
        }
        for (sa in heaterSeatAreas) safe("set-seat") {
            vehicle.getSeat().setHvacSeatTemperature(sa, 0,
                onFailed = { e -> Log.w(TAG, "시트 열선 끄기 실패: ${e.message}") })
        }
        for (wa in defrostAreas) safe("set-defrost") {
            vehicle.getHVAC().setHvacDefroster(wa, DEFROST_OFF_VALUE,
                onFailed = { e -> Log.w(TAG, "뒷유리 열선 끄기 실패: ${e.message}") })
        }
        mirrorOff()
        for (wa in windowAreas) safe("set-window") {
            vehicle.getWindow().setWindowPosition(wa, 0,
                onFailed = { e -> Log.w(TAG, "창문 닫기 실패: ${e.message}") })
        }
        safe("set-trunk") {
            vehicle.getDoor().setDoorPosition(trunkArea, 0,
                onFailed = { e -> Log.w(TAG, "트렁크 닫기 실패: ${e.message}") })
        }
        try {
            vehicle.getSteeringWheel().setHvacSteeringWheelHeat(0, {}, { e ->
                Log.w("BatteryAlert", "운전대 열선 끄기 실패: ${e.message}")
            })
        } catch (e: Exception) { Log.w("BatteryAlert", "운전대 열선 끄기 예외: ${e.message}") }
        // 블랙박스는 차 기능이 아니라서 앱이 끌 수 없음 → 설정값은 그대로 둠
        Log.d(TAG, "모두 조치 명령 전송 완료 (2초 뒤 실제 상태로 갱신)")
    }

    // ───── G1: 음성으로 하나씩 끄기 ("시트 열선 꺼줘", "창문 닫아줘" 등) ─────
    private fun seatOff() = heaterSeatAreas.forEach { sa -> safe("set-seat") {
        vehicle.getSeat().setHvacSeatTemperature(sa, 0, onFailed = { e -> Log.w(TAG, "시트 열선 끄기 실패: ${e.message}") }) } }
    private fun defrostOff() = defrostAreas.forEach { wa -> safe("set-defrost") {
        vehicle.getHVAC().setHvacDefroster(wa, DEFROST_OFF_VALUE, onFailed = { e -> Log.w(TAG, "뒷유리 열선 끄기 실패: ${e.message}") }) } }
    private fun mirrorOff() = mirrorAreas.forEach { ma -> safe("set-mirror") {
        vehicle.getSideMirror().setHvacSideMirrorHeat(ma, 0, onFailed = { e -> Log.w(TAG, "미러 열선 끄기 실패: ${e.message}") }) } }
    private fun steerOff() {
        try { vehicle.getSteeringWheel().setHvacSteeringWheelHeat(0, {}, { e -> Log.w(TAG, "운전대 열선 끄기 실패: ${e.message}") }) }
        catch (e: Exception) { Log.w(TAG, "운전대 열선 끄기 예외: ${e.message}") }
    }
    private fun hvacOff() {
        Log.d(TAG, "공조 끄기 명령: 전원 영역 ${hvacPowerAreas.size}개, 지금 전원=$hvacPowerOn, 팬=$fanRaw")
        if (hvacPowerAreas.isEmpty()) Log.w(TAG, "공조 끄기 불가: 공조 전원 영역을 아직 못 받음")
        hvacPowerAreas.forEach { pa -> safe("set-power") {
            vehicle.getHVAC().setHvacPower(pa, false, onFailed = { e -> Log.w(TAG, "공조 끄기 실패: ${e.message}") }) } }
    }
    private fun windowsClose() = windowAreas.forEach { wa -> safe("set-window") {
        vehicle.getWindow().setWindowPosition(wa, 0, onFailed = { e -> Log.w(TAG, "창문 닫기 실패: ${e.message}") }) } }
    private fun trunkClose() { safe("set-trunk") {
        vehicle.getDoor().setDoorPosition(trunkArea, 0, onFailed = { e -> Log.w(TAG, "트렁크 닫기 실패: ${e.message}") }) } }

    // target: all / heat / seat / steer / defrost / mirror / hvac / window / trunk
    fun turnOff(target: String) {
        Log.d(TAG, "음성 제어: $target 끄기")
        when (target) {
            "all" -> applyAllFixes()
            "heat" -> applyHeatersOff()
            "seat" -> seatOff()
            "steer" -> steerOff()
            "defrost" -> defrostOff()
            "mirror" -> mirrorOff()
            "hvac" -> hvacOff()
            "window" -> windowsClose()
            "trunk" -> trunkClose()
        }
    }

    // ───── X6: 주행 중 "열선 다 꺼줘" → 열선만 끄기 (공조·창문·트렁크는 그대로) ─────
    fun applyHeatersOff() {
        Log.d(TAG, "열선만 끄기 명령 전송")
        for (sa in heaterSeatAreas) safe("set-seat") {
            vehicle.getSeat().setHvacSeatTemperature(sa, 0,
                onFailed = { e -> Log.w(TAG, "시트 열선 끄기 실패: ${e.message}") })
        }
        for (wa in defrostAreas) safe("set-defrost") {
            vehicle.getHVAC().setHvacDefroster(wa, DEFROST_OFF_VALUE,
                onFailed = { e -> Log.w(TAG, "뒷유리 열선 끄기 실패: ${e.message}") })
        }
        mirrorOff()
        try {
            vehicle.getSteeringWheel().setHvacSteeringWheelHeat(0, {}, { e ->
                Log.w(TAG, "운전대 열선 끄기 실패: ${e.message}")
            })
        } catch (e: Exception) { Log.w(TAG, "운전대 열선 끄기 예외: ${e.message}") }
    }

    // ───── 운전대 열선 + 출력 제한 경고 ─────
    private var steerFailLogged = false
    private var powerDownFailLogged = false

    private fun pollSteeringAndSafety() {
        try {
            vehicle.getSteeringWheel().getHvacSteeringWheelHeatState(
                { level -> if (level != null) sdk("steer") { it.copy(steeringHeaterOn = level > 0) } },
                { e ->
                    if (!steerFailLogged) {
                        steerFailLogged = true
                        Log.w("BatteryAlert", "운전대 열선 읽기 실패: ${e.message}")
                    }
                }
            )
        } catch (e: Exception) {
            if (!steerFailLogged) { steerFailLogged = true; Log.w("BatteryAlert", "운전대 열선 예외: ${e.message}") }
        }
        try {
            vehicle.getSafety().hasPowerDownWarningIndicator(
                { on -> if (on != null) sdk("powerDown") { it.copy(powerDownWarning = on) } },
                { e ->
                    if (!powerDownFailLogged) {
                        powerDownFailLogged = true
                        Log.w("BatteryAlert", "출력 제한 경고 읽기 실패: ${e.message}")
                    }
                }
            )
        } catch (e: Exception) {
            if (!powerDownFailLogged) { powerDownFailLogged = true; Log.w("BatteryAlert", "출력 제한 경고 예외: ${e.message}") }
        }
        // Z2 워셔액 부족 경고
        safe("washer") {
            vehicle.getSafety().hasLowWasherFluidWarning(
                onComplete = { v -> if (v != null) {
                    logRaw("washer", "워셔액 부족 경고 원본값: $v")
                    sdk("washer") { it.copy(washerLow = v) }
                } },
                onFailed = { e -> logFailOnce("워셔액 경고", e) }
            )
        }
        // Z2 타이어: SDK 이름은 getLowTirePressure지만 실제로는 "저압 기준값"을 줌 (Android CRITICALLY_LOW_TIRE_PRESSURE)
        // → 실제 공기압(getTirePressure, 별도 권한)과 비교해서 판단. 실제 공기압을 못 읽으면 "확인 불가"
        for (wa in wheelAreas) {
            val name = areaName(wa)
            safe("tire-low") {
                vehicle.getTire().getLowTirePressure(wa,
                    onComplete = { v -> if (v != null) { tireThreshold[name] = v; applyTire() } },
                    onFailed = { e -> logFailOnce("타이어 저압 기준", e) })
            }
            safe("tire-pressure") {
                vehicle.getTire().getTirePressure(wa,
                    onComplete = { v -> if (v != null) { tirePressure[name] = v; applyTire() } },
                    onFailed = { e -> logFailOnce("타이어 공기압 (PRIVATE_CAR_SAFETY 권한 필요)", e) })
            }
        }
    }

    private fun applyTire() {
        logRaw("tire", "타이어 원본값: 저압 기준=$tireThreshold, 공기압=$tirePressure")
        val low: Boolean? = when {
            tirePressure.isNotEmpty() && tireThreshold.isNotEmpty() ->
                tirePressure.any { (k, p) -> tireThreshold[k]?.let { th -> p <= th } ?: false }
            // 기준값 대신 0/1 경고 신호로 주는 경우 대비
            tireThreshold.isNotEmpty() && tireThreshold.values.all { it <= 1f } -> tireThreshold.values.any { it >= 0.5f }
            else -> null
        }
        sdk("tire") { it.copy(tireLow = low) }
    }

    // ───── 기어로 주행 시작/종료 판단 ─────
    private fun onGear(mode: String) {
        val prev = lastMode
        lastMode = mode
        sdk("gear") { it.copy(driveMode = mode) }

        if (mode != "P" && (prev == null || prev == "P")) {          // 출발
            tripStartMillis = System.currentTimeMillis()
            tripStartOdo = odoKm
            Log.d(TAG, "주행 시작 (기어 $mode, 누적거리 $odoKm km)")
        }
        if (mode == "P" && prev != null && prev != "P") {            // 도착
            updateTrip()
            tripStartMillis = null                                     // 값 고정
            Log.d(TAG, "주행 종료")
        }
    }

    private fun updateTrip() {
        if (officialTrip) return
        val start = tripStartMillis ?: return
        val minutes = ((System.currentTimeMillis() - start) / 60_000).toInt()
        sdk("tripTime") { it.copy(tripMinutes = minutes) }
        val o = odoKm
        val so = tripStartOdo
        if (o != null && so != null) sdk("trip") { it.copy(tripKm = (o - so).coerceAtLeast(0.0)) }
    }

    // ───── 차한테 "어느 영역 지원해?" 물어보기 ─────
    private fun findAreas() {
        safe("fan-area") {
            vehicle.getHVAC().checkHvacFanSpeedCapabilities(
                onComplete = { _: CapabilityConfig, areas: Map<SeatArea, AreaBounds<Int>> ->
                    val fa = areas.keys.firstOrNull()
                    fanArea = fa
                    if (fa != null) fanMax = areas[fa]?.maxValue ?: 8
                    Log.d(TAG, "공조 팬 영역 ${areas.size}개, 팬 최대=${fanMax}단")
                },
                onFailed = { e -> logFailOnce("팬 영역 확인", e) }
            )
        }
        safe("power-area") {
            vehicle.getHVAC().checkHvacPowerCapabilities(
                onComplete = { _: CapabilityConfig, areas: List<SeatArea> ->
                    hvacPowerAreas = areas
                    Log.d(TAG, "공조 전원 영역 ${areas.size}개")
                },
                onFailed = { e -> logFailOnce("공조 전원 영역 확인", e) }
            )
        }
        safe("seat-heat-area") {
            vehicle.getSeat().checkSeatTemperatureCapabilities(
                onComplete = { _: CapabilityConfig, areas: Map<SeatArea, AreaBounds<Int>> ->
                    heaterSeatAreas = areas.keys.toList()
                    Log.d(TAG, "시트 열선 좌석 ${areas.size}개: ${areas.keys.joinToString { seatName(it) }}")
                },
                onFailed = { e -> logFailOnce("시트 열선 영역 확인", e) }
            )
        }
        safe("mirror-area") {
            vehicle.getSideMirror().checkHvacSideMirrorHeatCapabilities(
                onComplete = { _: CapabilityConfig, areas: Map<MirrorArea, AreaBounds<Int>> ->
                    mirrorAreas = areas.keys.toList()
                    Log.d(TAG, "미러 영역 ${areas.size}개: ${mirrorAreas.joinToString { areaName(it) }}")
                },
                onFailed = { e -> logFailOnce("미러 영역 확인", e) }
            )
        }
        safe("tire-area") {
            vehicle.getTire().checkLowTirePressureCapabilities(
                onComplete = { _: CapabilityConfig, areas: List<WheelArea> ->
                    wheelAreas = areas
                    Log.d(TAG, "타이어 영역 ${areas.size}개: ${areas.joinToString { areaName(it) }}")
                },
                onFailed = { e -> logFailOnce("타이어 영역 확인", e) }
            )
        }
        safe("defrost-area") {
            vehicle.getHVAC().checkHvacDefrosterCapabilities(
                onComplete = { _: CapabilityConfig, areas: List<WindowArea> ->
                    Log.d(TAG, "열선 유리 영역 전체 ${areas.size}개: ${areas.joinToString { areaName(it) }}")
                    defrostAreas = areas.filter { areaName(it).uppercase().contains("REAR") }
                        .ifEmpty { areas }
                    Log.d(TAG, "뒷유리로 쓰는 영역 ${defrostAreas.size}개: ${defrostAreas.joinToString { areaName(it) }}")
                },
                onFailed = { e -> logFailOnce("유리 열선 영역 확인", e) }
            )
        }
        safe("occ-area") {
            vehicle.getSeat().checkSeatOccupancyCapabilities(
                onComplete = { _: CapabilityConfig, areas: List<SeatArea> ->
                    occAreas = areas
                    Log.d(TAG, "좌석 점유 센서 ${areas.size}개: ${areas.joinToString { seatName(it) }}")
                },
                onFailed = { e -> logFailOnce("좌석 점유 영역 확인", e) }
            )
        }
        safe("belt-area") {
            vehicle.getSeat().checkSeatBeltBuckledCapabilities(
                onComplete = { _: CapabilityConfig, areas: List<SeatArea> ->
                    beltAreas = areas
                    Log.d(TAG, "안전벨트 센서 ${areas.size}개: ${areas.joinToString { seatName(it) }}")
                },
                onFailed = { e -> logFailOnce("안전벨트 영역 확인", e) }
            )
        }
        safe("window-area") {
            vehicle.getWindow().checkWindowPositionCapabilities(
                onComplete = { _: CapabilityConfig, areas: Map<WindowArea, AreaBounds<Int>> ->
                    windowAreas = areas.keys.toList()
                    Log.d(TAG, "창문 ${areas.size}개")
                },
                onFailed = { e -> logFailOnce("창문 영역 확인", e) }
            )
        }
    }

    // ───── 2초마다 읽기 ─────
    private fun pollOnce() {
        // 전조등
        val light = vehicle.getLight()
        safe("lowbeam") {
            light.getCurrentLowBeamLightState(
                onComplete = { st -> onOff(st)?.let { lowBeamOn = it; applyHeadlight() } },
                onFailed = { e -> logFailOnce("하향등", e) }
            )
        }
        safe("highbeam") {
            light.getCurrentHighBeamLightState(
                onComplete = { st -> onOff(st)?.let { highBeamOn = it; applyHeadlight() } },
                onFailed = { e -> logFailOnce("상향등", e) }
            )
        }

        // 공조 팬
        val fa = fanArea
        if (fa != null) {
            safe("fan") {
                vehicle.getHVAC().getHvacFanSpeed(fa,
                    onComplete = { v -> if (v != null) { fanRaw = v; applyFan() } },
                    onFailed = { e -> logFailOnce("공조 팬", e) }
                )
            }
        }

        // 공조 전원 (영역별, 하나라도 켜져 있으면 켜짐)
        for (pa in hvacPowerAreas) {
            safe("hvac-power") {
                vehicle.getHVAC().getHvacPowerState(pa,
                    onComplete = { on ->
                        if (on != null) {
                            hvacPowerStates[pa.toString()] = on
                            hvacPowerOn = hvacPowerStates.values.any { b -> b }
                            applyFan()
                        }
                    },
                    onFailed = { e -> logFailOnce("공조 전원", e) }
                )
            }
        }

        // 시트 열선 (좌석별)
        for (sa in heaterSeatAreas) {
            safe("seat") {
                vehicle.getSeat().getHvacSeatTemperature(sa,
                    onComplete = { v ->
                        if (v != null) {
                            seatHeatStates[sa.toString()] = v
                            val passengerHeat = heaterSeatAreas.filter { a -> isPassengerSeat(a) }
                                .any { a -> (seatHeatStates[a.toString()] ?: 0) > 0 }
                            sdk("seat") { it.copy(seatHeaterOn = seatHeatStates.values.any { lv -> lv > 0 },
                                passengerHeaterOn = passengerHeat) }
                        }
                    },
                    onFailed = { e -> logFailOnce("시트 열선", e) }
                )
            }
        }

        // 미러 열선: 영역 전부 읽고 하나라도 켜져 있으면 켜짐
        for (ma in mirrorAreas) {
            safe("mirror") {
                vehicle.getSideMirror().getHvacSideMirrorHeatState(ma,
                    onComplete = { v ->
                        if (v != null) {
                            mirrorStates[areaName(ma)] = v
                            logRaw("mirror", "미러 열선 원본값: $mirrorStates")
                            sdk("mirror") { it.copy(mirrorHeaterOn = mirrorStates.values.any { lv -> lv > 0 }) }
                        }
                    },
                    onFailed = { e -> logFailOnce("미러 열선", e) }
                )
            }
        }

        // 뒷유리 열선
        for (wa in defrostAreas) {
            safe("defrost") {
                vehicle.getHVAC().getHvacDefrosterState(wa,
                    onComplete = { v ->
                        if (v != null) {
                            logRaw("defrost-" + areaName(wa), "뒷유리 열선 원본값: ${areaName(wa)} = $v (반대 처리=${DEFROST_INVERTED})")
                            defrostStates[areaName(wa)] = if (DEFROST_INVERTED) !v else v   // 에뮬레이터는 반대로 줌
                            sdk("defrost") { it.copy(rearDefrostOn = defrostStates.values.any { b -> b }) }
                        }
                    },
                    onFailed = { e -> logFailOnce("뒷유리 열선", e) }
                )
            }
        }

        // X4 좌석 점유 (운전석 / 조수석)
        for (oa in occAreas) {
            safe("occ") {
                vehicle.getSeat().getCurrentSeatOccupancy(oa,
                    onComplete = { v ->
                        if (v != null) {
                            val key = seatName(oa)
                            if (occStates[key] != v) {
                                occStates[key] = v
                                Log.d(TAG, "좌석 점유 읽음: $key = $v")
                            }
                            when {
                                // Pleos SDK는 true = 비어 있음으로 줌 (8088 콘솔로 확인) → 뒤집어서 저장
                                isDriverSeat(oa) -> sdk("occ") { it.copy(driverSeatOccupied = !v) }
                                isPassengerSeat(oa) -> sdk("occP") { it.copy(passengerOccupied = !v) }
                            }
                        }
                    },
                    onFailed = { e -> logFailOnce("좌석 점유", e) }
                )
            }
        }
        // X4 운전석 안전벨트
        for (ba in beltAreas) {
            if (!isDriverSeat(ba)) continue
            safe("belt") {
                vehicle.getSeat().getCurrentSeatBeltBuckledState(ba,
                    onComplete = { v -> if (v != null) sdk("belt") { it.copy(driverBelted = v) } },
                    onFailed = { e -> logFailOnce("안전벨트", e) }
                )
            }
        }

        // 트렁크
        safe("trunk") {
            vehicle.getDoor().getCurrentDoorPosition(
                area = trunkArea,
                onComplete = { pos -> if (pos != null) sdk("trunk") { it.copy(trunkOpen = pos > 0) } },
                onFailed = { e -> logFailOnce("트렁크", e) }
            )
        }

        // 창문
        for (wa in windowAreas) {
            safe("window") {
                vehicle.getWindow().getCurrentWindowPosition(wa,
                    onComplete = { pos ->
                        if (pos != null) {
                            windowStates[wa.toString()] = pos != 0
                            sdk("window") { it.copy(windowOpen = windowStates.values.any { o -> o }) }
                        }
                    },
                    onFailed = { e -> logFailOnce("창문", e) }
                )
            }
        }

        // 전기차
        safe("ev") {
            vehicle.getEvBattery().getEvBatteryStateOfCharge(
                onComplete = { v -> if (v != null) sdk("ev") { it.copy(isEv = true, hvSoc = v) } },
                onFailed = { e -> logFailOnce("고전압 배터리", e) }
            )
        }

        // 주행: 누적거리계(km) — 기어 기준 주행거리 계산용
        val odo = vehicle.getOdometer()
        safe("odo") {
            odo.getCurrentDistanceDriven(
                onComplete = { km -> if (km != null) odoKm = km.toDouble() },
                onFailed = { e -> logFailOnce("누적 주행거리", e) }
            )
        }
        // 주행: 공식 트립 값 (실제 차에서 지원되면 이걸 우선 사용)
        safe("trip-dist") {
            odo.getTripCurrentDrivingDistance(
                onComplete = { m ->
                    if (m != null) { officialTrip = true; sdk("trip") { it.copy(tripKm = m / 1000.0) } }
                },
                onFailed = { e -> logFailOnce("주행거리", e) }
            )
        }
        safe("trip-time") {
            odo.getTripCurrentDrivingTime(
                onComplete = { min ->
                    if (min != null) { officialTrip = true; sdk("tripTime") { it.copy(tripMinutes = min) } }
                },
                onFailed = { e -> logFailOnce("주행시간", e) }
            )
        }
    }

    // ───── 도우미 함수들 ─────
    private var lastFanLog = ""
    private fun applyFan() {
        val logLine = "팬 읽음: 단수=$fanRaw, 공조 전원=$hvacPowerOn (영역별 $hvacPowerStates)"
        if (logLine != lastFanLog) { lastFanLog = logLine; Log.d(TAG, logLine) }
        sdk("fan") { it.copy(hvacFanLevel = if (hvacPowerOn == false) 0 else fanRaw, fanMax = fanMax) }
    }

    private fun applyHeadlight() {
        sdk("light") { it.copy(headlightOn = lowBeamOn == true || highBeamOn == true) }
    }

    private fun sdk(key: String, change: (VehicleSnapshot) -> VehicleSnapshot) {
        if (key !in state.value.sdkFields) Log.d(TAG, "SDK 실제값 연결됨: $key")
        update { change(it).copy(sdkFields = it.sdkFields + key) }
    }

    // 좌석 구분: SeatArea 안의 좌석 값(SeatValue)으로 판단 (한국 차 기준 운전석 = 1열 왼쪽)
    // 여러 좌석이 묶인 영역은 어느 좌석인지 알 수 없어서 제외
    private fun isDriverSeat(area: SeatArea): Boolean =
        area.values.size == 1 && area.values[0] == SeatValue.ROW_1_LEFT

    private fun isPassengerSeat(area: SeatArea): Boolean =
        area.values.size == 1 && area.values[0] == SeatValue.ROW_1_RIGHT

    private fun seatName(area: SeatArea): String = area.values.joinToString("+") { it.name }

    private fun onOff(v: Any?): Boolean? {
        val words = v?.toString()?.uppercase()?.split(Regex("[^A-Z]+")) ?: return null
        return when {
            "ON" in words -> true
            "OFF" in words -> false
            else -> null
        }
    }

    private fun gearToMode(v: Any?): String? {
        val t = v?.toString()?.uppercase() ?: return null
        return when {
            "PARK" in t -> "P"
            "REVERSE" in t -> "R"
            "NEUTRAL" in t -> "N"
            "DRIVE" in t || "FIRST" in t || "SECOND" in t -> "D"
            else -> null
        }
    }

    private inline fun safe(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            if (failedOnce.add(name)) Log.w(TAG, "$name 사용 불가: ${e.message}")
        }
    }

    // 영역 이름: SeatArea처럼 values(예: REAR_WINDSHIELD)가 있으면 그걸 쓰고, 없으면 toString
    private fun areaName(a: Any): String = try {
        val v = a.javaClass.getMethod("getValues").invoke(a)
        when (v) {
            is Array<*> -> v.joinToString("+")
            is Collection<*> -> v.joinToString("+")
            else -> v?.toString() ?: a.toString()
        }
    } catch (_: Exception) { a.toString() }

    // 진단 로그: 같은 내용이면 다시 안 찍음
    private fun logRaw(key: String, msg: String) {
        if (rawLog[key] != msg) { rawLog[key] = msg; Log.d(TAG, msg) }
    }

    private fun logFailOnce(name: String, e: Exception) {
        if (failedOnce.add("read-$name")) Log.w(TAG, "$name 읽기 실패: ${e.message}")
    }
}

// ───── 날씨 예보 (Open-Meteo, API 키 불필요) ─────
const val WEATHER_LAT = 35.54    // 울산 (나중에 NaviHelper 차량 현재 위치로 교체)
const val WEATHER_LON = 129.31

data class HourTemp(val epochMillis: Long, val tempC: Double)

suspend fun fetchHourlyForecast(
    lat: Double = WEATHER_LAT,
    lon: Double = WEATHER_LON
): List<HourTemp>? = withContext(Dispatchers.IO) {
    try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&hourly=temperature_2m&forecast_days=8&timeformat=unixtime"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val hourly = JSONObject(body).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val result = (0 until times.length())
            .map { i -> HourTemp(times.getLong(i) * 1000, temps.optDouble(i, Double.NaN)) }
            .filter { !it.tempC.isNaN() }
        Log.d("BatteryAlert", "날씨 예보 수신: ${result.size}시간분, 최저 ${result.minOfOrNull { it.tempC }}°C")
        result
    } catch (e: Exception) {
        Log.e("BatteryAlert", "날씨 받기 실패", e)
        null
    }
}


// ───── 위험 알림 기록 (배터리 리포트용, 기기에 저장) ─────
object AlertHistory {
    private const val PREF = "battery_alert_history"
    private const val KEY = "events"
    private const val DAY = 24L * 3_600_000

    data class Event(val time: Long, val risk: String, val hours: Double, val tempC: Double)

    fun load(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            runCatching { Event(p[0].toLong(), p[1], p[2].toDouble(), p[3].toDouble()) }.getOrNull()
        }
    }

    private fun save(context: Context, list: List<Event>) {
        val cutoff = System.currentTimeMillis() - 90 * DAY      // 90일 지난 기록은 정리
        val text = list.filter { it.time >= cutoff }
            .joinToString("\n") { "${it.time}|${it.risk}|${it.hours}|${it.tempC}" }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, text).apply()
    }

    fun add(context: Context, e: Event) {
        save(context, load(context) + e)
        Log.d("BatteryAlert", "위험 알림 기록 저장: ${e.risk}, 시동한계 ${e.hours.toInt()}시간, ${e.tempC}°C")
    }

    fun clear(context: Context) = save(context, emptyList())

    // 시연용: 최근 30일 동안 겨울철 알림이 쌓인 상황
    fun addDemo(context: Context) {
        val now = System.currentTimeMillis()
        val demo = listOf(
            Event(now - 2 * DAY, "DANGER", 6.0, -9.0),
            Event(now - 6 * DAY, "CAUTION", 14.0, -4.0),
            Event(now - 11 * DAY, "DANGER", 5.0, -11.0),
            Event(now - 17 * DAY, "CAUTION", 16.0, 1.0),
            Event(now - 25 * DAY, "DANGER", 7.0, -7.0)
        )
        save(context, load(context) + demo)
    }
}


// ───── 내비 연동 (Pleos NaviHelper): 현재 위치 받기 + 길 안내 ─────
class NaviBridge(context: Context) {
    private val TAG = "BatteryAlert"
    private val helper = NaviHelper(context.applicationContext)
    @Volatile var connected = false
        private set
    @Volatile var lastLocation: CurrentLocationInfo? = null
        private set
    // X1·X2: 목적지·경로 상태·집 즐겨찾기
    @Volatile var destination: DestinationInfo? = null
        private set
    @Volatile var routeState: RouteStateInfo? = null
        private set
    @Volatile var homeBookmark: BookmarkInfo? = null
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            lastLocation = currentLocationInfo
            Log.d(TAG, "현재 위치: ${currentLocationInfo.latitude}, ${currentLocationInfo.longitude} (${currentLocationInfo.address})")
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "내비 오류: $errorCode")
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            if (destination?.remainTime != destinationInfo.remainTime || destination?.name != destinationInfo.name) {
                Log.d(TAG, "목적지: ${destinationInfo.name}, 남은 ${destinationInfo.remainDistance}m · ${destinationInfo.remainTime}초")
            }
            destination = destinationInfo
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            if (routeState?.isRouting != routeStateInfo.isRouting || routeState?.routeOption != routeStateInfo.routeOption) {
                Log.d(TAG, "경로 상태: 안내 중=${routeStateInfo.isRouting}, 옵션=${routeStateInfo.routeOption}")
            }
            routeState = routeStateInfo
        }
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            homeBookmark = bookmarkInfoList.firstOrNull { it.category.name.uppercase().contains("HOME") }
            Log.d(TAG, "즐겨찾기 ${bookmarkInfoList.size}개: ${bookmarkInfoList.joinToString { "${it.name}(${it.category})" }}")
        }
        override fun onRouteOptionChanged(routeOption: RouteOption) {
            Log.d(TAG, "경로 옵션 변경됨: $routeOption")
            requestRouteInfo()
        }
        override fun onRouteCancelled() {
            Log.d(TAG, "경로 안내 취소")
            destination = null
        }
    }

    // 지금 경로 안내 중이고 목적지 정보가 있으면 그 목적지
    val activeDestination: DestinationInfo?
        get() = if (routeState?.isRouting == true) destination else null

    @SuppressLint("MissingPermission")
    fun requestRouteInfo() {
        if (!connected) return
        try {
            helper.getRouteStateInfo()
            helper.getDestinationInfo()
        } catch (e: Exception) {
            Log.w(TAG, "경로 정보 요청 실패: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun requestBookmarks() {
        if (!connected) return
        try { helper.getBookmarkInfo() } catch (e: Exception) {
            Log.w(TAG, "즐겨찾기 요청 실패: ${e.message}")
        }
    }

    // X2: 경로 옵션 바꾸기 (예: 무료도로 → 보통 더 오래 달림)
    @SuppressLint("MissingPermission")
    fun changeRouteOption(option: RouteOption): Boolean {
        if (!connected) return false
        return try {
            helper.changeRouteOption(option)
            Log.d(TAG, "경로 옵션 변경 요청: $option")
            true
        } catch (e: Exception) {
            Log.w(TAG, "경로 옵션 변경 실패: ${e.message}")
            false
        }
    }

    fun start() {
        try {
            helper.initialize(
                onConnected = {
                    connected = true
                    helper.addListener(listener)
                    Log.d(TAG, "내비 연결 성공")
                    requestLocation()
                    requestBookmarks()
                },
                onFailed = { t -> Log.w(TAG, "내비 연결 실패: ${t.message}") }
            )
        } catch (e: Exception) {
            Log.w(TAG, "내비 사용 불가: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun requestLocation() {
        if (!connected) return
        try { helper.getCurrentLocationInfo() } catch (e: Exception) {
            Log.w(TAG, "현재 위치 요청 실패: ${e.message}")
        }
    }

    // 위치 요청 후 최대 3초 기다림
    suspend fun awaitLocation(timeoutMs: Long = 3_000): CurrentLocationInfo? {
        requestLocation()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            lastLocation?.let { return it }
            delay(200)
        }
        return lastLocation
    }

    @SuppressLint("MissingPermission")
    fun routeTo(p: Place): Boolean {
        if (!connected) return false
        return try {
            helper.requestRoute(
                RouteInfo(
                    latitude = p.lat,
                    longitude = p.lon,
                    displayLatitude = p.lat,
                    displayLongitude = p.lon,
                    poiName = p.name,
                    poiId = p.id,
                    address = p.address,
                    poiSubId = "",
                    poiProvider = "kakao"
                )
            )
            Log.d(TAG, "내비 길 안내 요청: ${p.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "길 안내 요청 실패: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            helper.removeListener(listener)
            helper.release()
        } catch (_: Exception) { }
    }
}

// ───── 카카오 장소 검색: 가까운 블루핸즈 ─────
data class Place(
    val id: String, val name: String, val address: String, val phone: String,
    val lat: Double, val lon: Double, val distanceM: Int
)

suspend fun searchBluehands(lat: Double, lon: Double): List<Place>? = withContext(Dispatchers.IO) {
    try {
        val q = URLEncoder.encode("블루핸즈", "UTF-8")
        val url = URL(
            "https://dapi.kakao.com/v2/local/search/keyword.json" +
                    "?query=$q&x=$lon&y=$lat&radius=20000&sort=distance&size=5"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "KakaoAK ${BuildConfig.KAKAO_REST_KEY}")
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            Log.e("BatteryAlert", "카카오 검색 실패 ($code): $body")
            return@withContext null
        }
        val docs = JSONObject(body).getJSONArray("documents")
        val result = (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            Place(
                id = d.optString("id"),
                name = d.optString("place_name"),
                address = d.optString("road_address_name").ifBlank { d.optString("address_name") },
                phone = d.optString("phone"),
                lat = d.optString("y").toDoubleOrNull() ?: 0.0,
                lon = d.optString("x").toDoubleOrNull() ?: 0.0,
                distanceM = d.optString("distance").toIntOrNull() ?: 0
            )
        }.filter { it.name.contains("블루핸즈") }
        Log.d("BatteryAlert", "블루핸즈 검색 결과 ${result.size}곳")
        result
    } catch (e: Exception) {
        Log.e("BatteryAlert", "카카오 검색 오류", e)
        null
    }
}

// ───── F1 12V 에너지 장부: 들어오고 나간 전류를 계속 더하고 빼서 지금 12V 잔량을 추정 ─────
object EnergyLedger {
    private const val PREF = "battery_energy_ledger"
    private const val HOUR = 3_600_000.0
    private const val DAY = 24L * 3_600_000
    private const val SAMPLE_MS = 10 * 60_000L        // 그래프용 기록: 10분마다
    private const val KEEP_MS = 7 * DAY               // 그래프용 기록: 7일 보관
    private const val OFF_MIN_HOURS = 0.1             // 6분 이상 앱이 꺼져 있었으면 "주차했었다"로 판단
    const val INITIAL_SOC = 90.0                      // 처음 설치했을 때 가정값

    data class Sample(val time: Long, val soc: Double, val measured: Boolean = false)   // measured = 실측 점

    data class State(
        val soc: Double,               // 추정 12V SoC (%)
        val lastTick: Long,            // 마지막으로 계산한 시각
        val lastDrainA: Double,        // 마지막 주차 소모 전류 (앱이 꺼져 있던 동안 이 값으로 계산)
        val resetTime: Long,           // 마지막으로 실측값 보정한 시각
        val resetSource: String = SRC_NONE, // 마지막 기준점: 정비소 / 블랙박스 / 없음(설치 가정값)
        val selfCalCount: Int = 0,     // W1 자가 보정 횟수 (W1에서 사용)
        val tripStartSoc: Double?,     // 이번 주행 출발 시 SoC
        val tripEndSoc: Double?,       // 이번 주행 도착 시 SoC
        val samples: List<Sample>,
        val lastMode: String? = null,  // 직전 기어 (출발/도착 판단용, 저장 안 함)
        val lastEvent: String = ""     // 화면에 보여줄 최근 장부 변경 내역
    ) {
        fun daysSinceReset(now: Long = System.currentTimeMillis()): Double = (now - resetTime) / HOUR / 24.0

        // 오차: 기준점별 시작 폭(정비소 ±3, 블랙박스 ±6, 없음 ±10) + 하루당 2%, 최대 ±20%
        fun uncertainty(now: Long = System.currentTimeMillis()): Double =
            (baseUncertainty(resetSource) + daysSinceReset(now) * UNC_PER_DAY).coerceAtMost(UNC_MAX)

        // W3 신뢰도: 기준점 점수 − 경과 일수 × 5 + 자가 보정 횟수 × 3 (0~100)
        fun confidence(now: Long = System.currentTimeMillis()): Int =
            (baseConfidence(resetSource) - daysSinceReset(now) * CONF_LOSS_PER_DAY + selfCalCount * CONF_GAIN_PER_SELFCAL)
                .toInt().coerceIn(0, 100)
    }

    const val SRC_SHOP = "정비소"
    const val SRC_DASHCAM = "블랙박스"
    const val SRC_NONE = "없음"
    private const val UNC_PER_DAY = 2.0
    private const val UNC_MAX = 20.0
    private const val CONF_LOSS_PER_DAY = 5.0
    private const val CONF_GAIN_PER_SELFCAL = 3.0

    fun baseUncertainty(src: String): Double = when (src) {
        SRC_SHOP -> 3.0
        SRC_DASHCAM -> 6.0
        else -> 10.0          // 실측 없이 설치 때 가정값(90%)으로 시작
    }

    fun baseConfidence(src: String): Double = when (src) {
        SRC_SHOP -> 100.0
        SRC_DASHCAM -> 85.0   // 블랙박스 전압은 부하가 걸린 상태라 정비소보다 덜 정확
        else -> 60.0
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context): State {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val samples = (p.getString("samples", "") ?: "").lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                runCatching { Sample(parts[0].toLong(), parts[1].toDouble(), parts.getOrNull(2) == "1") }.getOrNull()
            }
        return State(
            soc = p.getString("soc", null)?.toDoubleOrNull() ?: INITIAL_SOC,
            lastTick = p.getLong("lastTick", now),
            lastDrainA = p.getString("drainA", null)?.toDoubleOrNull() ?: DARK_CURRENT_A,
            resetTime = p.getLong("resetTime", now),
            resetSource = p.getString("resetSource", SRC_NONE) ?: SRC_NONE,
            selfCalCount = p.getInt("selfCalCount", 0),
            tripStartSoc = p.getString("tripStartSoc", null)?.toDoubleOrNull(),
            tripEndSoc = p.getString("tripEndSoc", null)?.toDoubleOrNull(),
            samples = samples
        )
    }

    private fun save(context: Context, st: State, withSamples: Boolean) {
        val e = prefs(context).edit()
            .putString("soc", st.soc.toString())
            .putLong("lastTick", st.lastTick)
            .putString("drainA", st.lastDrainA.toString())
            .putLong("resetTime", st.resetTime)
            .putString("resetSource", st.resetSource)
            .putInt("selfCalCount", st.selfCalCount)
            .putString("tripStartSoc", st.tripStartSoc?.toString())
            .putString("tripEndSoc", st.tripEndSoc?.toString())
        if (withSamples) {
            e.putString("samples", st.samples.joinToString("\n") { "${it.time}|${it.soc}|${if (it.measured) 1 else 0}" })
        }
        e.apply()
    }

    private fun withSample(st: State, now: Long, measured: Boolean = false): State {
        val list = (st.samples + Sample(now, st.soc, measured)).filter { it.time >= now - KEEP_MS }
        return st.copy(samples = list)
    }

    // 앱이 켜질 때 1번: 꺼져 있던 시간(=주차) 동안 빠져나간 전류를 한 번에 빼기
    fun catchUp(context: Context, s: VehicleSnapshot): State {
        val st = load(context)
        val now = System.currentTimeMillis()
        val offH = ((now - st.lastTick) / HOUR).coerceIn(0.0, 24.0 * 30)
        if (offH < OFF_MIN_HOURS) return st.copy(lastTick = now)

        val drain = st.lastDrainA * offH / usableAh(s) * 100
        val startCost = if (s.isEv) 0.0 else START_COST_PERCENT     // 내연기관은 시동 1회 소모
        val after = (st.soc - drain - startCost).coerceIn(0.0, 100.0)
        val drop = st.soc - after                                 // 실제로 빠진 양 (0% 아래로는 안 감)
        var next = st.copy(
            soc = after,
            lastTick = now,
            lastEvent = "꺼져 있던 ${"%.1f".format(offH)}시간 동안 −${"%.1f".format(drop)}% 반영"
        )
        next = withSample(next, now)
        save(context, next, true)
        Log.d("BatteryAlert", "장부 소급: ${next.lastEvent} → ${"%.1f".format(next.soc)}%")
        return next
    }

    // 2초마다: 지금 들어가는/나가는 전류 × 시간 만큼 더하고 빼기
    fun tick(context: Context, st: State, s: VehicleSnapshot): State {
        val now = System.currentTimeMillis()
        val dtH = ((now - st.lastTick) / HOUR).coerceIn(0.0, 0.01)   // 한 번에 최대 36초까지만
        val soc = (st.soc + chargeNetA(s) * dtH / usableAh(s) * 100).coerceIn(0.0, 100.0)

        val driving = s.driveMode != "P"
        val wasDriving = st.lastMode != null && st.lastMode != "P"
        var next = st.copy(soc = soc, lastTick = now, lastDrainA = parkedDrainA(s), lastMode = s.driveMode)
        if (driving && !wasDriving) next = next.copy(tripStartSoc = soc, tripEndSoc = null)  // 출발
        if (!driving && wasDriving) next = next.copy(tripEndSoc = soc)                        // 도착

        val needSample = now - (st.samples.lastOrNull()?.time ?: 0L) >= SAMPLE_MS
        if (needSample) next = withSample(next, now)
        save(context, next, needSample)
        return next
    }

    // 실측값으로 다시 맞추기: 정비소 측정(±3%) 또는 블랙박스 표시 전압(±6%)
    fun calibrate(context: Context, st: State, soc: Double, source: String = SRC_SHOP, note: String = ""): State {
        val now = System.currentTimeMillis()
        var next = st.copy(soc = soc, resetTime = now, resetSource = source, lastTick = now,
            lastEvent = "$source 실측 ${soc.toInt()}%로 보정$note (오차 ±${baseUncertainty(source).toInt()}%)")
        next = withSample(next, now, measured = true)
        save(context, next, true)
        Log.d("BatteryAlert", "장부 보정: ${next.lastEvent}")
        return next
    }

    // W1: 자가 보정 횟수 반영 (신뢰도 +3점씩)
    fun setSelfCalCount(context: Context, st: State, count: Int): State {
        val next = st.copy(selfCalCount = count)
        save(context, next, false)
        return next
    }

    // [시연용] 마지막 실측이 며칠 전이었던 것처럼 만들기 (W3 신뢰도 테스트)
    fun shiftResetDays(context: Context, st: State, days: Int): State {
        val src = if (st.resetSource == SRC_NONE) SRC_SHOP else st.resetSource
        val next = st.copy(resetTime = System.currentTimeMillis() - days * DAY, resetSource = src,
            lastEvent = "[시연] 마지막 실측을 ${days}일 전으로 설정")
        save(context, next, false)
        return next
    }

    // Y5: 주차 절전에서 깨어날 때 → 절전하던 동안의 주차 소모를 한 번에 빼기 (F1 소급과 같은 방식)
    fun resumeAfterSleep(context: Context, st: State, s: VehicleSnapshot): State {
        val now = System.currentTimeMillis()
        val offH = ((now - st.lastTick) / HOUR).coerceIn(0.0, 24.0 * 30)
        val after = (st.soc - st.lastDrainA * offH / usableAh(s) * 100).coerceIn(0.0, 100.0)
        val drop = st.soc - after
        var next = st.copy(soc = after, lastTick = now,
            lastEvent = "절전 ${(offH * 60).toInt()}분 동안 −${"%.2f".format(drop)}% 반영")
        next = withSample(next, now)
        save(context, next, true)
        Log.d("BatteryAlert", "Y5 장부 소급: ${next.lastEvent} → ${"%.1f".format(next.soc)}%")
        return next
    }

    // [시연용] Y1: 12V를 원하는 값으로 맞추기 (실측이 아니라서 신뢰도 기준점은 그대로)
    fun demoSetSoc(context: Context, st: State, soc: Double, note: String): State {
        val now = System.currentTimeMillis()
        var next = st.copy(soc = soc.coerceIn(0.0, 100.0), lastTick = now, lastEvent = "[시연] $note")
        next = withSample(next, now)
        save(context, next, true)
        Log.d("BatteryAlert", "시연 12V 설정: $note → ${"%.1f".format(next.soc)}%")
        return next
    }

    // [시연용] 앱을 끄지 않고 "○시간 주차"한 효과 보기
    fun simulateParking(context: Context, st: State, hours: Double, drainA: Double, usableAh: Double): State {
        val after = (st.soc - drainA * hours / usableAh * 100).coerceIn(0.0, 100.0)
        val drop = st.soc - after                                 // 실제로 빠진 양 (0% 아래로는 안 감)
        val empty = if (after <= 0.0) " (완전 방전)" else ""
        var next = st.copy(soc = after,
            lastEvent = "[시연] 주차 ${hours.toInt()}시간 경과: −${"%.1f".format(drop)}%p$empty")
        next = withSample(next, System.currentTimeMillis())
        save(context, next, true)
        return next
    }

    // [시연용] 최근 7일 추이 예시: 평일 출퇴근 충전, 주말 방치, 3일 전 저녁 전조등 켜둔 채 주차
    fun addDemo(context: Context, st: State, usableAh: Double): State {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        var soc = 80.0
        val list = mutableListOf<Sample>()
        var t = now - KEEP_MS
        while (t <= now) {
            cal.timeInMillis = t
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            val commute = !weekend && ((h == 8 && m < 30) || (h == 18 && m < 30))
            val daysAgo = ((now - t) / DAY).toInt()
            val amps = when {
                commute -> CHARGE_ACCEPT_A
                daysAgo == 3 && h in 19..20 -> -(DARK_CURRENT_A + DASHCAM_A + HEADLIGHT_PARKED_A)
                else -> -(DARK_CURRENT_A + DASHCAM_A)
            }
            soc = (soc + amps * (SAMPLE_MS / HOUR) / usableAh * 100).coerceIn(0.0, 100.0)
            list.add(Sample(t, soc))
            t += SAMPLE_MS
        }
        val next = st.copy(soc = soc, samples = list, lastTick = now,
            lastEvent = "[시연] 7일 추이 예시 생성")
        save(context, next, true)
        return next
    }
}


// ───── F5 출발 기록: P에서 2시간 이상 주차했다가 출발한 시각만 저장 ─────
object DepartureLog {
    private const val PREF = "departure_log"
    private const val DAY = 24L * 3_600_000
    const val MIN_PARK_HOURS = 2.0         // 잠깐 정차(편의점 등)는 출발로 안 봄

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context): List<Long> =
        (prefs(context).getString("events", "") ?: "").lines()
            .mapNotNull { it.trim().toLongOrNull() }

    private fun save(context: Context, list: List<Long>) {
        val cutoff = System.currentTimeMillis() - 90 * DAY
        prefs(context).edit()
            .putString("events", list.filter { it >= cutoff }.sorted().joinToString("\n"))
            .apply()
    }

    // 도착(P)할 때: 주차 시작 시각 저장
    fun markPark(context: Context) {
        prefs(context).edit().putLong("parkStart", System.currentTimeMillis()).apply()
    }

    // 출발(P → D/R/N)할 때: 2시간 이상 주차했었으면 출발 기록 추가. 추가했으면 true
    fun recordDeparture(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val parkStart = prefs(context).getLong("parkStart", 0L)
        prefs(context).edit().remove("parkStart").apply()
        if (parkStart == 0L) return false
        val parkedH = (now - parkStart) / 3_600_000.0
        if (parkedH < MIN_PARK_HOURS) {
            Log.d("BatteryAlert", "출발 기록 안 함 (주차 ${"%.1f".format(parkedH)}시간 < ${MIN_PARK_HOURS}시간)")
            return false
        }
        save(context, load(context) + now)
        Log.d("BatteryAlert", "출발 기록 저장 (주차 ${"%.1f".format(parkedH)}시간)")
        return true
    }

    fun clear(context: Context) = save(context, emptyList())

    // [시연용] 최근 3주: 평일 08:05~08:19 출발, 토요일 10:30 전후 출발
    fun addDemo(context: Context) {
        val now = System.currentTimeMillis()
        val list = mutableListOf<Long>()
        for (d in 1..21) {
            val c = Calendar.getInstance().apply { timeInMillis = now - d * DAY }
            val dow = c.get(Calendar.DAY_OF_WEEK)
            when {
                dow == Calendar.SUNDAY -> continue
                dow == Calendar.SATURDAY -> { c.set(Calendar.HOUR_OF_DAY, 10); c.set(Calendar.MINUTE, 25 + d % 10) }
                else -> { c.set(Calendar.HOUR_OF_DAY, 8); c.set(Calendar.MINUTE, 5 + (d * 7) % 15) }
            }
            c.set(Calendar.SECOND, 0)
            list.add(c.timeInMillis)
        }
        save(context, load(context) + list)
    }
}


// ───── 사용자 설정 저장 (앱을 껐다 켜도 유지) ─────
object UserSettings {
    private const val PREF = "user_settings"
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // X5: 차대번호 연식으로 배터리 연수를 자동으로 채웠는지 (처음 한 번만)
    fun vinApplied(context: Context) = prefs(context).getBoolean("vinApplied", false)
    fun markVinApplied(context: Context) = prefs(context).edit().putBoolean("vinApplied", true).apply()

    fun load(context: Context, s: VehicleSnapshot): VehicleSnapshot {
        val p = prefs(context)
        if (!p.contains("saved")) return s
        return s.copy(
            autoDepart = p.getBoolean("autoDepart", s.autoDepart),
            departDaysLater = p.getInt("departDaysLater", s.departDaysLater),
            departHour = p.getInt("departHour", s.departHour),
            departMinute = p.getInt("departMinute", s.departMinute),
            batteryAgeYears = p.getInt("batteryAgeYears", s.batteryAgeYears),
            batteryAh = p.getInt("batteryAh", s.batteryAh),
            dashcamParkingOn = p.getBoolean("dashcamParkingOn", s.dashcamParkingOn),
            dashcamMa = p.getInt("dashcamMa", s.dashcamMa),
            evSaverEnabled = p.getBoolean("evSaverEnabled", s.evSaverEnabled),
            undergroundParking = p.getBoolean("undergroundParking", s.undergroundParking),
            voiceOn = p.getBoolean("voiceOn", s.voiceOn),
            shareConsent = p.getBoolean("shareConsent", s.shareConsent),
            autoProtect = p.getBoolean("autoProtect", s.autoProtect),
            protectMargin = p.getInt("protectMargin", s.protectMargin),
            dashcamCutoffSet = p.getBoolean("dashcamCutoffSet", s.dashcamCutoffSet)
        )
    }

    // 설정값이 바뀌었는지 비교하는 용도
    fun key(s: VehicleSnapshot): String = listOf(
        s.autoDepart, s.departDaysLater, s.departHour, s.departMinute, s.batteryAgeYears, s.batteryAh,
        s.dashcamParkingOn, s.dashcamMa, s.evSaverEnabled, s.undergroundParking, s.voiceOn, s.shareConsent,
        s.autoProtect, s.protectMargin, s.dashcamCutoffSet
    ).joinToString("|")

    fun save(context: Context, s: VehicleSnapshot) {
        prefs(context).edit()
            .putBoolean("saved", true)
            .putBoolean("autoDepart", s.autoDepart)
            .putInt("departDaysLater", s.departDaysLater)
            .putInt("departHour", s.departHour)
            .putInt("departMinute", s.departMinute)
            .putInt("batteryAgeYears", s.batteryAgeYears)
            .putInt("batteryAh", s.batteryAh)
            .putBoolean("dashcamParkingOn", s.dashcamParkingOn)
            .putInt("dashcamMa", s.dashcamMa)
            .putBoolean("evSaverEnabled", s.evSaverEnabled)
            .putBoolean("undergroundParking", s.undergroundParking)
            .putBoolean("voiceOn", s.voiceOn)
            .putBoolean("shareConsent", s.shareConsent)
            .putBoolean("autoProtect", s.autoProtect)
            .putInt("protectMargin", s.protectMargin)
            .putBoolean("dashcamCutoffSet", s.dashcamCutoffSet)
            .apply()
    }
}


// ───── 음성 안내 (Pleos TextToSpeech SDK, 차 안에서 바로 합성 → 서버 등록 불필요) ─────
class PleosVoice(context: Context) {
    private val TAG = "BatteryAlert"
    private val tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    } catch (e: Exception) {
        Log.w(TAG, "음성 안내 생성 실패: ${e.message}")
        null
    }
    @Volatile var ready = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val t = tts ?: return
        Log.d(TAG, "음성 안내 초기화 시작")
        try {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    t.setLocale(Locale.KOREA)
                    ready = true
                    Log.d(TAG, "음성 안내 준비 완료 (Pleos TTS ${TextToSpeech.version})")
                }
                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "음성 안내 초기화 실패: $errorType")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "음성 안내 초기화 예외: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun speak(text: String) {
        val t = tts ?: return
        if (!ready) {
            Log.w(TAG, "음성 안내 준비 안 됨 → 건너뜀: $text")
            return
        }
        try {
            t.stop()          // 말하던 게 있으면 끊고 새로 말하기
            t.speak(text)
            Log.d(TAG, "음성 안내: $text")
        } catch (e: Exception) {
            Log.w(TAG, "음성 안내 실패: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { tts?.release() } catch (_: Exception) { }
    }
}


// ───── W1 아침 시동 결과로 자가 보정 ─────
// 주차할 때 예측을 저장 → 다음 출발(2시간 이상 주차 후)이 "정답"
//  · 위험이라 했는데 정상 출발 → 주차 소모를 과대 추정한 것 → k × 0.9 (최소 0.6)
//  · "방전됐어요" 답변 → 과소 추정 → k × 1.25 (최대 1.6)
object SelfCal {
    private const val PREF = "self_calibration"
    const val K_DOWN = 0.9
    const val K_UP = 1.25
    const val K_MIN = 0.6
    const val K_MAX = 1.6
    const val ASK_GAP_HOURS = 6.0       // 6시간 넘게 꺼져 있다 켜지면 "어제 시동 괜찮았나요?" 질문

    data class Pending(val time: Long, val risk: String, val hours: Double)

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun k(context: Context): Double = prefs(context).getString("k", null)?.toDoubleOrNull() ?: 1.0
    fun count(context: Context): Int = prefs(context).getInt("count", 0)

    fun pending(context: Context): Pending? {
        val p = prefs(context)
        val t = p.getLong("pTime", 0L)
        if (t == 0L) return null
        return Pending(t, p.getString("pRisk", "OK") ?: "OK", p.getString("pHours", "0")?.toDoubleOrNull() ?: 0.0)
    }

    // 주차할 때: 이번 밤 예측 저장
    fun savePrediction(context: Context, risk: String, hours: Double) {
        prefs(context).edit().putLong("pTime", System.currentTimeMillis())
            .putString("pRisk", risk).putString("pHours", hours.toString()).apply()
        Log.d("BatteryAlert", "W1 예측 저장: $risk, 시동 한계 ${hours.toInt()}시간")
    }

    private fun setK(context: Context, k: Double, note: String, clearPending: Boolean = true) {
        val c = count(context) + 1
        val e = prefs(context).edit().putString("k", k.toString()).putInt("count", c)
        if (clearPending) e.putLong("pTime", 0L)
        e.apply()
        Log.d("BatteryAlert", "W1 보정: $note → k=${"%.2f".format(k)} (보정 ${c}회)")
    }

    // 정상 출발 (2시간 이상 주차 후 D) → 예측이 위험이었으면 k 낮추기. 예측은 처리 완료로 지움
    fun onStartSuccess(context: Context): Boolean {
        val p = pending(context) ?: return false
        if (p.risk == "DANGER") {
            setK(context, (k(context) * K_DOWN).coerceAtLeast(K_MIN), "위험 예측인데 정상 출발")
            return true
        }
        prefs(context).edit().putLong("pTime", 0L).apply()
        return false
    }

    // "방전됐어요" → k 올리기
    fun onStartFailed(context: Context) {
        setK(context, (k(context) * K_UP).coerceAtMost(K_MAX), "방전됐어요 응답")
    }

    // ───── G3 예측 → 실측 → 보정 폐루프 ─────
    // 실측(블랙박스 전압·정비소)할 때마다 "장부가 예측한 값 − 실측값"을 기록하고,
    // 한쪽으로 치우쳐 있으면(5%p 이상) 주차 소모 보정계수 k를 자동으로 고침
    const val MEAS_TOL = 5.0
    data class Measure(val time: Long, val err: Double)

    fun measures(context: Context): List<Measure> =
        (prefs(context).getString("errs", "") ?: "").lines().mapNotNull { line ->
            val p = line.split("|")
            runCatching { Measure(p[0].toLong(), p[1].toDouble()) }.getOrNull()
        }

    fun recordMeasurement(context: Context, predicted: Double, measured: Double, hadRealBase: Boolean): String {
        val err = predicted - measured        // + 면 장부가 실제보다 높게 봄 = 소모를 적게 잡았음
        if (!hadRealBase) return "실측 기준점을 잡았어요. 다음 실측부터 예측 오차를 비교해요"
        val list = (measures(context) + Measure(System.currentTimeMillis(), err)).takeLast(20)
        prefs(context).edit().putString("errs", list.joinToString("\n") { "${it.time}|${it.err}" }).apply()
        Log.d("BatteryAlert", "G3 실측 비교: 장부 ${"%.1f".format(predicted)}% vs 실측 ${"%.1f".format(measured)}% → 오차 ${"%+.1f".format(err)}%p")
        if (Math.abs(err) < MEAS_TOL) return "예측 오차 ${"%+.1f".format(err)}%p — 잘 맞고 있어요"
        val factor = (1 + err / 40.0).coerceIn(0.85, 1.2)
        val newK = (k(context) * factor).coerceIn(K_MIN, K_MAX)
        setK(context, newK, "실측 비교 오차 ${"%+.1f".format(err)}%p", clearPending = false)
        return "예측이 실제보다 ${"%.1f".format(Math.abs(err))}%p ${if (err > 0) "높았어요" else "낮았어요"} → 주차 소모 보정 ×${"%.2f".format(newK)}"
    }

    // "최근 5번 평균 오차 ±3.2%p (치우침 +1.0%p)"
    fun accuracyText(context: Context): String {
        val m = measures(context).takeLast(5)
        if (m.isEmpty()) return "예측 정확도: 아직 비교할 실측이 없어요 (실측 2번부터 계산)"
        val mae = m.map { Math.abs(it.err) }.average()
        val bias = m.map { it.err }.average()
        return "예측 정확도: 최근 ${m.size}번 평균 오차 ±${"%.1f".format(mae)}%p (치우침 ${"%+.1f".format(bias)}%p)"
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        Log.d("BatteryAlert", "W1 보정 초기화")
    }
}


// ───── X6 음성 인식 (Pleos SpeechToText SDK, 차 안에서 바로 인식 → 서버 등록 불필요) ─────
class PleosListener(context: Context) {
    private val TAG = "BatteryAlert"
    private val main = Handler(Looper.getMainLooper())
    private val stt: SpeechToText? = try {
        SpeechToText(context, SttMode.ON_DEVICE)   // 화면(Activity) context 그대로 전달 (SDK 내부 ViewModel용)
    } catch (e: Exception) {
        Log.w(TAG, "음성 인식 생성 실패: ${e.message}")
        null
    }
    @Volatile var ready = false
        private set
    var onResult: ((String) -> Unit)? = null      // 말이 끝나면 인식된 문장
    var onState: ((String) -> Unit)? = null       // 화면에 보여줄 상태 ("듣는 중..." 등)

    private val listener = object : ResultListener {
        override fun onReady() { main.post { onState?.invoke("말씀하세요...") } }
        override fun onStartedRecognition() { main.post { onState?.invoke("듣는 중...") } }
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                onState?.invoke("\"$stt\"")
                if (completed && stt.isNotBlank()) {
                    Log.d(TAG, "음성 인식 결과: $stt")
                    onResult?.invoke(stt)
                }
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onEndedRecognition() { Log.d(TAG, "음성 인식 끝") }
        override fun onError(errorType: SttErrorType) {
            Log.w(TAG, "음성 인식 오류: $errorType")
            main.post { onState?.invoke("인식 실패 ($errorType) — 다시 눌러 주세요") }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val s = stt ?: return
        try {
            s.initialize()
            s.setLocale(Locale.KOREA)
            s.addListener(listener)
            ready = true
            Log.d(TAG, "음성 인식 준비 완료 (Pleos STT)")
        } catch (e: Throwable) {      // SDK 내부 오류(Error 포함)도 잡아서 앱이 꺼지지 않게
            Log.w(TAG, "음성 인식 초기화 실패: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun listen() {
        val s = stt
        if (s == null || !ready) {
            onState?.invoke("음성 인식을 쓸 수 없어요")
            return
        }
        try {
            s.request()
            onState?.invoke("말씀하세요...")
        } catch (e: Exception) {
            Log.w(TAG, "음성 인식 요청 실패: ${e.message}")
            onState?.invoke("음성 인식 요청 실패")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { stt?.removeListener(listener); stt?.release() } catch (_: Exception) { }
    }
}

// ───── Y3 주차 기록 (영수증·주간 리포트용, 최근 30일) ─────
// 위치는 저장하지 않음 (Y4 장소 프로필과 별개)
object ParkLog {
    private const val PREF = "park_log"
    private const val KEY = "records"
    private const val KEY_SEEN = "seen_end_time"
    private const val KEEP_MS = 30L * 24 * 3_600_000L

    fun load(context: Context): List<ParkRecord> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val c = o.getJSONArray("c")
                ParkRecord(
                    endTime = o.getLong("t"),
                    hours = o.getDouble("h"),
                    causes = (0 until c.length()).map { j ->
                        val p = c.getJSONArray(j); p.getString(0) to p.getDouble(1)
                    },
                    tempC = o.getDouble("temp"),
                    socAfter = o.getDouble("soc")
                )
            }
        } catch (e: Exception) {
            Log.w("BatteryAlert", "주차 기록 읽기 실패: ${e.message}")
            emptyList()
        }
    }

    private fun save(context: Context, list: List<ParkRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            val c = JSONArray()
            r.causes.forEach { (name, ah) -> c.put(JSONArray().put(name).put(ah)) }
            arr.put(JSONObject().put("t", r.endTime).put("h", r.hours).put("c", c)
                .put("temp", r.tempC).put("soc", r.socAfter))
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, r: ParkRecord): List<ParkRecord> {
        val now = System.currentTimeMillis()
        val list = (load(context) + r).filter { it.endTime >= now - KEEP_MS }.sortedBy { it.endTime }.takeLast(200)
        save(context, list)
        Log.d("BatteryAlert", "Y3 주차 기록: ${"%.1f".format(r.hours)}시간, ${"%.2f".format(r.totalAh)}Ah " +
                r.causes.joinToString { "${it.first} ${"%.2f".format(it.second)}Ah" })
        return list
    }

    // 아침 영수증은 주차 1건당 1번만
    fun seenEndTime(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0L)

    fun markSeen(context: Context, endTime: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_SEEN, endTime).apply()
    }

    // [시연용] 최근 7일 밤마다 주차한 기록 (지금 설정 기준) → 주간 리포트 시연
    fun addDemoWeek(context: Context, s: VehicleSnapshot, tempC: Double): List<ParkRecord> {
        val now = System.currentTimeMillis()
        val nights = listOf(14.0, 14.0, 13.0, 14.0, 13.0, 14.0, 14.0)   // 합계 96시간 (하루 평균 약 13.7시간)
        var list = load(context).filter { it.endTime < now - 7 * 24 * 3_600_000L }
        nights.forEachIndexed { i, h ->
            val end = now - (7 - i) * 24 * 3_600_000L + 3_600_000L
            list = list + makeParkRecord(s, h, parkedDrainA(s), tempC, 70.0, end)
        }
        save(context, list.sortedBy { it.endTime })
        Log.d("BatteryAlert", "Y3 시연: 최근 7일 주차 기록 ${nights.size}건 (${nights.sum()}시간)")
        return load(context)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // Z5: 블랙박스 표시 전압 입력 기록 (최근 10개)
    fun addVoltage(context: Context, v: Double) {
        val list = (voltages(context) + v).takeLast(10)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("volts", list.joinToString(",")).apply()
    }

    fun voltages(context: Context): List<Double> =
        (context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("volts", "") ?: "")
            .split(",").mapNotNull { it.toDoubleOrNull() }
}

// ───── Y4 장소별 주차 프로필 (좌표는 이 기기 안에만 저장) ─────
data class ParkPlace(
    val id: Int,
    val name: String,
    val named: Boolean,         // 사용자가 이름을 직접 정했는지 (안 정했으면 자동 추천)
    val lat: Double,
    val lon: Double,
    val underground: Boolean,   // 지하·실내 주차
    val bonusC: Double,         // 지하일 때 바깥보다 따뜻한 정도 (°C)
    val visits: Int,            // 주차 횟수 (끝난 것)
    val totalH: Double,         // 주차 시간 합계
    val nights: Int,            // 밤새 주차한 횟수 → "집" 추천
    val days: Int,              // 평일 낮 주차 횟수 → "회사" 추천
    val risks: List<Long>       // 위험 알림 시각 (최근 30일 표시)
) {
    val avgH: Double get() = if (visits == 0) 0.0 else totalH / visits
    fun risks30(now: Long = System.currentTimeMillis()): Int = risks.count { it >= now - 30L * 24 * 3_600_000L }
    val label: String get() = "$name(${if (underground) "지하" else "지상"})"
}

object PlaceLog {
    private const val PREF = "place_log"
    private const val KEY = "places"
    private const val KEY_CUR = "current_id"
    private const val KEY_START = "park_start"

    fun load(context: Context): List<ParkPlace> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val rk = o.getJSONArray("risks")
                ParkPlace(o.getInt("id"), o.getString("name"), o.getBoolean("named"), o.getDouble("lat"), o.getDouble("lon"),
                    o.getBoolean("ug"), o.getDouble("bonus"), o.getInt("visits"), o.getDouble("totalH"),
                    o.getInt("nights"), o.getInt("days"), (0 until rk.length()).map { rk.getLong(it) })
            }
        } catch (e: Exception) {
            Log.w("BatteryAlert", "장소 프로필 읽기 실패: ${e.message}")
            emptyList()
        }
    }

    private fun save(context: Context, list: List<ParkPlace>) {
        val arr = JSONArray()
        list.forEach { p ->
            val rk = JSONArray(); p.risks.forEach { rk.put(it) }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("named", p.named).put("lat", p.lat).put("lon", p.lon)
                .put("ug", p.underground).put("bonus", p.bonusC).put("visits", p.visits).put("totalH", p.totalH)
                .put("nights", p.nights).put("days", p.days).put("risks", rk))
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun currentId(context: Context): Int = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_CUR, -1)

    fun current(context: Context): ParkPlace? = currentId(context).let { id -> load(context).firstOrNull { it.id == id } }

    private fun setCurrent(context: Context, id: Int, start: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_CUR, id).putLong(KEY_START, start).apply()
    }

    fun clearCurrent(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_CUR, -1).apply()
    }

    // P단에 들어오면: 200m 안의 장소를 찾고, 없으면 새로 만듦 → 지금 장소로 정함
    fun onParked(context: Context, lat: Double, lon: Double): ParkPlace {
        val list = load(context)
        val near = list.minByOrNull { distanceM(lat, lon, it.lat, it.lon) }
            ?.takeIf { distanceM(lat, lon, it.lat, it.lon) <= PLACE_RADIUS_M }
        val place = near ?: ParkPlace((list.maxOfOrNull { it.id } ?: 0) + 1, "자주 가는 곳 ${list.size + 1}", false,
            lat, lon, false, PLACE_UNDERGROUND_BONUS_C, 0, 0.0, 0, 0, emptyList()).also {
            save(context, list + it)
            Log.d("BatteryAlert", "Y4: 새 장소 만듦 → ${it.name}")
        }
        if (currentId(context) != place.id) {
            setCurrent(context, place.id, System.currentTimeMillis())
            Log.d("BatteryAlert", "Y4: 지금 장소 = ${place.label}, 평균 ${"%.1f".format(place.avgH)}시간, 방문 ${place.visits}번")
        }
        return place
    }

    // 주차가 끝나면(앱 다시 켜짐·절전 해제): 시간 합산 + 밤/평일 낮 판별 → 이름 자동 추천
    fun onParkEnded(context: Context, hours: Double, endTime: Long = System.currentTimeMillis()) {
        val id = currentId(context)
        if (id < 0 || hours < PARK_RECORD_MIN_H) return
        val start = endTime - (hours * 3_600_000).toLong()
        val c = Calendar.getInstance().apply { timeInMillis = start }
        val startHour = c.get(Calendar.HOUR_OF_DAY)
        val weekday = c.get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY
        val night = (startHour >= 18 || startHour < 4) && hours >= 6
        val day = weekday && startHour in 6..11 && hours in 4.0..12.0
        save(context, load(context).map { p ->
            if (p.id != id) p else {
                val n = p.nights + if (night) 1 else 0
                val d = p.days + if (day) 1 else 0
                val autoName = when {
                    p.named -> p.name
                    n >= 2 && n >= d -> "집"
                    d >= 2 && d > n -> "회사"
                    else -> p.name
                }
                p.copy(name = autoName, visits = p.visits + 1, totalH = p.totalH + hours, nights = n, days = d)
            }
        })
        Log.d("BatteryAlert", "Y4: 장소 주차 끝 ${"%.1f".format(hours)}시간 (밤=$night, 평일 낮=$day)")
    }

    fun addRisk(context: Context) {
        val id = currentId(context)
        if (id < 0) return
        save(context, load(context).map { if (it.id == id) it.copy(risks = (it.risks + System.currentTimeMillis()).takeLast(100)) else it })
    }

    fun rename(context: Context, id: Int, name: String) =
        save(context, load(context).map { if (it.id == id) it.copy(name = name, named = true) else it })

    fun setUnderground(context: Context, id: Int, on: Boolean) =
        save(context, load(context).map { if (it.id == id) it.copy(underground = on) else it })

    // [시연용] T6: 집(지하·평균 13시간·실내 +6°C) / 회사(지상·평균 9시간) 프로필 만들기 (지금 위치 근처 가짜 좌표)
    fun addDemo(context: Context, lat: Double, lon: Double): List<ParkPlace> {
        val now = System.currentTimeMillis()
        val day = 24L * 3_600_000
        val home = ParkPlace(901, "집", true, lat + 0.02, lon, true, PLACE_UNDERGROUND_BONUS_C, 20, 260.0, 18, 0,
            listOf(now - 9 * day))
        val office = ParkPlace(902, "회사", true, lat - 0.02, lon, false, PLACE_UNDERGROUND_BONUS_C, 15, 135.0, 0, 14,
            listOf(now - 2 * day, now - 5 * day, now - 12 * day))
        save(context, load(context).filter { it.id != 901 && it.id != 902 } + home + office)
        Log.d("BatteryAlert", "Y4 시연: 집(지하 13시간)·회사(지상 9시간) 프로필 만듦")
        return load(context)
    }

    fun setCurrentDemo(context: Context, id: Int) = setCurrent(context, id, System.currentTimeMillis())

    fun clear(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
}

// ───── Z4 12V 관리 점수 기록 (경고·조치, 회복 처방·주행, 주별 점수) ─────
object ScoreLog {
    private const val PREF = "care_score"
    private const val WEEK_MS = 7L * 24 * 3_600_000L

    data class Warn(val time: Long, val acted: Boolean)
    data class Recovery(val time: Long, val needMin: Int)

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun loadPairs(context: Context, key: String): List<Pair<Long, Double>> {
        val raw = prefs(context).getString(key, "") ?: ""
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|"); runCatching { p[0].toLong() to p[1].toDouble() }.getOrNull()
        }
    }

    private fun savePairs(context: Context, key: String, list: List<Pair<Long, Double>>) {
        val cutoff = System.currentTimeMillis() - 70L * 24 * 3_600_000L     // 10주만 보관
        prefs(context).edit().putString(key, list.filter { it.first >= cutoff }.takeLast(300)
            .joinToString("\n") { "${it.first}|${it.second}" }).apply()
    }

    // 경고 (acted: 0/1)
    fun warns(context: Context): List<Warn> = loadPairs(context, "warns").map { Warn(it.first, it.second > 0.5) }

    fun addWarn(context: Context, reason: String) {
        savePairs(context, "warns", loadPairs(context, "warns") + (System.currentTimeMillis() to 0.0))
        Log.d("BatteryAlert", "Z4 경고 기록: $reason")
    }

    // 사용자가 조치하면: 30분 안에 받은 경고 중 아직 조치 안 된 것을 "조치함"으로
    fun markActed(context: Context, what: String) {
        val now = System.currentTimeMillis()
        val list = loadPairs(context, "warns")
        val idx = list.indexOfLast { it.second < 0.5 && now - it.first <= SCORE_RESPONSE_MIN * 60_000L }
        if (idx < 0) return
        savePairs(context, "warns", list.mapIndexed { i, p -> if (i == idx) p.first to 1.0 else p })
        Log.d("BatteryAlert", "Z4 조치 기록: $what (경고 ${(now - list[idx].first) / 60_000}분 뒤)")
    }

    fun recoveries(context: Context): List<Recovery> = loadPairs(context, "recover").map { Recovery(it.first, it.second.toInt()) }

    fun addRecovery(context: Context, needMin: Int) {
        if (needMin <= 0) return
        savePairs(context, "recover", loadPairs(context, "recover") + (System.currentTimeMillis() to needMin.toDouble()))
        Log.d("BatteryAlert", "Z4 회복 주행 처방 기록: ${needMin}분")
    }

    fun addDrive(context: Context, minutes: Double) {
        if (minutes < 1) return
        savePairs(context, "drives", loadPairs(context, "drives") + (System.currentTimeMillis() to minutes))
        Log.d("BatteryAlert", "Z4 주행 기록: ${"%.0f".format(minutes)}분")
    }

    // 이번 주 입력값: 경고 수, 조치 수, (처방 분, 처방 뒤 18시간 안에 달린 분)
    fun weekInputs(context: Context, now: Long = System.currentTimeMillis()): Triple<Int, Int, List<Pair<Int, Int>>> {
        val w = warns(context).filter { it.time >= now - WEEK_MS }
        val drives = loadPairs(context, "drives")
        val rec = recoveries(context).filter { it.time >= now - WEEK_MS }.map { r ->
            val done = drives.filter { it.first in r.time..(r.time + 18 * 3_600_000L) }.sumOf { it.second }
            r.needMin to Math.round(done).toInt()
        }
        return Triple(w.size, w.count { it.acted }, rec)
    }

    // 주별 점수 (8주 추이)
    fun weeks(context: Context): List<Pair<Long, Int>> = loadPairs(context, "weeks").map { it.first to it.second.toInt() }

    fun saveWeek(context: Context, week: Long, score: Int) {
        val list = loadPairs(context, "weeks").filter { it.first != week } + (week to score.toDouble())
        savePairs(context, "weeks", list.sortedBy { it.first })
    }

    // [시연용] 최저 여유 덮어쓰기 (NaN = 안 씀)
    fun demoMargin(context: Context): Double? = prefs(context).getFloat("demo_margin", Float.NaN).toDouble().takeIf { !it.isNaN() }

    // [시연용] T10: 이번 주 경고 3번(조치 2번) + 최저 여유 10%p + 지난 7주 점수
    fun addDemoWeek(context: Context) {
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        savePairs(context, "warns", listOf((now - 50 * h) to 1.0, (now - 30 * h) to 1.0, (now - 5 * h) to 0.0))
        savePairs(context, "recover", emptyList())
        prefs(context).edit().putFloat("demo_margin", 10f).apply()
        val thisWeek = weekStart(now)
        val past = listOf(58, 61, 64, 66, 70, 69, 72)
        savePairs(context, "weeks", past.mapIndexed { i, sc -> (thisWeek - (7 - i) * WEEK_MS) to sc.toDouble() })
        Log.d("BatteryAlert", "Z4 시연: 경고 3 (조치 2), 최저 여유 10%p, 지난 7주 점수 $past")
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}