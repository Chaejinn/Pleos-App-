package com.pleos.explorer.platform.ux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UX access is independent of whether the app's recording service is running. */
enum class UxAvailability { CONNECTING, AVAILABLE, UNAVAILABLE, NON_AUTOMOTIVE, INACTIVE }

data class UxAccessState(
    val availability: UxAvailability = UxAvailability.INACTIVE,
    val requiresOptimization: Boolean? = null,
    val activeRestrictions: Int? = null,
) {
    val canShowDetails: Boolean get() = availability == UxAvailability.NON_AUTOMOTIVE ||
        (availability == UxAvailability.AVAILABLE && requiresOptimization == false && activeRestrictions == 0)
}

/** A stopped or replaced Activity cannot unlock the UI through late callbacks. */
class UxAccessGate {
    private var generation = 0L
    private val mutableState = MutableStateFlow(UxAccessState())
    val state = mutableState.asStateFlow()

    @Synchronized fun begin(): Long {
        generation++
        mutableState.value = UxAccessState(UxAvailability.CONNECTING)
        return generation
    }
    @Synchronized fun update(session: Long, value: UxAccessState) {
        if (session == generation) mutableState.value = value
    }
    @Synchronized fun end(session: Long) {
        if (session != generation) return
        generation++
        mutableState.value = UxAccessState()
    }
    @Synchronized fun fail(session: Long) {
        if (session != generation) return
        generation++
        mutableState.value = UxAccessState(UxAvailability.UNAVAILABLE)
    }
}

interface UxRestrictionSource {
    fun start(onChanged: (UxAccessState) -> Unit)
    fun stop()
}

class NonAutomotiveUxSource : UxRestrictionSource {
    override fun start(onChanged: (UxAccessState) -> Unit) {
        onChanged(UxAccessState(UxAvailability.NON_AUTOMOTIVE))
    }
    override fun stop() = Unit
}

class UxObservationSession(private val gate: UxAccessGate, private val source: UxRestrictionSource) {
    private var session: Long? = null
    fun start() {
        if (session != null) return
        val token = gate.begin()
        session = token
        try { source.start { gate.update(token, it) } }
        catch (_: Exception) { failed(token) }
        catch (_: LinkageError) { failed(token) }
    }
    private fun failed(token: Long) {
        gate.fail(token)
        runCatching { source.stop() }
    }
    fun stop() {
        val token = session ?: return
        session = null
        gate.end(token)
        runCatching { source.stop() }
    }
}
