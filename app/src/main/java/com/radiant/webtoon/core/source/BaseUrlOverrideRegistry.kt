package com.radiant.webtoon.core.source

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe host rewrite table consumed by [BaseUrlRewriteInterceptor].
 * Updated by [ExtensionManager] when overrides or installed sources change.
 */
object BaseUrlOverrideRegistry {

    private val hostRewrites = AtomicReference(emptyMap<String, String>())

    fun snapshot(): Map<String, String> = hostRewrites.get()

    fun update(rewrites: Map<String, String>) {
        hostRewrites.set(rewrites.toMap())
    }

    fun clear() {
        hostRewrites.set(emptyMap())
    }
}
