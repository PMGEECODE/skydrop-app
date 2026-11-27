package com.famage.skydrop.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.skydrop.model.PeerStatus
import com.famage.skydrop.model.SharedItem
import com.famage.skydrop.model.TransferState
import java.util.Locale

@Composable
fun SendMediaSection(
    peers: List<PeerStatus>,
    queuedItems: List<SharedItem>,
    sharedText: String?,
    transferState: TransferState,
    onAddUris: (List<Uri>) -> Unit,
    onRemoveItem: (SharedItem) -> Unit,
    onClearAll: () -> Unit,
    onSetSharedText: (String?) -> Unit,
    onSendPayload: (peers: List<PeerStatus>) -> Unit,
    onResetTransferState: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTextDialog by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddUris(uris)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddUris(uris)
        }
    }

    val contentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onAddUris(listOf(uri))
        }
    }

    val connectedPeers = remember(peers) {
        val ready = peers.filter { it.isPaired && (it.canSend || it.isConnected) }
        if (ready.isNotEmpty()) ready else peers.filter { it.isPaired }
    }

    var selectedTargetId by remember { mutableStateOf<String?>("ALL") }

    val effectiveSelectedTargetId = remember(selectedTargetId, connectedPeers) {
        if (selectedTargetId == "ALL" || connectedPeers.none { it.id == selectedTargetId }) {
            "ALL"
        } else {
            selectedTargetId
        }
    }

    val isAllSelected = effectiveSelectedTargetId == "ALL"

    val targetsToSend = remember(effectiveSelectedTargetId, connectedPeers) {
        if (effectiveSelectedTargetId == "ALL") {
            connectedPeers
        } else {
            connectedPeers.filter { it.id == effectiveSelectedTargetId }
        }
    }

    val totalQueuedSize = remember(queuedItems) { queuedItems.sumOf { it.size } }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val isCompact = maxWidth < 380.dp
        val isUltraCompact = maxWidth < 320.dp
        val horizontalPadding = if (isUltraCompact) 8.dp else if (isCompact) 12.dp else 16.dp

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .padding(horizontal = horizontalPadding, vertical = 8.dp)
        ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Target Device Card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (connectedPeers.isNotEmpty()) Color(0xFF1B5E20).copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val targetTitle = when {
                                    connectedPeers.isEmpty() -> "No Device Connected"
                                    isAllSelected && connectedPeers.size > 1 -> "Drop Target: All Devices (${connectedPeers.size})"
                                    targetsToSend.isNotEmpty() -> "Drop Target: ${targetsToSend.first().name}"
                                    else -> "No Device Selected"
                                }
                                Text(
                                    text = targetTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (connectedPeers.isNotEmpty()) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val targetSubtitle = when {
                                    connectedPeers.isEmpty() -> "Pair or connect to your PC/TV in Settings"
                                    isAllSelected && connectedPeers.size > 1 -> "Broadcasting to all ${connectedPeers.size} active paired devices"
                                    targetsToSend.isNotEmpty() -> "${targetsToSend.first().host}:${targetsToSend.first().port} • Ready for instant drops"
                                    else -> "Select a destination below"
                                }
                                Text(
                                    text = targetSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (connectedPeers.isEmpty()) {
                                FilledTonalButton(
                                    onClick = onNavigateToSettings,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Text("Pair Device")
                                }
                            } else {
                                SuggestionChip(
                                    onClick = onNavigateToSettings,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.05f),
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(Color(0xFF2E7D32), CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                if (connectedPeers.size > 1) "${connectedPeers.size} Online" else "Online",
                                                fontSize = 12.sp,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        if (connectedPeers.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    FilterChip(
                                        selected = isAllSelected,
                                        onClick = { selectedTargetId = "ALL" },
                                        label = { Text("✨ All (${connectedPeers.size})") },
                                        modifier = Modifier.remoteFocusable(shape = FilterChipDefaults.shape, focusedScale = 1.05f),
                                        leadingIcon = if (isAllSelected) {
                                            { Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                                items(connectedPeers) { peer ->
                                    val isSelected = !isAllSelected && effectiveSelectedTargetId == peer.id
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedTargetId = peer.id },
                                        label = { Text(peer.name) },
                                        modifier = Modifier.remoteFocusable(shape = FilterChipDefaults.shape, focusedScale = 1.05f),
                                        leadingIcon = if (isSelected) {
                                            { Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action Buttons: Pick Photos, Pick Files, Browse, Send Text
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .remoteFocusable(shape = ButtonDefaults.shape, focusedScale = 1.05f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Photos",
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .remoteFocusable(shape = ButtonDefaults.shape, focusedScale = 1.05f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Files",
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            contentPickerLauncher.launch("*/*")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .remoteFocusable(shape = ButtonDefaults.shape, focusedScale = 1.05f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Browse",
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = { showTextDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .remoteFocusable(shape = ButtonDefaults.shape, focusedScale = 1.05f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Text",
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Queued Files or Empty State
            if (queuedItems.isEmpty() && sharedText.isNullOrBlank()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Upload,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No Files Selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap 'Photos' or 'Files' above to choose media, or share from any app via the Share sheet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected (${queuedItems.size}) • ${formatBytes(totalQueuedSize)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onClearAll) {
                            Text("Clear All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (!sharedText.isNullOrBlank()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = sharedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { onSetSharedText(null) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove Text")
                                }
                            }
                        }
                    }
                }

                items(queuedItems.distinctBy { it.uri.toString() }, key = { it.uri.toString() }) { item ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MediaThumbnail(
                                uriString = item.uri.toString(),
                                name = item.name,
                                mimeType = item.mimeType,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = formatBytes(item.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = { onRemoveItem(item) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove File")
                            }
                        }
                    }
                }
            }
        }

        // Anchored Primary Send Button
        if (queuedItems.isNotEmpty() || !sharedText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            val isSending = transferState is TransferState.Preparing || transferState is TransferState.Uploading
            val canSend = targetsToSend.isNotEmpty() && !isSending
            Button(
                onClick = {
                    if (targetsToSend.isNotEmpty()) {
                        onSendPayload(targetsToSend)
                    }
                },
                enabled = canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.03f),
                shape = RoundedCornerShape(12.dp)
            ) {
                val count = queuedItems.size + (if (sharedText.isNullOrBlank()) 0 else 1)
                val targetLabel = when {
                    isAllSelected && connectedPeers.size > 1 -> "All Devices (${connectedPeers.size})"
                    targetsToSend.isNotEmpty() -> targetsToSend.first().name
                    else -> "Device"
                }
                if (isSending) {
                    Text("Sending...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Drop $count Item(s) to $targetLabel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (showTextDialog) {
            SendTextDialog(
                initialText = sharedText.orEmpty(),
                onDismiss = { showTextDialog = false },
                onAttach = { onSetSharedText(it) }
            )
        }
    }
}
}
