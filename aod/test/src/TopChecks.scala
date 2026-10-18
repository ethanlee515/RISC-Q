package riscq.aod

import spinal.core.sim._

/** AXI driver deliberately holds requests through an observed handshake edge. */
class TopDriver(val io: TopPorts) {
  import TestSupport._
  val clock = new Clock(io.clk)
  io.rst #= true; io.trig_in #= false
  io.s_axi_awaddr #= 0; io.s_axi_awprot #= 0; io.s_axi_awvalid #= false
  io.s_axi_wdata #= 0; io.s_axi_wstrb #= 15; io.s_axi_wvalid #= false
  io.s_axi_bready #= false
  io.s_axi_araddr #= 0; io.s_axi_arprot #= 0; io.s_axi_arvalid #= false
  io.s_axi_rready #= false
  io.probe_ch #= false; io.probe_tone #= 0; io.probe_addr #= 0
  clock.tick(20); io.rst #= false; clock.tick(20)

  def write(addr: Int, data: BigInt, response: Int = 0): Unit = {
    io.s_axi_awaddr #= addr; io.s_axi_wdata #= word(data)
    io.s_axi_awvalid #= true; io.s_axi_wvalid #= true
    clock.until(s"AXI write address 0x${addr.toHexString}", 100)(io.s_axi_awready.toBoolean && io.s_axi_wready.toBoolean)
    clock.tick()
    io.s_axi_awvalid #= false; io.s_axi_wvalid #= false
    clock.until("AXI write response", 100)(io.s_axi_bvalid.toBoolean)
    assert(io.s_axi_bresp.toInt == response, s"AXI write 0x${addr.toHexString} response=${io.s_axi_bresp.toInt}, expected=$response")
    io.s_axi_bready #= true; clock.tick(); io.s_axi_bready #= false; clock.tick()
  }
  def read(addr: Int, response: Int = 0): BigInt = {
    io.s_axi_araddr #= addr; io.s_axi_arvalid #= true
    clock.until(s"AXI read address 0x${addr.toHexString}", 100)(io.s_axi_arready.toBoolean)
    clock.tick(); io.s_axi_arvalid #= false
    clock.until("AXI read response", 100)(io.s_axi_rvalid.toBoolean)
    val data = io.s_axi_rdata.toBigInt
    assert(io.s_axi_rresp.toInt == response, s"AXI read 0x${addr.toHexString} response=${io.s_axi_rresp.toInt}")
    io.s_axi_rready #= true; clock.tick(); io.s_axi_rready #= false; clock.tick()
    data
  }
  def expect(addr: Int, value: BigInt): Unit = {
    val got = read(addr)
    assert(got == word(value), s"register 0x${addr.toHexString} got=0x${got.toString(16)} expected=0x${word(value).toString(16)}")
  }
  def poll(label: String, limit: Int = 20000)(p: => Boolean): Unit = {
    var i = 0; var ok = p
    while (!ok && i < limit) { i += 1; ok = p }
    assert(ok, s"Timeout polling $label ($limit reads)")
  }
  def busy(): Unit = poll("busy", 200)((read(0x14) & 1) != 0)
  def idle(): Unit = poll("idle")((read(0x14) & 1) == 0)
  def probe(ch: Int = 0, tone: Int = 0, addr: Int = 0): Unit = {
    io.probe_ch #= (ch == 1); io.probe_tone #= tone; io.probe_addr #= addr; sleep(1)
  }
}

object TopChecks {
  import TestSupport._
  def ch(c: Int): Int = 0x8000 + c * 0x4000
  def tone(c: Int, t: Int, field: Int): Int = ch(c) + field * 0x800 + t * 4
  def en(c: Int): Int = ch(c) + 0x2800
  def amp(c: Int): Int = ch(c) + 0x2808
  def sw(c: Int): Int = ch(c) + 0x2810
  def cfg(c: Int): Int = ch(c) + 0x2814
  def base(c: Int): Int = ch(c) + 0x2818

