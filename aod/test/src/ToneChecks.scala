package riscq.aod

import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

object ToneChecks {
  import Reference._
  def pulsed(dut: ToneDut): Unit = {
    val d = new ToneDriver(dut.io)
    val io = dut.io; val clock = d.clock
    val expected = lines(golden / "aod_tone1_exp.mem")
      .filter(_.trim.nonEmpty).map { line =>
        val raw = Integer.parseInt(line.trim, 16)
        if (raw >= 32768) raw - 65536 else raw
      }
    assert(expected.length == 20000)
    io.d_word #= BigInt("0083126f", 16) * 23
    io.a_word #= 751619277
    clock.tick(4); d.launch()
    var n = 0; var worst = 0; var waited = 0
    while (n < expected.length && waited < 100000) {
      if (io.dac_valid.toBoolean) {
        val err = math.abs(io.dac.toInt - expected(n))
        assert(err <= 4, s"Golden sample $n got=${io.dac.toInt} expected=${expected(n)} error=$err")
        worst = math.max(worst, err); n += 1
      }
      clock.tick(); waited += 1
    }
    assert(n == expected.length, s"Captured only $n golden samples")
    d.finish()
    assert(io.probe_inc.toBigInt == word(io.a_word.toBigInt + io.d_word.toBigInt))
    println(s"Pulsed: $n golden samples, max deviation $worst LSB")
    for (delta <- Seq(1, -1, 7919, -104729, 8589935, 8589934, 193273528, -193273528))
      d.endpoint(delta)

    def capture(ratio: Int): Seq[(Int, Int)] = {
      io.d_word #= 7919; io.a_word #= BigInt("20000000", 16); io.ratio #= ratio
      clock.tick(4); d.launch()
      val samples = ArrayBuffer.empty[(Int, Int)]
      var valid = 0
      var elapsed = 0
      while (samples.size < 3000 && elapsed < 100000) {
        if (io.dac_valid.toBoolean) {
          if (valid >= 20000) samples += ((io.dac.toInt, io.dac_q.toInt))
          valid += 1
        }
        clock.tick(); elapsed += 1
      }
      assert(samples.size == 3000, "rate clamp capture must fill")
      d.finish()
      samples.take(3000).toVector
    }
    assert(capture(4) == capture(7), "ratio below floor must match ratio=7 sample for sample")
    for (dec <- Seq(1, 2, 4, 8)) {
      io.d_word #= 193273528; io.a_word #= BigInt("10000000", 16)
      io.ratio #= 16; io.dec #= dec
      clock.tick(4); d.launch()
      val start = clock.cycles
      clock.until(s"dec=$dec sweep completion")( !io.busy.toBoolean )
      val elapsed = clock.cycles - start
      assert(elapsed == ((6250 + dec - 1) / dec) * 16, s"dec=$dec duration=$elapsed")
      d.finish()
      assert(io.probe_inc.toBigInt == word(io.a_word.toBigInt + 193273528))
    }
  }

  def indices(dut: ToneDut): Unit = {
    val d = new ToneDriver(dut.io)
    val io = dut.io; val clock = d.clock
    for (seg <- 0 until 64) {
      io.rom_sel #= 5; io.rom_addr #= seg
      for ((v, beat) <- Seq(seg * 98, 1, 0).zipWithIndex) {
        io.rom_beat #= beat; io.rom_wdata #= v; io.rom_we #= true; clock.tick()
      }
      io.rom_we #= false; clock.tick()
    }
    io.ratio #= 16; io.d_word #= BigInt(1) << 30
    // The original bench only prints these. Assert the measured reference values.
    // Preserve its cadence: 8 clocks after observing idle, then 6 setup clocks
    // before the next launch. At dec>1 the reference leaves dbusy set at the
    // boundary, so idle time affects epos before the following launch. A longer
    // generic pipeline-drain wait changes this diagnostic's stimulus and result.
    for ((dec, index) <- Seq(1 -> 6250, 2 -> 6248, 4 -> 6240, 8 -> 6230, 16 -> 6208)) {
      io.dec #= dec; clock.tick(6); d.launch()
      clock.until("index sweep completion")( !io.busy.toBoolean )
      clock.tick(8)
      println(s"Index: dec=$dec adopted=${io.probe_inc.toBigInt}")
      assert(io.probe_inc.toBigInt == index, s"dec=$dec adopted index=${io.probe_inc.toBigInt} expected=$index")
    }
  }

