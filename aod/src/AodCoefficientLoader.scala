package riscq.aod

import spinal.core._
import spinal.lib._

/** Serializes setup writes around the bank's held-enable broadcast. Runs in
  * the AOD clock domain; any host-domain crossing belongs in the future adapter.
  * Each accepted write drains fully before another can replace its held payload.
  */
class AodCoefficientLoader extends Component {
  val io = new Bundle {
    val clk, rst = in Bool()
    val write = slave(Stream(AodCoefficientWrite()))
    val coefficients = master(Flow(AodCoefficientWrite()))
    val busy = out Bool()
  }
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val drain = Reg(UInt(log2Up(AodCoefficientLoader.interval) bits))
    io.write.ready := !io.rst && drain === 0
    io.coefficients.valid := io.write.fire
    io.coefficients.payload := io.write.payload
    when(io.rst) { drain := 0 } elsewhen(io.write.fire) {
      drain := AodCoefficientLoader.interval - 1
    } elsewhen(drain =/= 0) { drain := drain - 1 }
    io.busy := io.rst || drain =/= 0
  }
}
object AodCoefficientLoader {
  // Bank capture → delayed enable → broadcast → four destination write clocks
  // is finished by edge 6 after acceptance. Earliest next acceptance is edge 8.
  val interval = 8
}
