package riscq.aod

import java.nio.file.{Files, Path, StandardCopyOption}

/** Shipped coefficient tables, also available when the module is packaged in a jar. */
object AodProfiles {
  lazy val minjerk: Path = {
    val dir = Files.createTempDirectory("riscq-aod-minjerk-")
    dir.toFile.deleteOnExit()
    for (name <- Seq("aod_unit.mem", "aod_seed.mem")) {
      val path = dir.resolve(name)
      val stream = Option(getClass.getResourceAsStream(s"/aod/profiles/minjerk/$name"))
        .getOrElse(throw new IllegalArgumentException(s"Missing packaged AOD profile: $name"))
      try Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING) finally stream.close()
      path.toFile.deleteOnExit()
    }
    dir
  }
}
