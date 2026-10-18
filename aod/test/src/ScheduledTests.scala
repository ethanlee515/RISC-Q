package riscq.aod

import spinal.core.{assert => _, _}
import spinal.core.sim._
import spinal.lib._
import utest._
import TestSupport._

object ScheduledTests extends TestSuite {
  def geometry(tones: Int = 2, channels: Int = 2) = AodConfig.default.copy(
    tones = tones, channels = channels, moveSteps = 48, ratioMax = 64)
  lazy val scheduled2 = config.compile(new AodTop(geometry()))
  lazy val scheduled4 = config.compile(new AodTop(geometry(4, 1)))
  lazy val engine = config.compile(new EngineDut(geometry()))

  // Initialize every field, including disabled tones. Deliberately vary all
  // per-movement fields and both axes; tables remain fixed throughout playback.
  def stage(p: AodConfig, write: (Int, BigInt) => Unit, variant: Int): Unit = {
    write(AodRegisterMap.ratio, if (variant == 1) 9 else 8)
    write(AodRegisterMap.decimation, if (variant == 1) 4 else 1)
    for (axis <- 0 until p.channels) {
      val base = AodRegisterMap.channel(axis)
      write(base, if (variant == 2) 1 else 3)
      write(base + 8, 12000 + variant * 2000 + axis * 500)
      write(base + 20, 0) // calibration tables are tested separately
      write(base + 24, 1234 + variant)
      for (tone <- 0 until p.tones) {
        write(AodRegisterMap.tone(axis, tone, 0), BigInt(0x01000000L + variant * 0x70000L + tone * 0x100000L + axis * 0x300000L))
        write(AodRegisterMap.tone(axis, tone, 1), if (variant == 1) -0x100000 else 0x200000)
        write(AodRegisterMap.tone(axis, tone, 2), BigInt(variant * 0x10000000L + axis * 0x2000000L + tone * 0x3000000L))
        write(AodRegisterMap.tone(axis, tone, 3), BigInt("70006000", 16) + variant * 0x10001)
      }
    }
  }
  def initSetup(setup: Stream[AodCoefficientWrite]): Unit = {
    setup.valid #= false; setup.table #= 0; setup.channel #= false
    setup.address #= 0; setup.beat #= 0; setup.data #= 0
  }
  def writer(cmd: Flow[AodWrite], clock: Clock)(address: Int, data: BigInt): Unit = {
    cmd.valid #= true; cmd.address #= address; cmd.data #= word(data)
    clock.tick(); cmd.valid #= false
  }

