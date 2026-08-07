package com.radiant.webtoon.ui.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.ui.extensions.ExtensionsScreen
import com.radiant.webtoon.ui.search.SearchScreen

@Composable
fun DiscoverScreen(
    extensionManager: ExtensionManager,
    onWebtoonClick: (String, String) -> Unit,
    onBrowseClick: (String) -> Unit,
    onSearchClick: () -> Unit
) {
    ExtensionsScreen(
        extensionManager = extensionManager,
    )
}
