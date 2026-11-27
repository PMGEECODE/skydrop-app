package com.famage.skydrop.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.famage.skydrop.R
import com.famage.skydrop.model.Device
import com.famage.skydrop.model.TransferState
import com.famage.skydrop.ui.components.*
import com.famage.skydrop.ui.theme.ThemePreference
import com.famage.skydrop.util.CameraUtils
import com.famage.skydrop.updater.MobileUpdateService
import com.famage.skydrop.updater.MobileUpdateInfo
import com.famage.skydrop.updater.MobileUpdateDownloadProgress
import com.famage.skydrop.ui.components.MobileUpdateDialog
import java.io.File
import kotlinx.coroutines.launch

enum class MainNavigationTab {
    MEDIA,
    CLIPBOARD,
    SETTINGS
}

enum class MediaSubTab {
    RECEIVED,
    SEND
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    viewModel: SendViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val peers by viewModel.peerStatuses.collectAsState()
    val queuedItems by viewModel.queuedItems.collectAsState()
    val sharedText by viewModel.sharedText.collectAsState()
    val transferState by viewModel.transferState.collectAsState()
    val isReceiverActive by viewModel.isReceiverActive.collectAsState()
    val transferHistory by viewModel.transferHistory.collectAsState()
    val receiveProgress by viewModel.receiveProgress.collectAsState()
    val themePreference by viewModel.themePreference.collectAsState()
    val incomingRequests by viewModel.incomingPairRequests.collectAsState()

    var activeMainTab by remember { mutableStateOf(MainNavigationTab.MEDIA) }
    var activeMediaSubTab by remember { mutableStateOf(MediaSubTab.RECEIVED) }

