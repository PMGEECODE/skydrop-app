package com.famage.skydrop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.famage.skydrop.model.Device

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun PairDeviceDialog(
    device: Device,
    isPairing: Boolean = false,
    errorMessage: String? = null,
    hasCamera: Boolean = true,
    onDismiss: () -> Unit,
    onScanQrClick: () -> Unit = {},
    onConfirmPair: (secret: String) -> Unit
) {
    var secret by remember { mutableStateOf("") }

    AlertDialog(
        modifier = Modifier.widthIn(max = 480.dp),
        onDismissRequest = { if (!isPairing) onDismiss() },
        title = { Text("Pair with ${device.name}") },
        text = {
            Column {
                Text(
                    text = if (hasCamera) {
                        "Enter the 6-digit pairing PIN shown on your device, or scan its QR code:"
                    } else {
                        "Enter the 6-digit pairing PIN shown on your device:"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { if (it.length <= 12) secret = it },
                    label = { Text("6-Digit PIN") },
                    placeholder = { Text("e.g. 123456") },
                    singleLine = true,
                    enabled = !isPairing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (hasCamera) {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onScanQrClick,
                        enabled = !isPairing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan QR Code Instead")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmPair(secret.trim()) },
                enabled = secret.isNotBlank() && !isPairing,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
            ) {
                if (isPairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pairing...")
                } else {
                    Text("Confirm & Pair")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isPairing,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ManualDeviceDialog(
    onDismiss: () -> Unit,
    onAdd: (host: String, port: Int, isTls: Boolean) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8443") }
    var isTls by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.widthIn(max = 480.dp),
        onDismissRequest = onDismiss,
        title = { Text("Add Peer Manually") },
        text = {
            Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP Address / Hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port (e.g. 8443)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isTls, onCheckedChange = { isTls = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use HTTPS / TLS")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portNum = port.toIntOrNull() ?: 8443
                    onAdd(host.trim(), portNum, isTls)
                },
                enabled = host.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SendTextDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onAttach: (String?) -> Unit
) {
    var textInput by remember { mutableStateOf(initialText) }

    AlertDialog(
        modifier = Modifier.widthIn(max = 480.dp),
        onDismissRequest = onDismiss,
        title = { Text("Send Text or URL") },
        text = {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text("Message or Link") },
                placeholder = { Text("https://example.com or note...") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onAttach(textInput.trim().ifBlank { null })
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
            ) {
                Text("Attach")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .remoteFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f)
            ) {
                Text("Cancel")
            }
        }
    )
}
