package riscq.aod

import spinal.core.sim._

/** Native posted-write driver and ready/valid coefficient producer. */
class TopDriver(val io: TopPorts) {
  import TestSupport._
  val clock = new Clock(io.clk)
  io.rst #= true; io.time #= 0
  io.cmd.valid #= false; io.cmd.address #= 0; io.cmd.data #= 0
  io.setup.valid #= false; io.setup.table #= 0; io.setup.channel #= false
  io.setup.address #= 0; io.setup.beat #= 0; io.setup.data #= 0
  io.probe_ch #= false; io.probe_tone #= 0; io.probe_addr #= 0; io.probe_beat #= 0
  val snapshots = scala.collection.mutable.Map.empty[(Int, Int), BigInt]
  val snapshotEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, BigInt)]
  clock.before = () => {
    io.time #= clock.cycles
    if (!io.rst.toBoolean && io.status.pa_we.toBoolean) {
      val ch = if (io.status.pa_ch.toBoolean) 1 else 0
      val addr = io.status.pa_addr.toInt
      val data = io.status.pa_data.toBigInt
      snapshots((ch, addr)) = data; snapshotEvents += ((ch, addr, data))
    }
  }
  clock.after = () => { io.time #= clock.cycles }
  clock.tick(20); io.rst #= false; settled()
  for (ch <- 0 until io.p.channels; t <- 0 until io.p.tones; field <- 0 until 4)
    write(AodRegisterMap.tone(ch,t,field), 0)

  // No idle tick: successive calls deliver beats on consecutive rising edges.
  def write(addr: Int, data: BigInt): Unit = {
    io.cmd.address #= addr; io.cmd.data #= word(data); io.cmd.valid #= true
    clock.tick(); io.cmd.valid #= false
  }
  def launch(): Long = {
    val start = clock.cycles + AodTop.minimumEnqueueLead(io.p) + 16
    write(AodRegisterMap.startTime, start); write(AodRegisterMap.fire, 0)
    start
  }
  def load(table: Int, address: Int, data: BigInt, channel: Int = 0, beat: Int = 0): Int = {
    io.setup.table #= table; io.setup.channel #= (channel == 1)
    io.setup.address #= address; io.setup.beat #= beat; io.setup.data #= word(data)
    io.setup.valid #= true; sleep(1)
    var stalled = 0
    while (!io.setup.ready.toBoolean && stalled < 100) { clock.tick(); stalled += 1 }
    assert(io.setup.ready.toBoolean, "coefficient write timed out")
    clock.tick(); io.setup.valid #= false
    stalled
  }
  def settled(): Unit = {
    sleep(1)
    clock.until("setup and scheduled work drained", 200000)(io.schedule.idle.toBoolean && !io.setup_busy.toBoolean && !io.setup.valid.toBoolean)
  }
  def poll(label: String, limit: Int = 200000)(p: => Boolean): Unit = clock.until(label, limit)(p)
  def busy(): Unit = poll("busy", 200)(io.status.busy.toBigInt != 0)
  def idle(): Unit = {
    poll("scheduled job and snapshot drain")(io.schedule.idle.toBoolean)
    assert(io.dac_valid.toBigInt == 0)
  }
  def probe(ch: Int = 0, tone: Int = 0, addr: Int = 0, beat: Int = 0): Unit = {
    io.probe_ch #= (ch == 1); io.probe_tone #= tone; io.probe_addr #= addr
    io.probe_beat #= beat; sleep(1)
  }
}

object TopChecks {
  import TestSupport._
  def ch(c: Int): Int = 0x8000 + c * 0x4000
  def tone(c: Int, t: Int, field: Int): Int = AodRegisterMap.tone(c, t, field)
  def en(c: Int): Int = AodRegisterMap.channel(c)
  def amp(c: Int): Int = en(c) + 8
  def cfg(c: Int): Int = en(c) + 20
  def base(c: Int): Int = en(c) + 24

