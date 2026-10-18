package riscq.aod

import spinal.core.{assert => _, _}
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.Opcode
import utest.{TestSuite, Tests, test}
import TestSupport._

class HostPorts(p: AodConfig) extends Bundle {
  val host_clk, host_rst, dsp_clk, dsp_rst = in Bool()
  val host = slave(tilelink.Bus(AodHostAdapter.busParameter(3)))
  val cmd = slave(Flow(AodWrite())) // test stimulus replacing the stopped CPU/link
  val time = out UInt(32 bits)
  val aod_reset = out Bool()
  val schedule = out(AodScheduleStatus(4))
  val dac_valid = out Bits(p.channels bits)
  val dac_x, dac_y = out Bits(32 bits)
  val write_event = master(Flow(AodCoefficientWrite()))
  val probe_ch = in Bool()
  val probe_tone = in UInt(6 bits)
  val probe_table, probe_beat = in UInt(2 bits)
  val probe_row = in UInt(8 bits)
  val probe_data = out Bits(32 bits)
}

/** Composition fixture, not another production AOD top. */
class HostDut(val p: AodConfig, withDiagnostics: Boolean) extends Component {
  val io = new HostPorts(p)
  val adapter = new AodHostAdapter(p, withDiagnostics, sourceWidth = 3)
  val top = new AodTop(p)
  adapter.io.host_clk := io.host_clk; adapter.io.host_rst := io.host_rst
  adapter.io.dsp_clk := io.dsp_clk; adapter.io.dsp_rst := io.dsp_rst
  adapter.io.host << io.host
  val hostCd = ClockDomain(io.host_clk, io.host_rst,
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
  val dspCd = ClockDomain(io.dsp_clk, io.dsp_rst,
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
  top.io.clk := io.dsp_clk; top.io.rst := adapter.io.aod_reset
  top.io.cmd << io.cmd; top.io.setup << adapter.io.setup
  adapter.io.state.setup_busy := top.io.setup_busy
  adapter.io.state.active := top.io.schedule.active
  adapter.io.state.idle := top.io.schedule.idle
  adapter.io.state.can_enqueue := top.io.schedule.can_enqueue
  if (withDiagnostics) {
    adapter.io.diagnostics.errors := top.io.schedule.errors
    adapter.io.diagnostics.clip := top.io.status.clip
    adapter.io.diagnostics.cfg_err := top.io.status.cfg_err
    adapter.io.diagnostics.go_dropped := top.io.status.go_dropped
    adapter.io.diagnostics.dec_clamped := top.io.status.dec_clamped
  }
  val timing = new ClockingArea(dspCd) {
    val time = Reg(UInt(32 bits)) init 0
    time := time + 1
    top.io.time := time; io.time := time
  }
  io.aod_reset := adapter.io.aod_reset; io.schedule := top.io.schedule
  io.dac_valid := top.io.dac_valid; io.dac_x := top.io.dac_x; io.dac_y := top.io.dac_y
  io.write_event.valid := adapter.io.setup.fire
  io.write_event.payload := adapter.io.setup.payload

  val selected = (io.probe_ch.asUInt.resize(7) * U(p.tones, 7 bits) + io.probe_tone.resize(14))
    .resize(log2Up(p.tones * p.channels))
  val words = top.engine.logic.banks.flatMap(_.logic.tones).map { t =>
    val gain = t.logic.gcal.rework { t.logic.gcal.logic.tab.readAsync(io.probe_row.pull().resized) }.pull()
    val unit = t.rework {
      val beat = Mux(io.probe_beat.pull() === 3, U(2, 2 bits), io.probe_beat.pull())
      Vec(t.logic.urom.map(_.readAsync(io.probe_row.pull().resize(log2Up(p.segments)))).map(_.resize(32)))(beat)
    }.pull()
    val seed = t.rework {
      val beat = Mux(io.probe_beat.pull() === 3, U(2, 2 bits), io.probe_beat.pull())
      Vec(t.logic.srom.map(_.readAsync(io.probe_row.pull().resize(log2Up(p.segments)))).map(_.resize(32)))(beat)
    }.pull()
    Mux(io.probe_table === AodTable.gain, gain, Mux(io.probe_table === AodTable.trajectory, unit, seed))
  }
  io.probe_data := Vec(words)(selected)
}

object HostTests extends TestSuite {
  def geometry = AodConfig.default.copy(tones = 2, moveSteps = 48, ratioMax = 64)
  lazy val reported = config.compile(new HostDut(geometry, withDiagnostics = true))
  lazy val quiet = config.compile(new HostDut(geometry.copy(channels = 1, gcalEntries = 128), withDiagnostics = false))
  case class Reply(data: BigInt, denied: Boolean, corrupt: Boolean, source: Int)
  case class Write(table: Int, channel: Int, row: Int, beat: Int, data: BigInt, cycle: Long)

  class Driver(val dut: HostDut, hostPeriod: Int = 14, dspPeriod: Int = 10) {
    def io: HostPorts = dut.io
    val hostCd = dut.hostCd; val dspCd = dut.dspCd
    val writes = scala.collection.mutable.ArrayBuffer.empty[Write]
    var dspCycles = 0L
    var resetCycles = 0
    var stalled = false
    io.cmd.valid #= false; io.cmd.address #= 0; io.cmd.data #= 0
    io.host.a.valid #= false; io.host.a.opcode #= Opcode.A.GET
    io.host.a.param #= 0; io.host.a.source #= 0; io.host.a.address #= 0
    io.host.a.size #= 2; io.host.a.mask #= 15; io.host.a.data #= 0; io.host.a.corrupt #= false
    io.host.d.ready #= false
    io.probe_ch #= false; io.probe_tone #= 0; io.probe_table #= 2; io.probe_beat #= 0; io.probe_row #= 0
    hostCd.forkStimulus(hostPeriod); dspCd.forkStimulus(dspPeriod)
    dspCd.onSamplings {
      dspCycles += 1
      if (!io.dsp_rst.toBoolean) {
        if (io.aod_reset.toBoolean) resetCycles += 1
        if (io.write_event.valid.toBoolean) writes += Write(io.write_event.table.toInt,
          if (io.write_event.channel.toBoolean) 1 else 0, io.write_event.address.toInt,
          io.write_event.beat.toInt, io.write_event.data.toBigInt, dspCycles)
      }
    }
    hostCd.onSamplings {
      if (!io.host_rst.toBoolean && io.host.a.valid.toBoolean && !io.host.a.ready.toBoolean) stalled = true
    }
    hostCd.waitSampling(40); dspCd.waitSampling(40); sleep(1)
    SimTimeout(20000000)

    def send(address: Int, data: BigInt = 0, get: Boolean = false, source: Int = 0,
             size: Int = 2, mask: Int = 15, partial: Boolean = false): Unit = {
      hostCd.waitFallingEdge()
      io.host.a.opcode #= (if (get) Opcode.A.GET else if (partial) Opcode.A.PUT_PARTIAL_DATA else Opcode.A.PUT_FULL_DATA)
      io.host.a.address #= address; io.host.a.data #= word(data); io.host.a.source #= source
      io.host.a.size #= size; io.host.a.mask #= mask; io.host.a.valid #= true
      hostCd.waitSamplingWhere(io.host.a.ready.toBoolean)
      sleep(1); io.host.a.valid #= false
    }
    def reply(get: Boolean = false, stall: Int = 0): Reply = {
      hostCd.waitSamplingWhere(io.host.d.valid.toBoolean)
      val r = Reply(io.host.d.data.toBigInt, io.host.d.denied.toBoolean,
        io.host.d.corrupt.toBoolean, io.host.d.source.toInt)
      assert(io.host.d.opcode.toEnum == (if (get) Opcode.D.ACCESS_ACK_DATA else Opcode.D.ACCESS_ACK))
      for (_ <- 0 until stall) {
        hostCd.waitSampling()
        assert(io.host.d.valid.toBoolean)
        assert(Reply(io.host.d.data.toBigInt, io.host.d.denied.toBoolean,
          io.host.d.corrupt.toBoolean, io.host.d.source.toInt) == r)
      }
      sleep(1); io.host.d.ready #= true; hostCd.waitSampling(); sleep(1); io.host.d.ready #= false
      r
    }
    def write(address: Int, data: BigInt, stall: Int = 0): Unit = {
      send(address, data); val r = reply(stall = stall); assert(!r.denied && !r.corrupt)
    }
    def read(address: Int): BigInt = {
      send(address, get = true); val r = reply(get = true); assert(!r.denied && !r.corrupt); r.data
    }
    def probe(table: Int, row: Int, beat: Int = 0, channel: Int = 0, tone: Int = 0): BigInt = {
      io.probe_table #= table; io.probe_row #= row; io.probe_beat #= beat
      io.probe_ch #= (channel == 1); io.probe_tone #= tone; sleep(1); io.probe_data.toBigInt
    }
    def cmd(address: Int, data: BigInt): Unit = {
      dspCd.waitFallingEdge()
      io.cmd.address #= address; io.cmd.data #= word(data); io.cmd.valid #= true
      dspCd.waitSampling(); sleep(1); io.cmd.valid #= false
    }
    def stage(): Unit = {
      cmd(AodRegisterMap.ratio, 8); cmd(AodRegisterMap.decimation, 1)
      for (ch <- 0 until dut.p.channels) {
        cmd(AodRegisterMap.channel(ch), 1); cmd(AodRegisterMap.channel(ch) + 8, 4096)
        cmd(AodRegisterMap.channel(ch) + 20, 0)
        for (t <- 0 until dut.p.tones; (f, v) <- Seq(0 -> BigInt(0x01000000), 1 -> BigInt(0),
          2 -> BigInt(0), 3 -> BigInt("80008000", 16))) cmd(AodRegisterMap.tone(ch,t,f), v)
      }
    }
    def fire(delay: Int): Long = {
      val start = io.time.toBigInt.toLong + delay
      cmd(AodRegisterMap.startTime, start); cmd(AodRegisterMap.fire, 0); start
    }
  }

  val tests = Tests {
    test("uploadCompletion") {
      for ((hp, dp) <- Seq((14, 10), (10, 18))) reported.doSim(s"upload-$hp-$dp", seed = 42) { dut =>
        val d = new Driver(dut, hp, dp)
        assert(d.read(AodHostMap.capabilities) == ((BigInt(1) << 24) | (BigInt(2) << 16) | (2 << 8) | 1))
        assert((d.read(AodHostMap.state) & 7) == 4)
        val random = new scala.util.Random(19)
        for (table <- 0 until 2; row <- Seq(0, 1, 31, 63); beat <- 0 until 4) {
          val data = BigInt(32, random)
          d.write(AodHostMap.profile(table, row, beat), data, stall = random.nextInt(7))
          // Response must follow actual memory completion, not FIFO acceptance.
          val width = if (table == AodTable.envelope && beat >= 2) 8 else 32
          for (ch <- 0 until 2; tone <- 0 until 2)
            assert(d.probe(table,row,beat,ch,tone) == (data & ((BigInt(1) << width) - 1)))
        }
        for (ch <- 0 until 2; entry <- Seq(0, 17, 127, 255)) {
          val data = BigInt(32, random)
          d.write(AodHostMap.calibration(ch,entry), data)
          for (tone <- 0 until 2) assert(d.probe(AodTable.gain,entry,channel=ch,tone=tone) == data)
        }
        assert(d.writes.size == 40)
        assert(d.writes.sliding(2).forall(w => w(1).cycle - w(0).cycle >= 8))
        val saved = d.probe(AodTable.gain,17)
        d.resetCycles = 0; d.write(AodHostMap.reset, 1)
        assert(d.resetCycles == AodTop.resetClocks && !d.io.aod_reset.toBoolean)
        assert(d.probe(AodTable.gain,17) == saved)
        assert(d.io.schedule.idle.toBoolean && d.io.schedule.errors.toInt == 0)
        assert(d.read(AodHostMap.reset) == 0)
      }
    }
    test("backpressureAndDeniedAccess") {
      reported.doSim("backpressureAndDeniedAccess", seed = 42) { dut =>
        val d = new Driver(dut)
        d.stalled = false
        var sent = false
        fork {
          for (i <- 0 until 8) d.send(AodHostMap.calibration(i % 2,17), 0x100 + i, source = i)
          sent = true
        }
        d.hostCd.waitSampling(120)
        assert(d.stalled && !sent)
        for (i <- 0 until 8) {
          val r = d.reply(stall = i % 4)
          assert(r.source == i && !r.denied && !r.corrupt)
        }
        assert(sent && d.writes.map(_.data).toVector == (0 until 8).map(i => BigInt(0x100 + i)).toVector)
        assert(d.probe(AodTable.gain,17) == 0x106 && d.probe(AodTable.gain,17,channel=1) == 0x107)
        val before = d.writes.size
        for ((addr, get, size, mask, partial) <- Seq(
          (0x80,false,2,15,false), (0x80,true,2,15,false),
          (0x1001,false,2,15,false), (0x1000,false,1,3,false),
          (0x1000,false,2,1,true), (0x1000,true,2,15,false),
          (0x1400,false,2,15,false), (0x3800,false,2,15,false),
          (AodHostMap.reset,false,2,15,false))) {
          d.send(addr, 3, get=get, source=7, size=size, mask=mask, partial=partial)
          val r = d.reply(get=get, stall=3)
          assert(r.denied && r.corrupt == get && r.source == 7)
        }
        assert(d.writes.size == before)
      }
    }
    test("playbackLockAndFlush") {
      reported.doSim("playbackLockAndFlush", seed = 42) { dut =>
        val d = new Driver(dut)
        d.stage(); d.fire(200)
        d.dspCd.waitSamplingWhere(d.io.dac_valid.toInt == 3); sleep(1)
        val old = d.probe(AodTable.gain,17)
        val before = d.writes.size
        d.send(AodHostMap.calibration(0,17), 12345)
        d.dspCd.waitSampling(30); sleep(1)
        assert(d.io.schedule.active.toBoolean && d.writes.size == before)
        assert(d.probe(AodTable.gain,17) == old && !d.io.host.d.valid.toBoolean)
        assert(!d.reply(stall=5).denied)
        assert(d.io.schedule.completed.toBigInt == 1 && d.io.schedule.idle.toBoolean)
        assert(d.probe(AodTable.gain,17) == 12345)
        // A per-run flush resets AOD only, leaving the host CDC alive and the
        // global DSP time running. Reuse uploaded tables in the following run.
        d.fire(1000)
        val time = d.io.time.toBigInt
        d.write(AodHostMap.reset,1)
        assert(d.io.schedule.queued.toInt == 0 && d.io.schedule.accepted.toBigInt == 0)
        assert(d.io.time.toBigInt > time && d.probe(AodTable.gain,17) == 12345)
        d.dspCd.waitSampling(1100); sleep(1)
        assert(d.io.schedule.started.toBigInt == 0 && d.io.dac_valid.toInt == 0)
        d.stage(); d.fire(200)
        d.dspCd.waitSamplingWhere(d.io.schedule.completed.toBigInt == 1); sleep(1)
        assert(d.io.schedule.errors.toInt == 0)
      }
    }
    test("optionalDiagnostics") {
      for ((compiled, enabled) <- Seq((reported,true), (quiet,false)))
        compiled.doSim(s"diagnostics-$enabled", seed = 42) { dut =>
          val d = new Driver(dut)
          assert(d.read(AodHostMap.capabilities).testBit(0) == enabled)
          d.cmd(AodRegisterMap.fire,0) // no future timestamp: rejected in either build
          val flags = d.read(AodHostMap.diagnostics)
          assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.late))
          if (enabled) assert((flags & 63) == (1 << AodScheduleError.late)) else assert(flags == 0)
          assert(d.io.schedule.accepted.toBigInt == 0)
          d.write(AodHostMap.reset,1)
          assert(d.read(AodHostMap.diagnostics) == 0)
          if (!enabled) {
            for (address <- Seq(AodHostMap.calibration(0,128), AodHostMap.calibration(1,0))) {
              d.send(address,123); assert(d.reply().denied)
            }
            assert(d.writes.isEmpty)
          }
        }
    }
    test("adapterRtl") {
      for (width <- Seq(0, 2, 3)) {
        val bus = AodHostAdapter.busParameter(width)
        assert(bus.sourceWidth == width && bus.node.m.sourceWidth == width)
      }
      val p = AodConfig.default
      for (enabled <- Seq(true, false)) {
        val directory = s"out/aod-host-rtl/${if (enabled) "reported" else "minimal"}"
        SpinalConfig(targetDirectory = directory).generateVerilog(new AodHostAdapter(p, enabled))
      }
    }
  }
}
