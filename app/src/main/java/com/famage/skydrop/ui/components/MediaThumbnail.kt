package com.famage.skydrop.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val thumbnailCache = LruCache<String, Bitmap>(60)

@Composable
fun MediaThumbnail(
    uriString: String?,
    name: String,
    mimeType: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }
    var videoDuration by remember(uriString) { mutableStateOf<String?>(null) }

    val isVideo = mimeType?.startsWith("video/") == true ||
            name.endsWith(".mp4", ignoreCase = true) ||
            name.endsWith(".mov", ignoreCase = true) ||
            name.endsWith(".mkv", ignoreCase = true) ||
            name.endsWith(".webm", ignoreCase = true)

    val isVisual = mimeType?.startsWith("image/") == true ||
            name.endsWith(".jpg", ignoreCase = true) ||
            name.endsWith(".jpeg", ignoreCase = true) ||
            name.endsWith(".png", ignoreCase = true) ||
            name.endsWith(".webp", ignoreCase = true) ||
            isVideo

    LaunchedEffect(uriString) {
        if (!uriString.isNullOrBlank() && isVisual) {
            withContext(Dispatchers.IO) {
                bitmap = loadMediaThumbnail(context, uriString)
                if (isVideo) {
                    videoDuration = extractVideoDuration(context, uriString)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                if (!videoDuration.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Text(
                            text = videoDuration!!,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(2.dp))
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        } else {
            val (icon, bgCol, label) = getFileTypePresentation(name, mimeType)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgCol),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(
                        text = label,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.95f)
                    )
                }
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun loadMediaThumbnail(context: Context, uriString: String): Bitmap? {
    val cached = thumbnailCache.get(uriString)
    if (cached != null) return cached

    return try {
        val uri = Uri.parse(uriString)
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
        } else {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val sample = calculateInSampleSize(boundsOptions, 160, 160)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }
        if (bitmap != null) {
            thumbnailCache.put(uriString, bitmap)
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun extractVideoDuration(context: Context, uriString: String): String? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, Uri.parse(uriString))
        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = time?.toLongOrNull() ?: return null
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {}
    }
}

private fun getFileTypePresentation(name: String, mimeType: String?): Triple<ImageVector, Color, String> {
    val ext = File(name).extension.lowercase()
    return when {
        mimeType?.startsWith("image/") == true || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") ->
            Triple(Icons.Default.Image, Color(0xFF1565C0), ext.uppercase().take(4).ifEmpty { "IMG" })
        mimeType?.startsWith("video/") == true || ext in listOf("mp4", "mkv", "mov", "avi", "webm") ->
            Triple(Icons.Default.Movie, Color(0xFF6A1B9A), ext.uppercase().take(4).ifEmpty { "VID" })
        mimeType?.startsWith("audio/") == true || ext in listOf("mp3", "m4a", "wav", "flac", "ogg", "aac") ->
            Triple(Icons.Default.MusicNote, Color(0xFF00838F), ext.uppercase().take(4).ifEmpty { "AUD" })
        ext == "pdf" ->
            Triple(Icons.Default.PictureAsPdf, Color(0xFFC62828), "PDF")
        ext in listOf("doc", "docx") ->
            Triple(Icons.Default.Description, Color(0xFF1976D2), "WORD")
        ext in listOf("xls", "xlsx", "csv") ->
            Triple(Icons.Default.TableChart, Color(0xFF2E7D32), "XLS")
        ext in listOf("ppt", "pptx") ->
            Triple(Icons.Default.Slideshow, Color(0xFFD84315), "PPT")
        ext in listOf("zip", "rar", "tar", "gz", "7z", "bz2") ->
            Triple(Icons.Default.FolderZip, Color(0xFF7B1FA2), "ZIP")
        ext == "apk" ->
            Triple(Icons.Default.Android, Color(0xFF388E3C), "APK")
        ext in listOf("kt", "java", "go", "js", "ts", "py", "json", "html", "css", "c", "cpp") ->
            Triple(Icons.Default.Code, Color(0xFF455A64), ext.uppercase().take(4))
        else ->
            Triple(Icons.Default.InsertDriveFile, Color(0xFF546E7A), ext.uppercase().take(4).ifEmpty { "FILE" })
    }
}
