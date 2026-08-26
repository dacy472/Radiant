package com.radiant.webtoon.ui.extensions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.radiant.webtoon.core.source.ExtensionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettingsScreen(
    sourceId: String,
    extensionManager: ExtensionManager,
    onBackClick: () -> Unit,
) {
    val sourceName = extensionManager.getSourceName(sourceId) ?: "Source"
    val configurable = extensionManager.getConfigurableSource(sourceId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$sourceName Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (configurable == null) {
            Text(
                text = "This source has no configurable settings.",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                factory = { ctx ->
                    val containerId = android.view.View.generateViewId()
                    val frame = android.widget.FrameLayout(ctx).apply { id = containerId }
                    val activity = ctx as? FragmentActivity
                    if (activity != null) {
                        val tag = "source_prefs_$sourceId"
                        if (activity.supportFragmentManager.findFragmentByTag(tag) == null) {
                            // Factory returns Fragment so Kotlin never resolves PreferenceFragmentCompat
                            activity.supportFragmentManager
                                .beginTransaction()
                                .replace(containerId, SourcePreferenceFragments.create(sourceId), tag)
                                .commitNowAllowingStateLoss()
                        }
                    }
                    frame
                },
            )
        }
    }
}
