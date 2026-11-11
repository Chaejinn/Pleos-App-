package com.pleos.explorer.domain.model.legacy

enum class LandmarkType { BRIDGE, TUNNEL, PASS, COASTAL_ROAD, REST_AREA }

/** 도감에 실리는 도로 명소. radiusM 안에 들어오면 지나간 것으로 본다. */
data class Landmark(
    val id: String,
    val name: String,
    val type: LandmarkType,
    val region: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = 300.0,
    val story: String? = null,
)
