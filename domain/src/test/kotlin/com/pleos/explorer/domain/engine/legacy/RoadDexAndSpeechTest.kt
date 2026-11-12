package com.pleos.explorer.domain.engine.legacy

import com.pleos.explorer.domain.model.legacy.DexEvent
import com.pleos.explorer.domain.model.legacy.Landmark
import com.pleos.explorer.domain.model.legacy.LandmarkType
import com.pleos.explorer.domain.model.legacy.VehicleSignal
import com.pleos.explorer.domain.port.legacy.InMemoryDexStore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadDexAndSpeechTest {

    private val bridge = Landmark("gwangan", "광안대교", LandmarkType.BRIDGE, "부산", 35.1475, 129.1300)

    @Test
    fun firstPassCollectsThenRevisits() {
        val dex = RoadDex(listOf(bridge), InMemoryDexStore())
        assertNull(dex.onLocation(35.10, 129.10, 0))
        assertTrue(dex.onLocation(35.1476, 129.1301, 1) is DexEvent.Collected)
        assertNull(dex.onLocation(35.1477, 129.1302, 2)) // 아직 안에 있으면 다시 알리지 않음
        dex.onLocation(35.10, 129.10, 3)
        assertTrue(dex.onLocation(35.1476, 129.1301, 4) is DexEvent.Revisited)
        assertEquals(100, dex.completion("부산"))
    }

    @Test
    fun speechWaitsNearTurnAndWhileBraking() {
        val spoken = mutableListOf<String>()
        val s = SpeechScheduler({ spoken += it })

        s.onSignal(VehicleSignal.TurnGuidance(150, 0))
        s.enqueue("중계", 0)
        assertEquals(0, spoken.size)

        s.onSignal(VehicleSignal.TurnGuidance(800, 10_000))
        assertEquals(listOf("중계"), spoken)

        s.onSignal(VehicleSignal.BrakePedal(true, 11_000))
        s.enqueue("두 번째", 11_000)
        assertEquals(1, spoken.size)
        s.onSignal(VehicleSignal.BrakePedal(false, 12_000))
        assertEquals(2, spoken.size)
    }

    @Test
    fun staleMessagesDropped() {
        val spoken = mutableListOf<String>()
        val s = SpeechScheduler({ spoken += it })
        s.onSignal(VehicleSignal.BrakePedal(true, 0))
        s.enqueue("오래된 중계", 0)
        s.onSignal(VehicleSignal.BrakePedal(false, 120_000))
        assertTrue(spoken.isEmpty())
        assertEquals(0, s.pending)
    }
}
