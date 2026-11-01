package com.example.mediacenter.ui.theme

import androidx.compose.ui.unit.dp

// TV overscan 安全区:物理电视过扫描会裁掉屏幕边缘内容,
// 全局横向内容边距不低于此值(Android TV 官方建议 48dp)。
val ScreenHorizontalPadding = 48.dp

// TV overscan 纵向安全区:上下边缘同样留出呼吸空间,避免控制条/标题贴边被裁。
val ScreenVerticalPadding = 24.dp

// 首页分类行的纵向呼吸间距(相邻行之间为 2 倍此值)。
val RowVerticalSpacing = 16.dp

// 横向卡片行内卡片间距:10ft 大屏观看需要比移动端更松的横向节奏。
val CardSpacing = 20.dp

// 分类/搜索海报网格的最小列宽:1080p TV(960×540dp)下 240dp 可稳定排 3 列。
val VideoGridMinWidth = 240.dp
