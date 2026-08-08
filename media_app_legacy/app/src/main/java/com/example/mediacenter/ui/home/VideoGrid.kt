package com.example.mediacenter.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.applyCardFocus
import com.example.mediacenter.ui.components.gridColumns
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.loadImage
import com.example.mediacenter.ui.setPlaceholderVisible
import com.example.mediacenter.ui.components.match
import com.example.mediacenter.ui.components.posterCardWidth
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.roundedGradient
import com.example.mediacenter.ui.components.videoMeta
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.config.ViewTypeFooter
import com.example.mediacenter.ui.homeCategoryKey
import com.example.mediacenter.ui.homeDefaultCategoryTarget
import com.example.mediacenter.ui.requestHomeFocus
import com.example.mediacenter.ui.config.FooterStableId
import com.example.mediacenter.ui.config.ViewTypeVideo
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.theme.HomeFocusArea
import com.example.mediacenter.ui.theme.HomeFocusTarget
import com.example.mediacenter.ui.views.ThumbnailImageView
import com.example.mediacenter.ui.views.ChevronDownView

// RecyclerView 视图缓存大小:滚出屏幕的卡片先留缓存(不清 bitmap)快速滚回即显;
// 太大浪费内存(老盒子低堆),太小失去缓存意义。6 张 ≈ 2 行卡片。
private const val VIDEO_VIEW_CACHE_SIZE = 6

    internal fun MainActivity.videoGrid(
        items: List<VideoSummary>,
        canLoadMore: Boolean,
        loadingMore: Boolean,
        loadMoreError: String?,
        onRetryLoadMore: () -> Unit,
        focusTarget: HomeFocusTarget?,
    ): View {
        val columns = gridColumns()
        val cardWidth = posterCardWidth(columns)
        val cardHeight = (cardWidth * 1.50f).toInt()
        val adapter = VideoGridAdapter(
            activity = this,
            columns = columns,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            onRetryLoadMore = onRetryLoadMore,
        )
        val layoutManager = GridLayoutManager(this, columns).apply {
            isItemPrefetchEnabled = false
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.isFooterPosition(position)) columns else 1
                }
            }
        }
        val recycler = RecyclerView(this).apply {
            this.layoutManager = layoutManager
            this.adapter = adapter
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            // clipToPadding=false 让首/末行聚焦放大有呼吸空间;clipChildren=true 把
            // 滚动出顶部的卡片裁掉,避免内容向上溢出盖住分类栏(分类栏是 LinearLayout
            // 中更靠前的兄弟,默认会被靠后的列表绘制覆盖)。顶部 padding 已留出聚焦
            // 放大所需的余量,首行 1.06x 放大不会被裁。
            clipToPadding = false
            clipChildren = true
            setPadding(0, dp(16), 0, dp(32))
            itemAnimator = null
            // 保留少量视图缓存:滚出屏幕的卡片先留在缓存里(不清 bitmap),
            // 快速滚回时立即显示,不用重新解码;超出的视图才走 onViewRecycled 释放
            // 资源。此前 0 缓存 + detach 即清图,快速滚动反复解码导致占位图闪烁。
            setItemViewCacheSize(VIDEO_VIEW_CACHE_SIZE)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= adapter.videoCount - columns) {
                        // loadMore() 会同步把 loadingMore=true 推给 StateFlow,
                        // 触发 render()->setData()->notifyItemChanged()。若直接在滚动回调里
                        // 调用,会在 RecyclerView 滚动派发期间修改适配器而抛 IllegalStateException。
                        // post 到下一帧,等本次滚动/布局派发结束后再触发。
                        recyclerView.post { viewModel.loadMore() }
                    }
                }
            })
        }
        homeVideoRecycler = recycler
        homeVideoAdapter = adapter
        // 恢复之前保存的滚动位置(如从分类栏焦点返回、invalidateUi 后重建等)
        savedGridState?.let { state ->
            layoutManager.onRestoreInstanceState(state)
            savedGridState = null
        }
        adapter.setData(
            newItems = items,
            newCanLoadMore = canLoadMore,
            newLoadingMore = loadingMore,
            newLoadMoreError = loadMoreError,
            focusTarget = focusTarget,
        )
        if (focusTarget is HomeFocusTarget.Video) {
            adapter.indexOfVideo(focusTarget.id).takeIf { it >= 0 }?.let { index ->
                recycler.scrollToPosition(index)
            }
        }
        return recycler
    }

    internal fun MainActivity.loadMoreIndicator(loadingMore: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(Colors.GlassFill, 24, 1, Colors.GlassBorder)
        }
        if (loadingMore) {
            row.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(label("正在加载更多…", 14f, Colors.TEXT_SECONDARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(12)))
        } else {
            row.addView(ChevronDownView(this), LinearLayout.LayoutParams(dp(16), dp(16)))
            row.addView(label("下滑加载更多", 14f, Colors.TEXT_TERTIARY, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(8)))
        }
        return row
    }

    internal fun MainActivity.loadMoreErrorIndicator(message: String, onRetry: () -> Unit): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(Colors.GlassFill, 24, 1, Colors.GlassBorder)
            isFocusable = true
            isClickable = true
            setOnClickListener { onRetry() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
            addView(label(message, 14f, Colors.TEXT_SECONDARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(label("重试", 14f, Colors.APPLE_BLUE, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(12)))
        }
    }

    internal class VideoGridAdapter(
        private val activity: MainActivity,
        private val columns: Int,
        private val cardWidth: Int,
        private val cardHeight: Int,
        private val onRetryLoadMore: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<VideoSummary> = emptyList()
        private var canLoadMore: Boolean = false
        private var loadingMore: Boolean = false
        private var loadMoreError: String? = null
        private var pendingFocusTarget: HomeFocusTarget.Video? = null

        val videoCount: Int
            get() = items.size

        init {
            setHasStableIds(true)
        }

        fun setData(
            newItems: List<VideoSummary>,
            newCanLoadMore: Boolean,
            newLoadingMore: Boolean,
            newLoadMoreError: String?,
            focusTarget: HomeFocusTarget?,
        ) {
            val oldItems = items
            val oldCanLoadMore = canLoadMore
            val oldLoadingMore = loadingMore
            val oldLoadMoreError = loadMoreError
            if (focusTarget is HomeFocusTarget.Video) {
                pendingFocusTarget = focusTarget
            }

            items = newItems
            canLoadMore = newCanLoadMore
            loadingMore = newLoadingMore
            loadMoreError = newLoadMoreError

            val samePrefix = oldItems.size <= newItems.size &&
                oldItems.indices.all { index -> oldItems[index] == newItems[index] }
            if (!samePrefix) {
                val common = minOf(oldItems.size, newItems.size)
                if (common > 0) notifyItemRangeChanged(0, common)
                if (newItems.size > oldItems.size) {
                    notifyItemRangeInserted(common, newItems.size - common)
                } else if (oldItems.size > newItems.size) {
                    notifyItemRangeRemoved(common, oldItems.size - common)
                }
                return
            }
            val insertedCount = newItems.size - oldItems.size
            if (insertedCount > 0) {
                notifyItemRangeInserted(oldItems.size, insertedCount)
            }
            when {
                oldCanLoadMore && newCanLoadMore -> {
                    if (insertedCount > 0 || oldLoadingMore != newLoadingMore || oldLoadMoreError != newLoadMoreError) {
                        notifyItemChanged(items.size)
                    }
                }
                oldCanLoadMore && !newCanLoadMore -> notifyItemRemoved(items.size)
                !oldCanLoadMore && newCanLoadMore -> notifyItemInserted(items.size)
            }
        }

        fun requestFocusOn(target: HomeFocusTarget.Video) {
            pendingFocusTarget = target
            val index = indexOfVideo(target.id)
            if (index >= 0) notifyItemChanged(index)
        }

        fun indexOfVideo(videoId: String): Int {
            return items.indexOfFirst { it.id == videoId }
        }

        fun isFooterPosition(position: Int): Boolean {
            return canLoadMore && position == items.size
        }

        override fun getItemCount(): Int {
            return items.size + if (canLoadMore) 1 else 0
        }

        override fun getItemId(position: Int): Long {
            return if (isFooterPosition(position)) {
                FooterStableId
            } else {
                stableVideoId(items[position].id)
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (isFooterPosition(position)) ViewTypeFooter else ViewTypeVideo
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == ViewTypeFooter) {
                LoadMoreHolder()
            } else {
                VideoHolder()
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is LoadMoreHolder) {
                holder.bind(loadingMore, loadMoreError)
            } else if (holder is VideoHolder) {
                holder.bind(items[position], position)
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is VideoHolder) {
                holder.loadVisibleThumbnail()
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            if (holder is VideoHolder) {
                // 只取消未完成的加载,不清 bitmap:视图还在 cache 里,保留位图
                // 让快速滚回立即显示;真正的资源释放在 onViewRecycled(缓存淘汰时)。
                holder.cancelPendingThumbnailLoad()
            }
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VideoHolder) {
                holder.recycle()
            }
            super.onViewRecycled(holder)
        }

        private fun stableVideoId(value: String): Long {
            var result = 1125899906842597L
            value.forEach { char -> result = 31 * result + char.code }
            return result
        }

        private inner class VideoHolder : RecyclerView.ViewHolder(createVideoCardRoot()) {
            private val card: FrameLayout = itemView as FrameLayout
            private val imageWrap = FrameLayout(activity).apply {
                background = activity.roundedGradient(
                    Colors.PlaceholderTop,
                    Colors.PlaceholderBottom,
                    16,
                    1,
                    Colors.GlassBorder,
                )
            }
            private val image = ThumbnailImageView(activity)
            private val initials = activity.label("", 42f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1).apply {
                gravity = Gravity.CENTER
                alpha = 1f
            }
            private val title = activity.label("", 14f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            private val meta = activity.label("", 11f, Color.argb(170, 255, 255, 255), maxLines = 1)
            private val focusIndicator = View(activity).apply {
                background = activity.rounded(Colors.APPLE_BLUE, 999)
                alpha = 0f
            }
            private var boundVideo: VideoSummary? = null
            private var boundThumbnailUrl: String? = null

            init {
                imageWrap.addView(image, activity.frameMatch())
                imageWrap.addView(initials, activity.frameMatch())
                imageWrap.addView(
                    View(activity).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.argb(22, 255, 255, 255), Color.TRANSPARENT),
                        )
                    },
                    activity.frameMatch(),
                )
                imageWrap.addView(
                    View(activity).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.TRANSPARENT, Color.argb(220, 0, 0, 0)),
                        )
                    },
                    activity.frameMatch(),
                )
                imageWrap.addView(
                    focusIndicator,
                    FrameLayout.LayoutParams(match(), activity.dp(4), Gravity.BOTTOM).apply {
                        leftMargin = activity.dp(12)
                        rightMargin = activity.dp(12)
                        bottomMargin = activity.dp(0)
                    },
                )
                card.addView(imageWrap, activity.frameMatch())

                val textBlock = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(10))
                    addView(title, activity.linearMatch())
                    addView(meta, activity.linearMatch())
                }
                imageWrap.addView(textBlock, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))

                activity.applyCardFocus(card) { focused ->
                    imageWrap.background = activity.roundedGradient(
                        if (focused) Colors.SURFACE_FOCUSED else Colors.PlaceholderTop,
                        if (focused) Colors.SURFACE_FOCUSED else Colors.PlaceholderBottom,
                        16,
                        if (focused) 2 else 1,
                        if (focused) Colors.GLASS_BORDER_FOCUSED else Colors.GlassBorder,
                    )
                    imageWrap.elevationCompat(if (focused) activity.dp(14).toFloat() else 0f)
                    val video = boundVideo
                    if (focused && video != null) {
                        activity.homeFocusArea = HomeFocusArea.Video
                        activity.rememberedVideoFocusByCategory[activity.homeCategoryKey(activity.currentState)] = video.id
                    }
                    focusIndicator.animate().cancel()
                    focusIndicator.animate().alpha(if (focused) 1f else 0f).setDuration(140).start()
                }
            }

            fun bind(video: VideoSummary, index: Int) {
                boundVideo = video
                card.tag = HomeFocusTarget.Video(video.id).viewTag()
                title.text = video.title
                meta.text = videoMeta(video)
                initials.text = video.title.take(2).ifBlank { "影" }
                boundThumbnailUrl = video.thumbnailUrl
                image.cancelThumbnailLoad()
                image.setThumbnailBitmap(null)
                setPlaceholderVisible(initials, true)
                if (card.isAttachedToWindow) {
                    loadVisibleThumbnail()
                }
                card.setOnClickListener {
                    activity.cancelPendingCategorySelect()
                    activity.viewModel.openDetails(video)
                }
                card.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    // loadMore 追加后,bind 时捕获的 index/itemCount 会变陈旧,
                    // 用实时的 binding 位置和适配器数量判断边界,避免中部 item 被
                    // 误判为末项而挡住右移。
                    val pos = bindingAdapterPosition.takeIf { it >= 0 } ?: index
                    val count = videoCount
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (pos >= columns) return@setOnKeyListener false
                            activity.homeDefaultCategoryTarget(activity.currentState)?.let { target ->
                                activity.homeFocusArea = HomeFocusArea.Category
                                // 保存滚动位置防止 RecyclerView 因焦点离开而重置到第一列
                                activity.savedGridState = activity.homeVideoRecycler?.layoutManager?.onSaveInstanceState()
                                activity.requestHomeFocus(target)
                                true
                            } ?: false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> pos % columns == 0
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            pos == count - 1 || pos % columns == columns - 1
                        }
                        else -> false
                    }
                }

                val target = pendingFocusTarget
                if (target != null && target.id == video.id) {
                    pendingFocusTarget = null
                    card.post {
                        if (card.requestFocus()) activity.pendingHomeFocus = null
                    }
                }
            }

            fun loadVisibleThumbnail() {
                val video = boundVideo ?: return
                activity.loadImage(
                    url = boundThumbnailUrl,
                    headers = emptyMap(),
                    image = image,
                    placeholder = initials,
                    title = video.title,
                    maxWidthPx = cardWidth,
                    maxHeightPx = cardHeight,
                )
            }

            /** 只取消未完成的加载,保留已解码位图(滚动优化:滚回立即显示)。 */
            fun cancelPendingThumbnailLoad() {
                image.cancelThumbnailLoad()
            }

            /** 完整释放:取消加载 + 清位图 + 显示占位(视图被回收/重绑时调用)。 */
            fun cancelVisibleThumbnail() {
                cancelPendingThumbnailLoad()
                image.setThumbnailBitmap(null)
                setPlaceholderVisible(initials, true)
            }

            fun recycle() {
                cancelVisibleThumbnail()
                boundVideo = null
                boundThumbnailUrl = null
            }
        }

        private inner class LoadMoreHolder : RecyclerView.ViewHolder(createLoadMoreRoot()) {
            private val container: FrameLayout = itemView as FrameLayout

            fun bind(loading: Boolean, error: String?) {
                container.removeAllViews()
                container.addView(
                    if (error == null) {
                        activity.loadMoreIndicator(loading)
                    } else {
                        activity.loadMoreErrorIndicator(error, onRetryLoadMore)
                    },
                    activity.frameMatch(),
                )
            }
        }

        private fun createVideoCardRoot(): FrameLayout {
            return FrameLayout(activity).apply {
                layoutParams = RecyclerView.LayoutParams(cardWidth, cardHeight).apply {
                    setMargins(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(18))
                }
                clipChildren = false
                isFocusable = true
                isClickable = true
                background = activity.rounded(Color.TRANSPARENT, 16, 1, Color.TRANSPARENT)
                setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3))
            }
        }

        private fun createLoadMoreRoot(): FrameLayout {
            return FrameLayout(activity).apply {
                layoutParams = RecyclerView.LayoutParams(match(), activity.dp(72)).apply {
                    setMargins(0, activity.dp(4), 0, activity.dp(18))
                }
            }
        }
    }
