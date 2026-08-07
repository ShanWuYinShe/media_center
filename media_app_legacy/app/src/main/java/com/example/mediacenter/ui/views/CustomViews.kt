package com.example.mediacenter.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.mediacenter.ui.theme.Colors
import kotlinx.coroutines.Job


enum class PillIcon {
    Search,
    Close,
    Play,
}

class ThumbnailImageView(context: Context) : View(context) {
    constructor(context: Context, attrs: AttributeSet?) : this(context)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private var bitmap: Bitmap? = null
    private var loadJob: Job? = null
    private var loadDetachListener: OnAttachStateChangeListener? = null

    fun setThumbnailBitmap(value: Bitmap?) {
        if (bitmap === value) return
        bitmap = value
        invalidate()
    }

    fun trackThumbnailLoad(job: Job, detachListener: OnAttachStateChangeListener) {
        cancelThumbnailLoad()
        loadJob = job
        loadDetachListener = detachListener
        addOnAttachStateChangeListener(detachListener)
    }

    fun clearThumbnailLoad(job: Job?) {
        if (job == null || loadJob !== job) return
        loadJob = null
        loadDetachListener?.let(::removeOnAttachStateChangeListener)
        loadDetachListener = null
    }

    fun cancelThumbnailLoad() {
        loadJob?.cancel()
        loadJob = null
        loadDetachListener?.let(::removeOnAttachStateChangeListener)
        loadDetachListener = null
    }

    override fun onDetachedFromWindow() {
        cancelThumbnailLoad()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = bitmap ?: return
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0 || current.width <= 0 || current.height <= 0) return

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val bitmapRatio = current.width.toFloat() / current.height.toFloat()
        if (bitmapRatio > viewRatio) {
            val cropWidth = (current.height * viewRatio).toInt().coerceAtLeast(1)
            val left = (current.width - cropWidth) / 2
            src.set(left, 0, left + cropWidth, current.height)
        } else {
            val cropHeight = (current.width / viewRatio).toInt().coerceAtLeast(1)
            val top = (current.height - cropHeight) / 2
            src.set(0, top, current.width, top + cropHeight)
        }
        dst.set(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        canvas.drawBitmap(current, src, dst, paint)
    }
}

