package eu.kanade.tachiyomi.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Temple Scan's installed APK calls these Chromium boundary methods directly on the host
 * WebView profile. WebKit 1.12.1's interface does not declare them, so the catalogue dies
 * with "No interface method clearOriginMatchedHeader(Ljava/lang/String;Ljava/lang/String;)V".
 */
class WebViewBoundaryCompatTest {

    @Test
    fun profileBoundaryDeclaresTempleScanOriginMatchedHeaders() {
        val profile = Class.forName("org.chromium.support_lib_boundary.ProfileBoundaryInterface")
        val clear = profile.getMethod(
            "clearOriginMatchedHeader",
            String::class.java,
            String::class.java,
        )
        val add = profile.getMethod(
            "addOriginMatchedHeader",
            String::class.java,
            String::class.java,
            Class.forName("java.util.Set"),
        )
        assertEquals(Void.TYPE, clear.returnType)
        assertEquals(Void.TYPE, add.returnType)
    }
}
