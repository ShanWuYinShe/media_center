package com.example.mediacenter.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.CardShape
import com.example.mediacenter.ui.theme.RowVerticalSpacing
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding

/**
 * 首页加载骨架屏:Hero + 两行卡片的轮廓,呼吸式透明度。
 *
 * 比居中转圈更能表达"页面正在长出来",也避免大屏上长时间只有一个
 * 小 spinner 的空白感;布局尺寸与真实内容接近,加载完成不会明显跳动。
 */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "home-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    val block = CardGray.copy(alpha = alpha)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(block, RoundedCornerShape(18.dp)),
        )
        repeat(2) {
            Spacer(Modifier.height(RowVerticalSpacing * 2))
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(22.dp)
                    .background(block, RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(4) {
                    Box(
                        modifier = Modifier
                            .width(240.dp)
                            .height(150.dp)
                            .background(block, CardShape),
                    )
                }
            }
        }
    }
}
