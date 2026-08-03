package com.radiant.webtoon.ui.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.core.model.Extension
import com.radiant.webtoon.ui.components.SourceIcon

@Composable
fun ExtensionIcon(
    extension: Extension,
    modifier: Modifier = Modifier
) {
    SourceIcon(
        icon = extension.icon,
        contentDescription = extension.name,
        size = 40.dp,
        modifier = modifier
    )
}
