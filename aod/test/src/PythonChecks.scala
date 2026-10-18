package riscq.aod

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.sys.process._

/** Keep the original Python oracles; their exit status is part of the test. */
object PythonChecks {
  def run(script: Path, args: Seq[String], logFile: Path): String = {
    Files.createDirectories(logFile.getParent)
    val output = new StringBuilder
    def append(line: String): Unit = output.synchronized {
      output.append(line).append('\n'); ()
    }
    val status = Process(Seq("python3", script.toString) ++ args)
      .!(ProcessLogger(append, append))
    val log = output.toString
    Files.write(logFile, log.getBytes(UTF_8))
    assert(status == 0, s"Python check ${script.getFileName} failed (exit $status); log: $logFile\n$log")
    log
  }
}
