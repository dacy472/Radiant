@file:JvmName("OkHttpExtensionsKt")
package eu.kanade.tachiyomi.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import rx.Observable
import rx.Producer
import rx.Subscription
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/**
 * Extension functions to bridge OkHttp with Coroutines and RxJava.
 * This file is named OkHttpExtensionsKt to ensure the generated class name
 * matches what Tachiyomi extensions expect.
 */

// --- Coroutine support ---

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) {
                response.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }
    })

    continuation.invokeOnCancellation {
        try { cancel() } catch (ex: Throwable) { /* Ignore */ }
    }
}

suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw Exception("HTTP error ${response.code}")
    }
    return response
}

// --- RxJava support (Legacy extensions) ---

fun Call.asObservable(): Observable<Response> = Observable.create { subscriber ->
    val call = clone()
    val requestDone = AtomicBoolean(false)

    subscriber.add(object : Subscription {
        override fun unsubscribe() {
            call.cancel()
        }

        override fun isUnsubscribed(): Boolean {
            return call.isCanceled()
        }
    })

    subscriber.setProducer { n ->
        if (n > 0 && requestDone.compareAndSet(false, true)) {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!subscriber.isUnsubscribed) {
                        subscriber.onError(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!subscriber.isUnsubscribed) {
                        subscriber.onNext(response)
                        subscriber.onCompleted()
                    }
                }
            })
        }
    }
}

fun Call.asObservableSuccess(): Observable<Response> = asObservable().map { response ->
    if (!response.isSuccessful) {
        response.close()
        throw Exception("HTTP error ${response.code}")
    }
    response
}
