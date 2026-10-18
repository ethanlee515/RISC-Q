package riscq.aod

import spinal.core._

/** Legacy standalone AXI-Lite pins. Address and data must overlap, as in the
  * reference; byte strobes/protection are intentionally ignored by its ABI.
  */
class AodAxiIo extends Bundle {
  val s_axi_awaddr, s_axi_araddr = in UInt(16 bits)
  val s_axi_awprot, s_axi_arprot = in Bits(3 bits)
  val s_axi_awvalid, s_axi_wvalid, s_axi_bready, s_axi_arvalid, s_axi_rready = in Bool()
  val s_axi_wdata = in Bits(32 bits)
  val s_axi_wstrb = in Bits(4 bits)
  val s_axi_awready, s_axi_wready, s_axi_bvalid, s_axi_arready, s_axi_rvalid = out Bool()
  val s_axi_bresp, s_axi_rresp = out Bits(2 bits)
  val s_axi_rdata = out Bits(32 bits)
}
class AodControl(p: AodConfig) extends Bundle {
  val a_word, d_word, phase0 = Vec(UInt(32 bits), p.channels * p.tones)
  val cal_a, cal_b = Vec(UInt(16 bits), p.channels * p.tones)
  val tone_en = Bits(p.channels * p.tones bits)
  val amp_scale = Vec(UInt(16 bits), p.channels)
  val ratio = UInt(p.ratioWidth bits)
  val dec = UInt(p.decWidth bits)
  val cw, go, soft_rst, trig_en, trig_pol, snap, sticky_clr, rom_we = Bool()
  val rom_sel = UInt(3 bits)
  val rom_addr = UInt(7 bits)
  val rom_beat = UInt(2 bits)
  val rom_wdata = Bits(32 bits)
  val rom_a8, rom_chan = Bool()
  val gcal_base = Vec(UInt(32 bits), p.channels)
  val gcal_zoom = Vec(UInt(2 bits), p.channels)
  val gcal_en = Bits(p.channels bits)
}
class AodStatus(p: AodConfig) extends Bundle {
  val busy, clip, cfg_err = Bits(p.channels bits)
  val sweep_cnt = Vec(UInt(32 bits), p.channels)
  val trig_cnt, fclk_cnt = UInt(32 bits)
  val snap_busy, dec_clamped, go_dropped, pa_we, pa_ch = Bool()
  val pa_addr = UInt(6 bits)
  val pa_data = Bits(32 bits)
}

/** Register transport is separate from the waveform banks. Preserves legacy
  * decode aliases, sentinel reads, response timing and one-cycle commands.
  */
