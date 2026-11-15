package com.pleos.explorer.presentation.archive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pleos.explorer.domain.model.PathSegment
import com.pleos.explorer.presentation.map.drawableRouteSegments
import kotlin.math.cos

@Composable
internal fun GhostPathPreview(past: List<PathSegment>, current: List<PathSegment>) {
    val old = drawableRouteSegments(past)
    val now = drawableRouteSegments(current)
    val points = (old + now).flatten()
    if (points.isEmpty()) return
    val minLat = points.minOf { it.latitudeDeg }; val maxLat = points.maxOf { it.latitudeDeg }
    val minLon = points.minOf { it.longitudeDeg }; val maxLon = points.maxOf { it.longitudeDeg }
    val longitudeScale = cos(Math.toRadians((minLat+maxLat)/2)).coerceAtLeast(0.01)
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val dx = ((maxLon-minLon)*longitudeScale).coerceAtLeast(0.000001)
        val dy = (maxLat-minLat).coerceAtLeast(0.000001)
        val scale = minOf((size.width-32.dp.toPx())/dx,(size.height-32.dp.toPx())/dy)
        val left = (size.width-dx*scale)/2
        val top = (size.height-dy*scale)/2
        fun draw(segments: List<List<com.pleos.explorer.domain.model.GeoPoint>>,color: Color,width: Float) {
            segments.forEach { segment ->
                val path = Path()
                segment.forEachIndexed { index,point ->
                    val x=(left+(point.longitudeDeg-minLon)*longitudeScale*scale).toFloat()
                    val y=(top+(maxLat-point.latitudeDeg)*scale).toFloat()
                    if (index==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                drawPath(path,color,style=Stroke(width=width))
            }
        }
        draw(old,Color(0xFFA5B4CC),10.dp.toPx())
        draw(now,Color(0xFF6750A4),4.dp.toPx())
    }
}
