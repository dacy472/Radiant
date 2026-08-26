package com.radiant.webtoon.ui.extensions;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.radiant.webtoon.WebtoonApplication;

import eu.kanade.tachiyomi.source.ConfigurableSource;

/**
 * Java fragment so Kotlin does not need to resolve DialogPreference.TargetFragment
 * (supertype of PreferenceFragmentCompat) at the call site.
 */
public class SourcePreferenceFragment extends PreferenceFragmentCompat {
    private static final String ARG_SOURCE_ID = "source_id";

    public static SourcePreferenceFragment newInstance(String sourceId) {
        SourcePreferenceFragment fragment = new SourcePreferenceFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SOURCE_ID, sourceId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Bundle args = requireArguments();
        String sourceId = args.getString(ARG_SOURCE_ID, "");
        getPreferenceManager().setSharedPreferencesName("source_" + sourceId);
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        WebtoonApplication app = (WebtoonApplication) requireContext().getApplicationContext();
        ConfigurableSource configurable = app.getExtensionManager().getConfigurableSource(sourceId);
        if (configurable != null) {
            configurable.setupPreferenceScreen(screen);
        }
    }
}
