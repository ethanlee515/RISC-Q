package riscq.soc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.fabric.Node
import riscq.aod._
import riscq.riscv.RiscqParam
import riscq.soc.fabric.{MemMapDriverFiber, TilelinkPortBridge}
import riscq.soc.link.{HostCmd, HostWindowFunnel}

/** Single CPU controlling one synchronized X/Y AOD device. Native complex DSP
  * outputs; RFDC conversion/packing belongs to the board wrapper, not this SoC.
  */
case class AodSocConfig(aod: AodConfig = AodConfig.default, memDepth: Int = 4096,
                        queueDepth: Int = 4, linkPipe: Int = 4,
                        withDiagnostics: Boolean = true, withMul: Boolean = false) {
  require(memDepth >= 1024 && isPow2(memDepth))
  require(queueDepth > 0 && linkPipe >= 0)
}

/** Host addresses are relative to the PS-mapped AXI aperture. CPU addresses are
  * a separate address space; both axes belong to the same 64-KiB posted device.
  */
object AodSocMap {
  /** Generated alongside RTL: software consumes this rather than an SC SocSpec. */
  def manifest(c: AodSocConfig): ujson.Obj = {
    val p = c.aod
    ujson.Obj("kind" -> "aod", "version" -> 1, "name" -> "aod-soc",
      "mem_depth" -> c.memDepth, "with_mul" -> c.withMul,
      "queue_depth" -> c.queueDepth, "link_pipe" -> c.linkPipe,
      "diagnostics" -> c.withDiagnostics, "dsp_hz" -> 400000000,
      "tones" -> p.tones, "axes" -> p.channels, "segments" -> p.segments,
      "gain_entries" -> p.gcalEntries, "move_steps" -> p.moveSteps,
      "ratio_min" -> p.rateMin, "ratio_max" -> p.ratioMax, "dec_max" -> p.decMax,
      "minimum_enqueue_lead" -> AodTop.minimumEnqueueLead(p),
      "lead_time" -> AodTop.leadTime(p),
      "host" -> ujson.Obj("program" -> program.toInt, "control" -> control.toInt, "setup" -> setup.toInt,
        "cpu_reset" -> cpuReset, "stopped" -> stopped, "done" -> done,
        "host_base_lo" -> hostBaseLo, "host_base_hi" -> hostBaseHi),
      "setup" -> ujson.Obj("capabilities" -> AodHostMap.capabilities,
        "reset" -> AodHostMap.reset, "state" -> AodHostMap.state,
        "diagnostics" -> AodHostMap.diagnostics, "trajectory" -> AodHostMap.trajectory,
        "envelope" -> AodHostMap.envelope, "gain" -> AodHostMap.gain),
      "cpu" -> ujson.Obj("aod" -> cpuAod.toInt, "host_window" -> RiscvSoc.hostWinBase.toInt, "time" -> 0xbff8, "time_cmp" -> 0x4000,
        "wait_time_cmp" -> 0x4008, "done" -> 0x4010),
      "registers" -> ujson.Obj("fire" -> AodRegisterMap.fire,
        "start" -> AodRegisterMap.startTime, "ratio" -> AodRegisterMap.ratio,
        "decimation" -> AodRegisterMap.decimation, "clear_sticky" -> AodRegisterMap.clearSticky,
        "tone" -> ujson.Arr.from((0 until p.channels).map(ch =>
          ujson.Arr.from((0 until p.tones).map(t =>
            ujson.Arr.from((0 until 4).map(f => AodRegisterMap.tone(ch, t, f))))))),
        "channel" -> ujson.Arr.from((0 until p.channels).map(AodRegisterMap.channel))))
  }

  val program = 0x00000000L
  val control = 0x00100000L
  val setup = 0x00200000L
  val cpuAod = 0x00010000L
  val cpuReset = 0x00
  val stopped = 0x04
  val hostBaseLo = 0x48
  val hostBaseHi = 0x4c
  val done = 0x50
}

