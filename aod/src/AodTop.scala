package riscq.aod

import spinal.core._
import spinal.lib._

/** Standalone native shell: posted runtime writes, backpressured coefficient
  * loading and explicit status. All ports use clk and synchronous active-high rst.
  * SoC bus adapters, scheduling and converter rate adaptation belong upstream.
  */
class AodTop(p: AodConfig) extends Component {
  val io = new Bundle {
    val clk, rst, trig_in = in Bool()
    val cmd = slave(Flow(AodWrite()))
    val setup = slave(Stream(AodCoefficientWrite()))
    val setup_busy, config_ready = out Bool()
    val status = out(new AodStatus(p))
    val dac_valid = out Bits(p.channels bits)
    val dac_x, dac_y = out Bits(32 bits)
  }
  val regs = new AodRegisters(p)
  val loader = new AodCoefficientLoader
  val engine = new AodEngine(p)
  regs.io.clk := io.clk; regs.io.rst := io.rst; regs.io.cmd << io.cmd
  loader.io.clk := io.clk; loader.io.rst := io.rst
  loader.io.write.payload := io.setup.payload
  loader.io.write.valid := io.setup.valid && !regs.io.control.soft_rst
  io.setup.ready := loader.io.write.ready && !regs.io.control.soft_rst
  engine.io.clk := io.clk; engine.io.rst := io.rst; engine.io.trig_in := io.trig_in
  engine.io.control := regs.io.control
  engine.io.coefficients << loader.io.coefficients
  io.status := engine.io.status
  io.dac_valid := engine.io.dac_valid
  io.dac_x := engine.io.dac_x; io.dac_y := engine.io.dac_y
  io.setup_busy := loader.io.busy
  io.config_ready := regs.io.settled && !loader.io.busy && !io.setup.valid && !regs.io.control.soft_rst
}

object AodTop {
  /** Clocks from an idle, settled posted-go acceptance edge to the first valid
    * final converter word. Pending launches and asynchronous triggers differ.
    */
  def idleLaunchLatency(p: AodConfig): Int = 53 + log2Up(p.tones)
}
