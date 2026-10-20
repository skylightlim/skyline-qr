package com.skylightlim.skylineqr.core

/**
 * A generator version is a drawing recipe, not a library version: the same link and the same
 * generator version always build the same city, so a saved or shared city records the version it
 * was made with. Changing how cities look adds a new version; it never edits an old one, and an
 * unknown version fails instead of quietly drawing with the newest recipe.
 *
 * Version 1 is still a draft. Its golden fingerprints may change until the first public release
 * and are frozen from then on. (The versioning scheme follows every-qrcode; see NOTICE.)
 */
object GeneratorVersion {

  const val CURRENT = 1

  val SUPPORTED: Set<Int> = setOf(1)

  fun resolve(version: Int): Int {
    if (version !in SUPPORTED) throw UnsupportedVersionException("generator", version)
    return version
  }
}
