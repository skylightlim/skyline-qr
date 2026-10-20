package com.skylightlim.skylineqr.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.skylightlim.skylineqr.R
import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.compose.SkylineEvent
import kotlin.math.abs
import kotlin.random.Random

/**
 * The city's sound: three loops crossfaded by the hour and the weather (traffic by day, insects at
 * night, rain), and one-shots for thunder and meteors. All CC0 recordings, credited in NOTICE.
 * Muted until the person turns it on; muted, nothing plays at all.
 */
class CitySound(private val context: Context) : AutoCloseable {

  private val attributes =
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_GAME)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()

  private class Loop(val player: MediaPlayer) {
    var volume = 0f
    var target = 0f
  }

  private val loops = listOf(R.raw.day_traffic, R.raw.night_insects, R.raw.rain).map { Loop(loop(it)) }
  private val traffic = loops[0]
  private val insects = loops[1]
  private val rain = loops[2]

  private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
  private val thunder = pool.load(context, R.raw.thunder, 1)
  private val whoosh = pool.load(context, R.raw.meteor_whoosh, 1)
  private val impact = pool.load(context, R.raw.meteor_impact, 1)

  private val handler = Handler(Looper.getMainLooper())
  private var foreground = true

  var muted = true
    set(value) {
      if (field == value) return
      field = value
      playOrPause()
    }

  /** Sets where each loop is heading; the fade gets there over about a second and a half. */
  fun mix(air: Atmosphere) {
    traffic.target = (0.12f + 0.45f * (1f - air.night)) * (1f - 0.5f * air.rain)
    insects.target = 0.9f * air.night * (1f - 0.7f * air.rain)
    rain.target = 0.85f * air.rain
    fade()
  }

  fun play(event: SkylineEvent) {
    if (muted || !foreground) return
    when (event) {
      // Thunder arrives a beat after the flash, later the further away the strike.
      SkylineEvent.LIGHTNING ->
        handler.postDelayed({ shot(thunder, 0.5f + 0.5f * Random.nextFloat(), 0.85f + 0.25f * Random.nextFloat()) }, 400L + Random.nextLong(2000))
      SkylineEvent.METEOR_FALLING -> shot(whoosh, 0.8f, 1f)
      SkylineEvent.METEOR_IMPACT -> shot(impact, 1f, 1f)
    }
  }

  /** The app went to the background or came back. */
  fun foreground(visible: Boolean) {
    foreground = visible
    playOrPause()
  }

  override fun close() {
    handler.removeCallbacksAndMessages(null)
    for (loop in loops) loop.player.release()
    pool.release()
  }

  private fun shot(sound: Int, volume: Float, rate: Float) {
    pool.play(sound, volume, volume, 1, 0, rate)
  }

  private fun playOrPause() {
    val audible = !muted && foreground
    for (loop in loops) {
      if (audible && !loop.player.isPlaying) {
        loop.volume = 0f
        loop.player.setVolume(0f, 0f)
        loop.player.start()
      } else if (!audible && loop.player.isPlaying) {
        loop.player.pause()
      }
    }
    if (audible) fade()
  }

  private val step =
    object : Runnable {
      override fun run() {
        var moving = false
        for (loop in loops) {
          val gap = loop.target - loop.volume
          if (abs(gap) < 0.005f) {
            loop.volume = loop.target
          } else {
            loop.volume += gap.coerceIn(-0.035f, 0.035f) // about 1.5 s across the full range
            moving = true
          }
          loop.player.setVolume(loop.volume, loop.volume)
        }
        if (moving) handler.postDelayed(this, 50)
      }
    }

  private fun fade() {
    if (muted || !foreground) return
    handler.removeCallbacks(step)
    handler.post(step)
  }

  private fun loop(id: Int): MediaPlayer =
    MediaPlayer().apply {
      setAudioAttributes(attributes)
      context.resources.openRawResourceFd(id).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
      isLooping = true
      prepare()
    }
}
