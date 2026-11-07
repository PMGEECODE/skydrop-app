package com.famage.skydrop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
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
    onRetryClick: (() -> Unit)? = null
) {
    val isReadyToSend = peer.isPaired && peer.canSend

    val deviceIcon = when {
        peer.name.lowercase().contains("laptop") || peer.name.lowercase().contains("mac") -> Icons.Default.Laptop
        peer.name.lowercase().contains("pc") || peer.name.lowercase().contains("linux") || peer.name.lowercase().contains("desktop") || peer.name.lowercase().contains("cdncode") -> Icons.Default.Computer
        else -> Icons.Default.Smartphone
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled && (isReadyToSend || !peer.isPaired),
                onClick = if (peer.isPaired) onSendClick else onPairClick
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isReadyToSend) {
                MaterialTheme.colorScheme.surface
            } else if (peer.isPaired && !peer.canSend) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Device Avatar Icon
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isReadyToSend) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = deviceIcon,
                        contentDescription = peer.name,
                        tint = if (isReadyToSend) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center: Device Details & Status Badge
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val dotColor = when {
                        !peer.isPaired -> MaterialTheme.colorScheme.primary
                        peer.presenceState == PresenceState.ONLINE -> Color(0xFF2E7D32)
                        peer.presenceState == PresenceState.BUSY -> Color(0xFF1976D2)
                        peer.presenceState == PresenceState.DEGRADED -> Color(0xFFFFA000)
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(dotColor, shape = CircleShape)
                    )

                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

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
                            MaterialTheme.colorScheme.outline
                        )
                        else -> Triple(
                            if (peer.isConnected) "ONLINE" else "CHECKING",
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        color = badgeBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = badgeTextColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))
                val subtitle = if (peer.canSend) {
                    "${peer.host}:${peer.port} • Ready"
                } else if (!peer.isPaired) {
                    "${peer.host}:${peer.port} • Ready to pair"
                } else if (!peer.errorMessage.isNullOrBlank()) {
                    "${peer.host}:${peer.port} • ${peer.errorMessage}"
                } else {
                    "${peer.host}:${peer.port} • Offline"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: Primary Action Button & Optional Unpair Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when {
                    peer.isPaired && peer.canSend -> {
                        Button(
                            onClick = onSendClick,
                            enabled = enabled && peer.canSend,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                        ) {
                            Text(
                                text = if (queuedCount > 0) "Drop ($queuedCount)" else "Drop Files",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    peer.isPaired && !peer.canSend -> {
                        OutlinedButton(
                            onClick = { onRetryClick?.invoke() },
                            enabled = onRetryClick != null,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = onPairClick,
                            enabled = enabled,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                        ) {
                            Text("Pair", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (peer.isPaired && onUnpairClick != null) {
                    IconButton(
                        onClick = onUnpairClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Unpair Device",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
