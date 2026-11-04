package com.altaris.linux.ui.terminal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.files.Attachments
import com.altaris.linux.ui.theme.FrostHairline
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Attach a file to the running agent.
 *
 * Three entry points, because the Android picker does not have one: an image,
 * a video, or any file. Each copies into shared storage and hands the shell a
 * line naming the path — the agent reads it like any other file, and the user
 * can still edit the message before it is sent.
 *
 * The copy happens off the main thread and the sheet stays open while it runs;
 * a 40 MB video is not instantaneous and a silent pause would read as a hang.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalAttachSheet(
    onDismissRequest: () -> Unit,
    onSend: (text: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Attachments.Saved?>(null) }

    fun handle(uri: Uri?) {
        if (uri == null) return
        busy = true
        status = "Copying…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                Attachments.save(context, uri)
            }
            busy = false
            result.onSuccess {
                saved = it
                status = it.name + " · " + humanSize(it.bytes)
            }.onFailure {
                status = it.message ?: "Could not read that file"
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> handle(uri) }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> handle(uri) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> handle(uri) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Send a file to the agent",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                "The file is copied to " + Attachments.GUEST_DIR +
                    ", which every shell can read. The agent gets its path.",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = TextSecondary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachTile(
                    icon = Icons.Default.Image,
                    label = "Image",
                    enabled = !busy,
                ) {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                AttachTile(
                    icon = Icons.Default.Movie,
                    label = "Video",
                    enabled = !busy,
                ) {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }
                AttachTile(
                    icon = Icons.Default.InsertDriveFile,
                    label = "File",
                    enabled = !busy,
                ) {
                    filePicker.launch(arrayOf("*/*"))
                }
            }

            if (status.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saved != null) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        status,
                        fontSize = 10.sp,
                        color = if (saved != null) TextPrimary else TextSecondary,
                    )
                }
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                placeholder = {
                    Text(
                        "What should the agent do with it?",
                        fontSize = 11.sp,
                        color = TextMuted,
                    )
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) { Text("Cancel") }
                TextButton(
                    enabled = saved != null && !busy,
                    onClick = {
                        val attachment = saved ?: return@TextButton
                        onSend(Attachments.prompt(attachment, message))
                        onDismissRequest()
                    },
                ) {
                    Text(
                        "Send",
                        color = if (saved != null) MaterialTheme.colorScheme.primary
                        else TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier = Modifier
            .width(84.dp)
            .height(66.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.primary else TextMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 9.5.sp,
            color = if (enabled) TextPrimary else TextMuted,
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
    else -> (bytes / (1024 * 1024)).toString() + " MB"
}
