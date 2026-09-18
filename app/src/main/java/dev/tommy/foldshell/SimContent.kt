package dev.tommy.foldshell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min

// Generated stand-ins for real screen content — enough structure (text,
// edges, cards) that stretch, blur and dimming are readable in motion.
object SimContent {

    fun appBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                Color.rgb(24, 34, 58), Color.rgb(52, 30, 72), Shader.TileMode.CLAMP)
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        text.textSize = min(w, h) * 0.09f
        c.drawText("INNER APP", w * 0.08f, h * 0.12f, text)
        text.textSize = min(w, h) * 0.035f
        text.color = Color.argb(200, 200, 210, 235)
        c.drawText("fold transition simulation", w * 0.08f, h * 0.16f, text)
        // Left/right halves are tinted differently so the rotating pane (left,
        // crease on its right edge) is visually separable from the static pane.
        val half = Paint()
        half.color = Color.argb(46, 255, 255, 255)
        c.drawRect(0f, 0f, w / 2f, h.toFloat(), half)
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 220, 120); textSize = min(w, h) * 0.028f
        }
        c.drawText("ROTATING PANE →", w * 0.06f, h * 0.20f, tag)
        tag.color = Color.argb(220, 140, 220, 255)
        c.drawText("STATIC PANE", w * 0.56f, h * 0.20f, tag)
        val card = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(230, 235, 245); textSize = min(w, h) * 0.03f
        }
        val cols = 2
        val rows = 4
        val pad = w * 0.04f
        val cw = (w - pad * (cols + 1)) / cols
        val ch = (h * 0.68f - pad * (rows + 1)) / rows
        for (r in 0 until rows) for (col in 0 until cols) {
            val x = pad + col * (cw + pad)
            val y = h * 0.26f + r * (ch + pad)
            card.color = Color.HSVToColor(floatArrayOf((r * cols + col) * 47f % 360f, 0.45f, 0.85f))
            c.drawRoundRect(RectF(x, y, x + cw, y + ch), 24f, 24f, card)
            c.drawText("${r * cols + col + 1}", x + cw * 0.08f, y + ch * 0.5f, label)
        }
        return bmp
    }

    fun lockBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                Color.rgb(12, 40, 60), Color.rgb(20, 16, 44), Shader.TileMode.CLAMP)
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        text.textSize = min(w, h) * 0.22f
        text.isFakeBoldText = true
        c.drawText("9:41", w * 0.12f, h * 0.30f, text)
        text.isFakeBoldText = false
        text.textSize = min(w, h) * 0.045f
        text.color = Color.argb(220, 190, 200, 225)
        c.drawText("Tue, September 18", w * 0.12f, h * 0.36f, text)
        text.textSize = min(w, h) * 0.03f
        text.color = Color.argb(200, 255, 220, 120)
        c.drawText("simulated cover — lights up early on close", w * 0.12f, h * 0.42f, text)
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 255, 255) }
        c.drawRoundRect(RectF(w * 0.30f, h * 0.90f, w * 0.70f, h * 0.915f), 20f, 20f, pill)
        return bmp
    }

    // Cheap blur: downscale hard then upscale with filtering.
    fun blurred(src: Bitmap, factor: Int = 10): Bitmap {
        val small = Bitmap.createScaledBitmap(src, (src.width / factor).coerceAtLeast(4),
            (src.height / factor).coerceAtLeast(4), true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }
}
