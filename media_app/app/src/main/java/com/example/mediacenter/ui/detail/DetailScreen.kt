package com.example.mediacenter.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil.compose.AsyncImage
import com.example.mediacenter.data.VideoCache
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.ErrorRed
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary

@Composable
fun DetailScreen(
    videoId: String,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val video = remember(videoId) { VideoCache.get(videoId) }
    DisposableEffect(videoId) {
        onDispose { VideoCache.clear() }
    }

    BackHandler(onBack = onBack)

    if (video == null) {
        Text("无法加载视频信息", color = ErrorRed, modifier = Modifier.fillMaxSize().padding(24.dp))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().requiredHeight(300.dp),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            video.title,
            color = TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        var showFullDesc by remember { mutableStateOf(false) }
        val desc = video.description.orEmpty()
        Text(
            desc.ifEmpty { "暂无简介" },
            color = TextSecondary,
            maxLines = if (showFullDesc) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (desc.length > 96) {
            TextButton(onClick = { showFullDesc = !showFullDesc }) {
                Text(if (showFullDesc) "收起" else "查看完整简介", color = AppleBlue)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPlay,
            modifier = Modifier.requiredHeight(56.dp).fillMaxWidth(),
            colors = ButtonDefaults.colors(containerColor = AppleBlue),
        ) {
            Text("播放", color = TextPrimary, fontSize = 18.sp)
        }
    }
}
