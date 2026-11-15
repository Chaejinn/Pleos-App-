package com.pleos.explorer.platform.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.pleos.explorer.domain.engine.TripStateMachine
import com.pleos.explorer.domain.model.*
import com.pleos.explorer.domain.port.IdGenerator
import org.junit.Assert.*
import org.junit.Test

class PleosTripSignalMapperTest {
    @Test fun unknownAndNumberedGearsCannotInventDriveTransition() {
        val machine = TripStateMachine(IdGenerator { "trip" })
        val initial = TripLifecycleSnapshot("session",recordingConsentGranted=true,locationPermissionGranted=true)
        for (raw in listOf(null,VehicleGear.GEAR_UNKNOWN,VehicleGear.GEAR_1,VehicleGear.GEAR_9)) {
            val mapped = mapPleosTripGear(raw)
            val update = machine.process(initial,TripInput.Vehicle(TripVehicleSample(1000,1000,gear=mapped)),0)
            assertTrue(update.transitions.isEmpty())
        }
        assertNull(mapPleosTripGear(null))
        assertNull(mapPleosTripGear(VehicleGear.GEAR_UNKNOWN))
        val drive = machine.process(initial,TripInput.Vehicle(TripVehicleSample(1000,1000,gear=mapPleosTripGear(VehicleGear.GEAR_DRIVE))),0)
        assertTrue(drive.transitions.single() is TripTransition.Started)
    }
}
