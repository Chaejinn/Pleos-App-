package com.pleos.explorer.domain.model

import kotlin.math.abs

enum class TurnSignal { OFF, LEFT, RIGHT, HAZARD }

enum class TurnDirection { LEFT, RIGHT }

sealed interface TurnSignalInput {
    data class Angle(val observation: Observation<Double?>) : TurnSignalInput
    data class Signal(val observation: Observation<TurnSignal?>) : TurnSignalInput
}

data class TurnSignalUsage(
    val turnCount: Int = 0,
    val signaledTurnCount: Int = 0,
    val unknownTurnCount: Int = 0,
    val hasUnobservedSteering: Boolean = false,
) {
    init {
        require(turnCount >= 0 && signaledTurnCount >= 0 && unknownTurnCount >= 0)
        require(unknownTurnCount <= turnCount && signaledTurnCount <= turnCount - unknownTurnCount)
    }

    val rate: Double?
        get() = if (turnCount == 0 || unknownTurnCount > 0 || hasUnobservedSteering) null
            else signaledTurnCount.toDouble() / turnCount

    fun record(signaled: Boolean?): TurnSignalUsage = TurnSignalUsage(
        Math.addExact(turnCount, 1),
        Math.addExact(signaledTurnCount, if (signaled == true) 1 else 0),
        Math.addExact(unknownTurnCount, if (signaled == null) 1 else 0),
        hasUnobservedSteering,
    )
}

data class TurnSignalState(
    val angle: Observation<Double?>? = null,
    val armed: Boolean = false,
    val signal: Observation<TurnSignal?>? = null,
    val coverageStartMs: Long? = null,
    val lastLeftMs: Long? = null,
    val lastRightMs: Long? = null,
    val usage: TurnSignalUsage = TurnSignalUsage(),
) {
    init {
        require(angle == null || angle.observedAtElapsedMs >= 0)
        require(angle?.value?.isFinite() != false)
        require(!armed || angle?.value?.let { abs(it) < DetectionPolicy.TURN_ANGLE_DEG } == true)
        val signalAt = signal?.observedAtElapsedMs
        require(signalAt == null || signalAt >= 0)
        require((signal?.value == null) == (coverageStartMs == null))
        require((signal?.value == TurnSignal.LEFT) == (signalAt != null && lastLeftMs == signalAt))
        require((signal?.value == TurnSignal.RIGHT) == (signalAt != null && lastRightMs == signalAt))
        require(coverageStartMs == null || signalAt != null && coverageStartMs >= 0 && coverageStartMs <= signalAt)
        require(listOfNotNull(lastLeftMs, lastRightMs).all {
            coverageStartMs != null && signalAt != null && it >= coverageStartMs && it <= signalAt
        })
    }
}

data class TurnSignalTurn(val direction: TurnDirection, val signaled: Boolean?, val elapsedMs: Long)

data class TurnSignalUpdate(val state: TurnSignalState, val turn: TurnSignalTurn? = null)