  class Driver(val dut: AodTop, timeBase: Long = 0) {
    def io = dut.io
    val clock = new Clock(io.clk)
    var now = timeBase & 0xffffffffL
    val samples = scala.collection.mutable.ArrayBuffer.empty[(Long, BigInt, BigInt)]
    val snapshots = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, BigInt)]
    io.rst #= true; io.time #= now
    io.cmd.valid #= false; io.cmd.address #= 0; io.cmd.data #= 0
    initSetup(io.setup)
    clock.before = () => {
      io.time #= now
      if (!io.rst.toBoolean && io.status.pa_we.toBoolean)
        snapshots += ((if (io.status.pa_ch.toBoolean) 1 else 0, io.status.pa_addr.toInt, io.status.pa_data.toBigInt))
    }
    clock.after = () => {
      if (!io.rst.toBoolean && io.dac_valid.toBigInt != 0) {
        assert(io.dac_valid.toBigInt == ((BigInt(1) << dut.p.channels) - 1))
        samples += ((now, io.dac_x.toBigInt, io.dac_y.toBigInt))
      } else {
        Predef.assert(io.dac_x.toBigInt == 0 && io.dac_y.toBigInt == 0, "off intervals must drive zeros")
      }
      now = (now + 1) & 0xffffffffL
      io.time #= now
    }
    clock.tick(20); io.rst #= false; clock.tick(10)
    val write: (Int, BigInt) => Unit = writer(io.cmd, clock)
    def fire(time: Long): Unit = { write(AodRegisterMap.startTime, BigInt(time & 0xffffffffL)); write(AodRegisterMap.fire, 0) }
    def advance(time: Long): Unit = clock.until(s"time $time", 20000)(now == (time & 0xffffffffL))
    def finish(count: Int): Unit = clock.until("all scheduled jobs completed", 20000)(io.schedule.completed.toBigInt == count && io.schedule.idle.toBoolean)
    def clear(): Unit = { write(AodRegisterMap.clearSticky, 0); assert(io.schedule.errors.toInt == 0) }
  }

  def reference(variant: Int): Vector[(BigInt, BigInt)] = {
    var result = Vector.empty[(BigInt, BigInt)]
    engine.doSim(s"reference-$variant", seed = 42) { dut =>
      val d = new EngineDriver(dut); val io = dut.io; val clock = d.clock
      val p = dut.p
      io.control.ratio #= (if (variant == 1) 9 else 8)
      io.control.dec #= (if (variant == 1) 4 else 1)
      io.control.gcal_en #= 0
      io.control.tone_en #= (if (variant == 2) 5 else 15)
      for (axis <- 0 until p.channels) {
        io.control.amp_scale(axis) #= (12000 + variant * 2000 + axis * 500)
        io.control.gcal_base(axis) #= (1234 + variant)
        for (tone <- 0 until p.tones)
          d.tone(axis, tone, BigInt(0x01000000L + variant * 0x70000L + tone * 0x100000L + axis * 0x300000L),
            if (variant == 1) -0x100000 else 0x200000,
            BigInt(variant * 0x10000000L + axis * 0x2000000L + tone * 0x3000000L),
            0x6000 + variant, 0x7000 + variant)
      }
      d.settled(); clock.tick(80); d.launch()
      clock.until("reference samples", 200)(io.dac_valid.toInt != 0)
      while (io.dac_valid.toInt != 0) {
        assert(io.dac_valid.toInt == 3)
        result :+= ((io.dac_x.toBigInt, io.dac_y.toBigInt)); clock.tick()
      }
    }
    assert(result.nonEmpty)
    result
  }

  val tests = Tests {
    test("immutableJobs") {
      val a = reference(0); val b = reference(1)
      assert(a != b)
      scheduled2.doSim("immutableJobs", seed = 42) { dut =>
        val d = new Driver(dut)
        stage(geometry(), d.write, 0); d.fire(500)
        stage(geometry(), d.write, 1); d.fire(1800)
        d.advance(550)
        assert(d.io.schedule.active.toBoolean && d.io.schedule.queued.toInt == 1)
        stage(geometry(), d.write, 2) // changes neither the current nor queued job
        d.write(AodRegisterMap.control, 0x3f) // no immediate go/CW/reset/trigger bypass
        d.advance(1200)
        assert(d.io.schedule.completed.toBigInt == 1)
        assert(d.io.schedule.queued.toInt == 1 && !d.io.schedule.idle.toBoolean)
        // Repeat the same decimated shot; automatic reset must remove inherited
        // idle-state trajectory drift, and phase0 must reload each time.
        stage(geometry(), d.write, 1); d.fire(3000); d.fire(4200); d.fire(5400)
        d.finish(5) // wraps the snapshot-slot ring while other entries remain queued
        val bursts = d.samples.foldLeft(Vector.empty[Vector[(Long, BigInt, BigInt)]]) { (acc, s) =>
          if (acc.isEmpty || s._1 != acc.last.last._1 + 1) acc :+ Vector(s)
          else acc.init :+ (acc.last :+ s)
        }
        assert(bursts.map(_.head._1) == Vector(500L, 1800L, 3000L, 4200L, 5400L))
        assert(bursts.map(_.map(s => (s._2, s._3))) == Vector(a, b, b, b, b))
        assert(d.io.schedule.errors.toInt == 0)
        assert(d.io.schedule.accepted.toBigInt == 5 && d.io.schedule.started.toBigInt == 5)
        assert(d.snapshots.size == 5 * 2 * 2)
      }
    }
    test("errorsAndReset") {
      scheduled2.doSim("errorsAndReset", seed = 42) { dut =>
        val d = new Driver(dut)
        stage(geometry(), d.write, 0)
        for (t <- Seq(1500L, 2500L, 3500L, 4500L)) d.fire(t)
        assert(d.io.schedule.queued.toInt == 4 && !d.io.schedule.can_enqueue.toBoolean)
        d.fire(5500); assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.overflow)); d.clear()
        d.io.rst #= true; d.clock.tick(12); d.io.rst #= false; d.clock.tick(10)
        assert(d.io.schedule.queued.toInt == 0 && d.io.schedule.idle.toBoolean)
        assert(d.io.schedule.accepted.toBigInt == 0 && d.io.schedule.errors.toInt == 0)
        stage(geometry(), d.write, 0)
        d.fire(d.now + AodTop.minimumEnqueueLead(geometry()) - 1)
        assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.late)); d.clear()
        d.write(AodRegisterMap.ratio, 0); d.fire(1800)
        assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.invalid)); d.clear()
        d.write(AodRegisterMap.ratio, 8)
        d.fire(1800); d.fire(1700)
        assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.order)); d.clear()
        d.fire(1820) // accepted into FIFO, but preparation overlaps first shot
        d.finish(1)
        assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.overlap))
        assert(d.io.schedule.accepted.toBigInt == 2 && d.io.schedule.started.toBigInt == 1)
        assert(d.samples.head._1 == 1800)
        assert(d.samples.size == 48 * 8)
        d.clear(); d.fire(3000); d.fire(3500)
        d.advance(3010)
        assert(d.io.dac_valid.toInt == 3 && d.io.schedule.queued.toInt == 1)
        d.io.rst #= true; d.clock.tick(12); d.io.rst #= false; d.clock.tick(1000)
        assert(d.io.schedule.idle.toBoolean && d.io.schedule.started.toBigInt == 0)
        assert(d.io.dac_valid.toInt == 0 && d.io.schedule.queued.toInt == 0)
      }
    }
    test("setupLockAndLateDispatch") {
      scheduled2.doSim("setupLockAndLateDispatch", seed = 42) { dut =>
        val d = new Driver(dut)
        stage(geometry(), d.write, 0); d.fire(600)
        d.io.setup.valid #= true
        d.io.setup.table #= AodTable.gain; d.io.setup.data #= 0x12345678
        sleep(1); assert(!d.io.setup.ready.toBoolean)
        d.advance(650)
        assert(d.io.schedule.queued.toInt == 0 && d.io.schedule.active.toBoolean)
        assert(!d.io.setup.ready.toBoolean)
        d.finish(1); sleep(1)
        assert(d.io.setup.ready.toBoolean)
        d.clock.tick(); d.io.setup.valid #= false
        assert(d.io.setup_busy.toBoolean)
        d.fire(1800)
        assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.setupConflict)); d.clear()
        d.clock.tick(10); d.fire(1800)
        // Discontinuous external time is outside the playback contract; an
        // already queued job that becomes overdue is dropped, never played late.
        d.now = 1900; d.clock.tick(10)
        assert(d.io.schedule.errors.toInt == (1 << AodScheduleError.late))
        assert(d.io.schedule.started.toBigInt == 1 && d.io.schedule.queued.toInt == 0)
      }
    }
    test("wrapAndMinimumLead") {
      scheduled4.doSim("wrapAndMinimumLead", seed = 42) { dut =>
        val p = geometry(4, 1)
        val d = new Driver(dut, 0xffffffa0L)
        stage(p, d.write, 0)
        val start = (d.now + AodTop.minimumEnqueueLead(p) + 1) & 0xffffffffL
        d.fire(start) // startTime write then fire: exactly minimum lead at fire
        val nextStart = (start + AodTop.conservativeStartSpacing(p, 8)) & 0xffffffffL
        d.fire(nextStart)
        d.finish(2)
        assert(d.samples.head._1 == start)
        assert(d.samples(48 * 8)._1 == nextStart)
        assert(d.samples.size == 2 * 48 * 8 && d.io.schedule.errors.toInt == 0)
        assert(d.snapshots.size == 8)
      }
    }
    test("snapshotStorageGeometry") {
      // Full-size descriptors exceed the default packed-signal width limit.
      // Exercise banked storage without increasing Spinal's global limit.
      for ((tones, channels, depth) <- Seq((32, 2, 4), (64, 2, 3), (2, 1, 1))) {
        val directory = workspace.resolve(s"scheduled-$tones-$channels-$depth")
        SpinalConfig(targetDirectory = directory.toString).generateVerilog(
          new AodTop(geometry(tones, channels), depth))
      }
    }
  }
}
