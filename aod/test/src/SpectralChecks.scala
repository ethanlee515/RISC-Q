package riscq.aod

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import spinal.core.sim._

object SpectralChecks {
  import TestSupport._
  private val sampleCount = 65536
  private val fsMHz = 400.0
  private val tones = 8
  private val dest = workspace / "spectrum"

  private case class Shot(name: String, mode: String, bb: Double, bb2: Double, count: Int = 1)

  // Match the SV bench's abs/round/sign conversion, including negative words.
  private def frequencyWord(mhz: Double): BigInt = {
    require(math.abs(mhz) < fsMHz / 2)
    val magnitude = BigInt(math.floor(math.abs(mhz) * 4294967296.0 / fsMHz + 0.5).toLong)
    word(if (mhz < 0) -magnitude else magnitude)
  }

  private def capture(compiled: SimCompiled[EngineDut], shot: Shot): Path = {
    Files.createDirectories(dest)
    val dump = dest / s"${shot.name}.txt"
    compiled.doSim(shot.name, seed = 42) { dut =>
      val d = new EngineDriver(dut)
      val io = dut.io; val clock = d.clock
      for (t <- 0 until tones) {
        val f0 = if (shot.mode == "comb")
          shot.bb + (shot.bb2 - shot.bb) * t / math.max(shot.count - 1, 1) else shot.bb
        val f1 = if (shot.mode == "chirp") shot.bb2 else f0
        val a = frequencyWord(f0)
        d.tone(0,t,a,word(frequencyWord(f1) - a), word(BigInt(t) * BigInt("20000000", 16)))
      }
      io.control.tone_en #= (BigInt(1) << shot.count) - 1
      io.control.amp_scale(0) #= 4096
      val chirp = shot.mode == "chirp"
      io.control.ratio #= (if (chirp) 16 else 512)
      io.control.dec #= (if (chirp) 16 else 1)
      clock.tick(128)
      val launchEdge = clock.cycles + 1
      var firstValid = -1L
      clock.after = () => {
        if (firstValid < 0 && io.dac_valid.toBigInt != 0) firstValid = clock.cycles
      }
      d.launch(cw = chirp)
      clock.tick(64)
      assert(firstValid - launchEdge == AodEngine.launchLatency(dut.p))
      clock.after = () => ()
      clock.until("nonzero converter output", 1000)(io.dac_x.toBigInt != 0)
      clock.tick(256)

      // This is the actual final converter word, after summing and rounding.
      val writer = Files.newBufferedWriter(dump, UTF_8)
      try {
        writer.write(s"# fs_mhz=$fsMHz mode=${shot.mode} bb=${shot.bb} bb2=${shot.bb2} ntone=${shot.count} amp=4096 nsamp=$sampleCount\n")
        for (_ <- 0 until sampleCount) {
          writer.write(f"${io.dac_x.toBigInt.toLong}%08x\n")
          clock.tick()
        }
      } finally writer.close()
      assert(!io.status.clip.toBigInt.testBit(0), s"${shot.name}: spectral capture must not clip")
    }
    dump
  }

  private def check(dump: Path, mode: String, extra: Seq[String] = Seq.empty): String = {
    val log = PythonChecks.run(tools / "tg_spectrum.py",
      Seq(dump.toString, "--check", mode) ++ extra,
      dest / s"${dump.getFileName.toString.stripSuffix(".txt")}-$mode.log")
    assert(log.contains("RESULT: PASS"), s"Missing spectral verdict:\n$log")
    println(log.trim)
    log
  }

  def cw(compiled: SimCompiled[EngineDut]): Unit = {
    val dump = capture(compiled, Shot("cw-positive", "cw", 20, 20))
    check(dump, "cw", Seq("--spur-dbc", "-70"))
    check(dump, "mirror", Seq("--mirror-dbc", "-40"))
  }

  def negative(compiled: SimCompiled[EngineDut]): Unit = {
    val dump = capture(compiled, Shot("mirror-negative", "mirror", -20, -20))
    check(dump, "mirror", Seq("--mirror-dbc", "-40"))
  }

  def comb(compiled: SimCompiled[EngineDut]): Unit = {
    val dump = capture(compiled, Shot("comb", "comb", -20, 20, tones))
    val log = check(dump, "comb")
    // The original script only reports comb levels. Require all eight requested
    // peaks and bound their spread (Blackman-window scalloping is <1.5 dB).
    val peaks = "tone\\s+(\\d+)\\s*:\\s*([+-]?[0-9.]+) MHz at\\s*([+-]?[0-9.]+) dBFS".r
      .findAllMatchIn(log).map(m => (m.group(1).toInt, m.group(2).toDouble, m.group(3).toDouble)).toVector
    assert(peaks.map(_._1) == (0 until tones).toVector, "FFT must find every enabled tone")
    for ((i, frequency, _) <- peaks)
      assert(math.abs(frequency - (-20.0 + 40.0 * i / (tones - 1))) < 0.05, s"comb tone $i at $frequency MHz")
    val levels = peaks.map(_._3)
    assert(levels.max - levels.min < 1.5, s"comb tone-level spread=${levels.max - levels.min} dB")
  }

  def chirp(linear: SimCompiled[EngineDut], minjerk: SimCompiled[EngineDut]): Unit = {
    val ramp = capture(linear, Shot("chirp-linear", "chirp", -10, 10))
    val smooth = capture(minjerk, Shot("chirp-minjerk", "chirp", -10, 10))
    val log = check(ramp, "chirp", Seq("--max-ripple", "3", "--compare", smooth.toString))
    def value(pattern: String): Double = pattern.r.findFirstMatchIn(log)
      .getOrElse(throw new AssertionError(s"Missing chirp comparison:\n$log")).group(1).toDouble
    val linearRipple = value("RIPPLE ([0-9.]+) dB")
    val minjerkRipple = value("compare\\s*:\\s*.+ ripple ([0-9.]+) dB")
    assert(linearRipple < minjerkRipple, s"linear chirp ($linearRipple dB) must be flatter than minjerk ($minjerkRipple dB)")
  }
}