  def cw(dut: ToneDut): Unit = {
    val d = new ToneDriver(dut.io)
    val io = dut.io; val clock = d.clock
    io.d_word #= 193273528; io.phase0 #= BigInt("12345678", 16)
    io.amp_scale #= 32768; io.ratio #= 17; io.dec #= 16
    var armed = false; var oldOsc = false; var oldPhi = BigInt(0); var oldInc = BigInt(0)
    var streaming = false; var watched = 0; var seams = 0; var dwell = 0
    val dwells = ArrayBuffer.empty[Int]
    clock.before = () => {
      oldOsc = io.probe_osc.toBoolean
      oldPhi = io.probe_phi.toBigInt; oldInc = io.probe_inc.toBigInt
      if (io.probe_end.toBoolean) seams += 1
    }
    clock.after = () => {
      if (armed && oldOsc) {
        assert(io.probe_phi.toBigInt == word(oldPhi + oldInc), "CW accumulator phase step")
        watched += 1
        if (streaming) assert(io.dac_valid.toBoolean, "CW output sample gap")
        if (io.dac_valid.toBoolean) streaming = true
      } else if (!oldOsc) streaming = false
      if (armed && io.probe_osc.toBoolean && !io.probe_run.toBoolean) dwell += 1
      else if (dwell > 0) { dwells += dwell; dwell = 0 }
    }
    def nextSeam(): Unit = {
      clock.tick()
      clock.until("CW seam", 8000)(io.probe_end.toBoolean)
    }
    def measure(a: BigInt): BigInt = {
      io.a_word #= a; io.cw #= true; clock.tick(8); d.launch()
      clock.tick(64); armed = true
      for (_ <- 0 until 3) nextSeam()
      clock.tick(4)
      io.phi_acc.toBigInt
    }
    def stop(): Unit = {
      io.cw #= false
      clock.until("CW stop", 8000)( !io.busy.toBoolean )
      clock.tick(40)
    }
    val q0 = measure(0); stop()
    val q1 = measure(1); stop()
    val period = word(q1 - q0)
    assert(period == ((6250 + 15) / 16) * 17 + 22, s"CW period=$period")
    val shift = period.lowestSetBit
    val divisor = BigInt(1) << shift
    assert(q0 % divisor == 0, "CW periodicity must be solvable")
    val modulus = BigInt(1) << (32 - shift)
    val solution = ((-q0 / divisor) * (period / divisor).modInverse(modulus)).mod(modulus)
    assert(measure(solution) == 0, "Solved CW phi_acc must be exactly zero")
    def capture(): Vector[(Int, Int)] = {
      assert(io.probe_end.toBoolean, "CW capture must start at a seam")
      clock.tick()
      val samples = ArrayBuffer.empty[(Int, Int)]
      while (!io.probe_end.toBoolean && samples.size < 8000) {
        samples += ((io.dac.toInt, io.dac_q.toInt)); clock.tick()
      }
      assert(samples.size == period.toInt - 1, s"CW capture length=${samples.size}")
      samples.toVector
    }
    nextSeam()
    val first = capture(); val second = capture()
    assert(first == second, "Solved consecutive CW sweeps must be bit identical in I and Q")
    stop(); armed = false
    assert(watched > 10000 && seams >= 6 && dwells.nonEmpty, "CW monitors must cross multiple seams")
    assert(dwells.forall(_ == 22), s"CW reload dwell lengths=$dwells")
    println(s"CW: period=$period clocks, dwell=22, $watched exact phase advances, ${first.size} identical I/Q samples")
  }
}
