package com.pleos.explorer.demo

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pleos.explorer.platform.vehicle.DemoPoint
import com.pleos.explorer.platform.vehicle.SeoulDemoRoute
import kotlin.math.cos
import kotlin.math.min

/** Offline illustrated map. Route and car share the same geographic projection. */
@Composable
internal fun SeoulMap(state: DemoState) {
    Canvas(Modifier.fillMaxSize().semantics {
        contentDescription = "서울 종로 도로 시연 지도. 광화문, 국립민속박물관. 수집 ${state.collected.size}개"
    }) {
        val scale = min(size.width / .0125f, size.height / .0128f)
        fun project(lat: Double, lon: Double) = Offset(
            size.width * .49f + ((lon - 126.9767) * cos(Math.toRadians(37.578)) * scale).toFloat(),
            size.height * .50f - ((lat - 37.5778) * scale).toFloat(),
        )
        fun project(p: DemoPoint) = project(p.lat, p.lon)
        fun line(points: List<DemoPoint>): Path = Path().apply {
            points.forEachIndexed { i, p ->
                val xy = project(p)
                if (i == 0) moveTo(xy.x, xy.y) else lineTo(xy.x, xy.y)
            }
        }
        fun label(text: String, lat: Double, lon: Double, fontSize: Float = 11f,
            color: Color = Muted, bold: Boolean = false) {
            val p = project(lat, lon)
            drawContext.canvas.nativeCanvas.drawText(text, p.x, p.y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                textSize = fontSize.dp.toPx()
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            })
        }
        fun block(north: Double, west: Double, south: Double, east: Double, color: Color, radius: Float = 9f) {
            val a = project(north, west)
            val b = project(south, east)
            drawRoundRect(color, a, Size(b.x - a.x, b.y - a.y), CornerRadius(radius.dp.toPx()))
        }
        drawRect(Color(0xFFEFF1E9))
        // Context is deliberately illustrated; it is not a live navigation basemap.
        for (row in 0..6) for (col in 0..8) {
            val west = 126.965 + col * .0025
            val north = 37.586 - row * .0025
            block(north, west, north - .0019, west + .0018, Color(0xFFE4E8DF), 5f)
        }
        block(37.5837, 126.9728, 37.57605, 126.9791, Color(0xFFD7E2C9), 22f)
        block(37.5817, 126.9735, 37.5767, 126.9785, Color(0xFFE3E9D6), 13f)
        // Palace courtyards and gardens.
        for (row in 0..3) {
            val north = 37.5805 - row * .0009
            block(north, 126.9755, north - .00045, 126.9771, Color(0xFFC5D0B6), 2f)
        }
        drawCircle(Color(0xFFC0D4C7), 18.dp.toPx(), project(37.5806, 126.9742))
        label("경 복 궁", 37.5818, 126.9757, 16f, Color(0xFF6F8966), true)
        label("GYEONGBOKGUNG", 37.58115, 126.9757, 7f, Color(0xFF8C9E80))
        label("서촌", 37.5789, 126.9702, 13f)
        label("북촌 한옥마을", 37.5805, 126.9847, 12f)
        val sideRoads = listOf(
            listOf(DemoPoint(37.571, 126.972), DemoPoint(37.585, 126.972)),
            listOf(DemoPoint(37.572, 126.984), DemoPoint(37.584, 126.984)),
            listOf(DemoPoint(37.5748, 126.966), DemoPoint(37.5754, 126.974),
                DemoPoint(37.5752, 126.9772), DemoPoint(37.5758, 126.9803), DemoPoint(37.5753, 126.988)),
            listOf(DemoPoint(37.572, 126.966), DemoPoint(37.572, 126.990)),
            listOf(DemoPoint(37.5835, 126.969), DemoPoint(37.5835, 126.9803)),
        )
        sideRoads.forEach {
            drawPath(line(it), Color(0xFFD5DCD1), style = Stroke(15.dp.toPx(), cap = StrokeCap.Round))
            drawPath(line(it), Color(0xFFFAFBF6), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
        }
        val route = line(SeoulDemoRoute.points)
        drawPath(route, Color(0xFFD1D9CD), style = Stroke(20.dp.toPx(), cap = StrokeCap.Round))
        drawPath(route, Color.White, style = Stroke(17.dp.toPx(), cap = StrokeCap.Round))
        drawPath(route, Color(0xFF9CB7A7), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        val distance = state.fraction * SeoulDemoRoute.lengthM
        val travelled = SeoulDemoRoute.points.filterIndexed { i, _ -> SeoulDemoRoute.cumulativeM[i] <= distance } + state.point
        drawPath(line(travelled), Ink, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        label("사직로", 37.57475, 126.9809, 9f)
        label("삼청로", 37.57855, 126.9812, 9f)
        label("광화문광장", 37.5734, 126.9755, 10f)
        label("세종대로", 37.57245, 126.9791, 9f)
        SeoulDemoRoute.landmarks.forEachIndexed { index, badge ->
            val position = project(badge.lat, badge.lon)
            val collected = badge.id in state.collected
            drawCircle(Ink.copy(alpha = .08f), 24.dp.toPx(), position)
            drawCircle(Color.White, 17.dp.toPx(), position)
            drawCircle(if (collected) Accent else Ink, 13.dp.toPx(), position)
            drawContext.canvas.nativeCanvas.drawText(if (collected) "✓" else "${index + 1}", position.x,
                position.y + 4.dp.toPx(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (if (collected) Ink else Color.White).toArgb()
                    textSize = 13.dp.toPx(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                })
            label(badge.name, badge.lat - .00085, badge.lon - if (index == 0) .0013 else .002, 11f, Ink, true)
        }
        val car = project(state.point)
        drawCircle(Ink.copy(alpha = .10f), 29.dp.toPx(), car)
        drawCircle(Color.White, 20.dp.toPx(), car)
        drawCircle(Ink, 16.dp.toPx(), car)
        rotate(state.point.bearing, car) {
            val arrow = Path().apply {
                moveTo(car.x, car.y - 9.dp.toPx())
                lineTo(car.x + 7.dp.toPx(), car.y + 7.dp.toPx())
                lineTo(car.x, car.y + 3.dp.toPx())
                lineTo(car.x - 7.dp.toPx(), car.y + 7.dp.toPx()); close()
            }
            drawPath(arrow, Color.White)
        }
    }
}

@Composable
internal fun BadgeArt(index: Int, collected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = if (collected) "수집한 뱃지" else "미수집 뱃지" }) {
        val unit = size.minDimension / 100f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        val color = if (collected) Ink else Color(0xFF98A69A)
        drawCircle(if (collected) Color(0xFFF5DEAB) else Color(0xFFE9EDE5))
        drawCircle(color.copy(alpha = .25f), 43 * unit, style = Stroke(1.2f * unit))
        if (index == 0) {
            drawRoundRect(color, p(24f, 49f), Size(52 * unit, 24 * unit), CornerRadius(2 * unit))
            for (x in listOf(36f, 50f, 64f)) {
                drawRoundRect(if (collected) Color(0xFFF5DEAB) else Color(0xFFE9EDE5),
                    p(x - 4, 59f), Size(8 * unit, 15 * unit), CornerRadius(4 * unit, 4 * unit))
            }
            drawRect(color, p(32f, 33f), Size(36 * unit, 12 * unit))
        } else {
            for (x in listOf(34f, 48f, 62f)) drawRect(color, p(x, 48f), Size(5 * unit, 24 * unit))
            drawLine(color, p(25f, 74f), p(75f, 74f), 5 * unit, StrokeCap.Round)
            drawRect(color, p(39f, 30f), Size(22 * unit, 12 * unit))
        }
        for ((y, width) in listOf(48f to 66f, 32f to 49f)) {
            drawPath(Path().apply {
                moveTo((50 - width / 2) * unit, y * unit)
                quadraticTo(37 * unit, (y - 3) * unit, 50 * unit, (y - 13) * unit)
                quadraticTo(63 * unit, (y - 3) * unit, (50 + width / 2) * unit, y * unit)
                close()
            }, color)
        }
    }
}
