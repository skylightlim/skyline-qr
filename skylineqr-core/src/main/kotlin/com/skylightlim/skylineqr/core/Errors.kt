package com.skylightlim.skylineqr.core

/** Why a link cannot become a city. [code] is stable for programs; the message is for people. */
sealed class SkylineQrException(val code: String, message: String) : IllegalArgumentException(message)

class InvalidLinkException(message: String) : SkylineQrException("invalid-link", message)

/** The link needs a bigger QR than survives being drawn as a city and scanned off a screen. */
class LinkTooLongException :
  SkylineQrException("link-too-long", "That link is too long for a scannable city. Try a shorter one.")

class UnsupportedVersionException(what: String, val version: Int) :
  SkylineQrException("unsupported-version", "Unsupported $what version: $version.")
