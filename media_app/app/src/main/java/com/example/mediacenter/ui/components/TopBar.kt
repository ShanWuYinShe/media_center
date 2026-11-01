package com.example.mediacenter.ui.components
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.ScreenVerticalPadding
import com.example.mediacenter.ui.theme.TextPrimary

@Composable
fun TopBar(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    providerLabel: String? = null,
    onProviderClick: (() -> Unit)? = null,
    // 首页首屏焦点落点;为 null 时不指定,沿用系统默认焦点顺序
    searchFocusRequester: FocusRequester? = null,
    // 搜索键按下时优先落到 Hero 主按钮(2D 焦点搜索会优先选中横向更近的"详情")
    searchDownFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().focusRestorer().padding(horizontal = ScreenHorizontalPadding, vertical = ScreenVerticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌区:蓝色播放标记 + 字标,给首页一个明确的视觉锚点
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(AppleBlue, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Media Center",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 视频源入口:仅当存在多个 provider 时显示。用可撑宽的胶囊,
            // 48dp 图标按钮会把 "YouTube" 这类名称裁成方块。
            if (providerLabel != null && onProviderClick != null) {
                TvChip(
                    label = providerLabel,
                    onClick = onProviderClick,
                    maxWidth = 220.dp,
                    trailing = {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                Spacer(Modifier.width(8.dp))
            }
            TvIconButton(
                onClick = onSettingsClick,
                contentDescription = "设置",
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            TvIconButton(
                onClick = onSearchClick,
                contentDescription = "搜索",
                modifier = Modifier
                    .then(
                        if (searchFocusRequester != null) {
                            Modifier.focusRequester(searchFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (searchDownFocusRequester != null) {
                            Modifier.focusProperties { down = searchDownFocusRequester }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = TextPrimary)
            }
        }
    }
}
