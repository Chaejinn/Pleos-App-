package com.pleos.explorer.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Forest = Color(0xFF164D3D)
internal val Paper = Color(0xFFF5F4EE)
internal val Mist = Color(0xFFE7EEE7)
internal val Clay = Color(0xFFAD5731)

@Composable
internal fun RoadLogTheme(content: @Composable () -> Unit) {
    val body = TextStyle(fontSize = 24.sp, lineHeight = 34.sp)
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White,
            primaryContainer = Mist, onPrimaryContainer = Forest, secondary = Clay,
            secondaryContainer = Color(0xFFFFE7D5), background = Paper, surface = Color.White,
            onBackground = Color(0xFF1D332C), onSurface = Color(0xFF1D332C),
            onSurfaceVariant = Color(0xFF52665D), outline = Color(0xFF728478)),
        typography = Typography(bodyLarge = body, bodyMedium = body, bodySmall = body,
            titleLarge = body.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
            titleMedium = body.copy(fontWeight = FontWeight.Bold), labelLarge = body,
            labelMedium = body, labelSmall = body),
        content = content,
    )
}

@Composable
internal fun TravelPanel(modifier: Modifier = Modifier, dark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = if (dark) Forest else MaterialTheme.colorScheme.surface,
        contentColor = if (dark) Color.White else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun SectionHeading(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Simple vector marks stay sharp on both the emulator and the vehicle display. */
@Composable
internal fun RoadIcon(kind: Int, modifier: Modifier = Modifier, color: Color = Forest) {
    Canvas(modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .065f, cap = StrokeCap.Round)
        when (kind) {
            0 -> {
                val path = Path().apply {
                    moveTo(w * .5f, h * .94f)
                    cubicTo(w * .3f, h * .7f, w * .12f, h * .49f, w * .2f, h * .29f)
                    cubicTo(w * .31f, h * -.01f, w * .69f, h * -.01f, w * .8f, h * .29f)
                    cubicTo(w * .88f, h * .49f, w * .7f, h * .7f, w * .5f, h * .94f)
                }
                drawPath(path, color, style = stroke)
                drawCircle(color, w * .1f, Offset(w * .5f, h * .35f), style = stroke)
            }
            1 -> {
                val path = Path().apply {
                    moveTo(w * .5f, h * .08f); lineTo(w * .84f, h * .22f)
                    lineTo(w * .8f, h * .66f); lineTo(w * .5f, h * .94f)
                    lineTo(w * .2f, h * .66f); lineTo(w * .16f, h * .22f); close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, Offset(w * .32f, h * .49f), Offset(w * .45f, h * .62f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .45f, h * .62f), Offset(w * .68f, h * .36f), stroke.width, StrokeCap.Round)
            }
            else -> {
                val path = Path().apply {
                    moveTo(w * .2f, h * .12f); lineTo(w * .78f, h * .12f)
                    lineTo(w * .78f, h * .9f); lineTo(w * .2f, h * .9f); close()
                }
                drawPath(path, color, style = stroke)
                for (y in listOf(.35f, .52f, .69f)) drawLine(color, Offset(w * .35f, h * y),
                    Offset(w * .64f, h * y), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun JourneyArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(Color(0xFFE7B47C), h * .17f, Offset(w * .8f, h * .25f))
        val hills = Path().apply {
            moveTo(0f, h * .8f); cubicTo(w * .2f, h * .15f, w * .38f, h, w * .57f, h * .55f)
            cubicTo(w * .8f, h * .04f, w * .92f, h * .55f, w, h * .36f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(hills, Color(0xFF719984))
        val road = Path().apply {
            moveTo(w * .28f, h * 1.1f)
            cubicTo(w * .12f, h * .5f, w * .8f, h * .9f, w * .56f, h * .4f)
        }
        drawPath(road, Paper, style = Stroke(h * .12f, cap = StrokeCap.Round))
        drawPath(road, Forest, style = Stroke(h * .012f, cap = StrokeCap.Round))
        drawCircle(Clay, h * .07f, Offset(w * .56f, h * .4f))
        drawCircle(Paper, h * .024f, Offset(w * .56f, h * .4f))
    }
}
