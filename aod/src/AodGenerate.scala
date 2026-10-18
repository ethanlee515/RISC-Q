package riscq.aod

import java.nio.file.Paths
import spinal.core._

/** ./mill aod.runMain riscq.aod.AodGenerate [output-directory]
  * Optional custom coefficients: AodGenerate <rom-directory> <output-directory>.
  */
object AodGenerate {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2, "Usage: AodGenerate [<rom-directory>] [<output-directory>]")
    val p = if (args.length == 2) {
      val roms = Paths.get(args(0)).toAbsolutePath
      AodConfig(roms.resolve("aod_unit.mem"), roms.resolve("aod_seed.mem"))
    } else AodConfig.default
    val output = args.lastOption.getOrElse("out/aod-rtl")
    SpinalConfig(targetDirectory = output).generateVerilog(new AodTop(p))
  }
}
