package com.skylightlim.skylineqr.city

/**
 * Four building materials that look right together, and the paint on the traffic. Colours are
 * display sRGB, 0..1. The light of the hour and the weather fall on them; they never change them.
 */
class MaterialRecipe(
  val name: String,
  /** Four families as rgb triples: the traffic's undercoat, and a generic city's districts. */
  val families: FloatArray,
  val vehicle: FloatArray,
  /** Thirty-two landmark colours as rgb triples, for a preset that has landmarks. */
  val landmarks: FloatArray = FloatArray(96),
  /**
   * The districts' colours as rgb triples, at most eight: each district's towers are built of its
   * colour, and its modules in the flat code inked in it (see [FlatColours]).
   */
  val districts: FloatArray = families,
)

/**
 * The material recipes a generic city can be built from. The site seed picks one, so a site's
 * colours are part of its identity: example.com is always the same city, in the same stone.
 */
object Materials {

  val recipes: List<MaterialRecipe> =
    listOf(
      recipe("Harbour glass", 0x4C7FB8, 0x3E8E8C, 0xE3DED3, 0x5B6470, vehicle = 0xE0533A),
      recipe("Old town", 0xB5543A, 0xC9A877, 0xE3DED3, 0x5E9C86, vehicle = 0x2F5E9E),
      recipe("Desert", 0xC9A877, 0xC79A3E, 0xB5543A, 0xA7A39A, vehicle = 0x2A8C8C),
      recipe("Nordic", 0x5B6470, 0xE3DED3, 0x2E3A4E, 0x8CC7A1, vehicle = 0xE8B23A),
      recipe("Downtown", 0x2E3A4E, 0x4C7FB8, 0xA7A39A, 0xC79A3E, vehicle = 0xD9473A),
      recipe("Garden city", 0x8CC7A1, 0x5E9C86, 0xE3DED3, 0xC9A877, vehicle = 0xC2453A),
      recipe("Sunset", 0xD9735B, 0xC9A877, 0x4C7FB8, 0xE3DED3, vehicle = 0x2E6E9E),
      recipe("Industrial", 0xA7A39A, 0x5B6470, 0xB5543A, 0x2E3A4E, vehicle = 0xE8B23A),
    )

  /** The recipe a seed builds with. Integer arithmetic only, so it is the same on every device. */
  fun pick(seed: Int): MaterialRecipe {
    var h = seed xor 0x5bd1e995
    h = (h xor (h ushr 15)) * 0x2c1b3c6d
    h = h xor (h ushr 12)
    return recipes[(h and 0x7fffffff) % recipes.size]
  }

  private fun recipe(name: String, a: Int, b: Int, c: Int, d: Int, vehicle: Int) =
    MaterialRecipe(name, floatArrayOf(*rgb(a), *rgb(b), *rgb(c), *rgb(d)), rgb(vehicle))

  internal fun rgb(hex: Int) =
    floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f)
}
