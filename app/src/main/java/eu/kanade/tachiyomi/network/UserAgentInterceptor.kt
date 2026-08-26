package eu.kanade.tachiyomi.network

/**
 * Host-package alias for Mihon's interceptor-package [eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor].
 */
class UserAgentInterceptor(
    defaultUserAgentProvider: () -> String = { DEFAULT_USER_AGENT },
) : eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor(defaultUserAgentProvider) {

    constructor(initialUserAgent: String) : this({ initialUserAgent })

    companion object {
        const val DEFAULT_USER_AGENT =
            eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor.DEFAULT_USER_AGENT
        const val FALLBACK_USER_AGENT: String = DEFAULT_USER_AGENT
    }
}
