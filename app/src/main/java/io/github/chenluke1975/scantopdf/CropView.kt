package io.github.chenluke1975.scantopdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Magnifier
import kotlin.math.hypot

/** Shows a page image with 4 big draggable corner handles for a perspective crop. */
class CropView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    /** Normalized corners: top-left, top-right, bottom-right, bottom-left. */
    var quad: List<Pt> = Geometry.FULL
        set(v) { field = v; invalidate() }

    var onChanged: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val pad = 30 * dp
    private val handleR = 16 * dp
    private val touchR = 56 * dp
    private val box = RectF()
    private var active = -1
    private var magnifier: Any? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x88000000.toInt(); style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E88E5.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * dp
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt() }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4 * dp
    }
    private val path = Path()

    private fun layoutBox(): Boolean {
        val b = bitmap ?: return false
        val r = Geometry.fit(b.width.toFloat(), b.height.toFloat(), width - 2 * pad, height - 2 * pad)
        box.set(pad + r[0], pad + r[1], pad + r[0] + r[2], pad + r[1] + r[3])
        return true
    }

    private fun vx(p: Pt) = box.left + p.x * box.width()
    private fun vy(p: Pt) = box.top + p.y * box.height()

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (!layoutBox()) return
        canvas.drawBitmap(b, null, box, bmpPaint)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(box, Path.Direction.CW)
        path.moveTo(vx(quad[0]), vy(quad[0]))
        for (i in 1..3) path.lineTo(vx(quad[i]), vy(quad[i]))
        path.close()
        canvas.drawPath(path, shade)
        path.reset()
        path.moveTo(vx(quad[0]), vy(quad[0]))
        for (i in 1..3) path.lineTo(vx(quad[i]), vy(quad[i]))
        path.close()
        canvas.drawPath(path, line)
        for (p in quad) {
            canvas.drawCircle(vx(p), vy(p), handleR, handleFill)
            canvas.drawCircle(vx(p), vy(p), handleR, handleRing)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || !layoutBox()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bestD = touchR
                quad.forEachIndexed { i, p ->
                    val d = hypot(e.x - vx(p), e.y - vy(p))
                    if (d < bestD) { bestD = d; best = i }
                }
                active = best
                if (active >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return false
                val p = Geometry.clamp(Pt((e.x - box.left) / box.width(), (e.y - box.top) / box.height()))
                quad = quad.toMutableList().also { it[active] = p }
                showMagnifier(vx(p), vy(p))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) {
                    active = -1
                    hideMagnifier()
                    quad = Geometry.sortCorners(quad)
                    onChanged?.invoke()
                }
                return true
            }
        }
        return false
    }

    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 28) return
        val m = (magnifier as? Magnifier) ?: Magnifier(this).also { magnifier = it }
        m.show(x, y)
    }

    private fun hideMagnifier() {
        if (Build.VERSION.SDK_INT < 28) return
        (magnifier as? Magnifier)?.dismiss()
    }
}
