package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.model.legacy.VehicleSignal

/**
 * 말해도 되는 순간에만 음성을 내보낸다.
 * - 회전 안내 지점이 가까우면(기본 300m) 미룬다.
 * - 브레이크를 밟고 있거나, 최근 3초 안에 핸들을 크게 움직였으면 미룬다.
 * - 오래 밀린 중계(기본 60초)는 의미가 없으므로 버린다.
 */
class SpeechScheduler(
    private val speak: (String) -> Unit,
    private val quietNearTurnM: Int = 300,
    private val steerCalmMs: Long = 3_000,
    private val bigSteerDeg: Float = 45f,
    private val maxAgeMs: Long = 60_000,
) {
    enum class Priority { HIGH, NORMAL }

    private data class Item(val text: String, val priority: Priority, val queuedAtMs: Long)

    private val queue = ArrayDeque<Item>()
    private var turnDistanceM: Int? = null
    private var braking = false
    private var lastBigSteerMs = Long.MIN_VALUE / 2
    private var lastSteerDeg = 0f

    fun enqueue(text: String, nowMs: Long, priority: Priority = Priority.NORMAL) {
        val item = Item(text, priority, nowMs)
        if (priority == Priority.HIGH) queue.addFirst(item) else queue.addLast(item)
        tryFlush(nowMs)
    }

    fun onSignal(signal: VehicleSignal) {
        when (signal) {
            is VehicleSignal.TurnGuidance -> turnDistanceM = signal.distanceM
            is VehicleSignal.BrakePedal -> braking = signal.pressed
            is VehicleSignal.SteeringAngle -> {
                if (kotlin.math.abs(signal.degrees - lastSteerDeg) >= bigSteerDeg ||
                    kotlin.math.abs(signal.degrees) >= bigSteerDeg
                ) lastBigSteerMs = signal.timeMs
                lastSteerDeg = signal.degrees
            }
            else -> Unit
        }
        tryFlush(signal.timeMs)
    }

    fun canSpeak(nowMs: Long): Boolean {
        val nearTurn = turnDistanceM?.let { it < quietNearTurnM } ?: false
        val steering = nowMs - lastBigSteerMs < steerCalmMs
        return !nearTurn && !braking && !steering
    }

    private fun tryFlush(nowMs: Long) {
        queue.removeAll { nowMs - it.queuedAtMs > maxAgeMs }
        while (queue.isNotEmpty() && canSpeak(nowMs)) {
            speak(queue.removeFirst().text)
        }
    }

    val pending: Int get() = queue.size
}