class AodRegisters(p: AodConfig) extends Component {
  val io = new Bundle {
    val clk, rst = in Bool()
    val axi = new AodAxiIo
    val control = out(new AodControl(p))
    val status = in(new AodStatus(p))
  }
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val bus = io.axi
    val c = new AodControl(p)
    c.elements.filterNot(e => Set("tone_en", "amp_scale").contains(e._1))
      .foreach(_._2.flatten.foreach(_.setAsReg()))
    io.control := c
    val en_full = Vec.fill(2)(Reg(Bits(64 bits)))
    val amp_full = Vec.fill(2)(Reg(UInt(16 bits)))
    // Combinational outputs in the reference (the other control fields are registers).
    for (ch <- 0 until p.channels) {
      c.tone_en(ch * p.tones, p.tones bits) := en_full(ch)(p.tones - 1 downto 0)
      c.amp_scale(ch) := amp_full(ch)
    }
    val awready, wready, bvalid = Reg(Bool())
    val bresp = Reg(Bits(2 bits))
    bus.s_axi_awready := awready; bus.s_axi_wready := wready
    bus.s_axi_bvalid := bvalid; bus.s_axi_bresp := bresp
    val aw_fire = bus.s_axi_awvalid && bus.s_axi_wvalid && !awready && !bvalid
    val wa = bus.s_axi_awaddr
    val w_ch = if (p.channels > 1) wa(14) else False
    val w_ch_ok = if (p.channels > 1) True else !wa(14)
    val w_region = wa(13 downto 11)
    val w_tone = wa(7 downto 2)
    val w_sub = wa(4 downto 2)
    val flatw = log2Up(p.channels * p.tones)
    val w_flat = if (p.channels == 1) w_tone.resize(flatw) else
      Mux(w_ch, U(p.tones, flatw bits), U(0, flatw bits)) + w_tone.resize(flatw)
    val cfgAw = 8 + (if (p.channels > 1) 1 else 0)
    val paAw = 6 + (if (p.channels > 1) 1 else 0)
    val cfg_ram = Mem(Bits(32 bits), 1 << cfgAw)
    val pa_ram = Mem(Bits(32 bits), 1 << paAw)
    cfg_ram.addAttribute("ram_style", "distributed"); pa_ram.addAttribute("ram_style", "distributed")
    val cfg_wa = (w_ch.asBits ## w_region(1 downto 0).asBits ## w_tone.asBits).asUInt.resize(cfgAw)
    val cfg_we = Bool(); cfg_we := False
    cfg_ram.write(cfg_wa, bus.s_axi_wdata, cfg_we)
    pa_ram.write((io.status.pa_ch.asBits ## io.status.pa_addr.asBits).asUInt.resize(paAw),
      io.status.pa_data, io.status.pa_we)
    c.go := False; c.snap := False; c.sticky_clr := False; c.rom_we := False
    when(io.rst) {
      awready := False; wready := False; bvalid := False; bresp := 0
      en_full.foreach(_ := 0); amp_full.foreach(_ := 0)
      c.ratio := p.rateMin; c.dec := 1; c.cw := False; c.soft_rst := False
      c.trig_en := False; c.trig_pol := False
      c.rom_sel := 5; c.rom_addr := 0; c.rom_beat := 0; c.rom_wdata := 0
      c.rom_a8 := False; c.rom_chan := False
      for (ch <- 0 until p.channels) { c.gcal_base(ch) := 0; c.gcal_zoom(ch) := 2; c.gcal_en(ch) := True }
    } otherwise {
      awready := False; wready := False
      when(aw_fire) {
        awready := True; wready := True; bvalid := True; bresp := 0
        when(wa(15)) {
          when(!w_ch_ok) { bresp := 2 } elsewhen(w_region < 4) {
            when(w_tone >= p.tones) { bresp := 2 } otherwise {
              cfg_we := True
              switch(w_region(1 downto 0)) {
                is(0) { c.a_word(w_flat) := bus.s_axi_wdata.asUInt }
                is(1) { c.d_word(w_flat) := bus.s_axi_wdata.asUInt }
                is(2) { c.phase0(w_flat) := bus.s_axi_wdata.asUInt }
                default {
                  c.cal_a(w_flat) := bus.s_axi_wdata(15 downto 0).asUInt
                  c.cal_b(w_flat) := bus.s_axi_wdata(31 downto 16).asUInt
                }
              }
            }
          } elsewhen(w_region === 5) {
            switch(w_sub) {
              is(0) { en_full(w_ch.asUInt)(31 downto 0) := bus.s_axi_wdata & B((BigInt(1) << math.min(32, p.tones)) - 1, 32 bits) }
              is(1) { en_full(w_ch.asUInt)(63 downto 32) := bus.s_axi_wdata & B((BigInt(1) << math.max(0, p.tones - 32)) - 1, 32 bits) }
              is(2) { amp_full(w_ch.asUInt) := bus.s_axi_wdata(15 downto 0).asUInt }
              is(5) {
                c.gcal_en(w_ch.asUInt.resize(log2Up(p.channels))) := bus.s_axi_wdata(0)
                c.gcal_zoom(w_ch.asUInt.resize(log2Up(p.channels))) := bus.s_axi_wdata(2 downto 1).asUInt
              }
              is(6) { c.gcal_base(w_ch.asUInt.resize(log2Up(p.channels))) := bus.s_axi_wdata.asUInt }
              default { bresp := 2 }
            }
          } otherwise { bresp := 2 }
        } otherwise {
          switch(wa(7 downto 2)) {
            is(2) {
              c.go := bus.s_axi_wdata(0); c.cw := bus.s_axi_wdata(1); c.soft_rst := bus.s_axi_wdata(2)
              c.trig_en := bus.s_axi_wdata(3); c.trig_pol := bus.s_axi_wdata(4); c.snap := bus.s_axi_wdata(5)
            }
            is(3) { c.ratio := bus.s_axi_wdata.asUInt.resized }
            is(4) { c.dec := bus.s_axi_wdata.asUInt.resized }
            is(6) { c.sticky_clr := True }
            is(10) {
              c.rom_sel := bus.s_axi_wdata(2 downto 0).asUInt
              c.rom_addr := bus.s_axi_wdata(9 downto 3).asUInt
              c.rom_beat := bus.s_axi_wdata(11 downto 10).asUInt
              c.rom_a8 := bus.s_axi_wdata(12); c.rom_chan := bus.s_axi_wdata(13); c.rom_we := True
            }
            is(11) { c.rom_wdata := bus.s_axi_wdata }
            default { bresp := 2 }
          }
        }
      } elsewhen(bvalid && bus.s_axi_bready) { bvalid := False }
    }

    val ra = bus.s_axi_araddr
    val r_ch = if (p.channels > 1) ra(14) else False
    val r_ch_ok = if (p.channels > 1) True else !ra(14)
    val r_region = ra(13 downto 11)
    val r_tone = ra(7 downto 2)
    val cfg_ra = (r_ch.asBits ## r_region(1 downto 0).asBits ## r_tone.asBits).asUInt.resize(cfgAw)
    val pa_ra = (r_ch.asBits ## r_tone.asBits).asUInt.resize(paAw)
    val cfg_rq = RegNext(cfg_ram.readAsync(cfg_ra))
    val pa_rq = RegNext(pa_ram.readAsync(pa_ra))
    val arready, rvalid, rd_p, rd_from_ram = Reg(Bool())
    val rresp = Reg(Bits(2 bits)); val rdata, rd_flop = Reg(Bits(32 bits))
    val rd_region = Reg(UInt(3 bits))
    bus.s_axi_arready := arready; bus.s_axi_rvalid := rvalid
    bus.s_axi_rresp := rresp; bus.s_axi_rdata := rdata
    val ar_fire = bus.s_axi_arvalid && !arready && !rvalid && !rd_p
    val status = io.status
    val p_busy = status.busy.resize(2); val p_clip = status.clip.resize(2); val p_cfg = status.cfg_err.resize(2)
    val r_ch_index = r_ch.asUInt.resize(log2Up(p.channels))
    when(io.rst) {
      arready := False; rvalid := False; rresp := 0; rdata := 0
      rd_p := False; rd_from_ram := False; rd_region := 0; rd_flop := 0
    } otherwise {
      arready := False; rd_p := False
      when(ar_fire) {
        arready := True; rd_p := True; rd_region := r_region; rd_from_ram := False
        rd_flop := B(0xdead0000L, 32 bits)
        when(ra(15)) {
          when(!r_ch_ok) { rd_flop := B(0xdead0003L, 32 bits) } elsewhen(r_region < 5) {
            when(r_tone < p.tones) { rd_from_ram := True } otherwise { rd_flop := B(0xdead0002L, 32 bits) }
          } elsewhen(r_region === 5) {
            switch(ra(4 downto 2)) {
              is(0) { rd_flop := en_full(r_ch.asUInt)(31 downto 0) }
              is(1) { rd_flop := en_full(r_ch.asUInt)(63 downto 32) }
              is(2) { rd_flop := amp_full(r_ch.asUInt).asBits.resize(32) }
              is(3) { rd_flop := (p_cfg(r_ch.asUInt).asBits ## p_clip(r_ch.asUInt).asBits ## p_busy(r_ch.asUInt).asBits).resize(32) }
              is(4) { rd_flop := status.sweep_cnt(r_ch_index).asBits }
              is(5) { rd_flop := (c.gcal_zoom(r_ch_index).asBits ## c.gcal_en(r_ch_index).asBits).resize(32) }
              is(6) { rd_flop := c.gcal_base(r_ch_index).asBits }
              default { rd_flop := B(0xdead0001L, 32 bits) }
            }
          } otherwise { rd_flop := B(0xdead0001L, 32 bits) }
        } otherwise {
          switch(ra(7 downto 2)) {
            is(0) { rd_flop := B(0x54473031L, 32 bits) }
            is(1) { rd_flop := B((p.dacPhysical.toLong << 24) | (32L << 16) | (p.tones << 8) | p.channels, 32 bits) }
            is(2) { rd_flop := (False.asBits ## c.trig_pol.asBits ## c.trig_en.asBits ## c.soft_rst.asBits ## c.cw.asBits ## False.asBits).resize(32) }
            is(3) { rd_flop := c.ratio.asBits.resize(32) }
            is(4) { rd_flop := c.dec.asBits.resize(32) }
            is(5) { rd_flop := (status.go_dropped.asBits ## status.dec_clamped.asBits ## status.snap_busy.asBits ## p_cfg.resize(8) ## p_clip.resize(8) ## p_busy.resize(8)).resize(32) }
            is(7) { rd_flop := B((p.moveSteps.toLong << 16) | ((p.segmentLength & 255) << 8) | (p.segments & 255), 32 bits) }
            is(8) { rd_flop := B((p.decMax.toLong << 24) | (p.rateMin << 16) | p.ratioMax, 32 bits) }
            is(9) { rd_flop := status.fclk_cnt.asBits }
            is(10) { rd_flop := (c.rom_chan.asBits ## c.rom_a8.asBits ## c.rom_beat.asBits ## c.rom_addr.asBits ## c.rom_sel.asBits).resize(32) }
            is(11) { rd_flop := c.rom_wdata }
            is(12) { rd_flop := status.trig_cnt.asBits }
            is(13) { rd_flop := B((p.rateExact << 8) | log2Up(p.gcalEntries), 32 bits) }
          }
        }
      }
      when(rd_p) {
        rvalid := True; rresp := 0
        rdata := Mux(rd_from_ram, Mux(rd_region === 4, pa_rq, cfg_rq), rd_flop)
      } elsewhen(rvalid && bus.s_axi_rready) { rvalid := False }
    }
  }
}
