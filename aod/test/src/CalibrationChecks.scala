package riscq.aod

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import spinal.core.sim._

object CalibrationChecks {
  import Reference._

  /** tb_aod_gcal_real's table-load/sweep, using the shipped synthetic curve. */
  def generated(tabn: Int): Unit = {
    val dir = testRoot / "simWorkspace" / "calibration" / s"entries$tabn"
    Files.createDirectories(dir)
    val table = dir / "gcal.mem"
    PythonChecks.run(root / "scripts" / "gen_gcal_table.py",
      Seq("--sweep", (root / "examples" / "example_sweep.txt").toString,
        "--out", table.toString, "--entries", tabn.toString, "--nco", "96"),
      dir / "generate.log")
    val metadata = read(dir / "gcal.json")
    def number(key: String): BigInt = {
      val pattern = ("\"" + key + "\"\\s*:\\s*([0-9]+)").r
      BigInt(pattern.findFirstMatchIn(metadata)
        .getOrElse(throw new AssertionError(s"Missing $key in gcal.json")).group(1))
    }
    val base = number("base_word")
    val zoom = number("zoom").toInt
    assert(number("entries") == tabn && zoom >= 0 && zoom <= 3)
    val entries = lines(table).filter(_.trim.nonEmpty).map(s => BigInt(s.trim, 16))
    assert(entries.size == tabn)
    assert(entries.map(_ & 65535).distinct.size > 1, "fixture must exercise a non-flat calibration")
    val slopes = entries.map(e => (e >> 16).toInt).map(s => if (s >= 32768) s - 65536 else s)
    assert(slopes.exists(_ > 0) && slopes.exists(_ < 0), "fixture must exercise both slope signs")

    val span = BigInt(1) << (32 - zoom - 1)
    val offsets = (0 until 4000).map(i => span * i / 4000) ++
      Seq(mask, span, span + 1, BigInt(1) << 31) // both clamps and the signed wrap
    val dump = dir / "gcal_rtl_dump.txt"
    config.compile(new GcalDut(tabn, 1)).doSim(s"generated-$tabn", seed = 42) { dut =>
      val io = dut.io
      val clock = new Clock(io.clk)
      io.half #= false; io.cfg_en #= true; io.cfg_base #= base; io.cfg_zoom #= zoom
      io.tab_we #= false; io.tab_addr #= 0; io.tab_wdata #= 0
      io.inc #= base; io.cal_now #= 32768
      clock.tick(8)
      for ((entry, addr) <- entries.zipWithIndex) {
        io.tab_addr #= addr; io.tab_wdata #= entry; io.tab_we #= true
        clock.tick(); io.tab_we #= false; clock.tick()
      }
      clock.tick(8)
      val writer = Files.newBufferedWriter(dump, UTF_8)
      try {
        writer.write(s"# w_offset cal_eff (synthetic sweep, TABN=$tabn zoom=$zoom)\n")
        for (offset <- offsets) {
          io.inc #= word(base + offset)
          clock.tick(12)
          writer.write(s"$offset ${io.cal_eff.toInt}\n")
        }
      } finally writer.close()
    }
    val log = PythonChecks.run(root / "rearrange" / "tb" / "check_gcal_real.py",
      Seq(dump.toString, table.toString, "--zoom", zoom.toString), dir / "compare.log")
    assert(log.contains(s"${offsets.size} sweep points") && log.contains("RESULT: PASS"),
      s"Calibration checker did not confirm the complete sweep:\n$log")
    println(log.trim)
  }
}
