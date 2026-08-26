package com.radiant.webtoon.core.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures detailed diagnostics when chapter page-list scraping returns empty or fails.
 * Writes timestamped files under [filesDir]/page_list_debug/ and keeps a ring buffer for UI.
 */
object PageListDebugLogger {

    const val TAG = "PageListDebug"
    private const val MAX_BODY_CHARS = 50_000
    private const val MAX_SCRIPT_PREVIEW = 500
    private const val RING_BUFFER_SIZE = 30

    private var filesDir: File? = null
    private val events = CopyOnWriteArrayList<PageListDebugEvent>()
    private val _eventsFlow = MutableStateFlow<List<PageListDebugEvent>>(emptyList())
    val eventsFlow: StateFlow<List<PageListDebugEvent>> = _eventsFlow.asStateFlow()

    private val activeSession = ThreadLocal<Session?>()

    data class Session(
        val id: String = UUID.randomUUID().toString(),
        val sourceId: String?,
        val sourceName: String?,
        val chapterId: String?,
        val chapterUrl: String?,
    )

    data class PageListDebugEvent(
        val id: String,
        val timestampMs: Long,
        val sourceId: String?,
        val sourceName: String?,
        val chapterId: String?,
        val chapterUrl: String?,
        val step: String,
        val httpStatus: Int?,
        val responseBody: String?,
        val bodyTruncated: Boolean,
        val error: String?,
        val strategy: String?,
        val extra: Map<String, String>,
        val logFilePath: String?,
    ) {
        fun formattedTimestamp(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))

        fun summaryLine(): String {
            val status = httpStatus?.let { "HTTP $it" } ?: "no status"
            val err = error?.let { " | $it" }.orEmpty()
            return "${formattedTimestamp()} | $step | $status$err"
        }

