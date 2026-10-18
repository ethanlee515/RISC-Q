package riscq.aod

import spinal.core._
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

/** Portable standalone AOD geometry. Arithmetic matches the 32-bit reference.
  * Table paths are elaboration inputs; no reference RTL is needed for synthesis.
  * The sample contract is one complex sample per clock (SPC=2).
  */
case class AodConfig(unitFile: Path, seedFile: Path, tones: Int = 32, channels: Int = 2,
                     ratioMax: Int = 65535, decMax: Int = 16, moveSteps: Int = 6250,
                     segments: Int = 64, segmentLength: Int = 98, gcalEntries: Int = 256,
                     rateMin: Int = 7, rateExact: Int = 17, fanout: Int = 8,
                     dacPhysical: Int = 14) {
  require(tones >= 2 && tones <= 64 && channels >= 1 && channels <= 2)
  require(ratioMax >= 4 && ratioMax <= 65535 && decMax >= 1 && decMax <= 16)
  require(segments >= 2 && segments <= 128 && isPow2(segments))
  require(moveSteps >= 1 && segmentLength > 0 && segments * segmentLength >= moveSteps)
  require(gcalEntries >= 16 && gcalEntries <= 256 && isPow2(gcalEntries))
  require(rateMin >= 1 && rateMin <= ratioMax && fanout > 0)
  require(dacPhysical >= 2 && dacPhysical <= 16)
  val ratioWidth = log2Up(ratioMax) + 1
  val decWidth = log2Up(decMax) + 1
  require(ratioWidth >= decWidth, "The rate divider must accommodate the decimation word")
}

private[aod] object AodHardware {
  // Explicit synchronous reset is used only where the reference resets state.
  // In particular, table contents and the arithmetic pipelines have no reset.
  def domain(clk: Bool) = ClockDomain(clk, config = ClockDomainConfig(resetKind = BOOT))
  def table(path: Path, width: Int, depth: Int): Seq[BigInt] = {
    val words = Files.readAllLines(path).asScala.flatMap(_.takeWhile(_ != '/').trim.split("\\s+"))
      .filter(_.nonEmpty).map(BigInt(_, 16)).toVector
    require(words.size == depth, s"$path: expected $depth rows, got ${words.size}")
    require(words.forall(v => v >= 0 && v.bitLength <= width), s"$path: row exceeds $width bits")
    words
  }
  def round(value: SInt, bits: Int): SInt = {
    val shift = value.getWidth - bits
    require(shift >= 0)
    val extended = value.resize(value.getWidth + 1) +
      S(if (shift == 0) BigInt(0) else BigInt(1) << (shift - 1), value.getWidth + 1 bits)
    val scaled = extended(value.getWidth downto shift)
    val limit = (BigInt(1) << (bits - 1)) - 1
    Mux(scaled > S(limit, bits + 1 bits), S(limit, bits bits), scaled.resize(bits))
  }
}

object AodConfig {
  /** Default standalone geometry and the packaged minimum-jerk coefficients. */
  def default: AodConfig = AodConfig(AodProfiles.minjerk.resolve("aod_unit.mem"),
    AodProfiles.minjerk.resolve("aod_seed.mem"))
}