class AodSoc(val config: AodSocConfig = AodSocConfig(),
             coreParam: RiscqParam = RiscqParam(gshareMem = true, csrWarl = true,
               aluNoFastForward = true, aluResultOneHot = true, pcRegMaxFanout = 16)) extends Component {
  val p = config.aod
  require(config.memDepth * 4L <= AodSocMap.control)
  val io = new Bundle {
    val dspClk, dspRst = in Bool()
    val axi = slave(Axi4(Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 2)))
    val hostMem = master(Axi4WriteOnly(HostWindowFunnel.axiConfig(40)))
    val dac_valid = out Bits(p.channels bits)
    val dac_x, dac_y = out Bits(32 bits)
  }
  val hostCd = ClockDomain.current
  val dspCd = ClockDomain(io.dspClk, io.dspRst, frequency = FixedFrequency(400 MHz))
  val bridge = new Axi4ToTilelinkFiber(blockSize = 64, slotsCount = 4)
  bridge.up load io.axi
  val hostBus = Node()
  hostBus at 0 of bridge.down
  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // Power up held. Per-run reset affects CPU/control and posted pipeline, never
  // the program RAM, the free-running time, or either side of a CDC FIFO.
  val cpuResetHost = Reg(Bool()) init True
  val cpuReset = dspCd(BufferCC(cpuResetHost, True) | io.dspRst)
  val cpuCd = ClockDomain(io.dspClk, cpuReset)
  val aod = new AodTop(p, config.queueDepth)
  aod.io.clk := io.dspClk

  val dsp = new ClockingArea(dspCd) {
    val time = Reg(UInt(32 bits)) init 0
    val nextTime = time + 1
    time := nextTime
    val cpu = RiscvSoc(plugins = coreParam.copy(
      fetchPcWidth = Some(log2Up(config.memDepth) + 2), fetchLatency = 4,
      withMul = config.withMul).plugins(), riscqCd = cpuCd,
      memDepth = config.memDepth, putAddrWidth = 16, sinks = Nil)
    cpu.time := time
    cpu.resultIn.valid := False
    cpu.resultIn.payload.assignFromBits(B(0, cpu.resultIn.payload.getBitsWidth bits))
    // Native AOD registers its outputs on the consuming edge. Present that
    // edge's counter value so first valid output carries exactly startTime.
    aod.io.time := nextTime

    // Explicit reset on valid prevents a stopped run's in-flight commands from
    // resurfacing after the host flushes AOD and releases the next run.
    var cmd = cpu.cmd
    for (_ <- 0 until config.linkPipe) {
      val next = cloneOf(cmd)
      next.payload := RegNext(cmd.payload)
      next.valid := RegNext(cmd.valid && !cpuReset) init False
      when(cpuReset) { next.valid := False }
      cmd = next
    }
    aod.io.cmd.valid := cmd.valid && !cpuReset
    aod.io.cmd.address := cmd.address
    aod.io.cmd.data := cmd.data
    // Published only after an edge with reset high has cleared the pipeline.
    val stopped = RegNext(cpuReset) init False

    val image = new Area {
      val up = Node.up()
      val logic = Fiber build new Area {
        up.m2s.supported load up.m2s.proposed.intersect(tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(0x100), putFull = tilelink.SizeRange.upTo(0x100),
          putPartial = tilelink.SizeRange.upTo(0x100)))
        up.s2m.none()
        TilelinkPortBridge.bridgeLoad(cpu.iLoad.node.bus, up.bus)
      }
    }
    val hostWrites = StreamFifoCC(HostCmd(24), 16, dspCd, hostCd)
    hostWrites.io.push << cpu.hostCmd
  }
  dsp.image.up at SizeMapping(AodSocMap.program, config.memDepth * 4) of hostBus
  dsp.image.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // Keep this leaf in hostCd: AodHostAdapter owns the host-to-DSP crossing.
  val setupPort = Node.up()
  setupPort at SizeMapping(AodSocMap.setup, 0x10000) of hostBus
  setupPort.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  val setupLogic = Fiber build new Area {
    setupPort.m2s.supported load tilelink.M2sSupport(
      transfers = setupPort.m2s.proposed.transfers.intersect(tilelink.M2sTransfers(
        get = tilelink.SizeRange(4), putFull = tilelink.SizeRange(4),
        putPartial = tilelink.SizeRange(4))),
      addressWidth = 16, dataWidth = 32)
    setupPort.s2m.none()
    val adapter = new AodHostAdapter(p, config.withDiagnostics, setupPort.bus.p.sourceWidth)
    adapter.io.host_clk := hostCd.readClockWire
    adapter.io.host_rst := hostCd.isResetActive
    adapter.io.dsp_clk := io.dspClk; adapter.io.dsp_rst := io.dspRst
    // Explicit-clock child ports carry these same two physical clocks.
    adapter.hostCd.setSynchronousWith(hostCd)
    adapter.dspCd.setSynchronousWith(dspCd)
    TilelinkPortBridge.bridgeLoad(adapter.io.host, setupPort.bus)
    // AXI's ordinary strobed writes arrive as PutPartial. All-byte writes are
    // complete coefficient words; retain partial masks/opcodes so AOD denies them.
    // Spinal's AXI bridge also carries a no-allocate cache hint in param.
    // This uncached register window has no cache allocation to perform.
    adapter.io.host.a.param.allowOverride
    when(setupPort.bus.a.param === tilelink.Param.Hint.NO_ALLOCATE_ON_MISS) {
      adapter.io.host.a.param := 0
    }
    // Read-only AXI/TL masters omit mask; the shared arbiter fills it with
    // zero. Reconstruct the complete word mask for this word-only endpoint.
    adapter.io.host.a.mask.allowOverride
    when(setupPort.bus.a.opcode === tilelink.Opcode.A.GET) {
      adapter.io.host.a.mask := B(15, 4 bits)
    }
    adapter.io.host.a.opcode.allowOverride
    when(setupPort.bus.a.opcode === tilelink.Opcode.A.PUT_PARTIAL_DATA && setupPort.bus.a.mask.andR) {
      adapter.io.host.a.opcode := tilelink.Opcode.A.PUT_FULL_DATA
    }
    aod.io.rst := adapter.io.aod_reset
    aod.io.setup << adapter.io.setup
    adapter.io.state.setup_busy := aod.io.setup_busy
    adapter.io.state.active := aod.io.schedule.active
    adapter.io.state.idle := aod.io.schedule.idle
    adapter.io.state.can_enqueue := aod.io.schedule.can_enqueue
    if (config.withDiagnostics) {
      adapter.io.diagnostics.errors := aod.io.schedule.errors
      adapter.io.diagnostics.clip := aod.io.status.clip
      adapter.io.diagnostics.cfg_err := aod.io.status.cfg_err
      adapter.io.diagnostics.go_dropped := aod.io.status.go_dropped
      adapter.io.diagnostics.dec_clamped := aod.io.status.dec_clamped
    }
  }
  io.dac_valid := aod.io.dac_valid
  io.dac_x := aod.io.dac_x; io.dac_y := aod.io.dac_y

  val hostWindow = HostWindowFunnel(1)
  hostWindow.io.cmd(0) << dsp.hostWrites.io.pop
  io.hostMem << hostWindow.io.axi
  val hostBaseLo = Reg(UInt(32 bits)) init 0
  val hostBaseHi = Reg(UInt(8 bits)) init 0
  val hostEnable = Reg(Bool()) init False
  hostWindow.io.base := hostBaseHi @@ hostBaseLo
  hostWindow.io.enable := hostEnable
  val doneHost = BufferCC(dsp.cpu.done, False)
  val stoppedHost = BufferCC(dsp.stopped, False)
  val controls = MemMapDriverFiber(addressWidth = 8, driveProc = { factory =>
    factory.readAndWrite(cpuResetHost, AodSocMap.cpuReset)
    factory.read(stoppedHost, AodSocMap.stopped)
    factory.read(doneHost, AodSocMap.done)
    factory.readAndWrite(hostBaseLo, AodSocMap.hostBaseLo)
    factory.readAndWrite(hostBaseHi, AodSocMap.hostBaseHi)
    factory.readAndWrite(hostEnable, AodSocMap.hostBaseHi, 31)
  })
  controls.up at SizeMapping(AodSocMap.control, 0x1000) of hostBus
  controls.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
}

