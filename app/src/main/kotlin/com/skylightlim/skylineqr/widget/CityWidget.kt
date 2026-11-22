package com.skylightlim.skylineqr.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.skylightlim.skylineqr.MainActivity
import com.skylightlim.skylineqr.R
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home screen widget (2026-11-22, at the user's request: "only show the city, no need other
 * settings"): a picture of the city the app last showed, as it looked, and nothing else. A home
 * screen can't run the WebGPU renderer, so the app keeps the picture fresh while it is open
 * ([save]) and the widget shows the saved file. Before the app has drawn a city it shows the
 * Kuala Lumpur poster. A tap opens the app.
 */
class CityWidget : AppWidgetProvider() {

  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    update(context, manager, ids)
  }

  companion object {
    private const val TAG = "SkylineQR"

    /** Long enough a side to stay sharp on a widget the width of the screen. */
    private const val MAX_SIDE = 1080

    private fun file(context: Context) = File(context.filesDir, "widget-city.png")

    /**
     * Keeps [city] as the widget's picture and redraws every placed widget with it. Call it off
     * the main thread: it scales and writes a PNG.
     */
    fun save(context: Context, city: Bitmap) {
      val scale = MAX_SIDE.toFloat() / max(city.width, city.height)
      val picture =
        if (scale < 1f) Bitmap.createScaledBitmap(city, (city.width * scale).roundToInt(), (city.height * scale).roundToInt(), true)
        else city
      val target = file(context)
      val partial = File(target.parentFile, target.name + ".part")
      try {
        partial.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // A widget drawn meanwhile reads the old picture or the new one, never half of one.
        if (!partial.renameTo(target)) error("could not replace ${target.name}")
      } catch (e: Exception) {
        Log.w(TAG, "widget picture not saved", e)
        partial.delete()
        return
      } finally {
        if (picture !== city) picture.recycle()
      }
      val manager = AppWidgetManager.getInstance(context) ?: return
      val ids = manager.getAppWidgetIds(ComponentName(context, CityWidget::class.java))
      if (ids.isNotEmpty()) update(context, manager, ids)
    }

    private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
      val city = file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
      val open =
        PendingIntent.getActivity(
          context,
          0,
          Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      for (id in ids) {
        val views = RemoteViews(context.packageName, R.layout.widget_city)
        if (city != null) {
          views.setImageViewBitmap(R.id.widget_city, city)
          views.setViewVisibility(R.id.widget_city, View.VISIBLE)
          views.setViewVisibility(R.id.widget_poster, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.widget_root, open)
        manager.updateAppWidget(id, views)
      }
    }
  }
}
