package dev.tommy.foldshell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

// One panel of the fold transition. The shared openFraction (0=closed,
// 1=open) drives every panel so both sides stay synchronized.
//
// Model: content lives on a fixed rectangular projection plane; the
// physical surface rotates behind it. Rotating regions keep the
// hinge-adjacent slice stretched to fill (countering foreshortening),
// surface outside the rectangle is black wedge + blurred boundary.
class PanelSimView(context: Context) : View(context) {
    enum class Role {
        OUT_COVER,   // cover rotating away while opening (whole panel)
        OUT_INNER,   // inner closing (left half rotates toward viewer)
        ARRIVE       // destination panel: content + hinge-side shade
    }

    var role = Role.ARRIVE
    var bitmap: Bitmap? = null
    var blurBitmap: Bitmap? = null
    var hingeLeft = true        // which side the hinge/crease is on
    var openFraction = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) { field = c; invalidate() }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val black = Paint().apply { color = Color.BLACK }
    private val shadePaint = Paint()
    private val blurEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
        color = Color.BLACK
    }
    private val src = Rect()
    private val blurSrc = Rect()
    private val dst = RectF()
    private val path = Path()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: run { c.drawColor(Color.BLACK); return }
        val w = width.toFloat(); val h = height.toFloat()
        // Outgoing progress: cover leaves as it opens; inner leaves as it closes.
        val outP = if (role == Role.OUT_COVER) openFraction else 1f - openFraction
        when (role) {
            Role.OUT_COVER -> drawOutgoing(c, b, w, h, outP, rotateWhole = true)
            Role.OUT_INNER -> drawOutgoing(c, b, w, h, outP, rotateWhole = false)
            Role.ARRIVE -> drawArrive(c, b, w, h, openFraction)
        }
    }

    private fun drawOutgoing(c: Canvas, b: Bitmap, w: Float, h: Float, p: Float, rotateWhole: Boolean) {
        val rotW = if (rotateWhole) w else w / 2f
        val cosP = cos(p * PI).toFloat().coerceAtLeast(0f)

        // Static half first (inner: right half stays, dims with progress).
        if (!rotateWhole) {
            src.set(b.width / 2, 0, b.width, b.height)
            dst.set(w / 2f, 0f, w, h)
            c.drawBitmap(b, src, dst, paint)
            drawDim(c, RectF(w / 2f, 0f, w, h), 0.45f * p)
        }

        // Rotating region: hinge-adjacent slice stretched to fill it.
        val sliceW = (if (rotateWhole) b.width.toFloat() else b.width / 2f) * cosP
        var drewSlice = false
        if (sliceW >= 2f) {
            when {
                hingeLeft -> src.set(0, 0, sliceW.toInt(), b.height)
                rotateWhole -> src.set((b.width - sliceW).toInt(), 0, b.width, b.height)
                else -> {
                    // inner: hinge is the crease = right edge of the left half
                    val end = b.width / 2
                    src.set((end - sliceW).toInt().coerceAtLeast(0), 0, end, b.height)
                }
            }
            dst.set(0f, 0f, rotW, h)
            c.drawBitmap(b, src, dst, paint)
            drewSlice = true
        } else {
            c.drawRect(0f, 0f, rotW, h, black)
        }

        // Progressive blur: free-edge side blurs first. Crossfade to the blur
        // copy using the SAME normalized src rect so it aligns with the slice.
        if (drewSlice) blurBitmap?.let { bb ->
            val sx = bb.width.toFloat() / b.width
            val sy = bb.height.toFloat() / b.height
            blurSrc.set((src.left * sx).toInt(), (src.top * sy).toInt(),
                (src.right * sx).toInt().coerceAtLeast((src.left * sx).toInt() + 1),
                (src.bottom * sy).toInt())
            val reach = smoothstep(0.02f, 0.55f, p) * rotW
            if (reach > 4f) {
                val steps = 10
                for (i in 0 until steps) {
                    val alpha = (255 * (i + 1f) / steps).toInt()
                    val bp = Paint(paint).apply { this.alpha = alpha }
                    c.save()
                    if (hingeLeft) {
                        val e0 = rotW - reach + reach * i / steps
                        c.clipRect(e0, 0f, e0 + reach / steps + 1f, h)
                    } else {
                        val off = if (rotateWhole) w - rotW else 0f
                        val e0 = off + reach - reach * i / steps
                        c.clipRect(e0 - reach / steps - 1f, 0f, e0, h)
                    }
                    c.drawBitmap(bb, blurSrc, dst, bp)
                    c.restore()
                }
            }
        }

        // Uniform dim rising with progress (peak mid-transition).
        val dim = 0.55f * smoothstep(0.3f, 1f, p) + 0.15f * sin(p * PI).toFloat()
        if (dim > 0.01f) drawDim(c, RectF(0f, 0f, rotW, h), dim)

        // Free-edge blackout once past edge-on.
        if (cosP < 0.05f) {
            val t = smoothstep(0.95f, 1f, p)
            val reach = rotW * t
            if (hingeLeft) c.drawRect(rotW - reach, 0f, rotW, h, black)
            else c.drawRect(0f, 0f, reach, h, black)
        }

        // Wedges: physical trapezoid outside the rectangle → black triangles
        // at the free edge, tapering to zero at the crease (inner only).
        if (!rotateWhole && cosP < 0.99f) {
            val wedgeH = h * 0.5f * (1f - cosP) * 0.55f
            if (wedgeH > 2f) {
                path.reset()
                path.moveTo(0f, 0f); path.lineTo(0f, wedgeH); path.lineTo(rotW, 0f); path.close()
                c.drawPath(path, black)
                path.reset()
                path.moveTo(0f, h); path.lineTo(0f, h - wedgeH); path.lineTo(rotW, h); path.close()
                c.drawPath(path, black)
                // blurred boundary along the wedge hypotenuse
                path.reset()
                path.moveTo(0f, wedgeH); path.lineTo(rotW, 0f)
                c.drawPath(path, blurEdge)
                path.reset()
                path.moveTo(0f, h - wedgeH); path.lineTo(rotW, h)
                c.drawPath(path, blurEdge)
            }
        }
    }

    private fun drawArrive(c: Canvas, b: Bitmap, w: Float, h: Float, f: Float) {
        c.drawBitmap(b, null, dst.apply { set(0f, 0f, w, h) }, paint)
        // hinge-side shadow that contracts as the transition completes
        val shade = (1f - f) * 0.55f + 0.08f * sin(f * PI).toFloat()
        if (shade > 0.01f) {
            shadePaint.shader = if (hingeLeft)
                LinearGradient(0f, 0f, w * 0.6f, 0f, Color.argb((shade * 255).toInt(), 0, 0, 0),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP)
            else
                LinearGradient(w, 0f, w * 0.4f, 0f, Color.argb((shade * 255).toInt(), 0, 0, 0),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, shadePaint)
        }
    }

    private fun drawDim(c: Canvas, r: RectF, a: Float) {
        shadePaint.shader = null
        shadePaint.color = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
        c.drawRect(r, shadePaint)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