  def registers(dut: TopDut): Unit = {
    val d = new TopDriver(dut.io); val io = dut.io; val clock = d.clock
    val nt = dut.p.tones
    def pat(c: Int, t: Int, f: Int): BigInt =
      (BigInt(0xc0 + c) << 24) | ((0x70 + t) << 16) | ((0xf0 + f) << 8) | 0x5a
    for (c <- 0 until 2) assert(io.control.amp_scale(c).toInt == 0)
    val begin = clock.cycles
    for (c <- 0 until 2; t <- 0 until nt; f <- 0 until 4) d.write(tone(c,t,f), pat(c,t,f))
    assert(clock.cycles - begin == 2 * nt * 4, "one runtime write per clock")
    for (c <- 0 until 2; t <- 0 until nt) {
      val i = c * nt + t
      assert(io.control.a_word(i).toBigInt == pat(c,t,0))
      assert(io.control.d_word(i).toBigInt == pat(c,t,1))
      assert(io.control.phase0(i).toBigInt == pat(c,t,2))
      assert(io.control.cal_a(i).toBigInt == (pat(c,t,3) & 65535))
      assert(io.control.cal_b(i).toBigInt == (pat(c,t,3) >> 16))
    }
    for (a <- Seq(tone(0,nt,0), tone(0,63,0), tone(0,0,4), ch(0)+0x280c, tone(0,0,0)+1, 0x28, 0x2c))
      d.write(a, BigInt("a5a5a5a5", 16))
    assert(io.control.a_word(0).toBigInt == pat(0,0,0), "unmapped writes must not alias valid registers")
    for (c <- 0 until 2) d.write(en(c), mask)
    assert(io.control.tone_en.toBigInt == ((BigInt(1) << (2 * nt)) - 1))
    d.write(0xc,17); d.write(0x10,16)
    assert(io.control.ratio.toInt == 17 && io.control.dec.toInt == 16)
    assert(io.schedule.accepted.toBigInt == 0 && io.schedule.idle.toBoolean)
    // The canonical control path is staging + timestamp + fire. Historical
    // immediate control commands cannot launch or change the active engine.
    d.write(AodRegisterMap.control, 0x3f); clock.tick(100)
    assert(io.schedule.accepted.toBigInt == 0 && !io.schedule.active.toBoolean)
    assert(io.dac_valid.toBigInt == 0)
  }

  def gcal(dut: TopDut): Unit = {
    val d = new TopDriver(dut.io); val io = dut.io; val clock = d.clock
    def pattern(c: Int,e: Int): BigInt = (c << 12) | (0x100 + e)
    def wr(c: Int,e: Int,v: BigInt,sel: Int = 7): Unit = {
      if (sel == 7) d.load(AodTable.gain, e, v, channel = c)
      else if (sel == 5 || sel == 6) d.load(sel - 5, e & 63, v)
    }
    def table(c: Int,t: Int,e: Int): BigInt = { d.probe(c,t,e); io.probe_table.toBigInt }
    for (e <- 0 until 256; c <- 0 until 2; t <- 0 until 2) {
      val got = table(c,t,e)
      assert(got == 32768, s"reset unity ch=$c tone=$t entry=$e got=$got")
    }
    for (c <- 0 until 2) {
      for (e <- 0 until 256) wr(c,e,pattern(c,e))
      clock.tick(8)
      for (axis <- 0 until 2; t <- 0 until 2; e <- 0 until 256) {
        val expected = if (axis <= c) pattern(axis,e) else BigInt(32768)
        assert(table(axis,t,e) == expected, s"table isolation ch=$axis tone=$t entry=$e")
      }
    }
    val before = table(0,0,37)
    for (sel <- Seq(5, 6)) {
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
    d.launch(); d.idle()
    for (c <- 0 until 2) {
      assert(io.control.gcal_base(c).toBigInt == bases(c))
      assert(io.control.gcal_zoom(c).toInt == (if (c == 0) 3 else 1))
      assert(io.control.gcal_en.toBigInt.testBit(c) == (c == 0))
      d.probe(ch=c)
      assert(io.probe_base.toBigInt == bases(c))
      assert(io.probe_zoom.toInt == (if (c == 0) 3 else 1))
      assert(io.probe_enable.toBoolean == (c == 0))
    }
    for (e <- 0 until 256) wr(0,e,pattern(0,e))
    d.write(base(0),0); d.write(cfg(0),5); d.settled(); d.launch(); d.idle(); d.probe()
    assert(io.probe_gain.toBigInt == pattern(0,io.probe_index.toInt), "live lookup uses loaded table")
    assert(io.probe_ce_gain.toBigInt == pattern(0,io.probe_index.toInt))
    d.write(cfg(0),4); d.launch(); d.idle(); d.probe()
    assert(io.probe_ce_gain.toInt == 32768, "disable multiplies by unity at the same latency")
    assert(table(0,0,37) == pattern(0,37), "disable preserves table")
    d.write(cfg(0),5); d.launch(); d.idle(); d.probe()
    assert(io.probe_ce_gain.toBigInt == pattern(0,io.probe_index.toInt), "re-enable restores correction")
  }
}
