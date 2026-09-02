package eu.kanade.tachiyomi.network

import android.content.Context
import com.radiant.webtoon.core.settings.SettingsManager
import eu.kanade.tachiyomi.network.interceptor.BaseUrlRewriteInterceptor
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.ConnectionResetRetryInterceptor
import eu.kanade.tachiyomi.network.interceptor.FlameComicsJsonRepairInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.kanade.tachiyomi.network.interceptor.limitConcurrentRequestsPerHost
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Network helper responsible for providing OkHttpClient instances.
 * Many Tachiyomi/Mihon extensions (like Madara-based ones) depend on this via
 * `Injekt.get<NetworkHelper>()` (registered in ExtensionCompatModule).
 *
 * Interceptor policy:
 * - [UncaughtExceptionInterceptor], [UserAgentInterceptor] (custom UA, else WebView UA),
 *   [AndroidCookieJar], [FlameComicsJsonRepairInterceptor], [CloudflareInterceptor],
 *   then [HttpLoggingInterceptor] HEADERS as the last network interceptor
 * - Default UA is [android.webkit.WebSettings.getDefaultUserAgent] unless Advanced Settings
 *   has a custom User-Agent
 * - Cloudflare DNS-over-HTTPS is the primary resolver (system DNS only if DoH fails)
 * - OkHttp default protocols (HTTP/2 + HTTP/1.1) — do not force HTTP/1.1
 * - Stripped / never added: `IgnoreGzipInterceptor`, `BrotliInterceptor`
 */
class NetworkHelper(context: Context) {

    private val appContext = context.applicationContext
    private val settingsManager = SettingsManager(appContext)
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Cookie jar synchronized with [android.webkit.CookieManager] so Cloudflare
     * `cf_clearance` from WebView bypass is visible to OkHttp / extensions.
     */
    val cookieJar = AndroidCookieJar(appContext)

    /**
     * Device WebView User-Agent from [android.webkit.WebSettings.getDefaultUserAgent].
     */
    private val webViewUserAgent: String = WebViewUserAgentProvider.getDefault(appContext)

    @Volatile
    private var customUserAgent: String = ""

    /**
     * Shared interceptor. Uses Advanced Settings custom UA when set, otherwise [webViewUserAgent].
     */
    val userAgentInterceptor = UserAgentInterceptor { resolveUserAgent() }

    init {
        runCatching {
            runBlocking(Dispatchers.IO) {
                customUserAgent = settingsManager.customUserAgent.first()
            }
        }
        userAgentInterceptor.userAgent = resolveUserAgent()
        helperScope.launch {
            settingsManager.customUserAgent.collect { value ->
                customUserAgent = value
                userAgentInterceptor.userAgent = resolveUserAgent()
            }
        }
    }

    val loggingInterceptor = HttpLoggingInterceptor { message ->
        android.util.Log.d("OkHttpLogger", message)
    }.apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    val okHttpClient = OkHttpClient.Builder()
        // ... other interceptors, cookieJar, dns ...
        .addNetworkInterceptor(loggingInterceptor) // Must be a network interceptor or the last interceptor
        .build()

    private fun resolveUserAgent(): String =
        customUserAgent.trim().ifBlank { webViewUserAgent }

    /**
     * Interceptor to bypass Cloudflare challenge pages (403/503 + challenge HTML).
     */
    val cloudflareInterceptor = CloudflareInterceptor(appContext, cookieJar, userAgentInterceptor)

    private val dohDns: Dns by lazy {
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val doh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
            )
            .build()

        // Encrypted DNS first so ISPs cannot poison or RST lookups for blocked manga hosts.
        // System DNS is only used if Cloudflare DoH itself fails. Prefer IPv4 when both
        // families exist — emulator IPv6 often connects then gets RST.
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = try {
                    doh.lookup(hostname)
                } catch (_: Exception) {
                    Dns.SYSTEM.lookup(hostname)
                }
                val ipv4 = addresses.filterIsInstance<Inet4Address>()
                return if (ipv4.isNotEmpty()) ipv4 else addresses
            }
        }
    }

    private fun buildDoHClient(includeCloudflareInterceptor: Boolean): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(dohDns)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 16
                    maxRequestsPerHost = 3
                }
            )
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.MINUTES)
            .cache(
                Cache(
                    directory = appContext.cacheDir.resolve("network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB — matches Mihon defaults
                )
            )
            .cookieJar(cookieJar)
            .addNetworkInterceptor(CloudflareCookieInterceptor(cookieJar))
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(BaseUrlRewriteInterceptor())
            .addInterceptor(FlameComicsJsonRepairInterceptor())
            .apply {
                if (includeCloudflareInterceptor) {
                    addInterceptor(cloudflareInterceptor)
                }
            }
            .addInterceptor(ConnectionResetRetryInterceptor())
            // Pace every host at 3 req/s so chapter CDNs and site HTML cannot burst
            // Cloudflare's "too many requests" filter. Image Coil client inherits this.
            .rateLimit(permits = 3, period = 1.seconds)
            .limitConcurrentRequestsPerHost(3)
            // Last network interceptor: logs the request that actually goes on the wire
            // (after application interceptors and CloudflareCookieInterceptor).
            .addNetworkInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                }
            )
            .build()
    }

    /**
     * Standard client for general requests — the singleton OkHttpClient extensions use.
     *
     * Deliberately omits IgnoreGzipInterceptor / BrotliInterceptor (Mihon modern networking).
     */
    val client: OkHttpClient by lazy { buildDoHClient(includeCloudflareInterceptor = true) }

    /**
     * DoH-enabled client for WebView resource loads during Cloudflare bypass.
     * Omits [CloudflareInterceptor] so [WebViewClient.shouldInterceptRequest] cannot
     * re-enter the bypass mutex while a challenge WebView is already active.
     */
    val webViewResourceClient: OkHttpClient by lazy { buildDoHClient(includeCloudflareInterceptor = false) }

    /**
     * Same client as [client]. Mihon deprecated a separate Cloudflare client because
     * the regular client already runs [CloudflareInterceptor].
     */
    val cloudflareClient: OkHttpClient by lazy { client }

    /**
     * Custom User-Agent from Advanced Settings, or the device WebView UA when that field is blank.
     */
    fun defaultUserAgentProvider(): String = resolveUserAgent()
}

class SequentialInterceptor : Interceptor {
    private val mutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        return runBlocking {
            mutex.withLock {
                chain.proceed(chain.request())
            }
        }
    }
}

/**
 * Bounds concurrent calls through an OkHttp client to [permits] in flight at once, using a
 * [kotlinx.coroutines.sync.Semaphore] instead of a hard single-caller [Mutex]. Used to keep
 * image loading (covers + reader pages) from either serializing to one request at a time or
 * spawning unbounded parallel requests that can spike memory.
 */
class BoundedConcurrencyInterceptor(
    private val semaphore: kotlinx.coroutines.sync.Semaphore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        runBlocking { semaphore.acquire() }
        try {
            return chain.proceed(chain.request())
        } finally {
            semaphore.release()
        }
    }
}
