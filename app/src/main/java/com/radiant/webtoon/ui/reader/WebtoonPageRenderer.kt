package com.radiant.webtoon.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.utils.ImageSlicer
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy

@Composable
fun WebtoonPageRenderer(
    pageUrl: String,
    headers: okhttp3.Headers
) {
    var imageSlices by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pageUrl) {
        val networkHelper: NetworkHelper by injectLazy()
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(pageUrl)
                    .headers(headers)
                    .build()

                val response = networkHelper.client.newCall(request).execute()
                if (!response.isSuccessful) {
                    errorMsg = "HTTP ${response.code}"
                    return@withContext
                }

                val body = response.body
                if (body != null) {
                    val bitmaps = ImageSlicer.slice(body.byteStream())
                    if (bitmaps.isEmpty()) {
                        errorMsg = "Failed to decode image"
                    } else {
                        imageSlices = bitmaps.map { it.asImageBitmap() }
                    }
                } else {
                    errorMsg = "Empty response body"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMsg = e.message ?: "Network error"
            }
        }
    }

    if (errorMsg != null) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Failed: $errorMsg",
                color = androidx.compose.ui.graphics.Color.Red,
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else if (imageSlices == null) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        Column {
            imageSlices?.forEach { slice ->
                Image(
                    bitmap = slice,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}
