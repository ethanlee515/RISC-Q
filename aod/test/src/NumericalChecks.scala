package riscq.aod

import spinal.core.sim._

object NumericalChecks {
  import TestSupport._
  def cordic(dut: CordicDut): Unit = {
    val io = dut.io
    val clock = new Clock(io.clk)
    io.xin #= 0; io.yin #= 0; io.phasein #= 0; io.gin #= false
    clock.tick(8)
    val amp = 1 << 18
    var worst = 0
    for (i <- 0 until 256) {
      io.xin #= amp; io.phasein #= i * (1 << 23) / 256; io.gin #= true
      clock.tick(); io.gin #= false
      clock.until("CORDIC tag", 64)(io.gout.toBoolean)
      val angle = 2 * math.Pi * i / 256
      val x = (1.6467602581210656 * amp * math.cos(angle)).toInt
      val y = (1.6467602581210656 * amp * math.sin(angle)).toInt
      val err = math.max(math.abs(io.xout.toInt - x), math.abs(io.yout.toInt - y))
      assert(err < 64, s"CORDIC phase=$i error=$err")
      worst = math.max(worst, err)
      clock.tick() // consume the output tag before sending another
    }
    println(s"CORDIC: 256 phases, worst error $worst LSB (20-bit datapath)")
  }

  def gcal(dut: GcalDut, tabn: Int, halves: Int): Unit = {
    val io = dut.io
    val clock = new Clock(io.clk)
    val unity = 32768
    io.half #= false; io.cfg_en #= true; io.tab_we #= false
    io.cfg_base #= 0; io.cfg_zoom #= 2; io.tab_addr #= 0
    io.tab_wdata #= 0; io.inc #= 0; io.cal_now #= unity
    clock.tick(8)
    def wr(e: Int, value: Int, slope: Int = 0, axis: Int = 0): Unit = {
      io.tab_addr #= axis * tabn + e
      io.tab_wdata #= (BigInt(slope & 65535) << 16) | (value & 65535)
      io.tab_we #= true; clock.tick(); io.tab_we #= false; clock.tick()
    }
    def settle(w: BigInt): Int = {
      io.inc #= word(w); clock.tick(12); io.cal_eff.toInt
    }
    def offset(e: Int, zoom: Int, num: Int = 1, den: Int = 2): BigInt = {
      val bin = BigInt(1) << (32 - Integer.numberOfTrailingZeros(tabn) - zoom - 1)
      e * bin + bin * num / den
    }
    for (i <- 0 until 16) {
      io.cal_now #= i * 4369
      assert(settle(BigInt("12345678", 16)) == i * 4369, s"unity transparency $i")
    }
    io.cal_now #= unity
    for (i <- 0 until tabn) wr(i, unity / 4)
    for (k <- 0 until tabn by tabn / 16) {
      wr(k, unity)
      val seen = (0 until tabn).filter(i => settle(offset(i, 2)) > unity / 2)
      assert(seen == Seq(k), s"impulse $k decoded at $seen")
      wr(k, unity / 4)
    }
    wr(0, unity / 8); wr(tabn - 1, unity)
    for (i <- 1 until tabn - 1) wr(i, unity / 2)
    val base = BigInt("40000000", 16)
    io.cfg_base #= base
    for (off <- Seq(-BigInt("1000000", 16), BigInt(-1), BigInt(0)))
      assert(settle(base + off) == unity / 8, s"lower clamp offset=$off")
    for (off <- Seq(BigInt("20000000", 16), BigInt("30000000", 16), BigInt("1fffffff", 16)))
      assert(settle(base + off) == unity, s"upper clamp offset=$off")
    for ((start, slope) <- Seq((16384, 256), (49152, -128))) {
      for (i <- 0 until tabn) wr(i, start + i * slope, if (i == tabn - 1) 0 else slope)
      for (k <- 0 until 8) {
        val got = settle(base + offset(100, 2, k, 8))
        val want = start + 100 * slope + slope * k / 8
        assert(got == want, s"interpolation slope=$slope fraction=$k/8 got=$got want=$want")
      }
    }
    for (i <- 0 until tabn) wr(i, unity / 4)
    wr(64, unity)
    io.cfg_zoom #= 1
    assert(settle(base + offset(64, 1)) == unity)
    io.cfg_zoom #= 2
    assert(settle(base + offset(64, 1)) == unity / 4)
    assert(settle(base + offset(64, 2)) == unity)
    io.cal_now #= 12345
    assert(settle(base + offset(65, 2, 0, 1)) == 12345 / 4)
    io.cfg_en #= false
    assert(settle(base + offset(65, 2, 0, 1)) == 12345)
    io.cfg_en #= true
    if (halves == 2) {
      io.cal_now #= unity; io.cfg_base #= 0
      for (i <- 0 until tabn) { wr(i, unity / 2); wr(i, unity / 4, axis = 1) }
      wr(91, unity / 8); wr(37, unity, axis = 1)
      for ((axis, entry, want) <- Seq((0,37,unity/2), (0,91,unity/8),
          (1,37,unity), (1,91,unity/4), (1,3,unity/4), (0,3,unity/2))) {
        io.half #= (axis == 1)
        assert(settle(offset(entry, 2)) == want, s"half=$axis entry=$entry")
      }
    }
  }
}
