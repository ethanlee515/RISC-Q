package riscq.aod

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.JavaConverters._
import spinal.core._
import spinal.core.sim._

/** Test-only bindings to the sibling checkout. No AOD RTL is copied or changed. */
object Reference {
  implicit class PathOps(val path: Path) extends AnyVal {
    def /(child: String): Path = path.resolve(child)
  }
  def read(path: Path): String = new String(Files.readAllBytes(path), UTF_8)
  def lines(path: Path): Vector[String] = Files.readAllLines(path, UTF_8).asScala.toVector

  val root = Paths.get(sys.env("AOD_REFERENCE_ROOT"))
  val testRoot = Paths.get(sys.env("AOD_TEST_ROOT"))
  val rtl = root / "rearrange" / "rtl"
  val golden = root / "rearrange" / "roms_norm_400"
  val allRtl = Seq("aod_tg_top.sv", "aod_tg_regs.sv", "aod_tg_bank.sv",
    "aod_tg_tone1.sv", "aod_gcal.sv", "aod_tg_round.sv", "cordicg3.sv", "reg_delay1.v")
  val mask: BigInt = (BigInt(1) << 32) - 1
  def word(v: BigInt): BigInt = v & mask
  // The standalone --binary runner starts otherwise-uninitialized registers at
  // zero. SpinalSim normally randomizes them, including unreset write-enable
  // distribution registers that can corrupt initialized tables during reset.
  def config = SimConfig.withVerilator.addSimulatorFlag("-Wno-fatal")
    .addSimulatorFlag("--x-initial 0")
    .workspacePath((testRoot / "simWorkspace").toString)

  def bind(box: BlackBox, name: String, files: Seq[String]): Unit = {
    box.setDefinitionName(name)
    box.noIoPrefix()
    files.foreach(f => box.addRTLPath((rtl / f).toString))
  }
  def memories(box: BlackBox, dir: Path): Unit = {
    for (file <- Seq("aod_unit.mem", "aod_seed.mem")) {
      require(Files.isRegularFile(dir / file), s"Missing reference memory: ${dir / file}")
    }
    box.addGeneric("UNIT_FILE", (dir / "aod_unit.mem").toString)
    box.addGeneric("SEED_FILE", (dir / "aod_seed.mem").toString)
  }

  /** Drive only with clk low, sample after the rising edge has settled. */
  class Clock(clk: Bool) {
    var cycles = 0L
    var before: () => Unit = () => ()
    var after: () => Unit = () => ()
    clk #= false
    def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
      sleep(1)
      before()
      clk #= true
      sleep(5)
      cycles += 1
      after()
      clk #= false
      sleep(4)
    }
    def until(label: String, limit: Int = 200000)(p: => Boolean): Unit = {
      var left = limit
      while (!p && left > 0) { tick(); left -= 1 }
      assert(p, s"Timeout waiting for $label after $limit clocks")
    }
  }
}

import Reference._

class CordicPorts extends Bundle {
  val clk = in Bool()
  val xin, yin = in SInt(20 bits)
  val phasein = in UInt(23 bits)
  val gin = in Bool()
  val xout, yout = out SInt(20 bits)
  val phaseout = out UInt(23 bits)
  val error, gout = out Bool()
}
class CordicBox extends BlackBox {
  val io = new CordicPorts
  Reference.bind(this, "cordicg", Seq("cordicg3.sv", "reg_delay1.v"))
  Seq("OPIN" -> 0, "WIDTH" -> 20, "NSTAGE" -> 22, "NORMALIZE" -> 0,
    "BUFIN" -> 1, "GW" -> 1).foreach { case (k, v) => addGeneric(k, v) }
}
class CordicDut extends Component {
  val io = new CordicPorts
  val ref = new CordicBox
  io <> ref.io
}

class GcalPorts(tabn: Int, halves: Int) extends Bundle {
  val clk, half, cfg_en, tab_we = in Bool()
  val cfg_base, inc = in UInt(32 bits)
  val cfg_zoom = in UInt(2 bits)
  val tab_addr = in UInt(log2Up(tabn * halves) bits)
  val tab_wdata = in Bits(32 bits)
  val cal_now = in UInt(16 bits)
  val cal_eff = out UInt(16 bits)
}
class GcalBox(tabn: Int, halves: Int) extends BlackBox {
  val io = new GcalPorts(tabn, halves)
  Reference.bind(this, "aod_gcal", Seq("aod_gcal.sv"))
  Seq("ACC" -> 32, "CALW" -> 16, "CAL_FRAC" -> 15, "TABN" -> tabn,
    "IDXW" -> log2Up(tabn), "FRACW" -> 8, "HALVES" -> halves)
    .foreach { case (k, v) => addGeneric(k, v) }
}
class GcalDut(tabn: Int, halves: Int) extends Component {
  val io = new GcalPorts(tabn, halves)
  val ref = new GcalBox(tabn, halves)
  io <> ref.io
}

