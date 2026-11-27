package com.famage.skydrop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Enhanced focus and hover feedback modifier for large screens, desktop cursor hovers,
 * and Android TV / D-Pad remote control navigation.
 *
 * When an element receives focus (via remote D-Pad, keyboard arrow keys) or hover (via mouse/trackpad),
 * this modifier automatically:
 * 1. Scales up the element slightly (e.g. 1.025x) with a smooth animation.
 * 2. Raises its z-index so it renders cleanly above neighboring cards/buttons.
 * 3. Applies a vibrant, high-contrast focus ring matching the element's exact shape.
 */
fun Modifier.remoteFocusable(
    shape: Shape,
    focusBorderWidth: Dp = 2.5.dp,
    focusBorderColor: Color? = null,
    focusedScale: Float = 1.025f,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true
): Modifier = composed {
    if (!enabled) return@composed this

    var isFocused by remember { mutableStateOf(false) }
    val localSource = interactionSource ?: remember { MutableInteractionSource() }
    val isHovered by localSource.collectIsHoveredAsState()
    val isActive = isFocused || isHovered

    val scale by animateFloatAsState(
        targetValue = if (isActive) focusedScale else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "remoteFocusScale"
    )

    val activeBorderColor = focusBorderColor ?: MaterialTheme.colorScheme.primary

    this
        .scale(scale)
        .clip(shape)
        .onFocusChanged { isFocused = it.isFocused }
        .hoverable(interactionSource = localSource)
        .zIndex(if (isActive) 2f else 0f)
        .then(
            if (isActive) {
                Modifier.border(
                    BorderStroke(focusBorderWidth, activeBorderColor),
                    shape = shape
                )
            } else {
                Modifier
            }
        )
}
