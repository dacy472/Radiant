package com.radiant.webtoon.ui.extensions;

import androidx.fragment.app.Fragment;

/**
 * Kotlin-safe factory: returns [Fragment] so Kotlin never needs to resolve
 * PreferenceFragmentCompat / DialogPreference.TargetFragment from the classpath.
 */
public final class SourcePreferenceFragments {
    private SourcePreferenceFragments() {}

    public static Fragment create(String sourceId) {
        return SourcePreferenceFragment.newInstance(sourceId);
    }
}
