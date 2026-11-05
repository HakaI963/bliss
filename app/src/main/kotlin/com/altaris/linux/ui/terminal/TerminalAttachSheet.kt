package com.altaris.linux.ui.terminal

import android.content.Context
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.files.Attachments
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a file for the agent.
 *
 * This is only a chooser: tapping a row opens the system picker, the chosen
 * file is copied into shared storage, and its path is written into the shell's
 * input line. There is no message box and no "what should the agent do" field —
 * the user types the question themselves, exactly as if they had pasted a path.
 *
 * The previous version wrapped this in its own form with a text field and a
 * Send button. That put the app between the user and their agent: the message
 * was composed for them and sent in one step, so they could not add to it or
 * edit it before it ran. A file picker should end when the file is picked.
 *
 * Three rows, because the Android picker does not have one entry point: the
 * photo picker is the right surface for images and video (it has no permission
 * prompt and shows thumbnails), and the document picker is the only thing that
 * can reach arbitrary files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalAttachSheet(
    onDismissRequest: () -> Unit,
    onPicked: (text: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun handle(uri: Uri?) {
        if (uri == null) {
            // The user backed out of the system picker; nothing to do, and the
            // sheet should not sit there pretending something happened.
            onDismissRequest()
            return
        }
        onDismissRequest()
        scope.launch {
            val result = withContext(Dispatchers.IO) { Attachments.save(context, uri) }
            result.onSuccess { onPicked(Attachments.reference(it)) }
                .onFailure { showToast(context, it.message ?: "Could not read that file") }
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
                .heightIn(max = 260.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Attach a file",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            PickerRow(Icons.Default.Image, "Image") {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
            PickerRow(Icons.Default.Movie, "Video") {
                videoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
            PickerRow(Icons.Default.InsertDriveFile, "File") {
                filePicker.launch(arrayOf("*/*"))
            }
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 12.5.sp, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text("›", fontSize = 15.sp, color = TextMuted)
    }
}

private fun showToast(context: Context, message: String) {
    android.os.Handler(context.mainLooper).post {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
