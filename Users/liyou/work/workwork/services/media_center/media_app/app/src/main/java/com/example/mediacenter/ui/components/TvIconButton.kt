package com.example.mediacenter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.FocusBorder

/**
 * 带电视焦点反馈的图标按钮：遥控器聚焦时显示 Apple Blue 底 + 近白外框，
 * 替代纯图标按钮在 10ft 距离下几乎不可见的焦点指示（项目 TV 焦点态约定）。
 * contentDescription 挂在按钮语义上——内部 Icon 一律传 null，避免重复朗读。
 * 从 TopBar 抽出共用：播放器控制、设置页返回、分类页返回等统一使用。
 */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .requiredSize(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) AppleBlue else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .then(
                if (focused) {
                    Modifier.border(2.dp, FocusBorder, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}
