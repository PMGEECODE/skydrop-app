package com.famage.skydrop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.skydrop.model.PeerStatus
import com.famage.skydrop.presence.PresenceConstants
import com.famage.skydrop.presence.PresenceState

@Composable
fun DeviceCard(
    peer: PeerStatus,
    enabled: Boolean,
    queuedCount: Int = 0,
    onSendClick: () -> Unit,
    onPairClick: () -> Unit,
    onUnpairClick: (() -> Unit)? = null,
    onRetryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isReadyToSend = peer.isPaired && peer.canSend

    val deviceIcon = when {
        peer.name.lowercase().contains("tv") -> Icons.Default.Tv
        peer.name.lowercase().contains("laptop") || peer.name.lowercase().contains("mac") -> Icons.Default.Laptop
        peer.name.lowercase().contains("pc") || peer.name.lowercase().contains("linux") || peer.name.lowercase().contains("desktop") || peer.name.lowercase().contains("cdncode") -> Icons.Default.Computer
        else -> Icons.Default.Smartphone
    }

    val dotColor = when {
        !peer.isPaired -> MaterialTheme.colorScheme.primary
        peer.presenceState == PresenceState.ONLINE -> Color(0xFF2E7D32)
        peer.presenceState == PresenceState.BUSY -> Color(0xFF1976D2)
        peer.presenceState == PresenceState.DEGRADED -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.outline
    }

    val (badgeText, badgeBg, badgeTextColor) = when {
        !peer.isPaired -> Triple(
            "Ready to Pair",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        peer.presenceState == PresenceState.ONLINE -> Triple(
            if (peer.latencyMs > 0) "ONLINE • ${peer.latencyMs}ms" else "ONLINE",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        peer.presenceState == PresenceState.BUSY -> Triple(
            "BUSY",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        peer.presenceState == PresenceState.DEGRADED -> Triple(
            "DEGRADED",
            Color(0xFFFFF8E1),
            Color(0xFFE65100)
        )
        peer.presenceState == PresenceState.ERROR -> Triple(
            if (peer.errorCode == PresenceConstants.ERR_AUTH_FAILED || peer.errorCode == "ERR_TOKEN_REVOKED") "TOKEN REVOKED" else "ERROR",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        peer.presenceState == PresenceState.OFFLINE -> Triple(
            "OFFLINE",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Triple(
            if (peer.isConnected) "ONLINE" else "CHECKING",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val subtitle = if (peer.canSend) {
        "${peer.host}:${peer.port} • Ready"
    } else if (!peer.isPaired) {
        "${peer.host}:${peer.port} • Ready to pair"
    } else if (!peer.errorMessage.isNullOrBlank()) {
        "${peer.host}:${peer.port} • ${peer.errorMessage}"
    } else {
        "${peer.host}:${peer.port} • Offline"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .remoteFocusable(
                shape = RoundedCornerShape(16.dp),
                focusBorderWidth = 2.5.dp,
                focusedScale = 1.02f,
                enabled = enabled && (isReadyToSend || !peer.isPaired)
            )
            .clickable(
                enabled = enabled && (isReadyToSend || !peer.isPaired),
                onClick = if (peer.isPaired) onSendClick else onPairClick
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isReadyToSend) 1.dp else 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (isReadyToSend) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            val isUltraCompact = maxWidth < 320.dp

            // Clean single horizontal row layout for all paired and unpaired devices
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isReadyToSend) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(if (isUltraCompact) 36.dp else 42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = deviceIcon,
                            contentDescription = peer.name,
                            tint = if (isReadyToSend) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(if (isUltraCompact) 20.dp else 24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(dotColor, shape = CircleShape)
                        )

                        Text(
                            text = peer.name,
                            style = if (isUltraCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            color = if (isReadyToSend) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )

                        Surface(
                            color = badgeBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = badgeTextColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = if (isUltraCompact) 10.sp else 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                when {
                    peer.isPaired -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!peer.canSend && onRetryClick != null) {
                                OutlinedButton(
                                    onClick = onRetryClick,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 34.dp)
                                        .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    if (!isUltraCompact) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            if (onUnpairClick != null) {
                                OutlinedButton(
                                    onClick = onUnpairClick,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 34.dp)
                                        .remoteFocusable(
                                            shape = RoundedCornerShape(10.dp),
                                            focusBorderColor = MaterialTheme.colorScheme.error,
                                            focusedScale = 1.05f
                                        ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "Unpair",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = onPairClick,
                            enabled = enabled,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 34.dp)
                                .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f)
                        ) {
                            Text("Pair", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
