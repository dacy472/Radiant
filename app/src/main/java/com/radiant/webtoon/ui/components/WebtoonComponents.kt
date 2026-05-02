package com.radiant.webtoon.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.radiant.webtoon.core.model.Webtoon

@Composable
fun WebtoonThumbnailCard(
    webtoon: Webtoon,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick)
            .padding(4.dp)
    ) {
        AsyncImage(
            model = webtoon.thumbnail,
            contentDescription = webtoon.title,
            modifier = Modifier
                .height(180.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Text(
            text = webtoon.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun SourceHeader(
    name: String,
    lang: String,
    icon: String,
    onBrowseClick: () -> Unit
) {
    val context = LocalContext.current
    val iconModel = remember(icon) {
        when {
            icon.startsWith("pkg:") -> {
                val pkgName = icon.substringAfter("pkg:")
                try {
                    context.packageManager.getApplicationIcon(pkgName)
                } catch (e: Exception) {
                    null
                }
            }
            icon.startsWith("emoji:") -> icon.substringAfter("emoji:")
            else -> icon // Could be a URL or raw emoji
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconModel is android.graphics.drawable.Drawable || (iconModel is String && !icon.startsWith("emoji:"))) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(iconModel)
                        .crossfade(true)
                        .build(),
                    placeholder = rememberVectorPainter(Icons.Default.Extension),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .padding(end = 8.dp)
                )
            } else {
                Text(
                    text = iconModel?.toString() ?: "🌐",
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            
            Column {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = lang.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        TextButton(onClick = onBrowseClick) {
            Text(text = "BROWSE")
        }
    }
}

@Composable
fun ExtensionIcon(extension: com.radiant.webtoon.core.model.Extension) {
    val context = LocalContext.current
    
    val iconModel = remember(extension) {
        when (extension) {
            is com.radiant.webtoon.core.model.Extension.Installed -> {
                try {
                    context.packageManager.getApplicationIcon(extension.pkgName)
                } catch (e: Exception) {
                    null
                }
            }
            is com.radiant.webtoon.core.model.Extension.Available -> {
                extension.icon
            }
        }
    }

    if (iconModel != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(iconModel)
                .crossfade(true)
                .build(),
            placeholder = rememberVectorPainter(Icons.Default.Extension),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        }
    }
}
