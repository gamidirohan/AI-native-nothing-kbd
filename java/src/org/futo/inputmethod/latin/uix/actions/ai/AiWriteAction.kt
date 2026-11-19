package org.futo.inputmethod.latin.uix.actions.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalManager
import org.futo.inputmethod.latin.uix.TextTarget

private sealed interface RewriteUiState {
    data object Idle : RewriteUiState
    data class Working(val partial: String) : RewriteUiState
    data class Done(val text: String) : RewriteUiState
    data class Failed(val message: String) : RewriteUiState
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

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AI Writing", text))
}

@Composable
private fun AiWriteScreen(manager: KeyboardManagerForAction) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { RewriteEngineProvider.get(context) }

    var category by remember { mutableStateOf(RewriteCategory.Editor) }
    var selectedStyle by remember { mutableStateOf<RewriteStyle?>(null) }
    var target by remember { mutableStateOf<TextTarget?>(manager.getTextTarget()) }
    var state by remember { mutableStateOf<RewriteUiState>(RewriteUiState.Idle) }
    var copied by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun refreshTarget() {
        job?.cancel()
        target = manager.getTextTarget()
        selectedStyle = null
        state = RewriteUiState.Idle
        copied = false
    }

    fun run(style: RewriteStyle) {
        val currentTarget = target ?: return
        job?.cancel()
        selectedStyle = style
        copied = false
        state = RewriteUiState.Working("")
        job = scope.launch {
            try {
                val result = engine.rewrite(RewriteRequest(style, currentTarget.text)) {
                    state = RewriteUiState.Working(it)
                }
                state = RewriteUiState.Done(result.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RewriteException.ModelMissing) {
                state = RewriteUiState.Failed(
                    context.getString(
                        R.string.ai_write_model_missing,
                        LlmModelStore.defaultModelFile(context).absolutePath
                    )
                )
            } catch (e: Exception) {
                state = RewriteUiState.Failed(e.message ?: "Unknown error")
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
            val t = target
            Text(
                text = when {
                    t == null -> stringResource(R.string.ai_write_no_text)
                    t.isSelection -> stringResource(R.string.ai_write_target_selection, t.text.length)
                    else -> stringResource(R.string.ai_write_target_all, t.text.length)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { refreshTarget() }) {
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val done = state as? RewriteUiState.Done
            val currentTarget = target

            Button(
                enabled = done != null && currentTarget != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (done == null || currentTarget == null) return@Button
                    if (manager.replaceTextTarget(currentTarget, done.text)) {
                        manager.closeActionWindow()
                    } else {
                        state = RewriteUiState.Failed(context.getString(R.string.ai_write_text_changed))
                    }
                }
            ) { Text(stringResource(R.string.ai_write_replace)) }

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
    persistentState = null,
    canShowKeyboard = false,
    windowImpl = { manager, _ ->
        object : ActionWindow {
            @Composable
            override fun windowName(): String = stringResource(R.string.ai_write_action_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                AiWriteScreen(manager)
            }

            override fun close() {}
        }
    }
)
