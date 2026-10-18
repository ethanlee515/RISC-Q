package riscq.aod

import spinal.core._

class AodBank(p: AodConfig) extends Component {
  val io = new Bundle {
    val clk, rst = in Bool()
    val d_word, a_word, phase0 = in Vec(UInt(32 bits), p.tones)
    val cal_a, cal_b = in Vec(UInt(16 bits), p.tones)
    val tone_en = in Bits(p.tones bits)
    val amp_scale = in UInt(16 bits)
    val ratio = in UInt(p.ratioWidth bits)
    val dec = in UInt(p.decWidth bits)
    val rom_we, gcal_sel, gcal_en, go, cw, pa_load, pa_sh = in Bool()
    val rom_sel = in UInt(3 bits)
    val rom_addr = in UInt(7 bits)
    val rom_beat, gcal_zoom = in UInt(2 bits)
    val rom_wdata = in Bits(32 bits)
    val gcal_addr = in UInt(8 bits)
    val gcal_base = in UInt(32 bits)
    val pa_dout = out UInt(32 bits)
    val pa_dv, busy, done, sweep_done, go_drop, dac_valid, clip, clip_live, cfg_err = out Bool()
    val dac, dac_q = out SInt(16 bits)
  }
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val rs_c = Reg(UInt(3 bits)); val ra_c = Reg(UInt(7 bits))
    val rb_c = Reg(UInt(2 bits)); val rd_c = Reg(Bits(32 bits))
    val ga_c = Reg(UInt(8 bits)); val gs_c = Reg(Bool())
    when(io.rom_we) {
      rs_c := io.rom_sel; ra_c := io.rom_addr; rb_c := io.rom_beat
      rd_c := io.rom_wdata; ga_c := io.gcal_addr; gs_c := io.gcal_sel
    }
    val we_cnt = Reg(UInt(3 bits))
    when(io.rst) { we_cnt := 0 } elsewhen(io.rom_we) { we_cnt := 4 } elsewhen(we_cnt =/= 0) { we_cnt := we_cnt - 1 }
    val we_d = RegNext(!io.rst && we_cnt =/= 0)
    val rw_q = RegNext(we_d); val rs_q = RegNext(rs_c); val ra_q = RegNext(ra_c)
    val rb_q = RegNext(rb_c); val rd_q = RegNext(rd_c)
    val gw_q = RegNext(we_d && gs_c); val ga_q = RegNext(ga_c)
    val gb_q = RegNext(io.gcal_base); val gz_q = RegNext(io.gcal_zoom); val ge_q = RegNext(io.gcal_en)
    val dec_q = RegNext(io.dec)
    Seq(rw_q, rs_q, ra_q, rb_q, rd_q, gw_q, ga_q, gb_q, gz_q, ge_q, dec_q)
      .foreach(_.addAttribute("max_fanout", 16))
    val rchain = Vec.fill(p.tones)(Reg(UInt(p.ratioWidth bits)))
    rchain(0) := io.ratio
    for (i <- 1 until p.tones) rchain(i) := rchain(i - 1)
    val rat_prev = RegNext(io.ratio)
    val stable = Reg(UInt(log2Up(p.tones) + 2 bits))
    val ratio_ready = stable >= p.tones
    when(io.rst || io.ratio =/= rat_prev) { stable := 0 } elsewhen(!ratio_ready) { stable := stable + 1 }
    val ctl_r = RegNext(io.pa_sh.asBits ## io.pa_load.asBits ## io.cw.asBits ## (io.go && ratio_ready).asBits)
    val rst_r = RegNext(io.rst)
    val branches = (p.tones + p.fanout - 1) / p.fanout
    val mids = (branches + 3) / 4
    val ctl_m = Vec.fill(mids)(RegNext(ctl_r))
    val rst_m = Vec.fill(mids)(RegNext(rst_r))
    val ctl_b = Vec((0 until branches).map(i => RegNext(ctl_m(i / 4))))
    val rst_b = Vec((0 until branches).map(i => RegNext(rst_m(i / 4))))
    (ctl_m.toSeq ++ ctl_b.toSeq).foreach(_.addAttribute("dont_touch", "true"))
    (rst_m.toSeq ++ rst_b.toSeq).foreach(_.addAttribute("max_fanout", 64))
    val cfg_err = Reg(Bool())
    when(io.rst) { cfg_err := False } elsewhen(io.go && !ratio_ready) { cfg_err := True }
    io.cfg_err := cfg_err
    val tones = (0 until p.tones).map { t =>
      val tone = new AodTone(p)
      val ti = tone.io
      val br = t / p.fanout
      ti.clk := io.clk; ti.rst := rst_b(br)
      ti.d_word := io.d_word(t).asSInt; ti.a_word := io.a_word(t); ti.phase0 := io.phase0(t)
      ti.cal_a := io.cal_a(t); ti.cal_b := io.cal_b(t); ti.en := io.tone_en(t)
      ti.amp_scale := io.amp_scale; ti.ratio := rchain(t); ti.dec := dec_q
      ti.go := ctl_b(br)(0); ti.cw := ctl_b(br)(1)
      ti.rom_we := rw_q; ti.rom_sel := rs_q; ti.rom_addr := ra_q; ti.rom_beat := rb_q; ti.rom_wdata := rd_q
      ti.gcal_we := gw_q; ti.gcal_addr := ga_q; ti.gcal_base := gb_q; ti.gcal_zoom := gz_q; ti.gcal_en := ge_q
      tone
    }
    val pa = Vec.fill(p.tones)(Reg(UInt(32 bits)))
    for (q <- 0 until p.tones) {
      val br = q / p.fanout
      when(ctl_b(br)(2)) { pa(q) := tones(q).io.phi_acc } elsewhen(ctl_b(br)(3)) { pa(q) := (if (q == 0) U(0, 32 bits) else pa(q - 1)) }
    }
    io.pa_dout := pa.last
    val pa_dv, go_drop = Reg(Bool())
    pa_dv := ctl_b(0)(2) || ctl_b(0)(3); go_drop := tones.head.io.go_drop
    when(io.rst) { pa_dv := False; go_drop := False }
    io.pa_dv := pa_dv; io.go_drop := go_drop

    val levels = log2Up(p.tones)
    val pow = 1 << levels
    val sw = 16 + levels
    val sum, sumq = Vec.fill(2 * pow)(Reg(SInt(sw bits)))
    for (n <- 0 until pow) {
      sum(pow + n) := (if (n < p.tones) tones(n).io.dac.resize(sw) else S(0, sw bits))
      sumq(pow + n) := (if (n < p.tones) tones(n).io.dac_q.resize(sw) else S(0, sw bits))
    }
    for (n <- 1 until pow) {
      sum(n) := sum(2 * n) + sum(2 * n + 1)
      sumq(n) := sumq(2 * n) + sumq(2 * n + 1)
    }
    def pipe(value: Bool): Bool = {
      val regs = Vec.fill(levels + 1)(Reg(Bool()))
      regs(0) := value
      for (i <- 1 until regs.size) regs(i) := regs(i - 1)
      regs.last
    }
    val dv_p = pipe(tones.head.io.dac_valid)
    val busy_p = pipe(tones.head.io.busy)
    val done_p = pipe(tones.head.io.done)
    val busy, busy_q, done, sweep_done, dac_valid, clip, clip_live = Reg(Bool())
    val dac, dac_q = Reg(SInt(16 bits))
    val sat_i = sum(1) > 32767 || sum(1) < -32768
    val sat_q = sumq(1) > 32767 || sumq(1) < -32768
    dac_valid := dv_p; busy := busy_p; busy_q := busy
    done := busy_q && !busy; sweep_done := done_p; clip_live := sat_i || sat_q
    def clamp(s: SInt): SInt = Mux(s > 32767, S(32767, 16 bits),
      Mux(s < -32768, S(-32768, 16 bits), s.resize(16)))
    dac := clamp(sum(1)); dac_q := clamp(sumq(1))
    when(sat_i || sat_q) { clip := True }
    when(io.rst) {
      dac := 0; dac_q := 0; dac_valid := False; busy := False; busy_q := False
      done := False; sweep_done := False; clip := False; clip_live := False
    }
    io.dac := dac; io.dac_q := dac_q; io.dac_valid := dac_valid
    io.busy := busy; io.done := done; io.sweep_done := sweep_done
    io.clip := clip; io.clip_live := clip_live
  }
}
