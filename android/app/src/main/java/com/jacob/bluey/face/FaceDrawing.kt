package com.jacob.bluey.face

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.jacob.bluey.link.Mood
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Face colors. Green on Android; the iPhone keeps the blueberry blues from Shared/Palette.swift
 * (0xA9BCFF, 0x6C86F5, 0x4254D6, 0x2B2F8F, nose 0x1C1F66). Same light-to-deep steps, so shine
 * and shading look the same.
 */
private object Palette {
    val berry1 = Color(0xFFB6F0A8)  // light mint
    val berry2 = Color(0xFF6FD36A)
    val berry3 = Color(0xFF3BAA4E)
    val berry4 = Color(0xFF1E6B34)  // deep green
    val nose = Color(0xFF163D1F)    // crown, brows and mouth
    val ink = Color(0xFF17151F)
    val cheek = Color(0xFFF3A6D8)
    val tongue = Color(0xFFE58BC4)
}

/** The design is drawn on an 844 × 390 landscape phone, like the iPhone app. */
private const val DESIGN_WIDTH = 844f
private const val DESIGN_HEIGHT = 390f

/** A rect from origin and size, like CGRect(x:y:width:height:). */
private fun box(x: Float, y: Float, width: Float, height: Float) = Rect(x, y, x + width, y + height)

private fun oval(rect: Rect) = Path().apply { addOval(rect) }

/** Moves to [from] and adds a quadratic curve as its exact cubic (avoids Compose API renames). */
private fun Path.quad(from: Offset, control: Offset, to: Offset) {
    moveTo(from.x, from.y)
    cubicTo(
        from.x + 2f / 3f * (control.x - from.x), from.y + 2f / 3f * (control.y - from.y),
        to.x + 2f / 3f * (control.x - to.x), to.y + 2f / 3f * (control.y - to.y),
        to.x, to.y,
    )
}

/** The blob outline: CSS `border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%` (BlobShape.swift). */
private fun blobPath(r: Rect): Path {
    val w = r.width
    val h = r.height
    val tl = Size(0.52f * w, 0.58f * h)
    val tr = Size(0.48f * w, 0.56f * h)
    val br = Size(0.46f * w, 0.44f * h)
    val bl = Size(0.54f * w, 0.42f * h)
    val k = 0.5523f  // cubic approximation of a quarter ellipse
    return Path().apply {
        moveTo(r.left + tl.width, r.top)
        lineTo(r.right - tr.width, r.top)
        cubicTo(r.right - tr.width * (1 - k), r.top, r.right, r.top + tr.height * (1 - k), r.right, r.top + tr.height)
        lineTo(r.right, r.bottom - br.height)
        cubicTo(r.right, r.bottom - br.height * (1 - k), r.right - br.width * (1 - k), r.bottom, r.right - br.width, r.bottom)
        lineTo(r.left + bl.width, r.bottom)
        cubicTo(r.left + bl.width * (1 - k), r.bottom, r.left, r.bottom - bl.height * (1 - k), r.left, r.bottom - bl.height)
        lineTo(r.left, r.top + tl.height)
        cubicTo(r.left, r.top + tl.height * (1 - k), r.left + tl.width * (1 - k), r.top, r.left + tl.width, r.top)
        close()
    }
}

/** The blueberry gradient at 150°, like the design. */
private fun berryBrush(r: Rect) = Brush.linearGradient(
    0f to Palette.berry1, 0.34f to Palette.berry2, 0.68f to Palette.berry3, 1f to Palette.berry4,
    start = Offset(r.left + 0.25f * r.width, r.top + 0.067f * r.height),
    end = Offset(r.left + 0.75f * r.width, r.top + 0.933f * r.height),
)

private fun shineBrush(body: Rect) = Brush.radialGradient(
    listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
    center = Offset(body.left + 246f, body.top + 126f), radius = 243f,
)

