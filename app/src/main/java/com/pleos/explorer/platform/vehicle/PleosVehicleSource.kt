package com.pleos.explorer.platform.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import com.pleos.explorer.platform.location.PleosLocationSubscription
import com.pleos.explorer.domain.model.legacy.GearState
import com.pleos.explorer.domain.model.legacy.TurnState
import com.pleos.explorer.domain.model.legacy.VehicleSignal
import kotlin.math.abs

/** SDK 콜백을 주행 서비스와 같은 메인 스레드·단조 시계로 전달한다. */
class PleosVehicleSource(
    private val context: Context,
    private val onStatus: (String, String) -> Unit = { _, _ -> },
) : VehicleSignalSource {
    private val handler = Handler(Looper.getMainLooper())
    private var emit: ((VehicleSignal) -> Unit)? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var location: LocationData? = null
    private var locationAt = 0L
    private var speedMps: Float? = null
    private var speedAt = 0L
    private var fuelSubscribed = false
    private var evSubscribed = false
    private var naviConnected = false

    private fun now() = SystemClock.elapsedRealtime()
    private fun dispatch(block: () -> Unit) {
        handler.post { if (emit != null) block() }
    }
    private fun status(channel: String, message: String) = dispatch { onStatus(channel, message) }
    private fun failure(channel: String, error: Exception) = status(channel, "오류: ${error.message ?: error.javaClass.simpleName}")
    private fun attempt(channel: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { failure(channel, e) }
    }

    private val steering = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) = dispatch {
            value?.takeIf { it.isFinite() }?.let {
                emit?.invoke(VehicleSignal.SteeringAngle(it, now()))
                onStatus("핸들", "신호 수신")
            }
        }
        override fun onFailed(exception: Exception) = failure("핸들", exception)
    }
    private val brake = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) = dispatch {
            value?.let { emit?.invoke(VehicleSignal.BrakePedal(it, now())); onStatus("브레이크", "신호 수신") }
        }
        override fun onFailed(exception: Exception) = failure("브레이크", exception)
    }
    private val turn = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = dispatch {
            val state = when (value) {
                TurnSignalValue.LEFT -> TurnState.LEFT
                TurnSignalValue.RIGHT -> TurnState.RIGHT
                TurnSignalValue.OFF -> TurnState.OFF
                null -> return@dispatch
            }
            emit?.invoke(VehicleSignal.TurnSignal(state, now()))
            onStatus("방향지시등", "신호 수신")
        }
        override fun onFailed(exception: Exception) = failure("방향지시등", exception)
    }
    private val gear = object : GearSelectionListener {
        override fun onSelected(value: VehicleGear?) = dispatch {
            val state = when (value) {
                VehicleGear.GEAR_PARK -> GearState.PARK
                VehicleGear.GEAR_REVERSE -> GearState.REVERSE
                VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
                VehicleGear.GEAR_UNKNOWN, null -> GearState.UNKNOWN
                else -> GearState.DRIVE
            }
            onStatus("기어", state.name)
            emit?.invoke(VehicleSignal.Gear(state, now()))
        }
        override fun onFailed(exception: Exception) = failure("기어", exception)
    }
    private val fuel = object : FuelEconomyListener {
        override fun onFuelEconomyUpdated(value: Float?) = efficiency(value)
        override fun onFailed(exception: Exception) = failure("효율", exception)
    }
    private val ev = object : EvBatteryEfficiencyListener {
        override fun onEvBatteryEfficiencyUpdated(value: Float?) = efficiency(value)
        override fun onFailed(exception: Exception) = failure("효율", exception)
    }
    private fun efficiency(value: Float?) = dispatch {
        value?.takeIf { it.isFinite() && it > 0 }?.let { emit?.invoke(VehicleSignal.Efficiency(it, now())) }
    }
    private val position = PleosLocationSubscription(
        context = context,
        onLocation = { value -> dispatch {
            if (value == null || !value.latitude.isFinite() || !value.longitude.isFinite() ||
                value.latitude !in -90.0..90.0 || value.longitude !in -180.0..180.0) return@dispatch
            location = value
            locationAt = now()
            onStatus("위치", "신호 수신")
            emitPosition()
        } },
        onError = { exception -> dispatch {
            location = null
            failure("위치", exception)
        } },
    )
    private fun emitPosition() {
        val loc = location ?: return
        val speed = speedMps ?: return
        if (now() - speedAt > 3_000 || now() - locationAt > 5_000) return
        emit?.invoke(VehicleSignal.Location(loc.latitude, loc.longitude, speed, loc.heading, now()))
    }
    private val speed = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = dispatch {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                speedMps = null
                onStatus("속도", "신호 사용 불가")
                return@dispatch
            }
            val v = (value.value as? Number)?.toFloat() ?: return@dispatch
            if (!v.isFinite()) return@dispatch
            speedMps = abs(v)
            speedAt = now()
            onStatus("속도", "신호 수신")
            emitPosition()
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = dispatch {
            speedMps = null
            onStatus("속도", "신호 오류")
        }
    }
    private val navigation = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfo: List<TBTInfo>) = dispatch {
            val distance = tbtInfo.map { it.distance }.filter { it.isFinite() && it >= 0 }.minOrNull()
            emit?.invoke(VehicleSignal.TurnGuidance(distance?.toInt(), now()))
            onStatus("내비", "안내 수신")
        }
        override fun onRouteCancelled() = clearGuidance()
        override fun onRouteEnded() = clearGuidance()
        override fun onError(errorCode: NaviErrorCode) = status("내비", "오류: $errorCode")
    }
    private fun clearGuidance() = dispatch { emit?.invoke(VehicleSignal.TurnGuidance(null, now())) }
    private val pollGuidance = object : Runnable {
        override fun run() {
            if (emit == null || !naviConnected) return
            attempt("내비") { navi?.getTBTInfo() }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun start(onSignal: (VehicleSignal) -> Unit) {
        check(emit == null) { "이미 주행 중입니다" }
        emit = onSignal
        attempt("Vehicle") {
            val v = Vehicle(context.applicationContext).also { vehicle = it }
            v.initialize()
            attempt("핸들") { v.getSteeringWheel().registerSteeringWheelAngle(steering) }
            attempt("브레이크") { v.getBrake().registerBrakePedalStatus(brake) }
            attempt("방향지시등") { v.getTurnSignal().registerTurnSignalState(turn) }
            attempt("기어") { v.getGear().registerGearSelection(gear) }
            // 연비와 전비를 같은 기록에 섞지 않고 읽기 가능한 한 종류만 선택한다.
            v.getFuel().checkInstantaneousFuelEconomyCapabilities({ config -> dispatch {
                if (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) {
                    attempt("효율") { v.getFuel().registerInstantaneousFuelEconomy(fuel); fuelSubscribed = true }
                } else subscribeEv(v)
            } }, { dispatch { subscribeEv(v) } })
        }
        attempt("위치") { position.start() }
        attempt("속도") {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready -> dispatch {
                if (!ready) {
                    speedMps = null
                    onStatus("속도", "차량 서비스 연결 끊김")
                } else attempt("속도") {
                    val manager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    properties = manager
                    check(manager.registerCallback(speed, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)) {
                        "차량 속도 구독이 지원되지 않습니다"
                    }
                }
            } }
        }
        attempt("내비") {
            val n = NaviHelper(context.applicationContext).also { navi = it }
            n.initialize({ dispatch {
                n.addListener(navigation)
                naviConnected = true
                handler.post(pollGuidance)
                onStatus("내비", "연결됨 · 안내 대기")
            } }, { status("내비", "연결 실패: ${it.message}") })
        }
    }
    private fun subscribeEv(v: Vehicle) = attempt("효율") {
        v.getEvBattery().checkInstantaneousEvEfficiencyCapabilities({ config -> dispatch {
            if (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) {
                attempt("효율") { v.getEvBattery().registerInstantaneousEvEfficiency(ev); evSubscribed = true }
            } else onStatus("효율", "지원되지 않음")
        } }, { failure("효율", it) })
    }

    override fun stop() {
        emit = null
        handler.removeCallbacksAndMessages(null)
        naviConnected = false
        properties?.let { runCatching { it.unregisterCallback(speed) } }
        car?.let { runCatching { it.disconnect() } }
        position.stop()
        navi?.let { n ->
            runCatching { n.removeListener(navigation) }
            runCatching { n.release() }
        }
        vehicle?.let { v ->
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steering) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(brake) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(turn) }
            runCatching { v.getGear().unregisterGearSelection(gear) }
            if (fuelSubscribed) runCatching { v.getFuel().unregisterInstantaneousFuelEconomy(fuel) }
            if (evSubscribed) runCatching { v.getEvBattery().unregisterInstantaneousEvEfficiency(ev) }
            runCatching { v.release() }
        }
        vehicle = null; navi = null; car = null; properties = null
        location = null; speedMps = null
    }
}
