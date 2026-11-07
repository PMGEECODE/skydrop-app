package com.famage.skydrop.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.skydrop.history.TransferHistoryItem
import com.famage.skydrop.receiver.ReceiveProgress
import java.io.File

@Composable
fun ReceivedMediaSection(
    items: List<TransferHistoryItem>,
    receiveProgress: ReceiveProgress?,
    onRefresh: () -> Unit,
    onDelete: (TransferHistoryItem) -> Unit = {},
    onDeleteMultiple: (List<String>) -> Unit = {},
    onSendAgain: (TransferHistoryItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("All") }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    val filteredItems = remember(items, selectedFilter) {
        val receivedOnly = items.filter { it.direction == "inbound" && it.status == "completed" }.distinctBy { it.id }
        when (selectedFilter) {
            "Photos & Videos" -> receivedOnly.filter { isPhotoOrVideo(it.name, it.mimeType) }
            "Documents" -> receivedOnly.filter { isDocument(it.name) }
            "Audio & Other" -> receivedOnly.filter { !isPhotoOrVideo(it.name, it.mimeType) && !isDocument(it.name) }
            else -> receivedOnly
        }
    }

    LaunchedEffect(filteredItems) {
        val existingIds = filteredItems.map { it.id }.toSet()
        selectedIds.removeAll { !existingIds.contains(it) }
        if (selectedIds.isEmpty()) {
            isSelectionMode = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Real-Time Inbound Receiving Progress Banner
        AnimatedVisibility(visible = receiveProgress != null) {
            receiveProgress?.let { prog ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        TransferProgressBar(
                            title = "Receiving Drop...",
                            filename = prog.filename,
                            bytesTransferred = prog.bytesReceived,
                            totalBytes = prog.totalBytes,
                            percent = prog.percent,
                            isUpload = false
                        )
                    }
                }
            }
        }

        // Multi-selection Action Bar
        AnimatedVisibility(visible = isSelectionMode) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                isSelectionMode = false
                                selectedIds.clear()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val allSelected = filteredItems.isNotEmpty() && selectedIds.size == filteredItems.size
                        TextButton(
                            onClick = {
                                if (allSelected) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(filteredItems.map { it.id })
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(if (allSelected) "Deselect All" else "Select All")
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Button(
                            onClick = {
                                val toDelete = selectedIds.toList()
                                isSelectionMode = false
                                selectedIds.clear()
                                onDeleteMultiple(toDelete)
                            },
                            enabled = selectedIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }

        // Filter chips row - horizontally scrollable for small screens (320dp) & all devices
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(listOf("All", "Photos & Videos", "Documents", "Audio & Other")) { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter, fontSize = 12.sp) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )
            }
        }

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Received Media Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Files dropped to this phone from your PC will display here with live thumbnails. Press and hold any file to multi-select and delete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val useGrid = maxWidth >= 600.dp
                if (useGrid) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 320.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredItems.size, key = { filteredItems[it].id }) { index ->
                            val item = filteredItems[index]
                            val isSelected = selectedIds.contains(item.id)
                            ReceivedMediaCard(
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    if (isSelected) {
                                        selectedIds.remove(item.id)
                                        if (selectedIds.isEmpty()) isSelectionMode = false
                                    } else {
                                        selectedIds.add(item.id)
                                    }
                                },
                                onOpen = { openMedia(context, item) },
                                onShowInFiles = { showInFiles(context, item) },
                                onShare = { shareMedia(context, item) },
                                onSendAgain = { onSendAgain(item) },
                                onDelete = { onDelete(item) },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedIds.add(item.id)
                                    } else {
                                        if (isSelected) {
                                            selectedIds.remove(item.id)
                                            if (selectedIds.isEmpty()) isSelectionMode = false
                                        } else {
                                            selectedIds.add(item.id)
                                        }
                                    }
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            val isSelected = selectedIds.contains(item.id)
                            ReceivedMediaCard(
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    if (isSelected) {
                                        selectedIds.remove(item.id)
                                        if (selectedIds.isEmpty()) isSelectionMode = false
                                    } else {
                                        selectedIds.add(item.id)
                                    }
                                },
                                onOpen = { openMedia(context, item) },
                                onShowInFiles = { showInFiles(context, item) },
                                onShare = { shareMedia(context, item) },
                                onSendAgain = { onSendAgain(item) },
                                onDelete = { onDelete(item) },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedIds.add(item.id)
                                    } else {
                                        if (isSelected) {
                                            selectedIds.remove(item.id)
                                            if (selectedIds.isEmpty()) isSelectionMode = false
                                        } else {
                                            selectedIds.add(item.id)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isPhotoOrVideo(name: String, mimeType: String?): Boolean {
    if (mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true) return true
    val ext = File(name).extension.lowercase()
    return ext in listOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "mkv", "mov", "avi")
}

private fun isDocument(name: String): Boolean {
    val ext = File(name).extension.lowercase()
    return ext in listOf("pdf", "doc", "docx", "txt", "rtf", "xls", "xlsx", "ppt", "pptx", "md", "csv")
}

private fun openMedia(context: Context, item: TransferHistoryItem) {
    if (item.uri.isNullOrBlank()) {
        Toast.makeText(context, "Cannot open: File path unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = Uri.parse(item.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun showInFiles(context: Context, item: TransferHistoryItem) {
    if (item.uri.isNullOrBlank()) {
        Toast.makeText(context, "Cannot locate file", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = Uri.parse(item.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open in Files"))
    } catch (_: Exception) {
        Toast.makeText(context, "Cannot open file manager", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMedia(context: Context, item: TransferHistoryItem) {
    if (item.uri.isNullOrBlank()) {
        Toast.makeText(context, "Cannot share: File path unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = Uri.parse(item.uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = item.mimeType ?: "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${item.name}"))
    } catch (_: Exception) {
        Toast.makeText(context, "Failed to share file", Toast.LENGTH_SHORT).show()
    }
}
