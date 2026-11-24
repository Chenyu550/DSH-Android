package io.github.jackwener.dsh.feature.conversation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshMarkdown
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Spinner
import io.github.jackwener.dsh.core.designsystem.pressable
import io.github.jackwener.dsh.core.session.ReasoningTranslator
import io.github.jackwener.dsh.core.session.ReasoningTranslator.Failure
import io.github.jackwener.dsh.core.session.ReasoningTranslator.State

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReasoningTranslatorEntryPoint { fun reasoningTranslator(): ReasoningTranslator }

private fun translatorOf(context: Context): ReasoningTranslator =
    EntryPointAccessors.fromApplication(context.applicationContext, ReasoningTranslatorEntryPoint::class.java).reasoningTranslator()

/**
 * 翻译 under an expanded thinking row (#15): the translation into the UI language goes under the
 * original, 隐藏译文 / 显示译文 toggles it (per row), failures say why with 重试. Not offered while the
 * text still streams, nor when it is already in the UI language's script (DeepSeek's Chinese
 * reasoning for a Chinese UI).
 */
@Composable
fun ReasoningTranslation(rowKey: String, text: String) {
    if (LocalInspectionMode.current) return
    val locale = LocalConfiguration.current.locales[0]
    val wanted = remember(text, locale) { text.isNotBlank() && ReasoningTranslator.needsTranslation(text, locale) }
    if (!wanted) return
    val context = LocalContext.current
    val translator = remember { translatorOf(context) }
    val language = remember(locale) { ReasoningTranslator.targetTag(locale) }
    val states by translator.states.collectAsStateWithLifecycle()
    val state = states[ReasoningTranslator.Key(text, language)]
    var shown by rememberSaveable(rowKey) { mutableStateOf(false) }
    val c = Dsh.colors
    val translate = { shown = true; translator.request(text, language) }

    Column(Modifier.padding(top = 6.dp)) {
        when (state) {
            is State.Done -> if (shown) {
                Text(stringResource(R.string.translation_label), style = DshType.xxxs11, color = c.labelCaption)
                DshMarkdown(state.text, Modifier.padding(top = 2.dp), compact = true)
                Action(stringResource(R.string.translation_hide)) { shown = false }
            } else Action(stringResource(R.string.translation_show)) { shown = true }
            State.Loading -> Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(12.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.translation_running), style = DshType.xxs12, color = c.labelTertiary)
            }
            is State.Failed -> {
                Text(failureText(state), Modifier.padding(top = 2.dp), style = DshType.xxs12, color = c.error)
                Action(stringResource(R.string.retry), translate)
            }
            null -> Action(stringResource(R.string.translation_translate), translate)
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(label, Modifier.pressable(onClick, Radius.sm).padding(vertical = 4.dp), style = DshType.xxs12m, color = Dsh.colors.labelSecondary)
}

@Composable
private fun failureText(f: State.Failed): String = when (f.reason) {
    Failure.Unreachable -> stringResource(R.string.translation_err_unreachable)
    Failure.BadResponse -> stringResource(R.string.translation_err_response)
    Failure.Engine -> stringResource(R.string.translation_err_engine)
    Failure.Other -> stringResource(R.string.translation_err_other, f.detail.ifBlank { "?" })
}
