package com.famage.skydrop.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.skydrop.R
import com.famage.skydrop.model.Device
import com.famage.skydrop.model.PeerStatus
import com.famage.skydrop.model.TransferState
import com.famage.skydrop.ui.theme.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSection(
    peers: List<PeerStatus>,
    queuedCount: Int = 0,
    isReceiverActive: Boolean,
    transferState: TransferState,
    onToggleReceiver: (Boolean) -> Unit,
    onScanQrClick: () -> Unit,
    onManualIpClick: () -> Unit,
    onShowMyQrClick: () -> Unit,
    onPairClick: (Device) -> Unit,
    onUnpairClick: (String) -> Unit,
    onSendClick: (Device) -> Unit,
    onSendClickAll: ((List<Device>) -> Unit)? = null,
    onRetryPeer: ((String) -> Unit)? = null,
    onExportDiagnostics: (() -> Unit)? = null,
    onOpenDownloadsClick: (() -> Unit)? = null,
    hasCamera: Boolean = true,
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    onThemeChange: (ThemePreference) -> Unit = {},
    onCheckForUpdates: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentVersion = remember(context) {
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.45"
        } catch (_: Exception) {
            "1.0.45"
        }
    }
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    } else {
        true
    }

    val connectedPeers = remember(peers) { peers.filter { it.isPaired && it.canSend } }
    val activeConnected = connectedPeers.firstOrNull()
    val offlinePaired = peers.firstOrNull { it.isPaired && !it.canSend }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val screenWidth = maxWidth
        val isUltraCompact = screenWidth < 320.dp
        val isCompact = screenWidth < 380.dp
        val isWide = screenWidth >= 640.dp

        val horizontalPadding = when {
            isUltraCompact -> 8.dp
            isCompact -> 12.dp
            else -> 16.dp
        }
        val verticalPadding = when {
            isUltraCompact -> 8.dp
            else -> 12.dp
        }
        val cardSpacing = when {
            isUltraCompact -> 10.dp
            else -> 14.dp
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(cardSpacing)
        ) {
            // Section: Active Connection Banner
            if (activeConnected != null) {
                item {
                    val isMultiple = connectedPeers.size > 1
                    val deviceListSummary = if (isMultiple) connectedPeers.joinToString { it.name } else ""
                    val bannerSubtitle = if (isMultiple) {
                        "$deviceListSummary • Ready for instant drops"
                    } else {
                        "${activeConnected.host}:${activeConnected.port} • Ready for instant drops"
                    }
                    val dropLabel = if (isMultiple) {
                        if (queuedCount > 0) "Drop to All ($queuedCount)" else "Drop to All (${connectedPeers.size})"
                    } else {
                        if (queuedCount > 0) "Drop ($queuedCount)" else "Drop Files"
                    }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .remoteFocusable(shape = RoundedCornerShape(16.dp), focusedScale = 1.02f)
                            .clickable {
                                if (isMultiple && onSendClickAll != null) {
                                    onSendClickAll(connectedPeers.map { it.toDevice() })
                                } else {
                                    onSendClick(activeConnected.toDevice())
                                }
                            }
                    ) {
                        Column(modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (isCompact) 36.dp else 40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isMultiple) Icons.Default.Devices else Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(if (isCompact) 20.dp else 22.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(Color(0xFF2E7D32), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isMultiple) "Paired with ${connectedPeers.size} Devices" else "Paired with ${activeConnected.name}",
                                            style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = bannerSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = if (isCompact) 11.sp else 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (!isCompact) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (isMultiple && onSendClickAll != null) {
                                                onSendClickAll(connectedPeers.map { it.toDevice() })
                                            } else {
                                                onSendClick(activeConnected.toDevice())
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = dropLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (isCompact) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        if (isMultiple && onSendClickAll != null) {
                                            onSendClickAll(connectedPeers.map { it.toDevice() })
                                        } else {
                                            onSendClick(activeConnected.toDevice())
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = dropLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (offlinePaired != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.outline, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${offlinePaired.name} is offline",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Will connect automatically when device joins Wi-Fi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Section: Receive Mode
            item {
                ReceiverModeCard(
                    isActive = isReceiverActive,
                    onToggle = onToggleReceiver
                )
            }

            // Section: Pair Action Buttons
            item {
                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasCamera) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onScanQrClick,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Scan QR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                                FilledTonalButton(
                                    onClick = onShowMyQrClick,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("My QR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        } else {
                            // No camera: My QR takes full width
                            FilledTonalButton(
                                onClick = onShowMyQrClick,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Show My QR Code", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        OutlinedButton(
                            onClick = onManualIpClick,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Manual IP Connect", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasCamera) {
                            FilledTonalButton(
                                onClick = onScanQrClick,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan QR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }

                        FilledTonalButton(
                            onClick = onShowMyQrClick,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("My QR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = onManualIpClick,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Manual", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }

            // Section: Nearby & Paired Devices Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nearby & Paired Devices",
                        style = if (isUltraCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${peers.size} found",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (peers.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        PeerSearchRadarAnimation(
                            modifier = Modifier.padding(16.dp),
                            message = "Searching for devices on local Wi-Fi..."
                        )
                    }
                }
            } else {
                items(peers.distinctBy { it.id }, key = { it.id }) { peer ->
                    DeviceCard(
                        peer = peer,
                        enabled = (transferState is TransferState.Idle || transferState is TransferState.Success || transferState is TransferState.Error),
                        queuedCount = queuedCount,
                        onSendClick = { onSendClick(peer.toDevice()) },
                        onPairClick = { onPairClick(peer.toDevice()) },
                        onUnpairClick = { onUnpairClick(peer.id) },
                        onRetryClick = { onRetryPeer?.invoke(peer.id) }
                    )
                }
            }

            // Section: User-Driven Battery Optimization Setting
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isIgnoringBattery) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
                    )
                ) {
                    BoxWithConstraints(modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp)) {
                        val isUltraNarrow = maxWidth < 340.dp
                        if (isUltraNarrow) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.BatterySaver,
                                        contentDescription = null,
                                        tint = if (isIgnoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isIgnoringBattery) "Reliable Background Sync Active" else "Enable Reliable Background Sync",
                                        style = if (isUltraCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isIgnoringBattery) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isIgnoringBattery)
                                        "Battery optimization is disabled for SkyDrop. Background drops and clipboard sync run 24/7 even when screen is off."
                                    else
                                        "SkyDrop operates in best-effort background mode. Tap below to manage system battery settings for uninterrupted background transfers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = if (isUltraCompact) 11.sp else 12.sp,
                                    color = if (isIgnoringBattery) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                }
                                            } catch (_: Exception) {
                                                try {
                                                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (isIgnoringBattery) "System Battery Settings" else "Configure Battery Settings",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.BatterySaver,
                                            contentDescription = null,
                                            tint = if (isIgnoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isIgnoringBattery) "Reliable Background Sync Active" else "Enable Reliable Background Sync",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isIgnoringBattery) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isIgnoringBattery)
                                            "Battery optimization is disabled for SkyDrop. Background drops and clipboard sync run 24/7 even when screen is off."
                                        else
                                            "SkyDrop operates in best-effort background mode. Tap to manage system battery settings for uninterrupted background transfers.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp,
                                        color = if (isIgnoringBattery) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        try {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            }
                                        } catch (_: Exception) {
                                            try {
                                                val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = if (isIgnoringBattery) "System Battery Settings" else "Configure Battery Settings",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section: Appearance (Theme Toggle)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Appearance",
                                    style = if (isUltraCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Choose your preferred color theme",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = if (isUltraCompact) 11.sp else 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = ThemePreference.entries
                            options.forEachIndexed { index, pref ->
                                SegmentedButton(
                                    selected = themePreference == pref,
                                    onClick = { onThemeChange(pref) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = options.size
                                    ),
                                    modifier = Modifier.remoteFocusable(
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                        focusedScale = 1.05f
                                    ),
                                    label = {
                                        Text(
                                            text = when (pref) {
                                                ThemePreference.SYSTEM -> "System"
                                                ThemePreference.LIGHT  -> "Light"
                                                ThemePreference.DARK   -> "Dark"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    },
                                    icon = {
                                        if (!isUltraCompact) {
                                            Icon(
                                                imageVector = when (pref) {
                                                    ThemePreference.SYSTEM -> Icons.Default.Brightness4
                                                    ThemePreference.LIGHT  -> Icons.Default.LightMode
                                                    ThemePreference.DARK   -> Icons.Default.DarkMode
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Section: Device & Storage Info
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp)) {
                        Text(
                            text = "Device & Storage Info",
                            style = if (isUltraCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        // Item 1: Device Name
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Smartphone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Device Name",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Item 2: Storage Save Location
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (onOpenDownloadsClick != null) Modifier.clickable { onOpenDownloadsClick() }
                                    else Modifier
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = "Save Location",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Downloads/SkyDrop",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (onOpenDownloadsClick != null) {
                                TextButton(
                                    onClick = onOpenDownloadsClick,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.05f),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("Open ↗", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Footer: Branding & Version Label with Update Check action
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onCheckForUpdates != null) {
                                Modifier.clickable { onCheckForUpdates() }
                            } else Modifier
                        ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.skydrop_text),
                            contentDescription = "SkyDrop Logo",
                            modifier = Modifier
                                .height(26.dp)
                                .wrapContentWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Version $currentVersion • Click to check for updates ↗",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
