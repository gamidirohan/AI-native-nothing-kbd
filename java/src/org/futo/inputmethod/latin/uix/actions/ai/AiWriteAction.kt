package org.futo.inputmethod.latin.uix.actions.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.TextTarget
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.settings.useDataStore

private sealed interface RewriteUiState {
    data object Idle : RewriteUiState
    data class Working(val partial: String) : RewriteUiState
    data class Done(val text: String) : RewriteUiState
    data class Failed(val message: String) : RewriteUiState
}

/** Where the text to rewrite comes from. */
private enum class TextSource { Field, Clipboard }

/** Releases the model when the system asks the keyboard to free memory. */
private class AiWritePersistentState : PersistentActionState {
    override suspend fun cleanUp() {
        RewriteEngineProvider.release()
    }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceBright,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(24.dp),
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize()
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, maxLines = 1)
        }
    }
}

private val compactTextButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AI Writing", text))
}

private fun openAiWritingSettings(context: Context) {
    val intent = Intent(context, SettingsActivity::class.java)
    intent.putExtra("navDest", "aiWriting")
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    context.startActivity(intent)
}

@Composable
private fun ModelDownloadCard(
    state: ModelDownloadState,
    onStart: () -> Unit,
    onStartAnyway: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val spec = LlmModelStore.DEFAULT_MODEL
    val totalMb = (spec.sizeBytes / (1024 * 1024)).toInt()

    Column(
        modifier = Modifier
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.ai_write_download_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(R.string.ai_write_download_desc, spec.displayName, totalMb),
            style = MaterialTheme.typography.bodySmall
        )

        when (state) {
            is ModelDownloadState.Downloading -> {
                val fraction = if (state.totalBytes > 0) {
                    (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        R.string.ai_write_download_progress,
                        (state.downloadedBytes / (1024 * 1024)).toInt(),
                        (state.totalBytes / (1024 * 1024)).toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.ai_write_cancel)) }
            }

            is ModelDownloadState.Verifying -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.ai_write_verifying), style = MaterialTheme.typography.bodySmall)
            }

            is ModelDownloadState.WaitingForUnmetered -> {
                Text(
                    stringResource(R.string.ai_write_download_wifi_only),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartAnyway) {
                        Text(stringResource(R.string.ai_write_download_anyway))
                    }
                    OutlinedButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.ai_write_settings))
                    }
                }
            }

            is ModelDownloadState.Failed -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onStart) { Text(stringResource(R.string.ai_write_retry)) }
            }

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart) {
                        Text(stringResource(R.string.ai_write_download_button, totalMb))
                    }
                    OutlinedButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.ai_write_import_file))
                    }
                }
            }
        }
    }
}

