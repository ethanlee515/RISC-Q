package riscq.aod

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.Opcode

object AodHostMap {
  val capabilities = 0x00
  val reset = 0x04
  val state = 0x08
  val diagnostics = 0x0c
  val trajectory = 0x1000
  val envelope = 0x2000
  val gain = 0x3000
  def profile(table: Int, row: Int, beat: Int): Int = {
    require(table == AodTable.trajectory || table == AodTable.envelope)
    (if (table == AodTable.trajectory) trajectory else envelope) + row * 16 + beat * 4
  }
  def calibration(channel: Int, entry: Int): Int = gain + channel * 0x400 + entry * 4
}

case class AodHostState() extends Bundle {
  val setup_busy, active, idle, can_enqueue = Bool()
}

case class AodHostDiagnostics(p: AodConfig) extends Bundle {
  val errors = Bits(AodScheduleError.width bits)
  val clip, cfg_err = Bits(p.channels bits)
  val go_dropped, dec_clamped = Bool()
}

object AodHostAdapter {
  def busParameter(sourceWidth: Int = 2): tilelink.BusParameter = {
    require(sourceWidth >= 0 && sourceWidth < 31)
    val support = tilelink.M2sSupport(
      transfers = tilelink.M2sTransfers(get = tilelink.SizeRange(4), putFull = tilelink.SizeRange(4)),
      addressWidth = 16, dataWidth = 32)
    tilelink.NodeParameters(m = tilelink.M2sParameters(support, 1 << sourceWidth)).toBusParameter()
  }
}

/** Host setup boundary, independent of CPU reset and the runtime posted link.
  * Rebase a host TileLink window into host; wire setup/state/reset to AodTop.
  * A successful coefficient response means the complete bank write has drained.
  * Only the essential completion/reset/state path is mandatory. Diagnostic
  * readback and its input ports can be omitted at elaboration.
  */
class AodHostAdapter(p: AodConfig, withDiagnostics: Boolean = true, sourceWidth: Int = 2) extends Component {
  val busParameter = AodHostAdapter.busParameter(sourceWidth)
  val io = new Bundle {
    val host_clk, host_rst, dsp_clk, dsp_rst = in Bool()
    val host = slave(tilelink.Bus(busParameter))
    val setup = master(Stream(AodCoefficientWrite()))
    val state = in(AodHostState())
    val diagnostics = withDiagnostics generate in(AodHostDiagnostics(p))
    val aod_reset = out Bool()
  }
  val hostCd = ClockDomain(io.host_clk, io.host_rst,
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
  val dspCd = ClockDomain(io.dsp_clk, io.dsp_rst,
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
  // Both sides are reset together at system initialization. Per-run AOD reset
  // stays downstream of these FIFOs, so it cannot desynchronize CDC pointers.
  val crossing = tilelink.FifoCc(busParameter, hostCd, dspCd,
    aDepth = 2, bDepth = 2, cDepth = 2, dDepth = 2, eDepth = 2)
  crossing.io.input << io.host

  val logic = new ClockingArea(dspCd) {
    val bus = crossing.io.output
    val phase = Reg(UInt(3 bits)) init 0 // accept, issue, drain, reset, respond
    val command = Reg(AodCoefficientWrite())
    val response = Reg(cloneOf(bus.d.payload))
    val resetRemaining = Reg(UInt(log2Up(AodTop.resetClocks + 1) bits)) init 0
    when(resetRemaining =/= 0) { resetRemaining := resetRemaining - 1 }
    io.aod_reset := io.dsp_rst || resetRemaining =/= 0
    io.setup.valid := phase === 1 && !io.dsp_rst
    io.setup.payload := command
    bus.a.ready := phase === 0 && !io.dsp_rst
    bus.d.valid := phase === 4 && !io.dsp_rst
    bus.d.payload := response

    val address = bus.a.address
    val isGet = bus.a.opcode === Opcode.A.GET
    val isPut = bus.a.opcode === Opcode.A.PUT_FULL_DATA
    val formatOk = (isGet || isPut) && bus.a.size === 2 && address(1 downto 0) === 0 &&
      bus.a.mask.andR && bus.a.param === 0 && !bus.a.corrupt
    val trajectory = address >= AodHostMap.trajectory && address < AodHostMap.trajectory + p.segments * 16
    val envelope = address >= AodHostMap.envelope && address < AodHostMap.envelope + p.segments * 16
    val gains = (0 until p.channels).map { ch =>
      address >= AodHostMap.calibration(ch, 0) && address < AodHostMap.calibration(ch, p.gcalEntries)
    }.reduce(_ || _)

    val stateWord = B(0, 27 bits) ## io.aod_reset ## io.state.can_enqueue ##
      io.state.idle ## io.state.active ## io.state.setup_busy
    val diagnosticWord = if (withDiagnostics) {
      val d = io.diagnostics
      B(0, 18 bits) ## d.dec_clamped ## d.go_dropped ## d.cfg_err.resize(2) ##
        d.clip.resize(2) ## B(0, 2 bits) ## d.errors
    } else B(0, 32 bits)
    val capabilities = (BigInt(1) << 24) | (BigInt(p.channels) << 16) |
      (BigInt(p.tones) << 8) | (if (withDiagnostics) BigInt(1) else BigInt(0))

    when(bus.a.fire) {
      response.opcode := Mux(isGet, Opcode.D.ACCESS_ACK_DATA(), Opcode.D.ACCESS_ACK())
      response.param := 0; response.source := bus.a.source; response.sink := 0
      response.size := bus.a.size; response.data := 0
      response.denied := False; response.corrupt := False
      phase := 4
      when(!formatOk) {
        response.denied := True; response.corrupt := isGet
      } elsewhen(isGet) {
        switch(address) {
          is(AodHostMap.capabilities) { response.data := B(capabilities, 32 bits) }
          is(AodHostMap.reset) { response.data := io.aod_reset.asBits.resize(32) }
          is(AodHostMap.state) { response.data := stateWord }
          is(AodHostMap.diagnostics) { response.data := diagnosticWord }
          default { response.denied := True; response.corrupt := True }
        }
      } elsewhen(address === AodHostMap.reset && (bus.a.data === 0 || bus.a.data === 1)) {
        when(bus.a.data(0)) { resetRemaining := AodTop.resetClocks; phase := 3 }
      } elsewhen(trajectory || envelope || gains) {
        command.table := Mux(trajectory, U(AodTable.trajectory, 2 bits),
          Mux(envelope, U(AodTable.envelope, 2 bits), U(AodTable.gain, 2 bits)))
        command.channel := gains && address(10)
        command.address := Mux(gains, address(9 downto 2), address(10 downto 4).resize(8))
        command.beat := address(3 downto 2)
        command.data := bus.a.data
        phase := 1
      } otherwise { response.denied := True }
    }
    when(phase === 1 && io.setup.fire) { phase := 2 }
    // Do not acknowledge on the acceptance edge: setup_busy rises on that edge.
    when(phase === 2 && !io.state.setup_busy) { phase := 4 }
    when(phase === 3 && resetRemaining === 0) { phase := 4 }
    when(bus.d.fire) { phase := 0 }
  }
}
