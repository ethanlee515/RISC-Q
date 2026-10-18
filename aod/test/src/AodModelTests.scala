package riscq.aod

import spinal.core._
import spinal.core.sim._
import utest._

class RoundPorts(bits: Int) extends Bundle {
  val clk, rst = in Bool()
  val din_i, din_q = in SInt(bits bits)
  val dout_i, dout_q = out SInt(bits bits)
  val sat = out Bool()
}
class RoundDut(bits: Int, physical: Int) extends Component {
  val io = new RoundPorts(bits)
  val dut = new AodRound(bits, physical)
  dut.io.elements.foreach { case (name, port) =>
    val external = io.elements.toMap.apply(name)
    if (port.isInput) port.assignFromBits(external.asBits) else external.assignFromBits(port.asBits)
  }
}

/** Streaming models, exhaustive quantization, and a preserved integration trace. */
object AodModelTests extends TestSuite {
  import TestSupport._
  val tests = Tests {
    test("roundingExhaustive") {
      for ((bits, phys) <- Seq((16,14), (16,16), (8,2))) {
        config.compile(new RoundDut(bits, phys)).doSim(seed = 42) { dut =>
          val io = dut.io
          val clock = new Clock(io.clk)
          val half = 1 << (bits - 1)
          val shift = bits - phys
          def expected(x: Int): Int = {
            val rounded = if (shift == 0) x else (x + (1 << (shift - 1))) >> shift
            math.min(rounded, (1 << (phys - 1)) - 1) << shift
          }
          io.rst #= true; io.din_i #= 0; io.din_q #= 0; clock.tick(4)
          io.rst #= false; clock.tick(128)
          for (i <- -half until half) {
            val q = -i - 1
            io.din_i #= i; io.din_q #= q; clock.tick()
            Predef.assert(io.dout_i.toInt == expected(i) && io.dout_q.toInt == expected(q),
              s"round $bits->$phys at I=$i Q=$q")
            val threshold = half - (if (shift == 0) 0 else 1 << (shift - 1))
            Predef.assert(io.sat.toBoolean == (shift > 0 && (i >= threshold || q >= threshold)),
              s"round saturation $bits->$phys at I=$i Q=$q")
          }
          io.rst #= true; clock.tick()
          Predef.assert(io.dout_i.toInt == 0 && io.dout_q.toInt == 0 && !io.sat.toBoolean)
        }
      }
    }
    test("cordicStreaming") {
      config.compile(new CordicDut).doSim(seed = 42) { dut =>
        val io = dut.io
        val clock = new Clock(io.clk)
        val random = new scala.util.Random(2701)
        io.xin #= 0; io.yin #= 0; io.phasein #= 0; io.gin #= false
        clock.tick(128)
        val pending = scala.collection.mutable.Queue.empty[Models.Rotation]
        def sample(x: Int, y: Int, phase: Int, tag: Boolean): Unit = {
          io.xin #= x; io.yin #= y; io.phasein #= phase; io.gin #= tag
          pending.enqueue(Models.rotate(x, y, phase, tag)); clock.tick()
          if (pending.size >= 24) {
            val expected = pending.dequeue()
            Predef.assert(io.xout.toInt == expected.x && io.yout.toInt == expected.y &&
              io.phaseout.toInt == expected.phase && io.gout.toBoolean == expected.tag && !io.error.toBoolean,
              s"CORDIC streaming clock ${clock.cycles}: expected=$expected")
          }
        }
        for (_ <- 0 until 20000) sample(random.nextInt(1 << 20) - (1 << 19),
          random.nextInt(1 << 20) - (1 << 19), random.nextInt(1 << 23), random.nextBoolean())
        for (_ <- 0 until 24) sample(0, 0, 0, false)
      }
    }
    test("runtimeProfiles") {
      config.compile(new ToneDut).doSim(seed = 42) { dut =>
        val driver = new ToneDriver(dut.io)
        val io = dut.io; val clock = driver.clock
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var traced = 0
        clock.after = () => {
          val row = Seq(io.dac.toInt.toString, io.dac_q.toInt.toString,
            io.dac_valid.toBoolean.toString, io.busy.toBoolean.toString, io.done.toBoolean.toString,
            io.phi_acc.toBigInt.toString, io.go_drop.toBoolean.toString, io.probe_inc.toBigInt.toString,
            io.probe_phi.toBigInt.toString, io.probe_osc.toBoolean.toString,
            io.probe_run.toBoolean.toString, io.probe_end.toBoolean.toString).mkString(",") + "\n"
          digest.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8)); traced += 1
        }
        val random = new scala.util.Random(2703)
        def writeRow(sel: Int, row: Int, value: BigInt): Unit = {
          for (beat <- 0 until 3) {
            io.rom_sel #= sel; io.rom_addr #= row
            // Both 2 and 3 address the short final slice in the legacy ABI.
            io.rom_beat #= (if (beat == 2 && row % 2 == 1) 3 else beat)
            io.rom_wdata #= word(value >> (32 * beat)); io.rom_we #= true; clock.tick()
          }
          io.rom_we #= false
        }
        for (row <- 0 until 64) {
          val trajectory = BigInt(random.nextInt(1 << 30)) |
            (word(BigInt(random.nextInt(200001) - 100000)) << 32) |
            (word(BigInt(random.nextInt(101) - 50)) << 64)
          val seed = (BigInt(30000 + row * 300) << 16) + row * 997
          val slope = BigInt(if (row % 2 == 0) 17317 else -29761) & ((BigInt(1) << 36) - 1)
          writeRow(5, row, trajectory); writeRow(6, row, seed | (slope << 36))
        }
        for (addr <- 0 until 256) {
          io.gcal_addr #= addr
          io.rom_wdata #= (word(BigInt(if (addr % 2 == 0) -100 else 200)) << 16 | (18000 + addr * 70)) & mask
          io.gcal_we #= true; clock.tick()
        }
        io.gcal_we #= false
        io.cal_a #= 12000; io.cal_b #= 45000; io.amp_scale #= 40000
        io.a_word #= BigInt("f1234567", 16); io.d_word #= -987654321
        io.phase0 #= BigInt("98765432", 16); io.ratio #= 17; io.dec #= 4
        clock.tick(128); driver.launch(); driver.finish()
        io.en #= false; clock.tick(32); driver.launch(); driver.finish()
        val hash = digest.digest().map(b => f"${b & 255}%02x").mkString
        val expected = new java.util.Properties
        val stream = java.nio.file.Files.newInputStream(fixtures / "runtime-profile.properties")
        try expected.load(stream) finally stream.close()
        Predef.assert(traced == expected.getProperty("clocks").toInt && hash == expected.getProperty("sha256"),
          s"Runtime profile trace: $traced clocks SHA-256=$hash")
      }
    }
    test("gcalStreaming") {
      for ((entries, halves) <- Seq((256,1), (128,2))) {
        config.compile(new GcalDut(entries, halves)).doSim(seed = 42) { dut =>
          val io = dut.io
          val clock = new Clock(io.clk)
          val random = new scala.util.Random(2702)
          io.half #= false; io.cfg_en #= true; io.tab_we #= false; io.cfg_base #= 0
          io.cfg_zoom #= 2; io.tab_addr #= 0; io.tab_wdata #= 0; io.inc #= 0; io.cal_now #= 32768
          clock.tick(128)
          val model = new Models.Gain(entries, halves)
          def sample(input: Models.GainInput): Unit = {
            io.half #= input.half; io.cfg_en #= input.enabled; io.tab_we #= input.write
            io.cfg_base #= input.base; io.cfg_zoom #= input.zoom; io.tab_addr #= input.address
            io.tab_wdata #= input.data; io.inc #= input.increment; io.cal_now #= input.amplitude
            val expected = model.step(input); clock.tick()
            expected.foreach(value => Predef.assert(io.cal_eff.toInt == value,
              s"Gain streaming clock ${clock.cycles}: got=${io.cal_eff.toInt} expected=$value"))
          }
          // Change configuration and arbitrary signed-slope entries every clock.
          for (_ <- 0 until 20000) sample(Models.GainInput(random.nextBoolean(), random.nextBoolean(),
            random.nextBoolean(), BigInt(32, random).toLong, random.nextInt(4),
            random.nextInt(entries * halves), BigInt(32, random).toLong,
            BigInt(32, random).toLong, random.nextInt(65536)))
          for (_ <- 0 until 16) sample(Models.GainInput(false, true, false, 0, 2, 0, 0, 0, 32768))
        }
      }
    }
  }
}
