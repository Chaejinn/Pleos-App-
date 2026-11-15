package com.pleos.explorer.platform.vehicle

import android.os.Looper
import com.pleos.explorer.domain.model.legacy.GearState
import com.pleos.explorer.domain.model.legacy.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DemoSignalSourceTest {
    @Test fun pauseStopAndRestartDoNotSkipOrDuplicateReplay() {
        val source = DemoSignalSource(false)
        val signals = mutableListOf<VehicleSignal>()
        val clock = shadowOf(Looper.getMainLooper())
        source.start(signals::add)
        clock.idleFor(Duration.ofSeconds(1))
        source.paused = true
        val count = signals.size
        clock.idleFor(Duration.ofSeconds(2))
        assertEquals(count, signals.size)
        source.paused = false
        clock.idleFor(Duration.ofSeconds(1))
        assertTrue(signals.size > count)
        source.stop()
        val stopped = signals.size
        clock.idleFor(Duration.ofSeconds(2))
        assertEquals(stopped, signals.size)
        signals.clear()
        source.start(signals::add)
        clock.idleFor(Duration.ofSeconds(73))
        val locations = signals.filterIsInstance<VehicleSignal.Location>()
        assertEquals(721, locations.size)
        assertEquals(0L, locations.first().timeMs)
        assertEquals(288_000L, locations.last().timeMs)
        assertEquals(GearState.PARK, (signals.last() as VehicleSignal.Gear).gear)
    }
}
