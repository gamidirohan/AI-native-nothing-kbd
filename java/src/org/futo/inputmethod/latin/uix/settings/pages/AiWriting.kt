package org.futo.inputmethod.latin.uix.settings.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.actions.ai.AI_AUTO_TRANSLATE_CLIPBOARD
import org.futo.inputmethod.latin.uix.actions.ai.AI_DOWNLOAD_WIFI_ONLY
import org.futo.inputmethod.latin.uix.actions.ai.LlmModelDownloader
import org.futo.inputmethod.latin.uix.actions.ai.LlmModelStore
import org.futo.inputmethod.latin.uix.actions.ai.ModelDownloadState
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.SettingToggleSharedPrefs
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.settings.Tip

private fun formatMegabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

@Composable
fun AiWritingScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped whenever the model file may have changed, so the status below is read again.
    var refresh by remember { mutableIntStateOf(0) }
    val installedBytes = remember(refresh) { LlmModelStore.installedSizeBytes(context) }
    val installed = installedBytes > 0

    val downloadState by LlmModelDownloader.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(downloadState) {
        if (downloadState is ModelDownloadState.Done) {
            refresh++
            LlmModelDownloader.reset()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { LlmModelStore.importFrom(context, uri) } }
            importing = false
            refresh++
            message = result.fold(
                onSuccess = { "Model imported." },
                onFailure = { it.message ?: "Import failed." }
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the AI model?") },
            text = { Text("This frees ${formatMegabytes(installedBytes)}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    LlmModelStore.delete(context)
                    LlmModelDownloader.reset()
                    refresh++
                    message = "Model deleted."
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    ScrollableList {
        ScreenTitle("AI Writing", showBack = true, navController)

        Tip(
            "AI Writing fixes grammar and spelling and changes the tone of your text. " +
                    "It runs on your phone with a downloaded model, so your text is not sent anywhere. " +
                    "Open it from the sparkle button on the keyboard toolbar, or long-press the emoji button."
        )

        val modelStatus = when {
            importing -> "Importing…"
            installed -> "${LlmModelStore.DEFAULT_MODEL.displayName} · ${formatMegabytes(installedBytes)} installed"
            else -> "Not installed"
        }
        SettingItem(title = "Model", subtitle = modelStatus) {}

        if (!installed) {
            val downloadSubtitle = when (val s = downloadState) {
                is ModelDownloadState.Downloading ->
                    "Downloading… ${formatMegabytes(s.downloadedBytes)} of ${formatMegabytes(s.totalBytes)}"
                is ModelDownloadState.Verifying -> "Checking the file…"
                is ModelDownloadState.WaitingForUnmetered -> "Waiting for Wi-Fi. Turn off Wi-Fi only below to use mobile data."
                is ModelDownloadState.Failed -> s.message
                else -> "${formatMegabytes(LlmModelStore.DEFAULT_MODEL.sizeBytes)} from Hugging Face"
            }
            val downloading = downloadState is ModelDownloadState.Downloading ||
                    downloadState is ModelDownloadState.Verifying

            SettingItem(
                title = if (downloading) "Cancel download" else "Download model",
                subtitle = downloadSubtitle,
                onClick = {
                    if (downloading) LlmModelDownloader.cancel(context) else LlmModelDownloader.start(context)
                }
            ) {}
        }

        SettingItem(
            title = "Import model file",
            subtitle = "Choose a .litertlm file from your phone. It replaces the installed model.",
            disabled = importing,
            onClick = { importLauncher.launch(arrayOf("*/*")) }
        ) {}

        if (installed) {
            SettingItem(
                title = "Delete model",
                subtitle = "Frees ${formatMegabytes(installedBytes)}",
                onClick = { confirmDelete = true }
            ) {}
        }

        message?.let { Tip(it) }

        SettingToggleDataStore(
            title = "Download on Wi-Fi only",
            subtitle = "Avoid using mobile data for the model download",
            setting = AI_DOWNLOAD_WIFI_ONLY
        )

        SettingToggleSharedPrefs(
            title = "AI reply key (~)",
            subtitle = "Adds a ~ key next to the spacebar. Copy a message, tap ~, and AI " +
                    "Writing drafts a reply on your phone. Long-pressing the emoji button " +
                    "opens AI Writing either way.",
            key = Settings.PREF_AI_ASSIST_KEY,
            default = false
        )

        SettingToggleDataStore(
            title = "Auto-translate copied text",
            subtitle = "When you open AI Writing with newly copied text, translate it right away " +
                    "so you can insert it. Uses the language chosen in the Translate option.",
            setting = AI_AUTO_TRANSLATE_CLIPBOARD
        )
    }
}
