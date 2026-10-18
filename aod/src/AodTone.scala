package riscq.aod

import spinal.core._

class AodToneIo(p: AodConfig) extends Bundle {
  val clk, rst, go, cw, en, rom_we, gcal_we, gcal_en = in Bool()
  val d_word = in SInt(32 bits)
  val a_word, phase0, gcal_base = in UInt(32 bits)
  val ratio = in UInt(p.ratioWidth bits)
  val dec = in UInt(p.decWidth bits)
  val cal_a, cal_b, amp_scale = in UInt(16 bits)
  val rom_sel = in UInt(3 bits)
  val rom_addr = in UInt(7 bits)
  val rom_beat, gcal_zoom = in UInt(2 bits)
  val rom_wdata = in Bits(32 bits)
  val gcal_addr = in UInt(8 bits)
  val busy, done, dac_valid, go_drop = out Bool()
  val phi_acc = out UInt(32 bits)
  val dac, dac_q = out SInt(16 bits)
}

/** Trajectory, envelope and phase accumulator with the reference's cycle timing. */
class AodTone(p: AodConfig) extends Component {
  val io = new AodToneIo(p)
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val segw = log2Up(p.segments) + 1
    val stepw = log2Up(p.moveSteps) + 1
    val lenw = log2Up(p.segmentLength) + 1
    val st = Reg(UInt(2 bits))
    val pcnt = Reg(UInt(5 bits))
    val rat_lat, rdiv = Reg(UInt(p.ratioWidth bits))
    val epos = Reg(UInt(lenw bits))
    val segno = Reg(UInt(segw bits))
    val stepno, stop_at = Reg(UInt(stepw bits))
    val dleft, dec_lat = Reg(UInt(p.decWidth bits))
    val dbusy = Reg(Bool())
    dleft.addAttribute("max_fanout", 16); dbusy.addAttribute("max_fanout", 16)
    val run = st === 2
    val preload = st === 1 && pcnt === 1
    val prime_em0 = st === 1 && pcnt === 2
    val prime_lat = st === 1 && pcnt === 19
    val prime_em1 = st === 1 && pcnt === 20
    val step_adv = run && rdiv === 0
    val emit = prime_em0 || prime_em1 || step_adv
    val adopt = prime_lat || step_adv
    val adv = emit || dbusy
    val seg_last = epos === p.segmentLength - 1
    val mv_last = stepno >= stop_at
    val mv_end = run && step_adv && mv_last
    val go_pend, cw_gap = Reg(Bool())
    val launch = st === 0 && (io.go || go_pend)
    when(io.rst) { cw_gap := False } elsewhen(mv_end && io.cw) { cw_gap := True } elsewhen(run) { cw_gap := False }
    val osc = run || cw_gap
    val done = Reg(Bool())
    done := False
    when(io.rst) {
      st := 0; pcnt := 0; rdiv := 0; epos := 0; segno := 0; stepno := 0
      dleft := 0; dbusy := False; dec_lat := 1; stop_at := p.moveSteps - 1
    } otherwise {
      switch(st) {
        is(0) {
          when(launch) {
            st := 1; pcnt := 0; epos := 0; segno := 0; stepno := 0
            dleft := 0; dbusy := False
            val effectiveDec = Mux(io.dec === 0, U(1, p.decWidth bits), io.dec)
            dec_lat := effectiveDec
            stop_at := U(p.moveSteps, stepw bits) - effectiveDec.resize(stepw)
            val rate = Mux(io.ratio < p.rateMin, U(p.rateMin, p.ratioWidth bits), io.ratio) - 1
            rdiv := rate; rat_lat := rate
          }
        }
        is(1) { pcnt := pcnt + 1; when(pcnt === 21) { st := 2 } }
        default {
          when(step_adv) {
            rdiv := rat_lat; dleft := dec_lat - 1; dbusy := dec_lat > 1
          } elsewhen(dbusy) { dleft := dleft - 1; dbusy := dleft > 1 }
          when(adv && !mv_end) { stepno := stepno + 1 }
          when(mv_end) {
            done := True
            when(io.cw) {
              st := 1; pcnt := 0; epos := 0; segno := 0; stepno := 0
              dleft := 0; dbusy := False
            } otherwise { st := 0 }
          }
          when(!step_adv) { rdiv := rdiv - 1 }
        }
      }
      // This priority also preserves the segment counter's idle-state behavior.
      when(adv && !mv_end) {
        when(seg_last) { epos := 0; segno := segno + 1 } otherwise { epos := epos + 1 }
      }
    }
    when(io.rst) { done := False }
    io.done := done
    io.busy := RegNext(osc)

