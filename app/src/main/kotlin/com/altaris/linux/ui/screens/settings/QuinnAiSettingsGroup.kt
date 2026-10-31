package com.altaris.linux.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.quinn.QuinnModelConfig
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quinn's own endpoint, in Settings.
 *
 * Quinn is a master of the installed agents, but it also needs a brain of its
 * own for the two things no agent provides: driving REA (a tool server with no
 * model) and answering `//` questions about Altaris itself. That endpoint lives
 * here, in one place, so the chat and the REA dashboard read the same config.
 */
@Composable
fun QuinnAiSettingsGroup(
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(QuinnModelConfig.load(context)) }
    var fetching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(config.models.isEmpty()) }

    fun save(next: QuinnModelConfig.Config) {
        config = next
        QuinnModelConfig.save(context, next)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Prism.Tight),
    ) {
        PrismSectionLabel("Quinn AI", accent)
        com.altaris.linux.ui.components.PrismCard(accent = accent) {
            PrismRow(
                title = "Model",
                subtitle = when {
                    !config.configured -> "Add a base URL and API key"
                    config.model.isBlank() -> "No model selected"
                    else -> config.model
                },
                accent = accent,
                leading = { PrismOrb(active = config.configured, accent = accent) },
                trailing = {
                    Text(
                        if (expanded) "Hide" else "Manage",
                        fontSize = 11.sp,
                        color = accent,
                    )
                },
                onClick = { expanded = !expanded },
            )
            PrismNote(
                "Used by Quinn itself: answering // questions about Altaris, and " +
                    "driving REA's tools. Your coding agents keep their own models."
            )

            if (expanded) {
                Spacer(Modifier.height(Prism.Tight))
                OutlinedTextField(
                    value = config.baseUrl,
                    onValueChange = { save(config.copy(baseUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL", fontSize = 10.sp) },
                    placeholder = {
                        Text("https://integrate.api.nvidia.com/v1", fontSize = 11.sp)
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                )
                Spacer(Modifier.height(Prism.Tight))
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { save(config.copy(apiKey = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key", fontSize = 10.sp) },
                    placeholder = { Text("stored on this device only", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                )
                Spacer(Modifier.height(Prism.ControlGap))
                PrismButton(
                    text = if (fetching) "Fetching…" else "Fetch models",
                    accent = accent,
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !fetching && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank(),
                    onClick = {
                        fetching = true
                        message = null
                        scope.launch {
                            val normalized = QuinnModelConfig.normalizeBaseUrl(config.baseUrl)
                            if (normalized == null) {
                                fetching = false
                                message = "That base URL is not http(s)."
                                return@launch
                            }
                            val base = config.copy(baseUrl = normalized)
                            save(base)
                            val fetched = withContext(Dispatchers.IO) { fetchModels(base) }
                            fetching = false
                            if (fetched.isEmpty()) {
                                message = "No models returned. Check the URL and key."
                            } else {
                                save(
                                    base.copy(
                                        models = fetched,
                                        model = base.model.ifBlank { fetched.first() },
                                    )
                                )
                                message = "${fetched.size} models"
                            }
                        }
                    },
                )

                if (config.models.isNotEmpty()) {
                    Spacer(Modifier.height(Prism.Tight))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("Search ${config.models.size} models", fontSize = 11.sp)
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    val shown = QuinnModelConfig.search(config.models, query)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        shown.take(300).forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Prism.ChipRadius))
                                    .clickable { save(config.copy(model = model)) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PrismOrb(
                                    active = model == config.model,
                                    accent = accent,
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    model,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (model == config.model) {
                                        FontWeight.Medium
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = if (model == config.model) {
                                        accent
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(Prism.Tight))
                    PrismNote("Fetch the list to choose a model.")
                }
                if (message != null) {
                    Spacer(Modifier.height(Prism.Tight))
                    PrismNote(message!!)
                }
            }
        }
    }
}

/** Fetch `<base>/models` with the user's key. Empty list means failure. */
private fun fetchModels(config: QuinnModelConfig.Config): List<String> {
    if (config.baseUrl.isBlank() || config.apiKey.isBlank()) return emptyList()
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val request = okhttp3.Request.Builder()
        .url(config.modelsUrl)
        .header("Authorization", "Bearer " + config.apiKey)
        .get()
        .build()
    return runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            QuinnModelConfig.parseModels(response.body?.string().orEmpty())
        }
    }.getOrDefault(emptyList())
}