/** The little blueberry crown on top of his head (SVG path from the design, 60×40 box). */
private fun crownPath(r: Rect): Path {
    val points = listOf(30 to 4, 36 to 16, 52 to 12, 40 to 24, 46 to 36, 30 to 28, 14 to 36, 20 to 24, 8 to 12, 24 to 16)
    return Path().apply {
        points.forEachIndexed { i, (x, y) ->
            val px = r.left + x / 60f * r.width
            val py = r.top + y / 40f * r.height
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

/** Fills a shape with his skin (gradient plus the same soft shine), for eyelids. */
private fun DrawScope.fillSkin(path: Path, body: Rect) {
    drawPath(path, berryBrush(body))
    drawPath(path, shineBrush(body))
}

private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

/**
 * The landscape face on pure black: he fills the screen and peeks up from the bottom edge.
 * Port of FaceView.draw in iOS/Sources/FaceView.swift, in the same 844 × 390 design units.
 */
fun DrawScope.drawFace(f: FaceAnimator.Frame, typeface: Typeface?) {
    drawRect(Color.Black)
    val canvas = drawContext.canvas
    canvas.save()

    val scale = max(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT)
    canvas.translate((size.width - DESIGN_WIDTH * scale) / 2, size.height - DESIGN_HEIGHT * scale)
    canvas.scale(scale, scale)

    // Whole-body motion: breathing, talking bounce, idle hops, and leaning toward what he looks at.
    val bounce = (-f.talk * 16 + f.breathe * 3 - f.hop).toFloat()
    val body = box(12f, 44f + bounce, 820f, 700f)
    val pivot = Offset(body.center.x, body.bottom)
    canvas.translate(pivot.x, pivot.y)
    canvas.rotate(Math.toDegrees(f.lean).toFloat())
    val stretch = (1 + min(0.03, max(-0.03, f.hop / 900))).toFloat()
    canvas.scale(2 - stretch, stretch)
    canvas.translate(-pivot.x, -pivot.y)

    val blob = blobPath(body)
    drawPath(blob, Palette.berry3)
    drawPath(blob, berryBrush(body))
    clipPath(blob) {
        drawPath(blob, shineBrush(body))
        // A little glossy sparkle on his head.
        drawOval(Color.White.copy(alpha = 0.35f), Offset(body.left + 190f, body.top + 40f), Size(46f, 22f))
    }

    val browLift = f.browLift.toFloat()
    drawPath(crownPath(box(body.left + 368f, body.top - 20f - browLift * 0.3f, 84f, 56f)), Palette.nose)

    val eyeY = body.top + 157f
    val eyes = listOf(Offset(body.left + 280f, eyeY), Offset(body.left + 540f, eyeY))
    val squash = (1 - f.talk * 0.12).toFloat()

    // Blushy cheeks, rosier when he's happy.
    cheekPaint.color = Palette.cheek.copy(alpha = (0.25 + 0.45 * f.blush).toFloat().coerceIn(0f, 1f)).toArgb()
    drawIntoCanvas { native ->
        eyes.forEachIndexed { i, c ->
            val side = if (i == 0) -1f else 1f
            val left = c.x - 45f + side * 62f
            native.nativeCanvas.drawOval(left, c.y + 96f, left + 90f, c.y + 96f + 42f, cheekPaint)
        }
    }

    eyes.forEachIndexed { i, c ->
        val side = if (i == 0) -1f else 1f

        // Little arched eyebrows do a lot of the acting.
        canvas.save()
        val browY = c.y - 112f - min(18f, max(-10f, browLift)) * 0.7f
        canvas.translate(c.x + side * 6f, browY)
        val thinkingBrow = if (f.mood == Mood.THINKING && i == 1) -0.25 else 0.0
        canvas.rotate(Math.toDegrees(f.browTilt * side * -1 + thinkingBrow).toFloat())
        val arch = Path().apply { quad(Offset(-38f, 6f), Offset(0f, -12f), Offset(38f, 6f)) }
        drawPath(arch, Palette.nose, style = Stroke(width = 14f, cap = StrokeCap.Round))
        canvas.restore()

        when {
            f.mood == Mood.RESTING -> {
                // Peacefully closed: a soft downward curve.
                val shut = Path().apply { quad(Offset(c.x - 62f, c.y + 6f), Offset(c.x, c.y + 52f), Offset(c.x + 62f, c.y + 6f)) }
                drawPath(shut, Palette.ink, style = Stroke(width = 18f, cap = StrokeCap.Round))
            }
            f.mood == Mood.HAPPY && f.squint > 0.6 -> {
                val arc = Path().apply { quad(Offset(c.x - 60f, c.y + 34f), Offset(c.x, c.y - 54f), Offset(c.x + 60f, c.y + 34f)) }
                drawPath(arc, Palette.ink, style = Stroke(width = 24f, cap = StrokeCap.Round))
            }
            else -> drawOpenEye(f, c, side, squash, body)
        }
    }

    if (f.mood == Mood.RESTING) {
        // Little z's floating up while he naps.
        textPaint.typeface = typeface?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
        drawIntoCanvas { native ->
            for (i in 0 until 3) {
                val phase = (f.time * 0.35 + i / 3.0) % 1.0
                textPaint.textSize = 26f + i * 8f
                textPaint.color = Color.White.copy(alpha = (0.9 * sin(PI * phase)).toFloat().coerceIn(0f, 1f)).toArgb()
                val x = body.left + 660f + phase.toFloat() * 70f + sin(phase * 6).toFloat() * 8f
                val y = body.top + 90f - phase.toFloat() * 110f
                // SwiftUI centers text on the point; a baseline sits about a third of the size below center.
                native.nativeCanvas.drawText("z", x, y + textPaint.textSize * 0.35f, textPaint)
            }
        }
    }

    if (f.mood == Mood.THINKING) {
        for (i in 0 until 3) {
            val wobble = (sin(f.time * 3 + i) * 4).toFloat()
            drawOval(Color.White.copy(alpha = (1 - i * 0.3).toFloat()),
                Offset(body.left + 690f + i * 26f, body.top + 40f - i * 12f + wobble), Size(16f, 16f))
        }
    }

    // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
    val talk = f.talk.toFloat()
    val noseW = 60f - talk * 8f
    val noseH = 38f + talk * 34f
    val nose = box(body.left + 410f - noseW / 2, body.top + 272f - talk * 6f, noseW, noseH)
    if (f.mood == Mood.HAPPY && f.talk < 0.1) {
        val smile = Path().apply {
            quad(Offset(nose.left - 8f, nose.top + 8f), Offset(nose.center.x, nose.top + 58f), Offset(nose.right + 8f, nose.top + 8f))
            close()
        }
        drawPath(smile, Palette.nose)
    } else {
        drawOval(Palette.nose, nose.topLeft, nose.size)
        if (f.talk > 0.25) {
            drawOval(Palette.tongue.copy(alpha = 0.9f), Offset(nose.center.x - noseW * 0.28f, nose.bottom - noseH * 0.38f),
                Size(noseW * 0.56f, noseH * 0.3f))
        }
    }

    canvas.restore()
}

private fun DrawScope.drawOpenEye(f: FaceAnimator.Frame, c: Offset, side: Float, squash: Float, body: Rect) {
    val open = (max(0.06, 1 - f.closed) * squash).toFloat()
    val white = box(c.x - 95f, c.y - 95f * open, 190f, 190f * open)
    val whitePath = oval(white)
    drawPath(whitePath, Color.White)
    if (open <= 0.2f) return

    val g = FaceAnimator.clampGaze(f.gaze)
    // Eyes converge a little when looking down close, and reach further for a livelier look.
    val reach = 50f
    val r = (46 * f.pupil).toFloat()
    var pupilY = c.y + g.y.toFloat() * reach * open
    if (f.mood == Mood.SLEEPY) pupilY = max(pupilY, c.y) + 28f  // eyes sink under heavy lids
    val pupil = Offset(c.x + g.x.toFloat() * reach - side * 3f, pupilY)
    clipPath(whitePath) {
        drawOval(Palette.ink, Offset(pupil.x - r, pupil.y - r), Size(r * 2, r * 2))
        // Two catchlights make them shine.
        drawOval(Color.White, Offset(pupil.x - r * 0.58f, pupil.y - r * 0.72f), Size(r * 0.56f, r * 0.56f))
        drawOval(Color.White.copy(alpha = 0.85f), Offset(pupil.x + r * 0.28f, pupil.y + r * 0.22f), Size(r * 0.24f, r * 0.24f))
        // Happy cheeks push up from below as he smiles.
        if (f.squint > 0.02) {
            fillSkin(oval(box(c.x - 110f, c.y + 95f * open - 70f * f.squint.toFloat(), 220f, 160f)), body)
        }
        // Heavy, droopy lids when he's getting sleepy.
        if (f.mood == Mood.SLEEPY) {
            val droop = (0.5 + 0.06 * sin(f.time * 1.3)).toFloat()
            fillSkin(Path().apply { addRect(box(c.x - 100f, white.top - 10f, 200f, white.height * droop + 10f)) }, body)
            val lidY = white.top + white.height * droop
            drawLine(Palette.nose.copy(alpha = 0.7f), Offset(c.x - 92f, lidY), Offset(c.x + 92f, lidY),
                strokeWidth = 8f, cap = StrokeCap.Round)
        }
        // A soft upper lid when he's focused on pointing.
        if (f.mood == Mood.POINTING) {
            fillSkin(Path().apply { addRect(box(c.x - 100f, white.top - 20f, 200f, 40f)) }, body)
        }
    }
}