  def registers(dut: TopDut): Unit = {
    val d = new TopDriver(dut.io); val io = dut.io; val clock = d.clock
    val nt = 4
    def pat(c: Int, t: Int, f: Int): BigInt =
      (BigInt(0xc0 + c) << 24) | ((0x70 + t) << 16) | ((0xf0 + f) << 8) | 0x5a
    d.expect(0, BigInt("54473031", 16))
    d.expect(4, BigInt("0e200402", 16))
    d.expect(0x1c, (6250 << 16) | (98 << 8) | 64)
    d.expect(0x20, BigInt("1007ffff", 16))
    for (c <- 0 until 2) d.expect(amp(c), 0)
    for (c <- 0 until 2; t <- 0 until nt; f <- 0 until 4) d.write(tone(c,t,f), pat(c,t,f))
    for (c <- 0 until 2; t <- 0 until nt; f <- 0 until 4) d.expect(tone(c,t,f), pat(c,t,f))
    for (a <- Seq(tone(0,nt,0), tone(0,63,0), tone(0,0,4), ch(0)+0x280c, 0))
      d.write(a, BigInt("a5a5a5a5", 16), response = 2)
    // The reference intentionally returns sentinels with OKAY for invalid reads.
    d.expect(tone(0,nt,0), BigInt("dead0002",16))
    d.expect(tone(0,nt,4), BigInt("dead0002",16))
    d.expect(0x44, BigInt("dead0000",16))
    d.expect(tone(0,0,0), pat(0,0,0))
    for ((a,v) <- Seq(0xc -> 40, 0x10 -> 4, 0x2c -> 0x12345678, 0x28 -> ((2 << 10) | (0x1f << 3) | 6))) {
      d.write(a,v); d.expect(a,v)
    }
    d.write(0x28, 5)
    for (c <- 0 until 2) {
      d.write(en(c), mask); d.expect(en(c), 15); d.expect(en(c)+4, 0)
    }
    d.write(0xc,8); d.write(0x10,16); clock.tick(8)
    assert(d.read(0x14).testBit(25), "dec > ratio must be reported")
    d.write(0x10,4); clock.tick(8)
    assert(!d.read(0x14).testBit(25))
    val fclk = d.read(0x24); clock.tick(200)
    assert(d.read(0x24) > fclk, "fabric counter must advance")
    for (c <- 0 until 2) {
      for (t <- 0 until nt; (f,v) <- Seq(0 -> BigInt("00100000",16), 1 -> BigInt(0),
          2 -> BigInt(0), 3 -> BigInt("80008000",16))) d.write(tone(c,t,f),v)
      d.write(amp(c),2048)
    }
    d.write(0xc,17); d.write(0x10,16); clock.tick(64)
    val before = d.read(sw(0))
    d.write(8,1); d.busy(); d.idle(); clock.tick(400)
    d.expect(sw(0),before+1)
    val phase = word(BigInt("00100000",16) * ((6250+15)/16) * 17)
    assert(phase != 0)
    for (c <- 0 until 2; t <- 0 until nt) d.expect(tone(c,t,4),phase)
    clock.tick(64)
    assert(io.dac_x.toBigInt == 0 && io.dac_y.toBigInt == 0, "pulsed sweep must become silent")
    d.write(0x18,1); assert(!d.read(0x14).testBit(8))
    for (c <- 0 until 2) d.write(amp(c),65535)
    clock.tick(64); d.write(8,1); d.busy(); d.idle()
    assert(d.read(0x14).testBit(8), "coherent overdrive must clip")
    d.expect(ch(0)+0x280c,2)
    for (c <- 0 until 2) d.write(amp(c),256)
    clock.tick(64); d.write(0x18,1); clock.tick(16)
    assert(!d.read(0x14).testBit(8), "clip W1C")

    d.write(8,0)
    val triggers = d.read(0x30); val sweeps = d.read(sw(0))
    io.trig_in #= true; clock.tick(20); io.trig_in #= false; clock.tick(20)
    d.expect(0x30,triggers); d.expect(sw(0),sweeps)
    d.write(8,8); clock.tick(20); io.trig_in #= true; clock.tick(20)
    d.expect(0x30,triggers+1); d.busy(); d.idle(); clock.tick(400)
    d.expect(sw(0),sweeps+1)
    io.trig_in #= false; clock.tick(40)
    val tc = d.read(0x30)
    d.write(8,0x18); clock.tick(40); d.expect(0x30,tc)
    d.write(8,0)

    d.write(0x18,1)
    val queued = d.read(sw(0))
    d.write(8,1); d.busy(); d.write(8,1)
    d.poll("two queued sweeps complete")(d.read(sw(0)) == queued+2)
    d.idle(); clock.tick(400)
    d.expect(sw(0),queued+2)
    assert(!d.read(0x14).testBit(26), "one pending launch must be accepted")
    d.write(0x18,1)
    val over = d.read(sw(0))
    d.write(8,1); d.busy(); d.write(8,1); d.write(8,1); clock.tick(40)
    assert(d.read(0x14).testBit(26), "third outstanding launch must report go_drop")
    d.poll("over-trigger sweeps complete")(d.read(sw(0)) == over+2)
    d.idle(); clock.tick(400); d.expect(sw(0),over+2); d.write(0x18,1)
    for (c <- 0 until 2) d.write(amp(c),2048)
    d.write(8,3); clock.tick(2000)
    assert(io.dac_x.toBigInt != 0 || io.dac_y.toBigInt != 0, "soft-reset check must start from active output")
    d.write(8,4); clock.tick(200)
    assert(io.dac_x.toBigInt == 0 && io.dac_y.toBigInt == 0, "soft reset must silence both outputs")
    d.write(8,0)
  }

