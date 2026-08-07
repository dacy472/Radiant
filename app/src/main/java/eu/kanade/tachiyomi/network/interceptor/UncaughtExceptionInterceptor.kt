package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Required by modern Keiyoushi/Mihon extensions. Extensions validate that the host app's
 * default [okhttp3.OkHttpClient] includes this interceptor before making requests.
 */
class UncaughtExceptionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: Exception) {
            throw when (e) {
                is IOException -> e
                is RuntimeException -> e
                else -> IOException(e.message, e)
            }
        }
    }
}
