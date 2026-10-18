package riscq.aod

import spinal.core._

class AodGcalIo(entries: Int, halves: Int) extends Bundle {
  val clk, half, cfg_en, tab_we = in Bool()
  val cfg_base, inc = in UInt(32 bits)
  val cfg_zoom = in UInt(2 bits)
  val tab_addr = in UInt(log2Up(entries * halves) bits)
  val tab_wdata = in Bits(32 bits)
  val cal_now = in UInt(16 bits)
  val cal_eff = out UInt(16 bits)
}

/** Frequency-indexed, runtime-writable gain table with fixed pipeline latency. */
class AodGcal(entries: Int = 256, halves: Int = 1) extends Component {
  require(entries >= 16 && entries <= 256 && isPow2(entries))
  require(halves == 1 || halves == 2)
  val io = new AodGcalIo(entries, halves)
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val iw = log2Up(entries)
    val w = RegNext(io.inc - io.cfg_base)
    val index = UInt(iw bits)
    val frac = UInt(8 bits)
    val overflow = Bool()
    index := 0; frac := 0; overflow := False
    switch(io.cfg_zoom) {
      for (zoom <- 0 until 4) is(zoom) {
        val high = 30 - zoom
        index := w(high downto high - iw + 1)
        frac := w(high - iw downto high - iw - 7)
        overflow := (if (zoom == 0) False else w(30 downto high + 1).orR)
      }
    }
    val idx_q = RegNext(Mux(w.msb, U(0, iw bits), Mux(overflow, U(entries - 1, iw bits), index)))
    val frac_q = RegNext(Mux(w.msb || overflow, U(0, 8 bits), frac))
    val hf_q = RegNext(io.half)
    val tab = Mem(Bits(32 bits), entries * halves)
    tab.addAttribute("rom_style", "distributed")
    tab.initBigInt(Seq.fill(entries * halves)(BigInt(32768)))
    tab.write(io.tab_addr, io.tab_wdata, io.tab_we)
    val address = if (halves == 2) (hf_q.asBits ## idx_q.asBits).asUInt else idx_q
    val ent_q = RegNext(tab.readAsync(address))
    val frac_q2 = RegNext(frac_q)
    val gm = RegNext(ent_q(31 downto 16).asSInt * frac_q2.resize(9).asSInt)
    val gm2 = RegNext(gm)
    val val_d = RegNext(ent_q(15 downto 0).asUInt)
    val val_d2 = RegNext(val_d)
    val sum = val_d2.resize(18).asSInt + gm2(24 downto 8).resize(18)
    val g = Reg(UInt(16 bits))
    g := Mux(sum < 0, U(0, 16 bits), Mux(sum > 65535, U(65535, 16 bits), sum.asUInt.resize(16)))
    val ce_cal = RegNext(io.cal_now)
    val ce_g = RegNext(Mux(io.cfg_en, g, U(32768, 16 bits)))
    val ce_m = RegNext(ce_cal * ce_g)
    val result = ce_m(31 downto 15)
    io.cal_eff := RegNext(Mux(result.msb, U(65535, 16 bits), result.resize(16)))
  }
}

class AodRound(bits: Int = 16, physical: Int = 14) extends Component {
  require(physical >= 2 && physical <= bits)
  val io = new Bundle {
    val clk, rst = in Bool()
    val din_i, din_q = in SInt(bits bits)
    val dout_i, dout_q = out SInt(bits bits)
    val sat = out Bool()
  }
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val shift = bits - physical
    val oi = AodHardware.round(io.din_i, physical)
    val oq = AodHardware.round(io.din_q, physical)
    val limit = (BigInt(1) << (bits - 1)) - (if (shift == 0) 1 else BigInt(1) << (shift - 1))
    val i, q = Reg(SInt(bits bits))
    val sat = Reg(Bool())
    i := (oi.asBits ## B(0, shift bits)).asSInt
    q := (oq.asBits ## B(0, shift bits)).asSInt
    sat := (if (shift == 0) False else io.din_i >= limit || io.din_q >= limit)
    when(io.rst) { i := 0; q := 0; sat := False }
    io.dout_i := i; io.dout_q := q; io.sat := sat
  }
}
