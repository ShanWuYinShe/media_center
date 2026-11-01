package com.example.mediacenter.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ButtonShape

/**
 * TV 主按钮的统一焦点态:胶囊形状 + 近白 2dp 外框 + 轻微放大。
 *
 * tv-material 默认焦点态只有容器变色;对本来就是 Apple Blue 的主按钮
 * (Hero 播放、详情播放)等于没有任何变化,遥控器用户看不出焦点在哪。
 * 这里显式给出外框与缩放,selected/未聚焦状态仍保持 Apple Blue。
 */

private val PillShape = RoundedCornerShape(50)

@Composable
fun tvPillButtonShape(): ButtonShape = ButtonDefaults.shape(shape = PillShape)

@Composable
fun tvPillButtonBorder(): ButtonBorder = ButtonDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, FocusBorder), shape = PillShape),
)

@Composable
fun tvButtonFocusScale(): ButtonScale = ButtonDefaults.scale(focusedScale = 1.04f)
