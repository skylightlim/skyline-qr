package com.skylightlim.skylineqr.city

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class FestivalsTest {

  private val kl = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

  private fun at(month: Int, day: Int, hour: Int, minute: Int) =
    Festivals.at(Calendar.getInstance(kl).apply { clear(); set(2026, month, day, hour, minute) }.timeInMillis, kl)

  @Test
  fun kualaLumpursBigNights() {
    assertEquals(Festivals.NONE, at(Calendar.AUGUST, 30, 19, 59))
    assertEquals(Festivals.SHOW, at(Calendar.AUGUST, 30, 20, 0))
    assertEquals(Festivals.SHOW, at(Calendar.AUGUST, 30, 23, 59))
    assertEquals(Festivals.FINALE, at(Calendar.AUGUST, 31, 0, 0))
    assertEquals(Festivals.SHOW, at(Calendar.AUGUST, 31, 0, 15))
    assertEquals(Festivals.NONE, at(Calendar.AUGUST, 31, 0, 30))
    assertEquals(Festivals.NONE, at(Calendar.AUGUST, 31, 20, 0))
    assertEquals(Festivals.SHOW, at(Calendar.DECEMBER, 31, 22, 0))
    assertEquals(Festivals.FINALE, at(Calendar.JANUARY, 1, 0, 5))
    assertEquals(Festivals.SHOW, at(Calendar.FEBRUARY, 1, 21, 0))
    assertEquals(Festivals.NONE, at(Calendar.FEBRUARY, 2, 0, 5))
    assertEquals(Festivals.NONE, at(Calendar.NOVEMBER, 9, 21, 0))
  }
}