/** Simulation-only observation port; no CPU/RAM access bypasses are exposed. */
class AodSocSimulation(config: AodSocConfig) extends AodSoc(config) {
  val sampleTime = out UInt(32 bits)
  sampleTime := dsp.time
}

/** Native-output SoC RTL; this does not generate an RFDC board design. */
object GenAodSoc extends App {
  val dir = args.find(!_.startsWith("--")).getOrElse("out/aod-soc-rtl")
  require(args.filter(_.startsWith("--")).forall(a => a == "--minimal" || a == "--sim"), "supported flags: --minimal, --sim")
  val simulation = args.contains("--sim")
  // Same production trajectory geometry, fewer parallel tones for interactive simulation.
  val config = AodSocConfig(aod = if (simulation) AodConfig.default.copy(tones = 2) else AodConfig.default,
    queueDepth = if (simulation) 2 else 4, withDiagnostics = !args.contains("--minimal"))
  SpinalConfig(targetDirectory = dir, romReuse = true).generateVerilog(
    if (simulation) new AodSocSimulation(config).setDefinitionName("AodSoc") else new AodSoc(config))
  java.nio.file.Files.write(java.nio.file.Paths.get(dir, "aod-soc.json"),
    ujson.write(AodSocMap.manifest(config), indent = 2).getBytes("UTF-8"))
}
