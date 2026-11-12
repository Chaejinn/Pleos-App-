package com.pleos.explorer.platform.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {
    @Test fun disabledConsentAndParkedNeverSpeak() {
        val gate = SpeechGate()
        assertFalse(gate.allow(0, true, false, true))
        assertFalse(gate.allow(0, false, true, true))
        assertFalse(gate.allow(0, true, true, false))
        assertTrue(gate.allow(0, true, true, true))
    }

    @Test fun minimumIntervalIncludesExactBoundary() {
        val gate = SpeechGate()
        assertTrue(gate.allow(100, true, true, true))
        assertFalse(gate.allow(60_099, true, true, true))
        assertTrue(gate.allow(60_100, true, true, true))
    }

    @Test fun backwardsClockDoesNotEnableSpeech() {
        val gate = SpeechGate()
        assertTrue(gate.allow(1_000, true, true, true))
        assertFalse(gate.allow(900, true, true, true))
    }

    @Test fun disabledRequestsDoNotConsumeNextInterval() {
        val gate = SpeechGate()
        assertFalse(gate.allow(50_000, true, false, true))
        assertTrue(gate.allow(50_001, true, true, true))
    }
}
