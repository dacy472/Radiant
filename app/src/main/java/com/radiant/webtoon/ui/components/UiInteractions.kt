package com.radiant.webtoon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

object AppMotion {
    /** Press and selection. Short so a tap is never waiting on the motion. */
    const val PressMs = 160

    /** Reader chrome only. Page images stay still. */
    const val ChromeMs = 180

    fun <T> press() = tween<T>(durationMillis = PressMs, easing = FastOutSlowInEasing)

    fun <T> chrome() = tween<T>(durationMillis = ChromeMs, easing = FastOutSlowInEasing)

    /** M3 Expressive "default spatial" spring: position and size, no visible overshoot. */
    fun <T> spatial() = spring<T>(dampingRatio = 0.9f, stiffness = 700f)

    /** M3 Expressive "fast spatial" spring with a small overshoot, for icons and selection pops. */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.6f, stiffness = 1400f)
}

/** Material-themed ripple touch feedback for list rows. */
fun Modifier.listItemRippleClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier = composed {
    clickable(
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.listItemRippleCombinedClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean = true,
): Modifier = composed {
    combinedClickable(
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * List rows used to animate their height on every recomposition, which relaid out
 * long chapter and history lists while scrolling. Rows stay fixed; removal still
 * animates through [AnimatedListRow].
 */
fun Modifier.animatedListRow(): Modifier = this

/**
 * Fades and collapses a row when [visible] becomes false — use before removing
 * the item from backing data for a smooth delete/cancel exit.
 */
@Composable
fun AnimatedListRow(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(AppMotion.press()) + expandVertically(AppMotion.press()),
        exit = fadeOut(AppMotion.press()) + shrinkVertically(
            animationSpec = AppMotion.press(),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        content()
    }
}

@Composable
fun SpringAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { transition.targetState = true }

    LaunchedEffect(transition.currentState, transition.targetState) {
        if (!transition.currentState && !transition.targetState) {
            onDismissRequest()
        }
    }

    fun requestDismiss() {
        transition.targetState = false
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = properties,
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = scaleIn(
                initialScale = 0.96f,
                animationSpec = AppMotion.press(),
            ) + fadeIn(animationSpec = AppMotion.press()),
            exit = scaleOut(
                targetScale = 0.96f,
                animationSpec = AppMotion.press(),
            ) + fadeOut(animationSpec = AppMotion.press()),
        ) {
            Surface(
                modifier = modifier,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    if (icon != null) {
                        Box(Modifier.fillMaxWidth()) {
                            icon()
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (title != null) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            title()
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (text != null) {
                        ProvideTextStyle(
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        ) {
                            text()
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissButton != null) {
                            dismissButton()
                        }
                        Spacer(Modifier.width(8.dp))
                        confirmButton()
                    }
                }
            }
        }
    }
}
