package riscq.soc.fabric

import spinal.core._

/** Connect negotiated Get/Put fabric ports to fixed-parameter component IO.
  * The destination must accommodate every upstream address, source and size.
  * No buffering or clock crossing: callers place this in one clock domain.
  */
object TilelinkPortBridge {
  def bridgeLoad(dn: spinal.lib.bus.tilelink.Bus, up: spinal.lib.bus.tilelink.Bus): Unit = {
    require(dn.p.dataWidth == up.p.dataWidth)
    require(dn.p.addressWidth >= up.p.addressWidth)
    require(dn.p.sourceWidth >= up.p.sourceWidth)
    require(dn.p.sizeWidth >= up.p.sizeWidth)
    // A channel: master `up` → slave `dn`
    dn.a.valid   := up.a.valid
    up.a.ready   := dn.a.ready
    dn.a.opcode  := up.a.opcode
    dn.a.param   := up.a.param
    dn.a.source  := up.a.source.resized
    dn.a.address := up.a.address.resized
    dn.a.size    := up.a.size.resized
    dn.a.mask    := up.a.mask
    dn.a.data    := up.a.data
    dn.a.corrupt := up.a.corrupt
    dn.a.debugId := up.a.debugId
    // D channel: slave `dn` → master `up`
    up.d.valid   := dn.d.valid
    dn.d.ready   := up.d.ready
    up.d.opcode  := dn.d.opcode
    up.d.param   := dn.d.param
    up.d.source  := dn.d.source.resized
    up.d.size    := dn.d.size.resized
    up.d.denied  := dn.d.denied
    up.d.data    := dn.d.data
    up.d.corrupt := dn.d.corrupt
    if (up.p.sinkWidth > 0) up.d.sink := dn.d.sink.resized
  }
}
