package com.example.mediacenter.ui

object TvNavigationPolicy {
    const val DOUBLE_BACK_EXIT_TIMEOUT_MS = 2000L

    enum class HomeBackResult {
        REFOCUS_CATEGORY_TAB,
        SHOW_EXIT_TOAST,
        FINISH_ACTIVITY,
    }

    /**
     * 计算在首页按返回键时的决策:
     * - 如果焦点在卡片网格、顶栏或空态/错误态动作按钮上,平滑将焦点重置回当前分类标签;
     * - 如果焦点已经在分类栏,2秒内再次按返回退出,否则提示"再按一次退出应用"。
     */
    fun evaluateHomeBack(
        hasContentOrTopBarFocus: Boolean,
        nowMs: Long,
        lastBackPressMs: Long,
        timeoutMs: Long = DOUBLE_BACK_EXIT_TIMEOUT_MS,
    ): Pair<HomeBackResult, Long> {
        return if (hasContentOrTopBarFocus) {
            HomeBackResult.REFOCUS_CATEGORY_TAB to lastBackPressMs
        } else if (nowMs - lastBackPressMs < timeoutMs) {
            HomeBackResult.FINISH_ACTIVITY to lastBackPressMs
        } else {
            HomeBackResult.SHOW_EXIT_TOAST to nowMs
        }
    }

    enum class PlayerBackDecision {
        HIDE_CONTROLS_OVERLAY,
        EXIT_PLAYBACK,
    }

    /**
     * 计算在播放页按返回键时的决策:
     * - 若全屏错误提示/重试盖层正在展示,返回键直接退出播放页;
     * - 否则若控制层遮罩可见,按返回键优先收起控制层,不中断播放;
     * - 控制层已处于收起状态时,按返回键退出播放页。
     */
    fun evaluatePlayerBack(
        controlsVisible: Boolean,
        isErrorShowing: Boolean,
    ): PlayerBackDecision {
        return if (!isErrorShowing && controlsVisible) {
            PlayerBackDecision.HIDE_CONTROLS_OVERLAY
        } else {
            PlayerBackDecision.EXIT_PLAYBACK
        }
    }
}
