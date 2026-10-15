package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.ui.theme.FolioDanger
import com.ivarna.fluxlinux.ui.theme.FolioFrostBorder
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary

/**
 * Folio active-desktop card: single frost container.
 * Primary white "Open X11" pill + ghost "Copy Log" / "Stop" pills.
 * No neon accents.
 */
@Composable
fun ActiveDesktopCard(
    session: DesktopSession,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(18.dp)
    val isRunning = session.phase == DesktopSession.Phase.Running
    val deName = if (session.type == DesktopSession.Type.KDE) "KDE Plasma" else "XFCE4"
    val titleText = if (isRunning) {
        "${session.distroName} desktop is running"
    } else {
        "Starting ${session.distroName} desktop…"
    }
    val subtitleText = "$deName on :0 · ${if (isRunning) "running" else "starting"}"
    val a11yDesc = "$titleText. $subtitleText"

    val distro = DistroRepository.supportedDistros.find { it.id == session.distroId }
    val iconRes = distro?.iconRes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(FrostSurface)
            .border(1.dp, FolioFrostBorder, cardShape)
            .semantics(mergeDescendants = true) {
                contentDescription = a11yDesc
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, FrostHairline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (iconRes != null) {
                    Image(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.DesktopWindows,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitleText,
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Primary: Open X11 (white pill, dark text)
        Button(
            onClick = onOpen,
            colors = ButtonDefaults.buttonColors(
                containerColor = TextPrimary,
                contentColor = Color(0xFF09090B)
            ),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open X11", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Ghost row: Copy Log + Stop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onLogs,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FrostHairline),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy Log", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = onStop,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = FolioDanger
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FrostHairline),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
