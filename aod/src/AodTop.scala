package riscq.aod

import spinal.core._

/** Standalone native engine. The legacy AXI register map and packed {sin,cos}
  * converter words are preserved; clock/rate adaptation belongs upstream.
  */
class AodTop(p: AodConfig) extends Component {
  val io = new Bundle {
    val clk, rst, trig_in = in Bool()
    val axi = new AodAxiIo
    val dac_x, dac_y = out Bits(32 bits)
  }
  val regs = new AodRegisters(p)
  regs.io.clk := io.clk; regs.io.rst := io.rst
  io.axi <> regs.io.axi
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val c = regs.io.control
    val s = regs.io.status
    val dec_clamped = c.dec.resize(p.ratioWidth) > c.ratio
    val dec_eff = Mux(dec_clamped, c.ratio.resize(p.decWidth), c.dec)
    s.dec_clamped := dec_clamped
    val tsync0 = RegNext(io.trig_in)
    val tsync1 = RegNext(tsync0)
    val tsync2 = RegNext(tsync1)
    Seq(tsync0, tsync1, tsync2).foreach(_.addAttribute("ASYNC_REG", "TRUE"))
    val trig_edge = c.trig_en && (tsync1 ^ c.trig_pol) && !(tsync2 ^ c.trig_pol)
    val trig_cnt, fclk_cnt = Reg(UInt(32 bits))
    when(io.rst) { trig_cnt := 0; fclk_cnt := 0 } otherwise {
      when(trig_edge) { trig_cnt := trig_cnt + 1 }
      fclk_cnt := fclk_cnt + 1
    }
    s.trig_cnt := trig_cnt; s.fclk_cnt := fclk_cnt
    val pa_load, pa_sh = Reg(Bool())
    val bank_rst = io.rst || c.soft_rst
    val rounded = (0 until p.channels).map(_ => new AodRound(16, p.dacPhysical))
    val banks = (0 until p.channels).map { ch =>
      val bank = new AodBank(p)
      val bi = bank.io
      bi.clk := io.clk; bi.rst := bank_rst
      for (t <- 0 until p.tones) {
        val flat = ch * p.tones + t
        bi.d_word(t) := c.d_word(flat); bi.a_word(t) := c.a_word(flat); bi.phase0(t) := c.phase0(flat)
        bi.cal_a(t) := c.cal_a(flat); bi.cal_b(t) := c.cal_b(flat)
      }
      bi.tone_en := c.tone_en(ch * p.tones, p.tones bits)
      bi.amp_scale := c.amp_scale(ch); bi.ratio := c.ratio; bi.dec := dec_eff
      bi.rom_we := c.rom_we; bi.rom_sel := c.rom_sel; bi.rom_addr := c.rom_addr
      bi.rom_beat := c.rom_beat; bi.rom_wdata := c.rom_wdata
      bi.gcal_sel := c.rom_sel === 7 && c.rom_chan === Bool(ch == 1)
      bi.gcal_addr := (c.rom_a8.asBits ## c.rom_addr.asBits).asUInt
      bi.gcal_base := c.gcal_base(ch); bi.gcal_zoom := c.gcal_zoom(ch); bi.gcal_en := c.gcal_en(ch)
      bi.go := c.go || trig_edge; bi.cw := c.cw; bi.pa_load := pa_load; bi.pa_sh := pa_sh
      val ri = rounded(ch).io
      ri.clk := io.clk; ri.rst := bank_rst; ri.din_i := bi.dac; ri.din_q := bi.dac_q
      val cs = Reg(Bool())
      when(io.rst || c.sticky_clr) { cs := False } elsewhen(bi.clip_live || ri.sat) { cs := True }
      val sw = Reg(UInt(32 bits))
      when(io.rst) { sw := 0 } elsewhen(bi.sweep_done) { sw := sw + 1 }
      s.busy(ch) := bi.busy; s.clip(ch) := cs; s.cfg_err(ch) := bi.cfg_err; s.sweep_cnt(ch) := sw
      bank
    }
    val gd_sticky = Reg(Bool())
    when(io.rst || c.sticky_clr) { gd_sticky := False } elsewhen(banks.map(_.io.go_drop).reduce(_ || _)) { gd_sticky := True }
    s.go_dropped := gd_sticky
    val sn_st = Reg(UInt(2 bits))
    val sn_ch = Reg(Bool())
    val sn_sh, sn_wr = Reg(UInt(7 bits))
    val pa_we, pa_ch = Reg(Bool())
    val pa_addr = Reg(UInt(6 bits))
    val pa_data = Reg(Bits(32 bits))
    s.pa_we := pa_we; s.pa_ch := pa_ch; s.pa_addr := pa_addr; s.pa_data := pa_data
    s.snap_busy := sn_st =/= 0
    val selected = if (p.channels == 1) banks.head.io.pa_dout else Mux(sn_ch, banks(1).io.pa_dout, banks.head.io.pa_dout)
    val dv = if (p.channels == 1) banks.head.io.pa_dv else Mux(sn_ch, banks(1).io.pa_dv, banks.head.io.pa_dv)
    pa_load := False; pa_sh := False; pa_we := False
    when(io.rst) {
      sn_st := 0; sn_ch := False; sn_sh := 0; sn_wr := 0
      pa_ch := False; pa_addr := 0; pa_data := 0
    } otherwise {
      switch(sn_st) {
        is(0) { when(banks.head.io.sweep_done || c.snap) { sn_ch := False; sn_st := 1 } }
        is(1) { pa_load := True; sn_sh := 0; sn_wr := 0; sn_st := 2 }
        default {
          when(sn_sh < p.tones - 1) { pa_sh := True; sn_sh := sn_sh + 1 }
          when(dv) {
            pa_we := True; pa_ch := sn_ch; pa_addr := U(p.tones - 1, 6 bits) - sn_wr.resize(6)
            pa_data := selected.asBits; sn_wr := sn_wr + 1
            when(sn_wr === p.tones - 1) {
              if (p.channels > 1) {
                when(!sn_ch) { sn_ch := True; sn_st := 1 } otherwise { sn_st := 0 }
              } else { sn_st := 0 }
            }
          }
        }
      }
    }
    io.dac_x := rounded.head.io.dout_i.asBits ## rounded.head.io.dout_q.asBits
    io.dac_y := (if (p.channels > 1) rounded(1).io.dout_i.asBits ## rounded(1).io.dout_q.asBits else B(0, 32 bits))
  }
}
