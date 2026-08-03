package com.radiant.webtoon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
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
    fun <T> springSnappy() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> springGentle() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
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

/** Gentle height transitions when list row content changes. */
fun Modifier.animatedListRow(): Modifier = composed {
    animateContentSize(animationSpec = AppMotion.springGentle())
}

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
        modifier = modifier.animatedListRow(),
        enter = fadeIn(AppMotion.springGentle()) + expandVertically(AppMotion.springGentle()),
        exit = fadeOut(tween(260)) + shrinkVertically(
            animationSpec = AppMotion.springGentle(),
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
                initialScale = 0.9f,
                animationSpec = AppMotion.springSnappy(),
            ) + fadeIn(animationSpec = AppMotion.springSnappy()),
            exit = scaleOut(
                targetScale = 0.94f,
                animationSpec = tween(durationMillis = 160),
            ) + fadeOut(animationSpec = tween(durationMillis = 140)),
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
