package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import kotlin.math.floor
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {

  private val cities: List<City> by lazy {
    listOf("https://github.com/skylightlim/skyline-qr", "https://um.edu.my/", "https://example.com/", "https://a.co/", "https://news.ycombinator.com/")
      .flatMap { url -> CityPreset.entries.map { plan(url, it) } }
  }

  private fun plan(url: String, preset: CityPreset): City {
    val identity = CityIdentity.of(url)
    return CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, preset)
  }

  @Test
  fun aJunctionNeverLetsBothWaysGoAtOnce() {
    for (city in cities) {
      val signals = city.signals
      for (j in signals.offsets.indices) {
        var t = 0f
        while (t < 2 * Signals.CYCLE) {
          val along = signals.state(j, 0, t)
          val across = signals.state(j, 1, t)
          assertTrue("junction $j at $t: both ways may go", along == Signals.RED || across == Signals.RED)
          for (axis in 0..1) {
            val go = signals.nextGreen(j, axis, t)
            assertTrue(go >= t && go - t < Signals.CYCLE)
            assertEquals("junction $j axis $axis green at $go", Signals.GREEN, signals.state(j, axis, go + 1e-3f))
          }
          t += 0.05f
        }
      }
    }
  }

  @Test
  fun lightsStandAtRealJunctionsSpreadOutAndClearOfTheCorners() {
    var total = 0
    var edges = 0
    var crossings = 0
    for (city in cities) {
      val n = city.modules
      val signals = city.signals
      val cells = signals.cells
      total += signals.offsets.size
      var inside = 0
      for (j in signals.offsets.indices) {
        val col = cells[j * 2]
        val row = cells[j * 2 + 1]
        when {
          // Kuala Lumpur's crossing, on the alignment pattern in the bottom right corner zone.
          j == signals.crossing -> {
            crossings++
            inside++
            assertEquals(Corners.alignment(city.qr), row * n + col)
          }
          // Out on the ring road, along the bottom or the right edge.
          col == n || row == n -> {
            edges++
            assertTrue("edge junction ($col, $row)", col in 0 until n || row in 0 until n)
          }
          else -> {
            inside++
            assertTrue("junction ($col, $row) is a street", !city.qr.isDark(col, row))
            assertTrue("junction ($col, $row) is in a corner", !LandmarkPlan.inCorner(col, row, n))
          }
        }
        for (k in 0 until j) assertTrue(hypot((col - cells[k * 2]).toFloat(), (row - cells[k * 2 + 1]).toFloat()) >= 4f)
      }
      assertTrue(inside <= Signals.MAX_JUNCTIONS)
      for (h in 0 until signals.count) {
        val o = h * Signals.HEAD
        // The pole stands on one of its own junction's modules.
        val j = signals.heads[o + 4].toInt()
        val col = floor(signals.heads[o] + n / 2f).toInt()
        val row = floor(signals.heads[o + 1] + n / 2f).toInt()
        val own = (col == cells[j * 2] && row == cells[j * 2 + 1]) ||
          (0 until signals.areas.size / 3).any { signals.areas[it * 3] == col && signals.areas[it * 3 + 1] == row && signals.areas[it * 3 + 2] == j }
        assertTrue("a pole of junction $j stands on ($col, $row)", own)
      }
      // The same link always gets the same lights.
      val again = plan(city.identity.link.payload, city.preset).signals
      assertTrue(signals.heads.contentEquals(again.heads) && signals.offsets.contentEquals(again.offsets))
    }
    assertTrue("only $total junctions with lights in ${cities.size} cities", total >= cities.size)
    assertTrue("no lights on the ring road's near edges", edges > 0)
    assertTrue("no crossing on an alignment pattern", crossings > 0)
  }

  @Test
  fun carsStopAtRedAndNeverJumpOrReverse() {
    var waits = 0
    for (city in cities) {
      for (car in city.movers.filter { (it.kind == Mover.CAR || it.kind == Mover.BUS) && it.stops.isNotEmpty() }) {
        val length = car.route.length
        var last = car.distance(0f)
        var t = 0.05f
        var stillFor = 0
        while (t < 240f) {
          val s = car.distance(t)
          assertTrue("car went back at $t", s >= last - 1e-4f)
          assertTrue("car jumped at $t", s - last <= car.speed * 0.05f + 1e-3f)
          if (s - last < 1e-5f) stillFor++ else stillFor = 0
          if (stillFor == 10) waits++
          // Moving past a stop line, the light for it is never red. (Halting with its middle on the
          // line is not crossing it; a thousandth allows for float rounding hundreds of units along.)
          for (k in 0 until car.stops.size / 3) {
            val line = car.stops[k * 3]
            val lap = floor((s - line) / length)
            val crossed = line + lap * length
            if (crossed >= last && crossed < s - 1e-3f) {
              val state = car.signals.state(car.stops[k * 3 + 1].toInt(), car.stops[k * 3 + 2].toInt(), t)
              assertTrue("a car crossed its line on red at $t", state != Signals.RED)
            }
          }
          last = s
          t += 0.05f
        }
      }
    }
    assertTrue("no car ever waited at a light", waits > 0)
  }
}
