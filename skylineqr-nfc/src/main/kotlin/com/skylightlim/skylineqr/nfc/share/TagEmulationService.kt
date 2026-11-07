package com.skylightlim.skylineqr.nfc.share

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.skylightlim.skylineqr.nfc.tag.Type4TagEmulator

/**
 * The phone's emulated NFC tag. Android routes a reader's commands for the NDEF tag application
 * here; the answers come from [Type4TagEmulator] with whatever [ShareSession] is sharing.
 */
class TagEmulationService : HostApduService() {

  private val tag = Type4TagEmulator(ShareSession::currentMessage).apply {
    onMessageRead = ShareSession::onMessageRead
  }

  override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
    tag.process(commandApdu ?: ByteArray(0))

  override fun onDeactivated(reason: Int) = tag.deactivate()
}
