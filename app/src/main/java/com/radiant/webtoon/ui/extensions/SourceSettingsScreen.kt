package com.radiant.webtoon.ui.extensions

import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import com.radiant.webtoon.WebtoonApplication
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
                            activity.supportFragmentManager
                                .beginTransaction()
                                .replace(
                                    containerId,
                                    SourcePreferenceFragment.newInstance(sourceId),
                                    tag,
                                )
                                .commitNowAllowingStateLoss()
                        }
                    }
                    frame
                },
            )
        }
    }
}

class SourcePreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val sourceId = requireArguments().getString(ARG_SOURCE_ID).orEmpty()
        preferenceManager.sharedPreferencesName = "source_$sourceId"
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen

        val app = requireContext().applicationContext as WebtoonApplication
        val configurable = app.extensionManager.getConfigurableSource(sourceId)
        configurable?.setupPreferenceScreen(screen)
    }

    companion object {
        private const val ARG_SOURCE_ID = "source_id"

        fun newInstance(sourceId: String): SourcePreferenceFragment {
            return SourcePreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE_ID, sourceId)
                }
            }
        }
    }
}