    var showManualDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var activeSessionForQr by remember { mutableStateOf<com.famage.skydrop.security.PairingManager.LocalPairingSession?>(null) }
    var pairingDevice by remember { mutableStateOf<Device?>(null) }
    var isPairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val updateService = remember { MobileUpdateService(context) }
    var updateInfo by remember { mutableStateOf<MobileUpdateInfo?>(null) }
    var isCheckingForUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<MobileUpdateDownloadProgress?>(null) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }

    fun handleCheckForUpdates(silent: Boolean = false) {
        if (isCheckingForUpdate) return
        isCheckingForUpdate = true
        coroutineScope.launch {
            if (!silent) {
                snackbarHostState.showSnackbar("Checking for SkyDrop updates...")
            }
            try {
                val res = updateService.checkGitHubReleases()
                if (res.isUpdateAvailable && res.targetAsset != null) {
                    updateInfo = res
                } else if (!silent) {
                    snackbarHostState.showSnackbar("SkyDrop is up to date (v${res.currentVersion})")
                }
            } catch (e: Exception) {
                if (!silent) {
                    snackbarHostState.showSnackbar("Failed to check for updates: ${e.message}")
                }
            } finally {
                isCheckingForUpdate = false
            }
        }
    }

    fun handleStartDownload() {
        val asset = updateInfo?.targetAsset ?: return
        if (isDownloadingUpdate) return
        isDownloadingUpdate = true
        updateProgress = MobileUpdateDownloadProgress(0, asset.sizeBytes, 0)

        coroutineScope.launch {
            val file = updateService.downloadApk(asset) { prog ->
                updateProgress = prog
            }

            if (file != null && file.exists()) {
                val expectedSha = updateService.fetchExpectedSha256(updateInfo?.checksumAsset, asset.name)
                updateProgress = updateProgress?.copy(isVerifyingSha256 = true)
                val isValid = updateService.verifySha256(file, expectedSha)

                isDownloadingUpdate = false
                if (isValid) {
                    downloadedApkFile = file
                    updateProgress = updateProgress?.copy(isVerifyingSha256 = false, isComplete = true)
                    updateService.promptInstallApk(file)
                } else {
                    file.delete()
                    updateProgress = updateProgress?.copy(
                        isVerifyingSha256 = false,
                        error = "SHA-256 verification failed. Corrupted package."
                    )
                }
            } else {
                isDownloadingUpdate = false
            }
        }
    }

    // Detect camera availability once per composition; CameraManager.cameraIdList is authoritative
    val hasCamera = remember(context) { CameraUtils.hasUsableCamera(context) }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
        viewModel.syncExistingDownloads()
        try {
            val launchUpdate = updateService.checkUpdateOnLaunch()
            if (launchUpdate != null && launchUpdate.isUpdateAvailable && launchUpdate.targetAsset != null) {
                updateInfo = launchUpdate
            }
        } catch (_: Exception) {}
    }

    // Auto-navigate to Received Media when an inbound transfer begins
    LaunchedEffect(receiveProgress != null) {
        if (receiveProgress != null) {
            if (activeMainTab != MainNavigationTab.MEDIA) {
                activeMainTab = MainNavigationTab.MEDIA
            }
            if (activeMediaSubTab != MediaSubTab.RECEIVED) {
                activeMediaSubTab = MediaSubTab.RECEIVED
            }
        }
    }

    // Auto-navigate to Received Media when a new inbound file is completed
    val latestInboundId = remember(transferHistory) {
        transferHistory.firstOrNull { it.direction == "inbound" }?.id
    }
    var previousInboundId by remember { mutableStateOf(latestInboundId) }
    LaunchedEffect(latestInboundId) {
        if (previousInboundId != null && latestInboundId != null && latestInboundId != previousInboundId) {
            activeMainTab = MainNavigationTab.MEDIA
            activeMediaSubTab = MediaSubTab.RECEIVED
        }
        previousInboundId = latestInboundId
    }

    // Auto-dismiss top sliding toast message after 3s on success / 4s on error
    LaunchedEffect(transferState) {
        if (transferState is TransferState.Success) {
            kotlinx.coroutines.delay(3000L)
            viewModel.resetTransferState()
        } else if (transferState is TransferState.Error) {
            kotlinx.coroutines.delay(4000L)
            viewModel.resetTransferState()
        }
    }

    val connectedPeers = remember(peers) {
        val ready = peers.filter { it.isPaired && (it.isConnected || it.canSend) }
        if (ready.isNotEmpty()) ready else peers.filter { it.isPaired }
    }
    val activeConnected = connectedPeers.firstOrNull()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val isWideScreen = screenWidth >= 600.dp
        val isDualPane = screenWidth >= 840.dp

        // Top-sliding Animated Toast Message Overlay
        AnimatedVisibility(
            visible = transferState !is TransferState.Idle,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(100f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            TransferStatusCard(
                state = transferState,
                onDismiss = { viewModel.resetTransferState() }
            )
        }

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.skydrop_logo),
                                contentDescription = "SkyDrop Logo",
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SkyDrop",
                                fontWeight = FontWeight.Bold,
                                style = if (screenWidth < 360.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                            )
                            if (activeConnected != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = Color(0xFFE8F5E9),
                                    modifier = Modifier.clickable { activeMainTab = MainNavigationTab.SETTINGS }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(Color(0xFF2E7D32), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (connectedPeers.size > 1) "${connectedPeers.size} Devices" else activeConnected.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1B5E20),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        if (hasCamera) {
                        FilledTonalButton(
                            onClick = { showQrScanner = true },
                            contentPadding = PaddingValues(
                                horizontal = if (screenWidth < 360.dp) 8.dp else 12.dp,
                                vertical = 6.dp
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                .padding(end = 8.dp)
                                .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR",
                                modifier = Modifier.size(18.dp)
                            )
                            if (screenWidth >= 360.dp) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan QR", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        }
                    }
                )
            },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        containerColor = Color.Transparent,
                        tonalElevation = 8.dp
                    ) {
                        NavigationBarItem(
                            modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            selected = activeMainTab == MainNavigationTab.MEDIA,
                            onClick = { activeMainTab = MainNavigationTab.MEDIA },
                            label = { Text("Media Hub") },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (receiveProgress != null) {
                                            Badge { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(10.dp)) }
                                        } else if (transferHistory.isNotEmpty()) {
                                            Badge { Text("${transferHistory.size}") }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = "Media Hub")
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            )
                        )
                        NavigationBarItem(
                            modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            selected = activeMainTab == MainNavigationTab.CLIPBOARD,
                            onClick = { activeMainTab = MainNavigationTab.CLIPBOARD },
                            label = { Text("Clipboard") },
                            icon = {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Clipboard")
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            )
                        )
                        NavigationBarItem(
                            modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            selected = activeMainTab == MainNavigationTab.SETTINGS,
                            onClick = { activeMainTab = MainNavigationTab.SETTINGS },
                            label = { Text("Settings & Devices") },
                            icon = {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                // Navigation Rail for Wide Screens (Tablets, Foldables, Desktop)
                if (isWideScreen) {
                    NavigationRail {
                        NavigationRailItem(
                            modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            selected = activeMainTab == MainNavigationTab.MEDIA,
                            onClick = { activeMainTab = MainNavigationTab.MEDIA },
                            label = { Text("Media Hub") },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (receiveProgress != null) {
                                            Badge { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(10.dp)) }
                                        } else if (transferHistory.isNotEmpty()) {
                                            Badge { Text("${transferHistory.size}") }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = "Media Hub")
                                }
                            }
                        )
                        NavigationRailItem(
                            modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            selected = activeMainTab == MainNavigationTab.CLIPBOARD,
                            onClick = { activeMainTab = MainNavigationTab.CLIPBOARD },
                            label = { Text("Clipboard") },
                            icon = {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Clipboard")
                            }
                        )
                        NavigationRailItem(
                            modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f),
                            selected = activeMainTab == MainNavigationTab.SETTINGS,
                            onClick = { activeMainTab = MainNavigationTab.SETTINGS },
                            label = { Text("Settings") },
                            icon = {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        )
                    }
                }

                // Main Content Column / Dual Pane Split View
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (isDualPane && activeMainTab != MainNavigationTab.CLIPBOARD && activeMainTab != MainNavigationTab.SETTINGS) {
                        // Dual Pane Layout (Tablets / Foldables / Unfolded Duo / Desktops)
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Left Pane: Media Hub
                            Column(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                                PrimaryTabRow(
                                    selectedTabIndex = activeMediaSubTab.ordinal
                                ) {
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.RECEIVED,
                                        onClick = { activeMediaSubTab = MediaSubTab.RECEIVED },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Received Media")
                                                if (receiveProgress != null) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("Live") }
                                                } else if (transferHistory.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("${transferHistory.size}") }
                                                }
                                            }
                                        }
                                    )
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.SEND,
                                        onClick = { activeMediaSubTab = MediaSubTab.SEND },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Send Files")
                                                if (queuedItems.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text("${queuedItems.size}") }
                                                }
                                            }
                                        }
                                    )
                                }

                                when (activeMediaSubTab) {
                                    MediaSubTab.RECEIVED -> {
                                        ReceivedMediaSection(
                                            items = transferHistory,
                                            receiveProgress = receiveProgress,
                                            onRefresh = { viewModel.syncExistingDownloads() },
                                            onDelete = { item -> viewModel.deleteHistoryItem(item.id) },
                                            onDeleteMultiple = { ids -> viewModel.deleteHistoryItems(ids) },
                                            onSendAgain = { item ->
                                                viewModel.queueHistoryItemForSend(item)
                                                activeMediaSubTab = MediaSubTab.SEND
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    MediaSubTab.SEND -> {
                                        SendMediaSection(
                                            peers = peers,
                                            queuedItems = queuedItems,
                                            sharedText = sharedText,
                                            transferState = transferState,
                                            onAddUris = { uris -> viewModel.addSharedUris(uris) },
                                            onRemoveItem = { item -> viewModel.removeQueuedItem(item) },
                                            onClearAll = { viewModel.clearQueuedItems() },
                                            onSetSharedText = { text -> viewModel.setSharedText(text) },
                                            onSendPayload = { targetPeers ->
                                                viewModel.sendToDevices(targetPeers.map { it.toDevice() })
                                            },
                                            onResetTransferState = { viewModel.resetTransferState() },
                                            onNavigateToSettings = { activeMainTab = MainNavigationTab.SETTINGS },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            VerticalDivider()

                            // Right Pane: Active Status (Placeholder or Recent Activity)
                            Column(modifier = Modifier.weight(0.9f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("SkyDrop Active", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        // Single Pane Layout (Phones / Narrow screens)
                        when (activeMainTab) {
                            MainNavigationTab.MEDIA -> {
                                PrimaryTabRow(
                                    selectedTabIndex = activeMediaSubTab.ordinal
                                ) {
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.RECEIVED,
                                        onClick = { activeMediaSubTab = MediaSubTab.RECEIVED },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Received Media")
                                                if (receiveProgress != null) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("Live") }
                                                } else if (transferHistory.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("${transferHistory.size}") }
                                                }
                                            }
                                        }
                                    )
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.SEND,
                                        onClick = { activeMediaSubTab = MediaSubTab.SEND },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Send Files")
                                                if (queuedItems.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text("${queuedItems.size}") }
                                                }
                                            }
                                        }
                                    )
                                }

                                when (activeMediaSubTab) {
                                    MediaSubTab.RECEIVED -> {
                                        ReceivedMediaSection(
                                            items = transferHistory,
                                            receiveProgress = receiveProgress,
                                            onRefresh = { viewModel.syncExistingDownloads() },
                                            onDelete = { item -> viewModel.deleteHistoryItem(item.id) },
                                            onDeleteMultiple = { ids -> viewModel.deleteHistoryItems(ids) },
                                            onSendAgain = { item ->
                                                viewModel.queueHistoryItemForSend(item)
                                                activeMediaSubTab = MediaSubTab.SEND
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    MediaSubTab.SEND -> {
                                        SendMediaSection(
                                            peers = peers,
                                            queuedItems = queuedItems,
                                            sharedText = sharedText,
                                            transferState = transferState,
                                            onAddUris = { uris -> viewModel.addSharedUris(uris) },
                                            onRemoveItem = { item -> viewModel.removeQueuedItem(item) },
                                            onClearAll = { viewModel.clearQueuedItems() },
                                            onSetSharedText = { text -> viewModel.setSharedText(text) },
                                            onSendPayload = { targetPeers ->
                                                viewModel.sendToDevices(targetPeers.map { it.toDevice() })
                                            },
                                            onResetTransferState = { viewModel.resetTransferState() },
                                            onNavigateToSettings = { activeMainTab = MainNavigationTab.SETTINGS },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            MainNavigationTab.CLIPBOARD -> {
                                val clipboardViewModel: com.famage.skydrop.clipboard.ClipboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                                com.famage.skydrop.clipboard.ClipboardScreen(
                                    viewModel = clipboardViewModel,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            MainNavigationTab.SETTINGS -> {
                                SettingsSection(
                                    peers = peers,
                                    queuedCount = queuedItems.size + (if (sharedText.isNullOrBlank()) 0 else 1),
                                    isReceiverActive = isReceiverActive,
                                    transferState = transferState,
                                    hasCamera = hasCamera,
                                    onToggleReceiver = { viewModel.toggleReceiver(it) },
                                    onScanQrClick = { showQrScanner = true },
                                    onManualIpClick = { showManualDialog = true },
                                    onShowMyQrClick = { showMyQrDialog = true },
                                    onPairClick = { device ->
                                        isPairing = true
                                        pairError = null
                                        viewModel.initiatePairingRequest(
                                            device = device,
                                            onStatus = { statusMsg ->
                                                coroutineScope.launch { snackbarHostState.showSnackbar(statusMsg) }
                                            },
                                            onReadyForPin = {
                                                isPairing = false
                                                pairingDevice = device
                                                pairError = null
                                            },
                                            onError = { err ->
                                                isPairing = false
                                                coroutineScope.launch { snackbarHostState.showSnackbar(err) }
                                            }
                                        )
                                    },
                                    onUnpairClick = { id -> viewModel.unpairDevice(id) },
                                    onSendClick = { device ->
                                        if (queuedItems.isNotEmpty() || !sharedText.isNullOrBlank()) {
                                            viewModel.sendToDevice(device)
                                        }
                                        activeMainTab = MainNavigationTab.MEDIA
                                        activeMediaSubTab = MediaSubTab.SEND
                                    },
                                    onSendClickAll = { devices ->
                                        if (queuedItems.isNotEmpty() || !sharedText.isNullOrBlank()) {
                                            viewModel.sendToDevices(devices)
                                        }
                                        activeMainTab = MainNavigationTab.MEDIA
                                        activeMediaSubTab = MediaSubTab.SEND
                                    },
                                    onRetryPeer = { id -> viewModel.retryPeer(id) },
                                    onExportDiagnostics = {
                                        val report = viewModel.getDiagnosticsReport()
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("SkyDrop Diagnostics", report))
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Diagnostics report copied to clipboard")
                                        }
                                        try {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_TEXT, report)
                                                putExtra(Intent.EXTRA_SUBJECT, "SkyDrop Diagnostics Report")
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "Export Diagnostics"))
                                        } catch (_: Exception) {}
                                    },
                                    onOpenDownloadsClick = {
                                        try {
                                            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    themePreference = themePreference,
                                    onThemeChange = viewModel::setTheme,
                                    onCheckForUpdates = { handleCheckForUpdates(silent = false) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualDeviceDialog(
            onDismiss = { showManualDialog = false },
            onAdd = { host, port, isTls ->
                viewModel.addManualDevice(host, port, isTls)
                showManualDialog = false
            }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onShowMyQrFallback = {
                showQrScanner = false
                showMyQrDialog = true
            },
            onManualFallback = {
                showQrScanner = false
                showManualDialog = true
            },
            onQrScanned = { rawUri ->
                showQrScanner = false
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Pairing with scanned QR code...")
                }
                viewModel.pairWithUri(rawUri) { success, err ->
                    coroutineScope.launch {
                        if (success) {
                            snackbarHostState.showSnackbar("Device paired successfully!")
                        } else {
                            snackbarHostState.showSnackbar("Pairing failed: ${err ?: "Connection error"}")
                        }
                    }
                }
            }
        )
    }

    if (showMyQrDialog) {
        QrGeneratorDialog(
            session = activeSessionForQr,
            onDismiss = {
                showMyQrDialog = false
                activeSessionForQr = null
            }
        )
    }

    incomingRequests.firstOrNull()?.let { req ->
        AlertDialog(
            modifier = Modifier.widthIn(max = 480.dp),
            onDismissRequest = { viewModel.declineIncomingPairRequest(req) },
            icon = { Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Pairing Request") },
            text = { Text("${req.deviceName} wants to pair with this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.acceptIncomingPairRequest(req) { session ->
                            activeSessionForQr = session
                            showMyQrDialog = true
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                ) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.declineIncomingPairRequest(req) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f)
                ) {
                    Text("Decline")
                }
            }
        )
    }

    pairingDevice?.let { targetDevice ->
        PairDeviceDialog(
            device = targetDevice,
            isPairing = isPairing,
            errorMessage = pairError,
            hasCamera = hasCamera,
            onDismiss = {
                pairingDevice = null
                pairError = null
                isPairing = false
            },
            onScanQrClick = {
                pairingDevice = null
                pairError = null
                isPairing = false
                showQrScanner = true
            },
            onConfirmPair = { secret ->
                isPairing = true
                pairError = null
                viewModel.pairWithSecret(targetDevice, secret) { success, err ->
                    isPairing = false
                    if (success) {
                        pairingDevice = null
                        pairError = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Successfully paired with ${targetDevice.name}")
                        }
                    } else {
                        pairError = err ?: "Pairing failed"
                    }
                }
            }
        )
    }

    updateInfo?.let { info ->
        MobileUpdateDialog(
            updateInfo = info,
            progress = updateProgress,
            isDownloading = isDownloadingUpdate,
            isReadyToInstall = downloadedApkFile != null && downloadedApkFile?.exists() == true,
            onStartDownload = { handleStartDownload() },
            onInstallDownloaded = {
                downloadedApkFile?.let { updateService.promptInstallApk(it) }
            },
            onDismiss = {
                updateInfo = null
                updateProgress = null
                downloadedApkFile = null
            }
        )
    }
}
