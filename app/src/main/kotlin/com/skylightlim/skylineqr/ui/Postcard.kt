package com.skylightlim.skylineqr.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A rectangle in the postcard's pixels. */
internal data class Area(val left: Int, val top: Int, val width: Int, val height: Int) {
  val right
    get() = left + width

  val bottom
    get() = top + height
}

/**
 * Where everything sits on a postcard. [code] is the whole code with its quiet zone, [modulePx]
 * pixels a module, so the code's bitmap is drawn at its own size and never resampled.
 */
internal data class PostcardLayout(val photo: Area, val words: Area, val code: Area, val modulePx: Int)

/**
 * The city as a postcard (2026-11-22, at the user's request: "share the current qr code as a
 * postcard"): the city as the camera sees it, framed on the code's paper, with the card's title,
 * the place, the hour and the day beneath it, and the code itself in the corner, so whoever gets
 * the postcard can scan it. 4:5, the shape most apps show whole.
 */
internal object Postcard {
  const val WIDTH = 1080
  const val HEIGHT = 1350

  /** The paper showing round the photo. */
  private const val MARGIN = 48

  /** Between the photo and what is beneath it, and between the words and the code. */
  private const val GAP = 36

  /** About how wide the code is drawn, quiet zone included. */
  private const val CODE_SIDE = 340

  /** The fewest pixels a module gets, however big the code, so a camera still reads it. */
  const val MIN_MODULE_PX = 3

  /** The quiet zone round the code, in modules (FlatCode's). */
  const val QUIET = 4

  private const val CORNER = 36f

  /**
   * The layout for a code [modules] modules wide. The code's quiet zone is paper like the margin,
   * so the code's own modules line up with the photo's right and bottom edges.
   */
  fun layout(modules: Int): PostcardLayout {
    val side = modules + 2 * QUIET
    val modulePx = max(MIN_MODULE_PX, CODE_SIDE / side)
    val codeSide = side * modulePx
    val quietPx = QUIET * modulePx
    // The modules end where the photo does; the quiet zone may reach into the margin, never past the edge.
    val codeRight = min(WIDTH, WIDTH - MARGIN + quietPx)
    val codeBottom = min(HEIGHT, HEIGHT - MARGIN + quietPx)
    val code = Area(codeRight - codeSide, codeBottom - codeSide, codeSide, codeSide)
    // GAP from the modules, but never over the quiet zone, which a camera needs clear.
    val photoBottom = min(code.top, code.top + quietPx - GAP)
    val photo = Area(MARGIN, MARGIN, WIDTH - 2 * MARGIN, photoBottom - MARGIN)
    val wordsTop = photo.bottom + GAP
    val wordsRight = min(code.left, code.left + quietPx - GAP)
    val words = Area(MARGIN, wordsTop, wordsRight - MARGIN, HEIGHT - MARGIN - wordsTop)
    return PostcardLayout(photo, words, code, modulePx)
  }

