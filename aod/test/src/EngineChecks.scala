package riscq.aod

import spinal.core.{assert => _, _}
import spinal.core.sim._
import spinal.lib._
import TestSupport._

/** Direct engine fixture: no posted decoder or alternate production shell. */
class EngineDut(val p: AodConfig) extends Component {
  val io = new Bundle {
    val clk, rst, trig_in = in Bool()
    val control = in(AodControl(p))
    val coefficients = slave(Flow(AodCoefficientWrite()))
    val status = out(AodStatus(p))
    val dac_valid = out Bits(p.channels bits)
    val dac_x, dac_y = out Bits(32 bits)
  }
  val engine = new AodEngine(p)
  engine.io.clk := io.clk; engine.io.rst := io.rst
  engine.io.trig_in := io.trig_in; engine.io.control := io.control
  engine.io.coefficients << io.coefficients
  io.status := engine.io.status; io.dac_valid := engine.io.dac_valid
  io.dac_x := engine.io.dac_x; io.dac_y := engine.io.dac_y
}

class EngineDriver(val dut: EngineDut) {
  def io = dut.io
  val p = dut.p
  val clock = new Clock(io.clk)
  io.rst #= true; io.trig_in #= false
  io.coefficients.valid #= false; io.coefficients.table #= 0
  io.coefficients.channel #= false; io.coefficients.address #= 0
  io.coefficients.beat #= 0; io.coefficients.data #= 0
  for (i <- 0 until p.channels * p.tones) {
    io.control.a_word(i) #= 0; io.control.d_word(i) #= 0; io.control.phase0(i) #= 0
    io.control.cal_a(i) #= 32768; io.control.cal_b(i) #= 32768
  }
  io.control.tone_en #= 0; io.control.ratio #= p.rateMin; io.control.dec #= 1
  io.control.cw #= false; io.control.go #= false; io.control.soft_rst #= false
  io.control.trig_en #= false; io.control.trig_pol #= false
  io.control.snap #= false; io.control.sticky_clr #= false
  io.control.gcal_en #= (BigInt(1) << p.channels) - 1
  for (ch <- 0 until p.channels) {
    io.control.amp_scale(ch) #= 0; io.control.gcal_base(ch) #= 0; io.control.gcal_zoom(ch) #= 2
  }
  val snapshots = scala.collection.mutable.Map.empty[(Int, Int), BigInt]
  val snapshotEvents = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, BigInt)]
  clock.before = () => {
    if (!io.rst.toBoolean && io.status.pa_we.toBoolean) {
      val ch = if (io.status.pa_ch.toBoolean) 1 else 0
      val tone = io.status.pa_addr.toInt
      val phase = io.status.pa_data.toBigInt
      snapshots((ch, tone)) = phase; snapshotEvents += ((ch, tone, phase))
    }
  }
  clock.tick(20); io.rst #= false; settled()
  def tone(ch: Int, t: Int, a: BigInt, delta: BigInt, phase: BigInt,
           calA: Int = 32768, calB: Int = 32768): Unit = {
    val i = ch * p.tones + t
    io.control.a_word(i) #= word(a); io.control.d_word(i) #= word(delta)
    io.control.phase0(i) #= word(phase)
    io.control.cal_a(i) #= calA; io.control.cal_b(i) #= calB
  }
  def settled(): Unit = clock.tick(p.tones + 8)
  def launch(cw: Boolean = false): Unit = {
    io.control.cw #= cw; io.control.go #= true; clock.tick(); io.control.go #= false
  }
  def clear(): Unit = {
    io.control.sticky_clr #= true; clock.tick(); io.control.sticky_clr #= false
  }
  def busy(): Unit = clock.until("engine busy", 200)(io.status.busy.toBigInt != 0)
  def idle(): Unit = {
    clock.until("engine idle")(io.status.busy.toBigInt == 0)
    clock.until("converter drain", 200)(io.dac_valid.toBigInt == 0)
  }
}