        fun fullText(): String = buildString {
            appendLine("=== Page List Debug ===")
            appendLine("Time: ${formattedTimestamp()}")
            appendLine("Event ID: $id")
            sourceId?.let { appendLine("Source ID: $it") }
            sourceName?.let { appendLine("Source: $it") }
            chapterId?.let { appendLine("Chapter ID: $it") }
            chapterUrl?.let { appendLine("Chapter URL: $it") }
            appendLine("Step: $step")
            httpStatus?.let { appendLine("HTTP Status: $it") }
            strategy?.let { appendLine("Strategy: $it") }
            error?.let { appendLine("Error: $it") }
            if (extra.isNotEmpty()) {
                appendLine("Extra:")
                extra.forEach { (k, v) -> appendLine("  $k: $v") }
            }
            if (!responseBody.isNullOrBlank()) {
                appendLine("Response body${if (bodyTruncated) " (truncated)" else ""}:")
                appendLine(responseBody)
            }
            logFilePath?.let { appendLine("Log file: $it") }
        }
    }

    fun init(context: Context) {
        filesDir = File(context.filesDir, "page_list_debug").also { it.mkdirs() }
    }

    fun beginSession(
        sourceId: String?,
        sourceName: String?,
        chapterId: String?,
        chapterUrl: String?,
    ): Session {
        return Session(
            sourceId = sourceId,
            sourceName = sourceName,
            chapterId = chapterId,
            chapterUrl = chapterUrl,
        )
    }

    suspend fun <T> withSession(session: Session, block: suspend () -> T): T {
        val previous = activeSession.get()
        activeSession.set(session)
        return try {
            block()
        } finally {
            activeSession.set(previous)
        }
    }

    fun currentSession(): Session? = activeSession.get()

    fun logStep(
        step: String,
        httpStatus: Int? = null,
        responseBody: String? = null,
        error: Throwable? = null,
        strategy: String? = null,
        extra: Map<String, String> = emptyMap(),
        session: Session? = null,
        writeFile: Boolean = false,
    ): PageListDebugEvent {
        val resolvedSession = session ?: activeSession.get()
        val (body, truncated) = truncateBody(responseBody)
        val event = PageListDebugEvent(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            sourceId = resolvedSession?.sourceId,
            sourceName = resolvedSession?.sourceName,
            chapterId = resolvedSession?.chapterId,
            chapterUrl = resolvedSession?.chapterUrl,
            step = step,
            httpStatus = httpStatus,
            responseBody = body,
            bodyTruncated = truncated,
            error = error?.let { formatThrowable(it) },
            strategy = strategy,
            extra = extra,
            logFilePath = null,
        )
        return record(event, writeFile = writeFile, level = if (error != null) Log.ERROR else Log.WARN)
    }

    fun logEmptyPageList(
        step: String,
        httpStatus: Int? = null,
        responseBody: String? = null,
        error: Throwable? = null,
        strategy: String? = null,
        extra: Map<String, String> = emptyMap(),
        session: Session? = null,
    ): PageListDebugEvent {
        return logStep(
            step = step,
            httpStatus = httpStatus,
            responseBody = responseBody,
            error = error,
            strategy = strategy,
            extra = extra,
            session = session,
            writeFile = true,
        )
    }

    fun logHttpFetch(
        url: String,
        httpStatus: Int,
        responseBody: String?,
        error: Throwable? = null,
        strategy: String? = null,
    ) {
        logStep(
            step = if (error != null) "http_fetch_error" else "http_fetch",
            httpStatus = httpStatus,
            responseBody = responseBody,
            error = error,
            strategy = strategy,
            extra = mapOf("url" to url),
            writeFile = error != null || httpStatus !in 200..299,
        )
    }

    fun logJsEvalFailure(script: String, error: Throwable, strategy: String) {
        val preview = script.take(MAX_SCRIPT_PREVIEW).let {
            if (script.length > MAX_SCRIPT_PREVIEW) "$it…" else it
        }
        logStep(
            step = "js_eval_failed",
            error = error,
            strategy = strategy,
            extra = mapOf("scriptPreview" to preview),
        )
    }

    fun logParseFailure(step: String, error: Throwable, responseBody: String? = null) {
        logStep(
            step = step,
            responseBody = responseBody,
            error = error,
            writeFile = true,
        )
    }

    fun lastEvent(): PageListDebugEvent? = events.firstOrNull()

    fun lastEventForChapter(chapterId: String): PageListDebugEvent? =
        events.firstOrNull { it.chapterId == chapterId }

    fun clearEvents() {
        events.clear()
        _eventsFlow.value = emptyList()
    }

    fun listLogFiles(): List<File> {
        val dir = filesDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun readLogFile(file: File): String = runCatching { file.readText() }.getOrDefault("(unable to read file)")

    fun deleteAllLogFiles() {
        listLogFiles().forEach { it.delete() }
    }

    private fun record(event: PageListDebugEvent, writeFile: Boolean, level: Int): PageListDebugEvent {
        val filePath = if (writeFile) writeToFile(event) else null
        val stored = if (filePath != null) event.copy(logFilePath = filePath) else event
        events.add(0, stored)
        while (events.size > RING_BUFFER_SIZE) {
            events.removeAt(events.lastIndex)
        }
        _eventsFlow.value = events.toList()

        val logMsg = buildString {
            append(stored.step)
            stored.httpStatus?.let { append(" status=$it") }
            stored.strategy?.let { append(" strategy=$it") }
            stored.error?.let { append(" error=$it") }
            stored.chapterUrl?.let { append(" url=$it") }
            if (stored.bodyTruncated) append(" body=truncated")
            else stored.responseBody?.let { append(" bodyChars=${it.length}") }
        }
        Log.println(level, TAG, logMsg)
        if (!stored.responseBody.isNullOrBlank() && writeFile) {
            Log.println(level, TAG, "Body preview: ${stored.responseBody.take(2000)}")
        }
        return stored
    }

    private fun writeToFile(event: PageListDebugEvent): String? {
        val dir = filesDir ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(event.timestampMs))
        val safeSource = (event.sourceName ?: "unknown").replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
        val file = File(dir, "page_list_${stamp}_${safeSource}.log")
        return runCatching {
            file.writeText(event.fullText())
            file.absolutePath
        }.getOrNull()
    }

    private fun truncateBody(body: String?): Pair<String?, Boolean> {
        if (body.isNullOrEmpty()) return null to false
        return if (body.length > MAX_BODY_CHARS) {
            body.take(MAX_BODY_CHARS) to true
        } else {
            body to false
        }
    }

    private fun formatThrowable(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(4).joinToString(" -> ") { it.message ?: it.javaClass.simpleName }
        return "${t.javaClass.simpleName}: $chain"
    }
}
