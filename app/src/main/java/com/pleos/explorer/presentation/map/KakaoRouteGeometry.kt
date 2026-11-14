package com.pleos.explorer.presentation.map

import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.PathSegment

internal fun drawableRouteSegments(segments: List<PathSegment>): List<List<GeoPoint>> =
    segments.filter { it.points.size >= 2 }.map { it.points }
