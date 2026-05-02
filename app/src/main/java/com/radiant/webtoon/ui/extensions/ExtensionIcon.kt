package com.radiant.webtoon.ui.extensions

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.radiant.webtoon.core.model.Extension

@Composable
fun ExtensionIcon(
    extension: Extension,
    modifier: Modifier = Modifier.size(40.dp)
) {
    val context = LocalContext.current
    val icon = extension.icon ?: "🌐"

    when {
        icon.startsWith("pkg:") -> {
            val packageName = icon.substringAfter("pkg:")
            val packageIcon = remember(packageName) { getPackageIcon(context, packageName) }
            if (packageIcon != null) {
                Image(
                    bitmap = packageIcon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = modifier
                )
            } else {
                Text("🌐", fontSize = 24.sp, modifier = modifier)
            }
        }
        icon.startsWith("apk:") -> {
            val apkPath = icon.substringAfter("apk:")
            val apkIcon = remember(apkPath) { getApkIcon(context, apkPath) }
            if (apkIcon != null) {
                Image(
                    bitmap = apkIcon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = modifier
                )
            } else {
                Text("📦", fontSize = 24.sp, modifier = modifier)
            }
        }
        icon.startsWith("http") -> {
            AsyncImage(
                model = icon,
                contentDescription = null,
                modifier = modifier
            )
        }
        else -> {
            Text(icon, fontSize = 24.sp, modifier = modifier)
        }
    }
}

private fun getPackageIcon(context: Context, packageName: String): Drawable? {
    return try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: Exception) {
        null
    }
}

private fun getApkIcon(context: Context, apkPath: String): Drawable? {
    return try {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(apkPath, 0)
        if (pkgInfo != null) {
            pkgInfo.applicationInfo.sourceDir = apkPath
            pkgInfo.applicationInfo.publicSourceDir = apkPath
            pm.getApplicationIcon(pkgInfo.applicationInfo)
        } else null
    } catch (e: Exception) {
        null
    }
}
