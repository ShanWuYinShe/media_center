package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.TextPrimary

/**
 * 统一返回入口：图标 + “返回”文字。
 *
 * 10ft 下纯箭头图标对老人不够直观；接入文字后也不用记住遥控器 BACK 键，
 * 看见返回就能按 OK。页面内的返回都走这一组件，保持焦点态一致。
 */
@Composable
fun TvBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvChip(
        label = "返回",
        onClick = onClick,
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        },
        modifier = modifier,
        maxWidth = 160.dp,
    )
}