  /**
   * Draws the postcard. [photo] is the city, cropped to fill its frame; null (no WebGPU here) leaves
   * the frame to [code] alone, drawn large. [code] must be drawn [PostcardLayout.modulePx] pixels a
   * module with a [QUIET] zone, as SkylineQrState.codeBitmap draws it. [paper] and [ink] are the
   * flat code's colours for the hour, as ARGB.
   */
  fun draw(photo: Bitmap?, code: Bitmap, layout: PostcardLayout, paper: Int, ink: Int, title: String, caption: String): Bitmap {
    val card = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(card)
    canvas.drawColor(paper)

    val frame = layout.photo
    val frameRect = RectF(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(), frame.bottom.toFloat())
    canvas.save()
    canvas.clipPath(Path().apply { addRoundRect(frameRect, CORNER, CORNER, Path.Direction.CW) })
    if (photo != null) {
      canvas.drawBitmap(photo, crop(photo.width, photo.height, frame.width, frame.height), frameRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    } else {
      // The code alone, as big as fits, in whole pixels a module so it stays crisp.
      canvas.drawColor(darken(ink, 0.12f, paper))
      val scale = max(1, min(frame.width, frame.height) * 4 / 5 / code.width)
      val side = code.width * scale
      val left = frame.left + (frame.width - side) / 2
      val top = frame.top + (frame.height - side) / 2
      canvas.drawBitmap(code, null, Rect(left, top, left + side, top + side), Paint())
    }
    canvas.restore()

    // The code at its own size: one bitmap pixel to one postcard pixel, nothing blurred.
    canvas.drawBitmap(code, layout.code.left.toFloat(), layout.code.top.toFloat(), null)

    val words = layout.words
    val titlePaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = darken(ink, 0.28f)
        textSize = 60f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
      }
    val titleLayout =
      StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, max(1, words.width))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setMaxLines(2)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .build()
    canvas.save()
    canvas.translate(words.left.toFloat(), words.top.toFloat() - 6f)
    titleLayout.draw(canvas)
    canvas.restore()

    val captionPaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = darken(ink, 0.55f)
        textSize = 34f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
      }
    val captionTop = words.top + titleLayout.height + 14
    val shownCaption = TextUtils.ellipsize(caption, captionPaint, words.width.toFloat(), TextUtils.TruncateAt.END)
    canvas.drawText(shownCaption, 0, shownCaption.length, words.left.toFloat(), captionTop - captionPaint.ascent(), captionPaint)

    // At the foot, level with the code's last row: what the code is for.
    val footPaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = darken(ink, 0.55f)
        alpha = 190
        textSize = 28f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.04f
      }
    val foot = TextUtils.ellipsize("Scan the code to open it · SkylineQR", footPaint, words.width.toFloat(), TextUtils.TruncateAt.END)
    canvas.drawText(foot, 0, foot.length, words.left.toFloat(), words.bottom - footPaint.descent(), footPaint)
    return card
  }

  /** The middle of a [width] by [height] image cut to the frame's shape. */
  fun crop(width: Int, height: Int, frameWidth: Int, frameHeight: Int): Rect {
    val frameAspect = frameWidth.toFloat() / frameHeight
    return if (width.toFloat() / height > frameAspect) {
      val w = (height * frameAspect).roundToInt()
      val left = (width - w) / 2
      Rect(left, 0, left + w, height)
    } else {
      val h = (width / frameAspect).roundToInt()
      val top = (height - h) / 2
      Rect(0, top, width, top + h)
    }
  }

  /** [argb] scaled toward black, keeping its hue; mixed over [over] when that is given. */
  private fun darken(argb: Int, keep: Float, over: Int? = null): Int {
    fun ch(shift: Int) = (argb shr shift) and 0xff
    if (over != null) {
      fun mix(shift: Int) = (((over shr shift) and 0xff) * (1f - keep) + ch(shift) * keep).roundToInt()
      return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
    fun dark(shift: Int) = (ch(shift) * keep).roundToInt()
    return (0xff shl 24) or (dark(16) shl 16) or (dark(8) shl 8) or dark(0)
  }

  /**
   * Saves [postcard] where only the share sheet's chosen app may read it, and opens the share
   * sheet. Earlier postcards are cleared first, so they never pile up.
   */
  suspend fun share(context: Context, postcard: Bitmap, title: String) {
    val file =
      withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "skylineqr-postcard-${System.currentTimeMillis()}.png").also { file ->
          file.outputStream().use { postcard.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
      }
    val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    val send =
      Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        // The share sheet's preview reads the image through the clip.
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    context.startActivity(Intent.createChooser(send, "Share the postcard").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
  }

  /** Under the app's cache, as res/xml/postcard_paths.xml shares it. */
  private const val DIR = "postcards"

  /** The FileProvider's authority after the package name, as the manifest declares it. */
  private const val AUTHORITY_SUFFIX = ".postcards"
}
