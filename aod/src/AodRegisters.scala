package riscq.aod

import spinal.core._
import spinal.lib._

/** Same payload shape as riscq.soc.link.Put, kept local so aod builds independently. */
case class AodWrite() extends Bundle {
  val address = UInt(16 bits)
  val data = Bits(32 bits)
}

/** One complete coefficient write. Profiles broadcast to both axes/all tones;
  * channel selects the axis only for gain calibration. Address is a row/entry,
  * not a byte address; beat selects a 32-bit slice of a profile row.
  */
case class AodCoefficientWrite() extends Bundle {
  val table = UInt(2 bits)
  val channel = Bool()
  val address = UInt(8 bits)
  val beat = UInt(2 bits)
  val data = Bits(32 bits)
}
object AodTable {
  val trajectory = 0
  val envelope = 1
  val gain = 2
}

case class AodControl(p: AodConfig) extends Bundle {
  val a_word, d_word, phase0 = Vec(UInt(32 bits), p.channels * p.tones)
  val cal_a, cal_b = Vec(UInt(16 bits), p.channels * p.tones)
  val tone_en = Bits(p.channels * p.tones bits)
  val amp_scale = Vec(UInt(16 bits), p.channels)
  val ratio = UInt(p.ratioWidth bits)
  val dec = UInt(p.decWidth bits)
  val cw, go, soft_rst, trig_en, trig_pol, snap, sticky_clr = Bool()
  val gcal_base = Vec(UInt(32 bits), p.channels)
  val gcal_zoom = Vec(UInt(2 bits), p.channels)
  val gcal_en = Bits(p.channels bits)
}

/** Native status levels plus a phase-snapshot write event (pa_we and payload).
  * There is no synchronous read request or shadow RAM in the engine.
  */
case class AodStatus(p: AodConfig) extends Bundle {
  val busy, clip, cfg_err = Bits(p.channels bits)
  val sweep_cnt = Vec(UInt(32 bits), p.channels)
  val trig_cnt, fclk_cnt = UInt(32 bits)
  val snap_busy, dec_clamped, go_dropped, pa_we, pa_ch = Bool()
  val pa_addr = UInt(6 bits)
  val pa_data = Bits(32 bits)
}

object AodRegisterMap {
  val fire = 0x00
  val startTime = 0x4100
  val control = 0x08
  val ratio = 0x0c
  val decimation = 0x10
  val clearSticky = 0x18
  def tone(channel: Int, tone: Int, field: Int): Int =
    0x8000 + channel * 0x4000 + field * 0x800 + tone * 4
  def channel(channel: Int): Int = 0xa800 + channel * 0x4000
}

/** Ordered, word-aligned posted writes: one beat per clock, no response/backpressure.
  * Unmapped/misaligned writes have no effect. Coefficients use a separate Stream.
  */
class AodRegisters(p: AodConfig) extends Component {
  val io = new Bundle {
    val clk, rst = in Bool()
    val cmd = slave(Flow(AodWrite()))
    val control = out(new AodControl(p))
    val settled = out Bool()
  }
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val c = new AodControl(p)
    c.flatten.foreach(_.setAsReg())
    io.control := c
    val wa = io.cmd.address
    val data = io.cmd.data
    val write = io.cmd.valid && wa(1 downto 0) === 0
    val tone = wa(7 downto 2)
    val region = wa(13 downto 11)
    val ch = if (p.channels == 2) wa(14) else False
    val chOk = if (p.channels == 2) True else !wa(14)
    val flatWidth = log2Up(p.channels * p.tones)
    val flat = if (p.channels == 1) tone.resize(flatWidth) else
      tone.resize(flatWidth) + Mux(ch, U(p.tones, flatWidth bits), U(0, flatWidth bits))

    // Conservative bound covers the ratio chain, change detection and control
    // distribution. Command strobes do not restart settling.
    val settling = Reg(UInt(log2Up(p.tones + 9) bits))
    when(io.rst) { settling := p.tones + 8 } otherwise {
      when(settling =/= 0) { settling := settling - 1 }
      when(c.soft_rst || (io.cmd.valid && wa =/= AodRegisterMap.control && wa =/= AodRegisterMap.clearSticky)) {
        settling := p.tones + 8
      }
    }
    io.settled := !io.rst && settling === 0
    c.go := False; c.snap := False; c.sticky_clr := False
    when(io.rst) {
      c.tone_en := 0; c.amp_scale.foreach(_ := 0)
      c.ratio := p.rateMin; c.dec := 1; c.cw := False; c.soft_rst := False
      c.trig_en := False; c.trig_pol := False
      for (axis <- 0 until p.channels) {
        c.gcal_base(axis) := 0; c.gcal_zoom(axis) := 2; c.gcal_en(axis) := True
      }
    } elsewhen(write) {
      when(wa(15) && chOk && region < 4 && wa(10 downto 8) === 0 && tone.resize(7) < p.tones) {
        switch(region(1 downto 0)) {
          is(0) { c.a_word(flat) := data.asUInt }
          is(1) { c.d_word(flat) := data.asUInt }
          is(2) { c.phase0(flat) := data.asUInt }
          is(3) { c.cal_a(flat) := data(15 downto 0).asUInt; c.cal_b(flat) := data(31 downto 16).asUInt }
        }
      }
      for (axis <- 0 until p.channels) {
        val base = AodRegisterMap.channel(axis)
        when(wa === base) { c.tone_en(axis * p.tones, scala.math.min(32, p.tones) bits) := data.resized }
        if (p.tones > 32) when(wa === base + 4) {
          c.tone_en(axis * p.tones + 32, p.tones - 32 bits) := data.resized
        }
        when(wa === base + 8) { c.amp_scale(axis) := data(15 downto 0).asUInt }
        when(wa === base + 20) { c.gcal_en(axis) := data(0); c.gcal_zoom(axis) := data(2 downto 1).asUInt }
        when(wa === base + 24) { c.gcal_base(axis) := data.asUInt }
      }
      switch(wa) {
        is(AodRegisterMap.control) {
          c.go := data(0); c.cw := data(1); c.soft_rst := data(2)
          c.trig_en := data(3); c.trig_pol := data(4); c.snap := data(5)
        }
        is(AodRegisterMap.ratio) { c.ratio := data.asUInt.resized }
        is(AodRegisterMap.decimation) { c.dec := data.asUInt.resized }
        is(AodRegisterMap.clearSticky) { c.sticky_clr := True }
      }
    }
  }
}