class PillIconView(
    context: Context,
    private val icon: PillIcon,
) : View(context) {
    constructor(context: Context, attrs: AttributeSet?) : this(context, PillIcon.Search)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context, PillIcon.Search)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private var iconColor: Int = Colors.TEXT_PRIMARY

    fun setIconColor(color: Int) {
        if (iconColor == color) return
        iconColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = iconColor
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = (w.coerceAtMost(h) * 0.12f).coerceAtLeast(2f)
        paint.strokeWidth = stroke
        when (icon) {
            PillIcon.Search -> {
                val radius = w.coerceAtMost(h) * 0.28f
                val cx = w * 0.43f
                val cy = h * 0.43f
                canvas.drawCircle(cx, cy, radius, paint)
                canvas.drawLine(
                    cx + radius * 0.72f,
                    cy + radius * 0.72f,
                    w * 0.88f,
                    h * 0.88f,
                    paint,
                )
            }
            PillIcon.Close -> {
                canvas.drawLine(w * 0.26f, h * 0.26f, w * 0.74f, h * 0.74f, paint)
                canvas.drawLine(w * 0.74f, h * 0.26f, w * 0.26f, h * 0.74f, paint)
            }
            PillIcon.Play -> {
                fillPaint.color = iconColor
                path.reset()
                path.moveTo(w * 0.28f, h * 0.18f)
                path.lineTo(w * 0.28f, h * 0.82f)
                path.lineTo(w * 0.82f, h * 0.50f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
        }
    }
}

enum class TransportIcon {
    Previous,
    Rewind,
    Forward,
    Next,
    Aspect,
}


class TransportIconView(
    context: Context,
    private val icon: TransportIcon,
) : View(context) {
    constructor(context: Context, attrs: AttributeSet?) : this(context, TransportIcon.Previous)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context, TransportIcon.Previous)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val bar = RectF()
    private var iconColor: Int = Colors.TEXT_PRIMARY

    fun setIconColor(color: Int) {
        if (iconColor == color) return
        iconColor = color
        invalidate()
    }

    private fun Canvas.triangleRight(left: Float, apexX: Float, top: Float, bottom: Float) {
        path.reset()
        path.moveTo(left, top)
        path.lineTo(left, bottom)
        path.lineTo(apexX, (top + bottom) / 2f)
        path.close()
        drawPath(path, fillPaint)
    }

    private fun Canvas.triangleLeft(right: Float, apexX: Float, top: Float, bottom: Float) {
        path.reset()
        path.moveTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(apexX, (top + bottom) / 2f)
        path.close()
        drawPath(path, fillPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fillPaint.color = iconColor
        val w = width.toFloat()
        val h = height.toFloat()
        val top = h * 0.20f
        val bottom = h * 0.80f
        val barRadius = w * 0.06f
        when (icon) {
            TransportIcon.Forward -> {
                canvas.triangleRight(w * 0.10f, w * 0.50f, top, bottom)
                canvas.triangleRight(w * 0.50f, w * 0.90f, top, bottom)
            }
            TransportIcon.Rewind -> {
                canvas.triangleLeft(w * 0.90f, w * 0.50f, top, bottom)
                canvas.triangleLeft(w * 0.50f, w * 0.10f, top, bottom)
            }
            TransportIcon.Next -> {
                canvas.triangleRight(w * 0.14f, w * 0.62f, top, bottom)
                bar.set(w * 0.74f, top, w * 0.86f, bottom)
                canvas.drawRoundRect(bar, barRadius, barRadius, fillPaint)
            }
            TransportIcon.Previous -> {
                canvas.triangleLeft(w * 0.86f, w * 0.38f, top, bottom)
                bar.set(w * 0.14f, top, w * 0.26f, bottom)
                canvas.drawRoundRect(bar, barRadius, barRadius, fillPaint)
            }
            TransportIcon.Aspect -> {
                // 四角括号构成的画面边框图标,表示“画面比例/缩放”。
                strokePaint.color = iconColor
                strokePaint.strokeWidth = (w.coerceAtMost(h) * 0.1f).coerceAtLeast(2f)
                val left = w * 0.18f
                val right = w * 0.82f
                val arm = w * 0.18f
                canvas.drawLine(left, top, left + arm, top, strokePaint)
                canvas.drawLine(left, top, left, top + arm, strokePaint)
                canvas.drawLine(right - arm, top, right, top, strokePaint)
                canvas.drawLine(right, top, right, top + arm, strokePaint)
                canvas.drawLine(left, bottom - arm, left, bottom, strokePaint)
                canvas.drawLine(left, bottom, left + arm, bottom, strokePaint)
                canvas.drawLine(right - arm, bottom, right, bottom, strokePaint)
                canvas.drawLine(right, bottom - arm, right, bottom, strokePaint)
            }
        }
    }
}

class ChevronDownView(context: Context) : View(context) {
    constructor(context: Context, attrs: AttributeSet?) : this(context)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT_TERTIARY
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.strokeWidth = (w.coerceAtMost(h) * 0.13f).coerceAtLeast(2f)
        canvas.drawLine(w * 0.20f, h * 0.35f, w * 0.50f, h * 0.65f, paint)
        canvas.drawLine(w * 0.50f, h * 0.65f, w * 0.80f, h * 0.35f, paint)
    }
}

class WarningGlyphView(context: Context) : View(context) {
    constructor(context: Context, attrs: AttributeSet?) : this(context)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT_SECONDARY
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT_SECONDARY
        style = Paint.Style.FILL
    }
    private val stem = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = w.coerceAtMost(h) * 0.42f
        ringPaint.strokeWidth = (w.coerceAtMost(h) * 0.085f).coerceAtLeast(2f)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val stemHalf = (w.coerceAtMost(h) * 0.05f).coerceAtLeast(1.5f)
        stem.set(cx - stemHalf, h * 0.28f, cx + stemHalf, h * 0.58f)
        canvas.drawRoundRect(stem, stemHalf, stemHalf, markPaint)
        canvas.drawCircle(cx, h * 0.70f, stemHalf * 1.15f, markPaint)
    }
}

// 播放请求 User-Agent:必须用桌面浏览器标识。部分上游 CDN(如 bilibili)对
// 含 "Android" 的 UA 做防盗链拦截会返回 403(实测桌面 Chrome / 裸 Mozilla 可 206,
