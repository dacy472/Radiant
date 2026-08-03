package com.radiant.webtoon.ui.components

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Renders source/extension icons for http(s), pkg:, apk:, and emoji: schemes.
 * Coil alone cannot load pkg:/apk: URIs used by ExtensionManager.
 */
@Composable
fun SourceIcon(
    icon: String?,
    contentDescription: String? = null,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val value = icon.orEmpty()
    val shape = RoundedCornerShape(4.dp)

    when {
        value.startsWith("pkg:") -> {
            val packageName = value.removePrefix("pkg:")
            val drawable = remember(packageName) { loadPackageIcon(context, packageName) }
            if (drawable != null) {
                Image(
                    bitmap = drawable.toBitmap().asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = modifier.size(size).clip(shape),
                    contentScale = ContentScale.Crop
                )
            } else {
                FallbackIcon(size, modifier, shape)
            }
        }
        value.startsWith("apk:") -> {
            val apkPath = value.removePrefix("apk:")
            val drawable = remember(apkPath) { loadApkIcon(context, apkPath) }
            if (drawable != null) {
                Image(
                    bitmap = drawable.toBitmap().asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = modifier.size(size).clip(shape),
                    contentScale = ContentScale.Crop
                )
            } else {
                FallbackIcon(size, modifier, shape)
            }
        }
        value.startsWith("http://") || value.startsWith("https://") -> {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(value)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                modifier = modifier.size(size).clip(shape),
                contentScale = ContentScale.Crop,
                placeholder = null,
                error = null
            )
        }
        value.startsWith("emoji:") -> {
            Text(
                text = value.removePrefix("emoji:"),
                fontSize = (size.value * 0.55f).sp,
                modifier = modifier.size(size)
            )
        }
        value.isNotBlank() && !value.contains("://") && value.length <= 4 -> {
            // Raw emoji
            Box(modifier.size(size), contentAlignment = Alignment.Center) {
                Text(text = value, fontSize = (size.value * 0.55f).sp)
            }
        }
        else -> FallbackIcon(size, modifier, shape)
    }
}

@Composable
private fun FallbackIcon(size: Dp, modifier: Modifier, shape: RoundedCornerShape) {
    Box(
        modifier = modifier.size(size).clip(shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            modifier = Modifier.size(size * 0.7f)
        )
    }
}

fun loadPackageIcon(context: Context, packageName: String): Drawable? {
    return try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
        null
    }
}

fun loadApkIcon(context: Context, apkPath: String): Drawable? {
    return try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pkgInfo = pm.getPackageArchiveInfo(apkPath, 0) ?: return null
        val appInfo = pkgInfo.applicationInfo ?: return null
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        pm.getApplicationIcon(appInfo)
    } catch (_: Exception) {
        null
    }
}