    // Split wide rows into beat-addressed banks for independent slice writes.
    val uWords = AodHardware.table(p.unitFile, 96, p.segments)
    val sWords = AodHardware.table(p.seedFile, 72, p.segments)
    def profile(words: Seq[BigInt], width: Int, selector: Int): (Seq[Mem[Bits]], Bits) = {
      val banks = (0 until 3).map { beat =>
        val bw = math.min(32, width - beat * 32)
        val mem = Mem(Bits(bw bits), p.segments)
        mem.addAttribute("rom_style", "distributed")
        mem.initBigInt(words.map(w => (w >> (beat * 32)) & ((BigInt(1) << bw) - 1)))
        val beatMatch = if (beat == 2) io.rom_beat >= 2 else io.rom_beat === beat
        mem.write(io.rom_addr.resize(segw - 1), io.rom_wdata.resize(bw),
          io.rom_we && io.rom_sel === selector && beatMatch)
        mem
      }
      val segnx = Mux(!run, U(0, segw bits), Mux(segno === p.segments - 1, segno, segno + 1))
      val row = banks.reverse.map(m => m.readAsync(segnx.resize(segw - 1))).reduce(_ ## _)
      (banks, RegNext(row))
    }
    val (urom, urow) = profile(uWords, 96, 5)
    val (srom, srow) = profile(sWords, 72, 6)
    val d = Vec.fill(3)(Reg(UInt(32 bits)))
    val u_cur = Reg(UInt(32 bits))
    when(preload) {
      for (j <- 0 until 3) d(j) := urow(j * 32, 32 bits).asUInt
    } otherwise {
      when(emit) { u_cur := d(0) }
      when(adv) {
        when(seg_last) {
          for (j <- 0 until 3) d(j) := urow(j * 32, 32 bits).asUInt
        } otherwise {
          for (j <- 0 until 2) d(j) := d(j) + d(j + 1)
        }
      }
    }
    val d_q = RegNext(io.d_word)
    val d_q2 = RegNext(d_q)
    val u_q = RegNext(u_cur(30 downto 0))
    val u_q2 = RegNext(u_q)
    // The SV product is 63 bits, including deliberate high-bit truncation.
    val prod = RegNext((d_q2 * u_q2.resize(32).asSInt).resize(63))
    val prod_q = RegNext(prod)
    val prod_q2 = RegNext(prod_q)
    val inc = RegNext(io.a_word + prod_q2(61 downto 30).asUInt)
    val inc_live = Reg(UInt(32 bits))
    when(adopt) { inc_live := inc }
    val phi = Reg(UInt(32 bits))
    when(launch) { phi := io.phase0 } elsewhen(osc) { phi := phi + inc_live }
    when(io.rst) { go_pend := False } otherwise {
      when(io.go && st =/= 0) { go_pend := True }
      when(launch || (mv_end && io.cw)) { go_pend := False }
    }
    val go_drop = Reg(Bool())
    go_drop := io.go && st =/= 0 && go_pend
    when(io.rst) { go_drop := False }
    io.go_drop := go_drop
    val phi_prev, phi_acc = Reg(UInt(32 bits))
    val mv_end_d = RegNext(mv_end)
    when(launch) { phi_prev := io.phase0; phi_acc := 0 } elsewhen(mv_end_d) { phi_acc := phi - phi_prev; phi_prev := phi }
    io.phi_acc := phi_acc

    val aacc, astep, seed_cur = Reg(UInt(36 bits))
    val seed_live = Reg(UInt(20 bits))
    when(preload) { aacc := srow(35 downto 0).asUInt; astep := srow(71 downto 36).asUInt } elsewhen(emit) {
        seed_cur := aacc
        when(seg_last) { aacc := srow(35 downto 0).asUInt; astep := srow(71 downto 36).asUInt } otherwise { aacc := aacc + astep }
      }
    val seg_cal_nx = RegNext(segno + 1)
    val cdiff_q = RegNext(io.cal_b.resize(17).asSInt - io.cal_a.resize(17).asSInt)
    val cdiff_m = RegNext(cdiff_q * io.amp_scale.resize(17).asSInt)
    val cdiff_s = RegNext(cdiff_m(31 downto 15))
    val cal_a_q = RegNext(io.cal_a)
    val cal_a_m = RegNext(cal_a_q * io.amp_scale)
    val cal_a_s = RegNext(cal_a_m(30 downto 15))
    val cprod_nx = RegNext((cdiff_s * seg_cal_nx.resize(segw + 1).asSInt).resize(17 + segw))
    val cal_nx = RegNext(cal_a_s + cprod_nx(log2Up(p.segments), 16 bits).asUInt)
    val cal_now = Reg(UInt(16 bits))
    when(preload) { cal_now := cal_a_s } elsewhen(emit && seg_last) { cal_now := cal_nx }
    val gcal = new AodGcal(p.gcalEntries)
    gcal.io.clk := io.clk; gcal.io.half := False
    gcal.io.cfg_base := io.gcal_base; gcal.io.cfg_zoom := io.gcal_zoom; gcal.io.cfg_en := io.gcal_en
    gcal.io.tab_we := io.gcal_we; gcal.io.tab_addr := io.gcal_addr.resized
    gcal.io.tab_wdata := io.rom_wdata; gcal.io.inc := inc; gcal.io.cal_now := cal_now
    val seed_mul = RegNext(seed_cur(35 downto 16) * gcal.io.cal_eff)
    when(adopt) { seed_live := Mux(io.en, seed_mul(34 downto 15), U(0, 20 bits)) } elsewhen(st === 0) { seed_live := 0 }
    val cordic = new AodCordic
    cordic.io.clk := io.clk; cordic.io.xin := seed_live.asSInt; cordic.io.yin := 0
    cordic.io.phasein := phi(31 downto 9); cordic.io.gin := osc.asBits
    io.dac := AodHardware.round(cordic.io.yout, 16)
    io.dac_q := AodHardware.round(cordic.io.xout, 16)
    io.dac_valid := cordic.io.gout(0)
  }
}
