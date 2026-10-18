package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.aod._
import riscq.soc.{AodSoc, AodSocConfig, AodSocMap}
import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer
import scala.sys.process._

/** All setup/program writes enter through host AXI; the real CPU is the sole
  * runtime writer. Compare every X/Y sample with independently programmed native
  * AodTop jobs. No CPU data-bus tap or backdoor memory loading.
  * Requires the RISC-V clang toolchain on PATH; see docs/soc/AodSoc.md.
  */
object AodSocCpuSim extends App {
  val p = AodConfig.default.copy(tones = 2, moveSteps = 48, ratioMax = 64)
  val cfg = AodSocConfig(aod = p, queueDepth = 2)
  val dir = Paths.get("out/aod-soc-cpu")
  val fixture = ujson.read(new String(Files.readAllBytes(Paths.get("software/configs/aod-sim.json")), "UTF-8"))
  require(fixture == AodSocMap.manifest(cfg), "regenerate the AOD software sim manifest from the hardware map")
  Files.createDirectories(dir)
  Files.write(dir.resolve("aod-soc.json"), ujson.write(AodSocMap.manifest(cfg)).getBytes("UTF-8"))
  val compileScript = """
from pathlib import Path
import json
from riscq.aod import AodMap
m = AodMap.load('out/aod-soc-cpu/aod-soc.json')
image = m.compile(Path('software/examples/aod_moves.c').read_text())
Path('out/aod-soc-cpu/program.bin').write_bytes(image.data)
Path('out/aod-soc-cpu/symbols.json').write_text(json.dumps(image.symbols))
"""
  require(Process(Seq("python3", "-c", compileScript), None,
    "PYTHONPATH" -> "software").! == 0, "AOD firmware compilation failed")
  val image = Files.readAllBytes(dir.resolve("program.bin"))
  val symbols = ujson.read(new String(Files.readAllBytes(dir.resolve("symbols.json")), "UTF-8"))
  val mask = (BigInt(1) << 32) - 1
  // Distinct host-uploaded tables; these differ from the packaged elaboration ROMs.
  val tables = for (table <- 0 until 2; row <- 0 until p.segments; beat <- 0 until 3) yield {
    val value = if (table == 0) Seq(BigInt(0x10000000), BigInt(0x10000), BigInt(0x100))(beat)
                else ((BigInt("400000000", 16) + row * 0x100000L) >> (beat * 32)) & mask
    (table, 0, row, beat, value)
  }
  val gains = for (axis <- 0 until p.channels; row <- 0 until p.gcalEntries)
    yield (2, axis, row, 0, BigInt(0x7000 + axis * 0x800))
  val coefficients = tables ++ gains
  def stage(write: (Int, BigInt) => Unit, variant: Int): Unit = {
    write(AodRegisterMap.ratio, 32 + (variant & 1))
    write(AodRegisterMap.decimation, if ((variant & 1) != 0) 4 else 1)
    for (axis <- 0 until p.channels) {
      val base = AodRegisterMap.channel(axis)
      write(base, 3); write(base + 4, 0)
      write(base + 8, 12000 + variant * 2000 + axis * 500)
      write(base + 20, 1); write(base + 24, 0)
      for (tone <- 0 until p.tones) {
        val fields = Seq(BigInt(0x01000000L + variant * 0x70000L + tone * 0x100000L + axis * 0x300000L),
          BigInt(if ((variant & 1) != 0) -0x100000 else 0x200000),
          BigInt(variant * 0x10000000L + axis * 0x2000000L + tone * 0x3000000L),
          BigInt("70006000", 16) + variant * 0x10001)
        for (field <- 0 until 4) write(AodRegisterMap.tone(axis, tone, field), fields(field))
      }
    }
  }
  def simulation = SimConfig.withVerilator.addSimulatorFlag("-Wno-fatal").addSimulatorFlag("--x-initial 0")
  val reference = simulation.compile(new AodTop(p))
  val expected = (0 until 3).map { variant =>
    val samples = ArrayBuffer.empty[(BigInt, BigInt)]
    reference.doSim(s"native-$variant", seed = 42) { d =>
      var time = 0L
      d.io.clk #= false; d.io.rst #= true; d.io.time #= 0
      d.io.cmd.valid #= false; d.io.cmd.address #= 0; d.io.cmd.data #= 0
      d.io.setup.valid #= false; d.io.setup.table #= 0; d.io.setup.channel #= false
      d.io.setup.address #= 0; d.io.setup.beat #= 0; d.io.setup.data #= 0
      def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
        d.io.clk #= false; sleep(5); d.io.clk #= true; sleep(5)
        if (d.io.dac_valid.toInt != 0) samples += ((d.io.dac_x.toBigInt, d.io.dac_y.toBigInt))
        time += 1; d.io.time #= (time & 0xffffffffL)
      }
      tick(20); d.io.rst #= false; tick(20)
      for ((table, axis, row, beat, value) <- coefficients) {
        d.io.setup.table #= table; d.io.setup.channel #= (axis != 0)
        d.io.setup.address #= row; d.io.setup.beat #= beat; d.io.setup.data #= value
        while (!d.io.setup.ready.toBoolean) tick()
        d.io.setup.valid #= true; tick(); d.io.setup.valid #= false
      }
      while (d.io.setup_busy.toBoolean) tick()
      def write(address: Int, data: BigInt): Unit = {
        d.io.cmd.valid #= true; d.io.cmd.address #= address; d.io.cmd.data #= (data & mask)
        tick(); d.io.cmd.valid #= false
      }
      stage(write, variant)
      write(AodRegisterMap.startTime, time + 256); write(AodRegisterMap.fire, 1)
      tick(2400)
      assert(d.io.schedule.completed.toBigInt == 1 && d.io.schedule.errors.toInt == 0)
      assert(samples.nonEmpty && samples.exists(_._1 != 0) && samples.exists(_._2 != 0))
    }
    samples.toVector
  }

  class ObservedSoc extends AodSoc(cfg) {
    Fiber build new Area {
      dsp.time.simPublic()
      aod.io.schedule.simPublic()
      aod.io.cmd.simPublic()
    }
  }
  val compiled = simulation.compile(new ObservedSoc)
  for ((hp, dp) <- Seq((14, 10), (10, 18))) compiled.doSim(s"cpu-$hp-$dp", seed = 42) { d =>
    val hc = d.hostCd; val dc = d.dspCd
    d.io.axi.ar.valid #= false; d.io.axi.aw.valid #= false; d.io.axi.w.valid #= false
    d.io.axi.r.ready #= false; d.io.axi.b.ready #= false
    hc.forkStimulus(hp); dc.forkStimulus(dp)
    val memory = HostMemModel(d.io.hostMem, hc)
    SimTimeout(20000000)
    val samples = ArrayBuffer.empty[(Long, BigInt, BigInt)]
    var changedWhileActive = false
    dc.onSamplings {
      if (!d.io.dspRst.toBoolean) {
        if (d.io.dac_valid.toInt != 0) {
          assert(d.io.dac_valid.toInt == 3, "axes must remain aligned")
          samples += ((d.dsp.time.toLong, d.io.dac_x.toBigInt, d.io.dac_y.toBigInt))
        } else assert(d.io.dac_x.toBigInt == 0 && d.io.dac_y.toBigInt == 0)
        if (d.aod.io.cmd.valid.toBoolean && d.aod.io.schedule.active.toBoolean &&
            d.aod.io.cmd.address.toInt == AodRegisterMap.ratio) changedWhileActive = true
      }
    }
    hc.waitSampling(60)
    val axi = Axi4Master(d.io.axi, hc)
    def write(address: Long, value: BigInt): Unit =
      axi.write(BigInt(address), List.tabulate(4)(i => ((value >> (i * 8)) & 255).toByte))
    def read(address: Long): Long = axi.read(BigInt(address), 4).zipWithIndex
      .foldLeft(0L) { case (v, (b, i)) => v | ((b & 255L) << (i * 8)) }
    val control = AodSocMap.control; val setup = AodSocMap.setup
    def stop(): Unit = {
      write(control + AodSocMap.cpuReset, 1)
      var guard = 0
      while (read(control + AodSocMap.stopped) == 0 && guard < 100) guard += 1
      assert(read(control + AodSocMap.stopped) == 1)
      write(setup + AodHostMap.reset, 1)
      assert(read(setup + AodHostMap.reset) == 0)
      assert(read(control + AodSocMap.done) == 0)
    }
    assert(read(control + AodSocMap.cpuReset) == 1, "CPU must power up held")
    stop()
    assert(read(setup + AodHostMap.capabilities) == 0x01020201L)
    for (i <- image.indices by 4) {
      val word = (0 until 4).foldLeft(BigInt(0))((v, b) =>
        v | (BigInt(if (i + b < image.length) image(i + b) & 255 else 0) << (8 * b)))
      write(AodSocMap.program + i, word)
      assert(read(AodSocMap.program + i) == word.toLong)
    }
    for ((table, axis, row, beat, value) <- coefficients) {
      val addr = if (table == 2) AodHostMap.calibration(axis, row) else AodHostMap.profile(table, row, beat)
      write(setup + addr, value)
    }
    val resultBase = BigInt("840000000", 16)
    write(control + AodSocMap.hostBaseLo, resultBase & mask)
    write(control + AodSocMap.hostBaseHi, (resultBase >> 32) | (BigInt(1) << 31))
    for (run <- 0 until 2) {
      samples.clear(); changedWhileActive = false
      val before = d.dsp.time.toLong
      write(control + AodSocMap.cpuReset, 0)
      var guard = 0
      while (read(control + AodSocMap.done) == 0 && guard < 10000) { hc.waitSampling(200); guard += 1 }
      assert(read(control + AodSocMap.done) == 1, "CPU did not complete")
      assert((read(setup + AodHostMap.state) & 7) == 4)
      assert(read(setup + AodHostMap.diagnostics) == 0)
      assert(changedWhileActive, "test must modify staging during playback")
      val startAddr = symbols("starts")(0).num.toLong - 0x80000000L + AodSocMap.program
      val starts = (0 until 7).map(i => read(startAddr + i * 4))
      assert(starts.head > before)
      assert(samples.size == (0 until 7).map(i => expected(i % 3).size).sum)
      var offset = 0
      for (i <- 0 until 7) {
        val want = expected(i % 3)
        val got = samples.slice(offset, offset + want.size)
        assert(got.head._1 == starts(i), s"job $i timestamp ${got.head._1}, expected ${starts(i)}")
        assert(got.map(s => (s._2, s._3)).toVector == want, s"job $i waveform differs from native AOD")
        assert(got.zipWithIndex.forall { case (s, k) => s._1 == starts(i) + k })
        offset += want.size
      }
      hc.waitSampling(100) // result writes are posted, as in superconducting RISC-Q
      assert(memory.writes.size == run + 1)
      assert(memory.writes.last == ((resultBase, BigInt(starts.last), BigInt(15))))
      val oldTime = d.dsp.time.toLong
      stop()
      assert(d.dsp.time.toLong > oldTime, "per-run reset must preserve shared time")
    }
    // Abort with an actual future job queued; flush both queue and posted pipeline.
    write(control + AodSocMap.cpuReset, 0)
    var guard = 0
    while (d.aod.io.schedule.queued.toInt == 0 && guard < 10000) { dc.waitSampling(); guard += 1 }
    assert(d.aod.io.schedule.queued.toInt != 0)
    stop()
    val count = samples.size
    dc.waitSampling(6000)
    assert(d.aod.io.schedule.idle.toBoolean && samples.size == count)
    println(s"[AodSocCpuSim] PASS clocks $hp/$dp: two runs, seven jobs each; exact X/Y samples/time, pacing, setup, reset/abort")
  }
}
