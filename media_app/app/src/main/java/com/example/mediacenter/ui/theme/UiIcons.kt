package com.example.mediacenter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// 播放器控制图标:Material Design 标准 path 数据自绘为 ImageVector,
// 避免依赖 extended 图标库,风格与项目其余矢量图标一致。
object UiIcons {
    val PlayArrow: ImageVector by lazy { materialIcon("PlayArrow", "M8,5v14l11,-7z") }
    val Pause: ImageVector by lazy { materialIcon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z") }
    val ArrowBack: ImageVector by lazy { materialIcon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val KeyboardArrowLeft: ImageVector by lazy { materialIcon("KeyboardArrowLeft", "M15.41,16.59L10.83,12l4.58,-4.59L14,6l-6,6 6,6z") }
    val KeyboardArrowRight: ImageVector by lazy { materialIcon("KeyboardArrowRight", "M8.59,16.59L13.17,12 8.59,7.41 10,6l6,6 -6,6z") }

    private fun materialIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) { addPathNodes(pathData) }
        }.build()
}