class TonePorts extends Bundle {
  val clk, rst, go, cw, en, rom_we, gcal_we, gcal_en = in Bool()
  val d_word = in SInt(32 bits)
  val a_word, phase0, gcal_base = in UInt(32 bits)
  val ratio = in UInt(10 bits)
  val dec = in UInt(5 bits)
  val cal_a, cal_b, amp_scale = in UInt(16 bits)
  val rom_sel = in UInt(3 bits)
  val rom_addr = in UInt(7 bits)
  val rom_beat, gcal_zoom = in UInt(2 bits)
  val rom_wdata = in Bits(32 bits)
  val gcal_addr = in UInt(8 bits)
  val busy, done, dac_valid, go_drop = out Bool()
  val phi_acc = out UInt(32 bits)
  val dac, dac_q = out SInt(16 bits)
  val probe_inc, probe_phi = out UInt(32 bits)
  val probe_osc, probe_run, probe_end = out Bool()
}
class ToneBox extends BlackBox {
  val io = new TonePorts
  Reference.bind(this, "aod_tone_probe", Reference.allRtl)
  addRTLPath((Reference.testRoot / "rtl" / "aod_tone_probe.sv").toString)
  Reference.memories(this, Reference.golden)
}
class ToneDut extends Component {
  val io = new TonePorts
  val ref = new ToneBox
  io <> ref.io
}

class TopPorts extends Bundle {
  val clk, rst, trig_in = in Bool()
  val s_axi_awaddr, s_axi_araddr = in UInt(16 bits)
  val s_axi_awprot, s_axi_arprot = in Bits(3 bits)
  val s_axi_awvalid, s_axi_wvalid, s_axi_bready, s_axi_arvalid, s_axi_rready = in Bool()
  val s_axi_wdata = in Bits(32 bits)
  val s_axi_wstrb = in Bits(4 bits)
  val s_axi_awready, s_axi_wready, s_axi_bvalid, s_axi_arready, s_axi_rvalid = out Bool()
  val s_axi_bresp, s_axi_rresp = out Bits(2 bits)
  val s_axi_rdata, dac_x, dac_y = out Bits(32 bits)
  val probe_ch = in Bool()
  val probe_tone = in UInt(6 bits)
  val probe_addr = in UInt(8 bits)
  val probe_table, probe_urom, probe_srom, probe_base = out UInt(32 bits)
  val probe_zoom = out UInt(2 bits)
  val probe_enable = out Bool()
  val probe_index = out UInt(8 bits)
  val probe_gain, probe_ce_gain = out UInt(16 bits)
}
class TopBox(nt: Int, roms: Path, nch: Int = 2) extends BlackBox {
  val io = new TopPorts
  Reference.bind(this, "aod_top_probe", Reference.allRtl)
  addRTLPath((Reference.testRoot / "rtl" / "aod_top_probe.sv").toString)
  addGeneric("NT", nt)
  addGeneric("NCH", nch)
  Reference.memories(this, roms)
}
class TopDut(nt: Int, roms: Path, nch: Int = 2) extends Component {
  val io = new TopPorts
  val ref = new TopBox(nt, roms, nch)
  io <> ref.io
}

class ToneDriver(val io: TonePorts) {
  import Reference._
  val clock = new Clock(io.clk)
  io.rst #= true
  io.go #= false; io.cw #= false; io.en #= true
  io.d_word #= 0; io.a_word #= 0; io.phase0 #= 0
  io.ratio #= 8; io.dec #= 1
  io.cal_a #= 32768; io.cal_b #= 32768; io.amp_scale #= 65535
  io.rom_we #= false; io.rom_sel #= 0; io.rom_addr #= 0
  io.rom_beat #= 0; io.rom_wdata #= 0
  io.gcal_we #= false; io.gcal_addr #= 0; io.gcal_base #= 0
  io.gcal_zoom #= 2; io.gcal_en #= true
  clock.tick(8); io.rst #= false; clock.tick(8)
  def launch(): Unit = {
    io.go #= true; clock.tick(); io.go #= false
    clock.until("tone busy")(io.busy.toBoolean)
  }
  def finish(): Unit = {
    clock.until("tone idle")( !io.busy.toBoolean )
    clock.tick(40) // drain CORDIC before changing parameters or capturing a new shot
    assert(!io.dac_valid.toBoolean, "pulsed output must drain")
  }
  def endpoint(delta: Int): Unit = {
    io.d_word #= delta; clock.tick(4); launch(); finish()
    assert(io.probe_inc.toBigInt == word(io.a_word.toBigInt + delta), s"endpoint delta=$delta")
  }
}