  def gcal(dut: TopDut): Unit = {
    val d = new TopDriver(dut.io); val io = dut.io; val clock = d.clock
    def pattern(c: Int,e: Int): BigInt = (c << 12) | (0x100 + e)
    def wr(c: Int,e: Int,v: BigInt,sel: Int = 7): Unit = {
      d.write(0x2c,v)
      d.write(0x28,(c << 13) | ((e >> 7) << 12) | ((e & 127) << 3) | sel)
    }
    def table(c: Int,t: Int,e: Int): BigInt = { d.probe(c,t,e); io.probe_table.toBigInt }
    for (e <- 0 until 256; c <- 0 until 2; t <- 0 until 2) {
      val got = table(c,t,e)
      assert(got == 32768, s"reset unity ch=$c tone=$t entry=$e got=$got")
    }
    val info = d.read(0x34)
    assert((info & 255) == 8 && ((info >> 8) & 255) == 17, "GCAL_INFO depth and exact ratio")
    for (c <- 0 until 2) {
      for (e <- 0 until 256) wr(c,e,pattern(c,e))
      clock.tick(8)
      for (axis <- 0 until 2; t <- 0 until 2; e <- 0 until 256) {
        val expected = if (axis <= c) pattern(axis,e) else BigInt(32768)
        assert(table(axis,t,e) == expected, s"table isolation ch=$axis tone=$t entry=$e")
      }
    }
    val before = table(0,0,37)
    for (sel <- 0 until 7) {
      wr(0,37,BigInt("deadbeef",16),sel); clock.tick(8)
      assert(table(0,0,37) == before, s"selector $sel must not write gcal")
    }
    wr(0,37,BigInt("12345678",16)); clock.tick(8)
    assert(table(0,0,37) == BigInt("12345678",16), "selector 7 must write gcal")
    wr(0,37,pattern(0,37)); clock.tick(8)
    val snapshots = (0 until 64).map { e =>
      d.probe(addr=e); (io.probe_urom.toBigInt,io.probe_srom.toBigInt)
    }
    for (e <- 0 until 256) wr(0,e,16384)
    clock.tick(8)
    for (e <- 0 until 64) {
      d.probe(addr=e)
      assert((io.probe_urom.toBigInt,io.probe_srom.toBigInt) == snapshots(e), s"gcal load corrupted trajectory/envelope row $e")
    }
    val bases = Seq(BigInt("cafe0000",16),BigInt("0bad1000",16))
    for (c <- 0 until 2) {
      d.write(base(c),bases(c)); d.write(cfg(c),if (c == 0) 7 else 2)
    }
    clock.tick(16)
    for (c <- 0 until 2) {
      d.expect(base(c),bases(c)); d.expect(cfg(c),if (c == 0) 7 else 2)
      d.probe(ch=c)
      assert(io.probe_base.toBigInt == bases(c))
      assert(io.probe_zoom.toInt == (if (c == 0) 3 else 1))
      assert(io.probe_enable.toBoolean == (c == 0))
    }
    for (e <- 0 until 256) wr(0,e,pattern(0,e))
    d.write(base(0),0); d.write(cfg(0),5); clock.tick(64); d.probe()
    assert(io.probe_gain.toBigInt == pattern(0,io.probe_index.toInt), "live lookup uses loaded table")
    assert(io.probe_ce_gain.toBigInt == pattern(0,io.probe_index.toInt))
    d.write(cfg(0),4); clock.tick(32); d.probe()
    assert(io.probe_ce_gain.toInt == 32768, "disable multiplies by unity at the same latency")
    assert(table(0,0,37) == pattern(0,37), "disable preserves table")
    d.write(cfg(0),5); clock.tick(32); d.probe()
    assert(io.probe_ce_gain.toBigInt == pattern(0,io.probe_index.toInt), "re-enable restores correction")
  }
}