object EngineChecks {
  def control(dut: EngineDut): Unit = {
    val d = new EngineDriver(dut); val io = dut.io; val clock = d.clock; val p = dut.p
    io.control.ratio #= 8; io.control.dec #= 16; clock.tick()
    assert(io.status.dec_clamped.toBoolean)
    io.control.dec #= 4; clock.tick()
    assert(!io.status.dec_clamped.toBoolean)
    val fclk = io.status.fclk_cnt.toBigInt; clock.tick(200)
    assert(io.status.fclk_cnt.toBigInt > fclk)
    for (ch <- 0 until p.channels; t <- 0 until p.tones) d.tone(ch,t,0x100000,0,0)
    io.control.tone_en #= (BigInt(1) << (p.tones * p.channels)) - 1
    io.control.amp_scale.foreach(_ #= 2048)
    io.control.ratio #= 17; io.control.dec #= 16; d.settled()
    val before = io.status.sweep_cnt(0).toBigInt
    val launchEdge = clock.cycles + 1
    var firstValid = -1L
    clock.after = () => { if (firstValid < 0 && io.dac_valid.toBigInt != 0) firstValid = clock.cycles }
    d.launch(); d.busy(); d.idle(); clock.tick(400)
    assert(firstValid - launchEdge == AodEngine.launchLatency(p))
    clock.after = () => ()
    val phase = word(BigInt(0x100000) * ((p.moveSteps+15)/16) * 17)
    assert(d.snapshotEvents.size == p.tones * p.channels)
    for (ch <- 0 until p.channels; t <- 0 until p.tones) {
      assert(io.status.sweep_cnt(ch).toBigInt == before+1)
      assert(d.snapshots((ch,t)) == phase)
    }
    assert(io.dac_x.toBigInt == 0 && io.dac_y.toBigInt == 0)
    d.clear(); clock.tick(); assert(io.status.clip.toBigInt == 0)
    io.control.amp_scale.foreach(_ #= 65535)
    d.settled(); d.launch(); d.busy(); d.idle()
    assert(io.status.clip.toBigInt == 3, "coherent overdrive must clip both axes")
    io.control.amp_scale.foreach(_ #= 256)
    d.settled(); d.clear(); clock.tick(16); assert(io.status.clip.toBigInt == 0)

    val triggers = io.status.trig_cnt.toBigInt; val sweeps = io.status.sweep_cnt(0).toBigInt
    io.trig_in #= true; clock.tick(20); io.trig_in #= false; clock.tick(20)
    assert(io.status.trig_cnt.toBigInt == triggers && io.status.sweep_cnt(0).toBigInt == sweeps)
    io.control.trig_en #= true; clock.tick(20); io.trig_in #= true; clock.tick(20)
    assert(io.status.trig_cnt.toBigInt == triggers+1)
    d.busy(); d.idle(); clock.tick(400)
    assert(io.status.sweep_cnt(0).toBigInt == sweeps+1)
    io.trig_in #= false; clock.tick(40)
    val tc = io.status.trig_cnt.toBigInt
    io.control.trig_pol #= true; clock.tick(40)
    assert(io.status.trig_cnt.toBigInt == tc)
    io.control.trig_en #= false; io.control.trig_pol #= false

    d.clear()
    val queued = io.status.sweep_cnt(0).toBigInt
    d.launch(); d.busy(); d.launch()
    clock.until("two pending sweeps complete")(io.status.sweep_cnt(0).toBigInt == queued+2)
    d.idle(); clock.tick(400); assert(!io.status.go_dropped.toBoolean)
    d.clear()
    val over = io.status.sweep_cnt(0).toBigInt
    d.launch(); d.busy(); d.launch(); d.launch(); clock.tick(40)
    assert(io.status.go_dropped.toBoolean)
    clock.until("over-trigger sweeps complete")(io.status.sweep_cnt(0).toBigInt == over+2)
    d.idle(); clock.tick(400); d.clear()
    io.control.amp_scale.foreach(_ #= 2048)
    d.settled(); d.launch(cw = true); clock.tick(2000)
    assert(io.dac_x.toBigInt != 0 || io.dac_y.toBigInt != 0)
    io.control.soft_rst #= true; clock.tick(200)
    assert(io.dac_x.toBigInt == 0 && io.dac_y.toBigInt == 0 && io.dac_valid.toBigInt == 0)
  }
}
