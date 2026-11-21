package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.learning.LearnedWords
import org.futo.inputmethod.latin.learning.LearnedWordsCore
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleSharedPrefs
import org.futo.inputmethod.latin.uix.settings.Tip

@Composable
fun LearnedWordsScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LearnedWords.load(context) }

    val words by LearnedWords.words.collectAsState()
    val corrections by LearnedWords.corrections.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget all learned words?") },
            text = { Text("They will be autocorrected again. Words already in your personal dictionary stay there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    LearnedWords.clear()
                    // Also forgets the learned typos.
                }) { Text("Forget all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    ScrollableList {
        ScreenTitle("Learned Words", showBack = true, navController)

        Tip(
            "Words you keep: when autocorrect changes a word and you undo it with backspace, " +
                    "or delete the corrected word within a minute, the keyboard remembers the " +
                    "word you typed and stops correcting it. After the second time it is also " +
                    "added to your personal dictionary so it shows up in suggestions. This helps " +
                    "with Telugu, Hindi or Tamil typed in English letters, names and slang.\n\n" +
                    "Your typos: when you delete a word and type a similar one instead, or tap a " +
                    "suggestion in place of what you typed (\"kr\" -> \"it\"), the keyboard " +
                    "learns it. After the second time, it corrects that typo for you."
        )

        SettingToggleSharedPrefs(
            title = "Learn from my corrections",
            subtitle = "Words are never learned in password or incognito fields",
            key = Settings.PREF_LEARN_FROM_CORRECTIONS,
            default = true
        )

        SettingItem(
            title = "Edit personal dictionary",
            subtitle = "Words added after the second time are saved here",
            onClick = {
                val intent = Intent("android.settings.USER_DICTIONARY_SETTINGS")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        ) {}

        if (corrections.isNotEmpty()) {
            Tip("Your typos (corrected after ${LearnedWordsCore.APPLY_CORRECTION_AFTER} times)")
        }
        corrections.forEach { entry ->
            val status = if (entry.count >= LearnedWordsCore.APPLY_CORRECTION_AFTER) {
                "Fixed ${entry.count} times · corrected automatically"
            } else {
                "Fixed ${entry.count} time · learning"
            }
            SettingItem(title = "${entry.from} → ${entry.to}", subtitle = status) {
                IconButton(onClick = { LearnedWords.removeCorrection(entry.from) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Forget ${entry.from}")
                }
            }
        }

        if (words.isEmpty()) {
            Tip("No learned words yet.")
        } else {
            SettingItem(
                title = "Forget all",
                subtitle = "${words.size} learned words",
                onClick = { confirmClear = true }
            ) {}
        }

        words.forEach { entry ->
            val status = if (entry.rejections >= LearnedWordsCore.SAVE_TO_DICTIONARY_AFTER) {
                "Kept ${entry.rejections} times · in suggestions"
            } else {
                "Kept ${entry.rejections} time · not autocorrected"
            }
            SettingItem(title = entry.word, subtitle = status) {
                IconButton(onClick = { LearnedWords.remove(entry.word) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Forget ${entry.word}")
                }
            }
        }
    }
}
