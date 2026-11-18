package me.rerere.rikkahub.ui.components.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatErrorSolution
import me.rerere.rikkahub.ui.context.LocalNavController
import kotlin.uuid.Uuid

@Composable
fun ErrorCardsDisplay(
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    modifier: Modifier = Modifier,
    onCompactAndRetry: ((Uuid) -> Unit)? = null,
    onRetryLastTurn: ((Uuid) -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = errors.isNotEmpty(),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // 清除全部按钮（当有多个错误时显示）
            if (errors.size > 1) {
                Surface(
                    onClick = onClearAllErrors,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = stringResource(R.string.chat_page_clear_all_errors),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // 错误卡片列表
            errors.forEach { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { onDismissError(error.id) },
                    onCompactAndRetry = onCompactAndRetry?.let { action -> { action(error.id) } },
                    onRetryLastTurn = onRetryLastTurn?.let { action -> { action(error.id) } },
                )
            }
        }
    }
}

@Composable
fun ErrorCard(
    error: ChatError,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCompactAndRetry: (() -> Unit)? = null,
    onRetryLastTurn: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val checkFastModelSettings = stringResource(R.string.chat_page_check_fast_model_settings)
    val linkColor = MaterialTheme.colorScheme.primary
    var isExpanded by remember { mutableStateOf(false) }
    val rawMessage = error.error.message ?: "Unknown error"
    val httpEx = generateSequence(error.error) { it.cause }
        .filterIsInstance<me.rerere.ai.util.HttpException>()
        .firstOrNull()
    val rawBody = httpEx?.rawBody?.takeIf { it.isNotBlank() }
    val hasDistinctRawBody = rawBody != null && rawBody.trim() != rawMessage.trim()
    val displayMessage = if (rawMessage.contains("bad_response_status_code", ignoreCase = true) &&
        rawMessage.contains("openai_error", ignoreCase = true)
    ) {
        "$rawMessage\n(Upstream relay error: the provider's upstream channel returned an error status for this model or endpoint)"
    } else {
        rawMessage
    }
    val providerModelLabel = listOfNotNull(
        error.providerName?.takeIf { it.isNotBlank() },
        error.modelName?.takeIf { it.isNotBlank() },
    ).joinToString(" / ").takeIf { it.isNotBlank() }
    val isContextOrCompressionError = rawMessage.contains("ContextWindowExceeded", ignoreCase = true) ||
        rawMessage.contains("context length", ignoreCase = true) ||
        rawMessage.contains("longer than the model", ignoreCase = true) ||
        rawMessage.contains("maximum context", ignoreCase = true) ||
        rawMessage.contains("compressed summary", ignoreCase = true) ||
        (error.title?.contains("compression", ignoreCase = true) == true)
    val hasActionChips = (isContextOrCompressionError && onCompactAndRetry != null) || onRetryLastTurn != null

    // Actionable or user-expanded errors stay visible longer (never dismiss while expanded)
    LaunchedEffect(error.id, hasActionChips, isExpanded) {
        if (!isExpanded) {
            delay(if (hasActionChips) 12_000L else 5_000L)
            onDismiss()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (error.title != null) {
                        Text(
                            text = if (providerModelLabel != null) "${error.title} ($providerModelLabel)" else error.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (providerModelLabel != null) {
                        Text(
                            text = providerModelLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = displayMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                        overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { isExpanded = !isExpanded },
                    )
                    if (hasDistinctRawBody) {
                        if (isExpanded) {
                            Text(
                                text = "Original response:\n$rawBody",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        } else {
                            Text(
                                text = "Show raw response",
                                style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
                                color = linkColor,
                                modifier = Modifier.clickable { isExpanded = true },
                            )
                        }
                    }
                    if (error.solution == ChatErrorSolution.CheckFastModelSettings) {
                        Text(
                            text = buildAnnotatedString {
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = "check_fast_model_settings",
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = linkColor,
                                                textDecoration = TextDecoration.Underline,
                                            )
                                        ),
                                        linkInteractionListener = {
                                            navController.navigate(Screen.SettingModels)
                                        },
                                    )
                                ) {
                                    append(checkFastModelSettings)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val fullErrorText = buildString {
                            append(rawMessage)
                            if (hasDistinctRawBody) {
                                append("\n\nOriginal provider response:\n")
                                append(rawBody)
                            }
                        }
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    clipData = ClipData.newPlainText("Error", fullErrorText)
                                )
                            )
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = stringResource(R.string.accessibility_copy_error_message),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.chat_page_dismiss_error),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (hasActionChips) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onCompactAndRetry != null) {
                        Surface(
                            onClick = onCompactAndRetry,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        ) {
                            Text(
                                text = "⚡ Compact & Continue",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    if (onRetryLastTurn != null) {
                        Surface(
                            onClick = onRetryLastTurn,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                        ) {
                            Text(
                                text = "🔄 Retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
