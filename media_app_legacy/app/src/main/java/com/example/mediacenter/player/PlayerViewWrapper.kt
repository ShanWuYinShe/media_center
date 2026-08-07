// 文件级抑制:import 行的 deprecation 警告不受类级 @Suppress 控制。
// PlayerView 是 ExoPlayer 2.18.7 唯一可用的播放器 UI(无替代 API,见类注释),
// 整个文件的 deprecation 收敛于此,业务代码通过 PlayerViewHolder 间接使用。
@file:Suppress("DEPRECATION")

package com.example.mediacenter.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.exoplayer2.ui.PlayerView

/**
 * PlayerView 的收敛封装。
 *
 * ExoPlayer 2.17+ 起把 [PlayerView] 标记为 @Deprecated(为迁移 Media3 铺垫),
 * 而 2.18.7 没有替代 UI 类(2.19+/Media3 才有 StyledPlayerView),Media3 又要求
 * minSdk 21,与项目"必须支持 Android 4.4.2"的目标冲突,不能升级。
 *
 * 因此业务代码**不直接引用** deprecated 的 [PlayerView] 类型,统一通过
 * [PlayerViewHolder] 间接使用:deprecation 警告收敛到这一个文件,其余代码
 * 引用的是非 deprecated 的 [PlayerViewHolder]。这是依赖库强加、无法用替代
 * API 消除的 deprecation 的最小化处理(PlayerView 本身无行为变化)。
 */

/** 持有播放器视图,对外提供非 deprecated 的访问入口。 */
class PlayerViewHolder private constructor(val playerView: PlayerView) {
    companion object {
        /** 从 view_player_texture 布局 inflate 并构造持有者。 */
        fun inflate(inflater: LayoutInflater, parent: ViewGroup?, attachToRoot: Boolean): PlayerViewHolder {
            val view = inflater.inflate(
                com.example.mediacenter.R.layout.view_player_texture,
                parent,
                attachToRoot,
            ) as PlayerView
            return PlayerViewHolder(view)
        }

        /** 从任意 View(layout 根即 PlayerView)构造持有者。 */
        fun from(view: View): PlayerViewHolder = PlayerViewHolder(view as PlayerView)
    }
}
