package com.pleos.explorer.platform.ux

import org.junit.Assert.*
import org.junit.Test

class UxAccessGateTest {
    private val parked = UxAccessState(UxAvailability.AVAILABLE, false, 0)
    private val driving = UxAccessState(UxAvailability.AVAILABLE, true, 1)

    @Test fun missingAndPartialStateNeverUnlockDetails() {
        assertFalse(UxAccessState().canShowDetails)
        assertFalse(UxAccessState(UxAvailability.CONNECTING).canShowDetails)
        assertFalse(UxAccessState(UxAvailability.UNAVAILABLE).canShowDetails)
        assertFalse(UxAccessState(UxAvailability.AVAILABLE, false, null).canShowDetails)
        assertFalse(UxAccessState(UxAvailability.AVAILABLE, null, 0).canShowDetails)
    }
    @Test fun optimizationOrAnyActiveRestrictionKeepsDetailedUiClosed() {
        assertTrue(parked.canShowDetails)
        assertFalse(driving.canShowDetails)
        assertFalse(UxAccessState(UxAvailability.AVAILABLE, true, 0).canShowDetails)
        assertFalse(UxAccessState(UxAvailability.AVAILABLE, false, 1).canShowDetails)
    }
    @Test fun stoppedActivityCannotUnlockUiThroughLateCallback() {
        val gate = UxAccessGate()
        val source = FakeSource()
        val session = UxObservationSession(gate, source)
        session.start()
        source.emit(parked)
        assertTrue(gate.state.value.canShowDetails)
        session.stop()
        source.emit(parked)
        assertFalse(gate.state.value.canShowDetails)
        assertEquals(UxAvailability.INACTIVE, gate.state.value.availability)
    }
    @Test fun replacedActivityIgnoresOldCallbacksAndOldStop() {
        val gate = UxAccessGate()
        val oldSource = FakeSource()
        val currentSource = FakeSource()
        val old = UxObservationSession(gate, oldSource)
        val current = UxObservationSession(gate, currentSource)
        old.start()
        oldSource.emit(parked)
        current.start()
        assertFalse(gate.state.value.canShowDetails)
        currentSource.emit(driving)
        oldSource.emit(parked)
        old.stop()
        assertEquals(driving, gate.state.value)
        currentSource.emit(parked)
        assertTrue(gate.state.value.canShowDetails)
    }
    @Test fun connectionLossAndRestartRequireANewConfirmedState() {
        val gate = UxAccessGate()
        val source = FakeSource()
        val session = UxObservationSession(gate, source)
        session.start()
        source.emit(parked)
        source.emit(UxAccessState(UxAvailability.UNAVAILABLE))
        assertFalse(gate.state.value.canShowDetails)
        session.stop()
        session.start()
        assertFalse(gate.state.value.canShowDetails)
        source.emit(parked)
        assertTrue(gate.state.value.canShowDetails)
    }
    @Test fun failedSubscriptionIsClosedAndLateCallbacksAreIgnored() {
        val gate = UxAccessGate()
        val source = FakeSource(failStart = true)
        UxObservationSession(gate, source).start()
        assertEquals(UxAvailability.UNAVAILABLE, gate.state.value.availability)
        assertEquals(1, source.stops)
        source.emit(parked)
        assertFalse(gate.state.value.canShowDetails)
    }
    @Test fun repeatedLifecycleCallsDoNotDuplicateSubscriptions() {
        val gate = UxAccessGate()
        val source = FakeSource()
        val session = UxObservationSession(gate, source)
        session.start(); session.start()
        assertEquals(1, source.starts)
        session.stop(); session.stop()
        assertEquals(1, source.stops)
    }
    @Test fun phoneDemoSourceWorksWithoutLoadingCarApis() {
        val gate = UxAccessGate()
        val session = UxObservationSession(gate, NonAutomotiveUxSource())
        session.start()
        assertTrue(gate.state.value.canShowDetails)
        assertEquals(UxAvailability.NON_AUTOMOTIVE, gate.state.value.availability)
        session.stop()
        assertFalse(gate.state.value.canShowDetails)
    }

    private class FakeSource(private val failStart: Boolean = false) : UxRestrictionSource {
        var starts = 0
        var stops = 0
        private lateinit var callback: (UxAccessState) -> Unit
        override fun start(onChanged: (UxAccessState) -> Unit) {
            starts++
            callback = onChanged
            if (failStart) throw IllegalStateException("car service unavailable")
        }
        override fun stop() { stops++ }
        fun emit(state: UxAccessState) = callback(state)
    }
}
