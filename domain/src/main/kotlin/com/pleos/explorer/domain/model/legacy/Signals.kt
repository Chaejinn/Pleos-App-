package com.pleos.explorer.domain.model.legacy

/**
 * 차량·내비·위치에서 들어오는 신호를 SDK와 무관한 형태로 표현한다.
 * Pleos SDK 콜백은 app 모듈의 어댑터에서 이 타입으로 변환해 넘긴다.
 * 모든 시각(timeMs)은 단조 증가하는 밀리초 값이다.
 */
sealed interface VehicleSignal {
    val timeMs: Long

    /** 핸들 각도(도). 왼쪽이 음수, 오른쪽이 양수. */
    data class SteeringAngle(val degrees: Float, override val timeMs: Long) : VehicleSignal

    /** 브레이크 페달 밟힘 여부. */
    data class BrakePedal(val pressed: Boolean, override val timeMs: Long) : VehicleSignal

    data class TurnSignal(val state: TurnState, override val timeMs: Long) : VehicleSignal

    /** 위치. speedMps는 FusedLocation 또는 차량 속도 속성에서 채운다. */
    data class Location(
        val lat: Double,
        val lon: Double,
        val speedMps: Float,
        val bearingDeg: Float,
        override val timeMs: Long,
    ) : VehicleSignal

    /** 순간 연비(km/L) 또는 전비(km/kWh). 값의 단위는 차종에 따라 다르므로 같은 차 안에서만 비교한다. */
    data class Efficiency(val value: Float, override val timeMs: Long) : VehicleSignal

    data class Gear(val gear: GearState, override val timeMs: Long) : VehicleSignal

    /** 다음 회전 안내 지점까지 남은 거리(m). 안내가 없으면 null. */
    data class TurnGuidance(val distanceM: Int?, override val timeMs: Long) : VehicleSignal
}

enum class TurnState { OFF, LEFT, RIGHT, HAZARD }

enum class GearState { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 주행 중 감지되는 운전 사건. 고스트 비교의 기본 단위. */
sealed interface DrivingEvent {
    val timeMs: Long

    data class HarshBrake(val decelMps2: Float, override val timeMs: Long) : DrivingEvent
    data class HarshSteer(val degPerSec: Float, override val timeMs: Long) : DrivingEvent
    data class TurnWithoutSignal(override val timeMs: Long) : DrivingEvent
}
