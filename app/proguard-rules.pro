# Protect Tachiyomi Bridge
-keep class eu.kanade.tachiyomi.** { *; }
-keepclassmembers class eu.kanade.tachiyomi.** { *; }
-dontwarn eu.kanade.tachiyomi.**

# Protect Core Extension Dependencies
-keep class org.jsoup.** { *; }
-keep class rx.** { *; }
-keep class okhttp3.** { *; }
-keep class kotlinx.serialization.** { *; }

# Kotlin Runtime Protection (For pre-compiled extensions)
-keep class kotlin.coroutines.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.jvm.functions.** { *; }

# CRITICAL: Protect Kotlin synthetic $default methods globally 
# Extensions rely on these for optional parameters.
-keepclassmembers class ** {
    *** *$default(...);
}

# Don't warn about missing okio/okhttp classes that extensions might look for
-dontwarn okio.**
-dontwarn okhttp3.**

# Compatibility Stubs Explicit Keep
-keep class eu.kanade.tachiyomi.AppInfo { *; }
-keep class eu.kanade.tachiyomi.network.OkHttpExtensionsKt { *; }
