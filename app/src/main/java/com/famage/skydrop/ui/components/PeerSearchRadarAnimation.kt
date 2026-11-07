package com.famage.skydrop.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PeerSearchRadarAnimation(
    modifier: Modifier = Modifier,
    message: String = "Searching for nearby peers..."
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarTransition")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse1"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse2"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(150.dp)) {
            val radius = size.minDimension / 2f
            val center = center

            // Static outer guide ring
            drawCircle(
                color = primaryColor.copy(alpha = 0.15f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Expanding ripple 1
            if (pulse1 > 0f) {
                drawCircle(
                    color = primaryColor.copy(alpha = (1f - pulse1) * 0.6f),
                    radius = radius * pulse1,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            // Expanding ripple 2
            if (pulse2 > 0f) {
                drawCircle(
                    color = primaryColor.copy(alpha = (1f - pulse2) * 0.6f),
                    radius = radius * pulse2,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            // Center device node core
            drawCircle(
                color = primaryColor,
                radius = 10.dp.toPx(),
                center = center
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}
