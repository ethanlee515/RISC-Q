package riscq.aod

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.JavaConverters._
import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Local fixtures, simulator configuration, and clock driver. */
object TestSupport {
  implicit class PathOps(val path: Path) extends AnyVal {
    def /(child: String): Path = path.resolve(child)
  }
  def read(path: Path): String = new String(Files.readAllBytes(path), UTF_8)
  def lines(path: Path): Vector[String] = Files.readAllLines(path, UTF_8).asScala.toVector
  def resource(name: String): Path = {
    val url = Option(getClass.getResource("/aod/" + name))
      .getOrElse(throw new IllegalArgumentException(s"Missing AOD test resource: $name"))
    Paths.get(url.toURI)
  }
  val fixtures = resource("fixtures/geometry.properties").getParent
  val tools = resource("tools/gen_tg_profile.py").getParent
  val profiles = AodProfiles.minjerk
  // Mill forks test suites concurrently: isolate builds and generated fixtures.
  val workspace = {
    val base = Paths.get("aod", "simWorkspace").toAbsolutePath
    Files.createDirectories(base)
    Files.createTempDirectory(base, "run-")
  }
  val mask: BigInt = (BigInt(1) << 32) - 1
  def word(v: BigInt): BigInt = v & mask
  // Match the established startup contract for unreset arithmetic/distribution registers.
  def config = SimConfig.withVerilator.addSimulatorFlag("-Wno-fatal")
    .addSimulatorFlag("--x-initial 0").workspacePath(workspace.toString)

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

import TestSupport._

class CordicPorts extends Bundle {
  val clk = in Bool()
  val xin, yin = in SInt(20 bits)
  val phasein = in UInt(23 bits)
  val gin = in Bool()
  val xout, yout = out SInt(20 bits)
  val phaseout = out UInt(23 bits)
  val error, gout = out Bool()
}
class CordicDut extends Component {
  val io = new CordicPorts
  val dut = new AodCordic
  io.elements.foreach { case (name, port) =>
    val target = dut.io.elements.toMap.apply(name)
    if (name == "gin") dut.io.gin := io.gin.asBits
    else if (name == "gout") io.gout := dut.io.gout(0)
    else if (port.isInput) target.assignFromBits(port.asBits)
    else port.assignFromBits(target.asBits)
  }
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
class GcalDut(tabn: Int, halves: Int) extends Component {
  val io = new GcalPorts(tabn, halves)
  val dut = new AodGcal(tabn, halves)
  dut.io.elements.foreach { case (name, port) =>
    val external = io.elements.toMap.apply(name)
    if (port.isInput) port.assignFromBits(external.asBits) else external.assignFromBits(port.asBits)
  }
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
class ToneDut extends Component {
  val io = new TonePorts
  val p = AodConfig(profiles / "aod_unit.mem", profiles / "aod_seed.mem", ratioMax = 512)
  val dut = new AodTone(p)
  dut.io.elements.foreach { case (name, port) =>
    val external = io.elements.toMap.apply(name)
    if (port.isInput) port.assignFromBits(external.asBits) else external.assignFromBits(port.asBits)
  }
  io.probe_inc := dut.logic.inc_live.pull()
  io.probe_phi := dut.logic.phi.pull()
  io.probe_osc := dut.logic.osc.pull()
  io.probe_run := dut.logic.run.pull()
  io.probe_end := dut.logic.mv_end.pull()
}

class TopPorts(p: AodConfig) extends Bundle {
  val clk, rst, trig_in = in Bool()
  val cmd = slave(Flow(AodWrite()))
  val setup = slave(Stream(AodCoefficientWrite()))
  val setup_busy, config_ready = out Bool()
  val status = out(new AodStatus(p))
  val control = out(new AodControl(p)) // test-only observation, not a production read bus
  val dac_valid = out Bits(p.channels bits)
  val dac_x, dac_y = out Bits(32 bits)
  val probe_ch = in Bool()
  val probe_tone = in UInt(6 bits)
  val probe_beat = in UInt(2 bits)
  val probe_addr = in UInt(8 bits)
  val probe_table, probe_urom, probe_srom, probe_base = out UInt(32 bits)
  val probe_zoom = out UInt(2 bits)
  val probe_enable = out Bool()
  val probe_index = out UInt(8 bits)
  val probe_gain, probe_ce_gain = out UInt(16 bits)
  val probe_phase, probe_inc = out UInt(32 bits)
}
class TopDut(nt: Int, roms: Path, nch: Int = 2) extends Component {
  val p = AodConfig(roms / "aod_unit.mem", roms / "aod_seed.mem", tones = nt, channels = nch)
  val io = new TopPorts(p)
  val dut = new AodTop(p)
  dut.io.clk := io.clk; dut.io.rst := io.rst; dut.io.trig_in := io.trig_in
  dut.io.cmd << io.cmd; dut.io.setup << io.setup
  io.setup_busy := dut.io.setup_busy; io.config_ready := dut.io.config_ready
  io.status := dut.io.status; io.control := dut.regs.io.control.pull()
  io.dac_valid := dut.io.dac_valid
  io.dac_x := dut.io.dac_x; io.dac_y := dut.io.dac_y
  val banks = dut.engine.logic.banks
  val selected = (io.probe_ch.asUInt.resize(7) * U(nt, 7 bits) + io.probe_tone.resize(14)).resize(log2Up(nt * nch))
  def select[T <: Data](values: Seq[T]): T = Vec(values)(selected)
  io.probe_table := select(banks.flatMap(_.logic.tones).map(t => t.logic.gcal.rework { t.logic.gcal.logic.tab.readAsync(io.probe_addr.pull().resized).asUInt }.pull()))
  io.probe_urom := select(banks.flatMap(_.logic.tones).map(t => t.rework { Vec(t.logic.urom.map(_.readAsync(io.probe_addr.pull().resize(log2Up(p.segments)))).map(_.resize(32)))(io.probe_beat.pull()).asUInt }.pull()))
  io.probe_srom := select(banks.flatMap(_.logic.tones).map(t => t.rework { Vec(t.logic.srom.map(_.readAsync(io.probe_addr.pull().resize(log2Up(p.segments)))).map(_.resize(32)))(io.probe_beat.pull()).asUInt }.pull()))
  io.probe_index := select(banks.flatMap(_.logic.tones).map(_.logic.gcal.logic.idx_q.pull())).resized
  io.probe_gain := select(banks.flatMap(_.logic.tones).map(_.logic.gcal.logic.g.pull()))
  io.probe_ce_gain := select(banks.flatMap(_.logic.tones).map(_.logic.gcal.logic.ce_g.pull()))
  io.probe_phase := select(banks.flatMap(_.logic.tones).map(_.logic.phi.pull()))
  io.probe_inc := select(banks.flatMap(_.logic.tones).map(_.logic.inc.pull()))
  val ch = io.probe_ch.asUInt.resize(log2Up(nch))
  io.probe_base := Vec(banks.map(_.logic.gb_q.pull()))(ch)
  io.probe_zoom := Vec(banks.map(_.logic.gz_q.pull()))(ch)
  io.probe_enable := Vec(banks.map(_.logic.ge_q.pull()))(ch)
}

class ToneDriver(val io: TonePorts) {
  import TestSupport._
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