@Composable
private fun AiWriteScreen(manager: KeyboardManagerForAction) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { RewriteEngineProvider.get(context) }

    val levelSetting = useDataStore(AI_FORMAT_LEVEL)
    val languageSetting = useDataStore(AI_TRANSLATE_TARGET)
    val level = FormatLevel.fromOrdinal(levelSetting.value)
    val languageCode = languageSetting.value

    var category by remember { mutableStateOf(RewriteCategory.Editor) }
    var selectedStyle by remember { mutableStateOf<RewriteStyle?>(null) }
    var source by remember { mutableStateOf(TextSource.Field) }
    var fieldTarget by remember { mutableStateOf<TextTarget?>(manager.getTextTarget()) }
    var clipboardText by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<RewriteUiState>(RewriteUiState.Idle) }
    var copied by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var installed by remember { mutableStateOf(LlmModelStore.isInstalled(context)) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    val downloadState by LlmModelDownloader.state.collectAsState()

    LaunchedEffect(downloadState) {
        if (downloadState is ModelDownloadState.Done) {
            installed = LlmModelStore.isInstalled(context)
            LlmModelDownloader.reset()
        }
    }

    /** The text the chosen style will be applied to. */
    fun inputText(): String? = when (source) {
        TextSource.Field -> fieldTarget?.text
        TextSource.Clipboard -> clipboardText
    }

    fun run(style: RewriteStyle) {
        val text = inputText() ?: return
        if (!installed) return
        job?.cancel()
        selectedStyle = style
        copied = false
        notice = null
        state = RewriteUiState.Working("")
        job = scope.launch {
            try {
                // Read the settings at the moment of the tap, so a change is never missed.
                val currentLevel = FormatLevel.fromOrdinal(context.getSetting(AI_FORMAT_LEVEL))
                val currentLanguage = AiTranslateLanguages.name(context.getSetting(AI_TRANSLATE_TARGET))

                val result = engine.rewrite(
                    RewriteRequest(style, text, currentLevel, currentLanguage)
                ) { state = RewriteUiState.Working(it) }
                state = RewriteUiState.Done(result.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RewriteException.ModelMissing) {
                installed = false
                state = RewriteUiState.Idle
            } catch (e: Exception) {
                state = RewriteUiState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    fun useField() {
        job?.cancel()
        source = TextSource.Field
        fieldTarget = manager.getTextTarget()
        selectedStyle = null
        state = RewriteUiState.Idle
        copied = false
        notice = null
    }

    fun useClipboard() {
        job?.cancel()
        val text = readClipboardText(context)
        if (text == null) {
            notice = context.getString(R.string.ai_write_clipboard_empty)
            return
        }
        source = TextSource.Clipboard
        clipboardText = text
        selectedStyle = null
        state = RewriteUiState.Idle
        copied = false
        notice = null
    }

    // When the panel opens: text handed over from the clipboard history, or newly copied text
    // that should be translated automatically.
    LaunchedEffect(Unit) {
        val handed = AiWriteHandoff.takePendingClipboardText()
        if (handed != null) {
            source = TextSource.Clipboard
            clipboardText = handed
            return@LaunchedEffect
        }

        if (context.getSetting(AI_AUTO_TRANSLATE_CLIPBOARD) && LlmModelStore.isInstalled(context)) {
            val copiedText = readClipboardText(context)
            if (copiedText != null && AiWriteHandoff.isNewForAutoTranslate(copiedText)) {
                AiWriteHandoff.markAutoHandled(copiedText)
                source = TextSource.Clipboard
                clipboardText = copiedText
                category = RewriteCategory.Tone
                run(RewriteStyle.Translate)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RewriteCategory.entries.forEach {
                Pill(
                    label = stringResource(it.label),
                    selected = category == it,
                    modifier = Modifier.weight(1f)
                ) { category = it }
            }
            // Tap to cycle Light -> Balanced -> Strong.
            Pill(
                label = stringResource(R.string.ai_write_level_label, stringResource(level.label)),
                selected = false,
                modifier = Modifier.weight(1.6f)
            ) {
                levelSetting.setValue((level.ordinal + 1) % FormatLevel.entries.size)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RewriteStyle.forCategory(category)) { style ->
                Pill(
                    label = stringResource(style.label),
                    selected = selectedStyle == style
                ) { run(style) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = inputText()
            val infoText = when {
                notice != null -> notice!!
                text == null -> stringResource(R.string.ai_write_no_text)
                source == TextSource.Clipboard -> stringResource(R.string.ai_write_target_clipboard, text.length)
                fieldTarget?.isSelection == true -> stringResource(R.string.ai_write_target_selection, text.length)
                else -> stringResource(R.string.ai_write_target_all, text.length)
            }
            Text(
                text = infoText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            // Language picker, only for styles that need one.
            if (selectedStyle?.needsLanguage == true) {
                Box {
                    TextButton(onClick = { languageMenuOpen = true }, contentPadding = compactTextButtonPadding) {
                        Text(stringResource(R.string.ai_write_translate_to, AiTranslateLanguages.name(languageCode)))
                    }
                    DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_write_translate_note)) },
                            onClick = {},
                            enabled = false
                        )
                        AiTranslateLanguages.languages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    languageMenuOpen = false
                                    scope.launch {
                                        // Save first: run() reads the saved language.
                                        context.setSetting(AI_TRANSLATE_TARGET, code)
                                        run(RewriteStyle.Translate)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (source == TextSource.Field) {
                TextButton(onClick = { useClipboard() }, contentPadding = compactTextButtonPadding) {
                    Text(stringResource(R.string.ai_write_use_clipboard))
                }
            } else {
                TextButton(onClick = { useField() }, contentPadding = compactTextButtonPadding) {
                    Text(stringResource(R.string.ai_write_use_field))
                }
            }
            TextButton(onClick = { if (source == TextSource.Clipboard) useClipboard() else useField() },
                contentPadding = compactTextButtonPadding) {
                Text(stringResource(R.string.ai_write_refresh))
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceBright,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (!installed) {
                ModelDownloadCard(
                    state = downloadState,
                    onStart = { LlmModelDownloader.start(context) },
                    onStartAnyway = { LlmModelDownloader.start(context, allowMetered = true) },
                    onCancel = { LlmModelDownloader.cancel(context) },
                    onOpenSettings = { openAiWritingSettings(context) }
                )
            } else {
                val body = when (val s = state) {
                    is RewriteUiState.Idle -> stringResource(R.string.ai_write_idle_hint)
                    is RewriteUiState.Working -> s.partial.ifEmpty { stringResource(R.string.ai_write_working) }
                    is RewriteUiState.Done -> s.text
                    is RewriteUiState.Failed -> s.message
                }
                Text(
                    text = body,
                    color = if (state is RewriteUiState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val done = state as? RewriteUiState.Done
            val currentTarget = fieldTarget

            Button(
                enabled = done != null && (source == TextSource.Clipboard || currentTarget != null),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (done == null) return@Button
                    if (source == TextSource.Clipboard) {
                        // Clipboard text goes in at the cursor, replacing any selection.
                        manager.typeText(done.text)
                        manager.closeActionWindow()
                    } else if (currentTarget != null) {
                        if (manager.replaceTextTarget(currentTarget, done.text)) {
                            manager.closeActionWindow()
                        } else {
                            state = RewriteUiState.Failed(context.getString(R.string.ai_write_text_changed))
                        }
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (source == TextSource.Clipboard) R.string.ai_write_insert else R.string.ai_write_replace
                    )
                )
            }

            OutlinedButton(
                enabled = done != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (done == null) return@OutlinedButton
                    copyToClipboard(context, done.text)
                    copied = true
                }
            ) {
                Text(stringResource(if (copied) R.string.ai_write_copied else R.string.ai_write_copy))
            }
        }
    }
}

val AiWriteAction = Action(
    icon = R.drawable.ai_write,
    name = R.string.ai_write_action_title,
    simplePressImpl = null,
    persistentState = { AiWritePersistentState() },
    canShowKeyboard = false,
    windowImpl = { manager, _ ->
        object : ActionWindow {
            @Composable
            override fun windowName(): String = stringResource(R.string.ai_write_action_title)

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) {
                super.WindowTitleBar(rowScope)

                val context = LocalContext.current
                IconButton(onClick = { openAiWritingSettings(context) }) {
                    Icon(
                        painterResource(id = R.drawable.settings),
                        contentDescription = stringResource(R.string.ai_write_settings)
                    )
                }
            }

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                AiWriteScreen(manager)
            }

            override fun close() {}
        }
    }
)
