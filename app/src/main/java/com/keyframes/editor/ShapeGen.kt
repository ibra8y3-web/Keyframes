package com.keyframes.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Draws real vector shapes to PNG files on the device so they can be used as stickers. */
object ShapeGen {
    val GLYPHS = listOf("★", "♥", "➤", "●", "■", "◆", "✚")

    val BG = listOf(
        0xFF000000.toInt() to 0xFF000000.toInt(), 0xFFFFFFFF.toInt() to 0xFFEEEEEE.toInt(),
        0xFF7C4DFF.toInt() to 0xFF00BFA5.toInt(), 0xFFFF9800.toInt() to 0xFFE91E63.toInt(),
        0xFF0D47A1.toInt() to 0xFF00E5FF.toInt(), 0xFF0B0B14.toInt() to 0xFF4A148C.toInt()
    )

    /** A solid or gradient picture with the given aspect ratio, used as a colour clip. Returns uri, width, height. */
    fun solid(ctx: Context, kind: Int, aspect: Float): Triple<Uri, Int, Int> {
        var w = if (aspect >= 1f) 1280 else (1280 * aspect).toInt()
        var h = if (aspect >= 1f) (1280 / aspect).toInt() else 1280
        w -= w % 2; h -= h % 2
        val f = File(ctx.filesDir, "bg_${kind}_${w}x$h.png")
        if (!f.exists()) {
            val (c1, c2) = BG[kind.coerceIn(0, BG.size - 1)]
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, Shader.TileMode.CLAMP) }
            Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return Triple(Uri.fromFile(f), w, h)
    }

    private fun poly(vararg pts: Float): Path {
        val p = Path()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.close()
        return p
    }

    fun make(ctx: Context, kind: Int, color: Int): Uri {
        val f = File(ctx.filesDir, "shape_${kind}_${Integer.toHexString(color)}.png")
        if (!f.exists()) {
            val s = 256
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
            when (kind) {
                0 -> {
                    val p = Path()
                    for (i in 0 until 10) {
                        val a = -PI / 2 + i * PI / 5
                        val r = if (i % 2 == 0) 120.0 else 50.0
                        val x = (128 + r * cos(a)).toFloat(); val y = (128 + r * sin(a)).toFloat()
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close(); cv.drawPath(p, paint)
                }
                1 -> {
                    val p = Path()
                    p.moveTo(128f, 226f)
                    p.cubicTo(20f, 150f, 10f, 60f, 70f, 40f)
                    p.cubicTo(105f, 28f, 128f, 55f, 128f, 75f)
                    p.cubicTo(128f, 55f, 151f, 28f, 186f, 40f)
                    p.cubicTo(246f, 60f, 236f, 150f, 128f, 226f)
                    p.close(); cv.drawPath(p, paint)
                }
                2 -> cv.drawPath(poly(20f, 100f, 150f, 100f, 150f, 50f, 236f, 128f, 150f, 206f, 150f, 156f, 20f, 156f), paint)
                3 -> cv.drawCircle(128f, 128f, 110f, paint)
                4 -> cv.drawRoundRect(RectF(16f, 40f, 240f, 216f), 28f, 28f, paint)
                5 -> cv.drawPath(poly(128f, 16f, 240f, 128f, 128f, 240f, 16f, 128f), paint)
                else -> cv.drawPath(poly(100f, 20f, 156f, 20f, 156f, 100f, 236f, 100f, 236f, 156f, 156f, 156f, 156f, 236f, 100f, 236f, 100f, 156f, 20f, 156f, 20f, 100f, 100f, 100f), paint)
            }
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return Uri.fromFile(f)
    }
}
