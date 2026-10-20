package com.wain.app.data.net

import com.wain.app.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val WainJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

class HttpFailure(val code: Int, val body: String) : IOException("HTTP $code")

fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", AppConfig.userAgent).build())
    }
    .build()

/** Cancellation-aware OkHttp call; cancelling the coroutine cancels the network request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** Executes [request] and returns the body text. Throws [HttpFailure] for non-2xx responses. */
suspend fun OkHttpClient.fetch(request: Request): String {
    val response = newCall(request).await()
    return withContext(Dispatchers.IO) {
        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpFailure(r.code, body.take(2000))
            body
        }
    }
}
