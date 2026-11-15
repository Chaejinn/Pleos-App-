package com.pleos.explorer.platform.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.pleos.explorer.domain.model.Gear

internal fun mapPleosTripGear(value: VehicleGear?): Gear? = when (value) {
    VehicleGear.GEAR_PARK -> Gear.P
    VehicleGear.GEAR_REVERSE -> Gear.R
    VehicleGear.GEAR_NEUTRAL -> Gear.N
    VehicleGear.GEAR_DRIVE -> Gear.D
    VehicleGear.GEAR_UNKNOWN, null -> null
    else -> Gear.OTHER
}
