// Rotation datapath translated from AODToneGeneration/cordicg3.sv, modified
// from common-hdl/cordicg1.v by Larry Doolittle (LBL-QubiC/gateware).
// Qubit Control System (QubiC) Copyright (c) 2021, The Regents of the
// University of California, through Lawrence Berkeley National Laboratory
// (subject to receipt of any required approvals from the U.S. Dept. of
// Energy). All rights reserved. Upstream distributed under the 3-clause BSD
// licence; the reference names third_party/LICENSE.QubiC for its full notice.

package riscq.aod

import spinal.core._

class AodCordicIo(width: Int, stages: Int, tagWidth: Int) extends Bundle {
  val clk = in Bool()
  val xin, yin = in SInt(width bits)
  val phasein = in UInt(stages + 1 bits)
  val gin = in Bits(tagWidth bits)
  val xout, yout = out SInt(width bits)
  val phaseout = out UInt(stages + 1 bits)
  val error = out Bool()
  val gout = out Bits(tagWidth bits)
}

/** Faithful rotation-mode, unnormalized QubiC-derived cordicg3 datapath.
  * Deliberately retains its NSTAGE-1 output tap and tag latency. The envelope
  * tables already compensate CORDIC gain. Vectoring/normalization are unused
  * by the standalone engine and are not exposed here.
  */
class AodCordic(width: Int = 20, stages: Int = 22, buffered: Boolean = true,
                tagWidth: Int = 1) extends Component {
  require(width >= 2 && stages >= 2 && stages <= 31 && tagWidth > 0)
  val io = new AodCordicIo(width, stages, tagWidth)
  val logic = new ClockingArea(AodHardware.domain(io.clk)) {
    val zw = stages + 1
    val xi = if (buffered) RegNext(io.xin) init(0) else io.xin
    val yi = if (buffered) RegNext(io.yin) init(0) else io.yin
    val zi = if (buffered) RegNext(io.phasein) init(0) else io.phasein
    val x = Vec.fill(stages)(Reg(SInt(width + 1 bits)))
    val y = Vec.fill(stages)(Reg(SInt(width + 1 bits)))
    val z = Vec.fill(stages)(Reg(UInt(zw bits)))
    val fold = zi(zw - 1) ^ zi(zw - 2)
    x(0) := Mux(fold, -xi.resize(width + 1), xi.resize(width + 1))
    y(0) := Mux(fold, -yi.resize(width + 1), yi.resize(width + 1))
    z(0) := zi ^ Mux(fold, U(BigInt(1) << (zw - 1), zw bits), U(0, zw bits))
    // Integer constants from cordicg3; generating atan with host floating point
    // would introduce an unnecessary second rounding definition.
    val angles = Seq(536870912L,316933406L,167458907L,85004756L,42667331L,
      21354465L,10679838L,5340245L,2670163L,1335087L,667544L,333772L,
      166886L,83443L,41722L,20861L,10430L,5215L,2608L,1304L,652L,326L,
      163L,81L,41L,20L,10L,5L,3L,1L,1L,0L,0L)
    for (i <- 0 until stages - 1) {
      val angle = U(angles(i) >> (32 - zw), zw bits)
      when(z(i).msb) {
        x(i + 1) := x(i) + (y(i) |>> i)
        y(i + 1) := y(i) - (x(i) |>> i)
        z(i + 1) := z(i) + angle
      } otherwise {
        x(i + 1) := x(i) - (y(i) |>> i)
        y(i + 1) := y(i) + (x(i) |>> i)
        z(i + 1) := z(i) - angle
      }
    }
    io.xout := RegNext(x.last.resize(width)) init(0)
    io.yout := RegNext(y.last.resize(width)) init(0)
    io.phaseout := RegNext(z.last) init(0)
    io.error := False
    val tags = Vec.fill(stages + (if (buffered) 1 else 0) + 1)(Reg(Bits(tagWidth bits)) init(0))
    tags(0) := io.gin
    for (i <- 1 until tags.size) tags(i) := tags(i - 1)
    io.gout := tags.last
  }
}

