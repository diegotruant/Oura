package it.anello.app.ble

import kotlinx.coroutines.flow.MutableStateFlow

/** Segnala alle schermate che sono arrivate nuove notti dall'anello. */
object RingDataBus {
    val updated = MutableStateFlow(0L)
}
