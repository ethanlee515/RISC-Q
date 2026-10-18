package riscq.aod

import java.nio.file.{Files, Path}

object ProfileChecks {
  import Reference._
  /** Keep the two Python checks as generator checks; write only ignored artifacts. */
  def generate(): Path = {
    val dest = testRoot / "simWorkspace" / "profiles"
    Files.createDirectories(dest)
    def run(name: String, profile: String, dec: Int = 1): String = {
      PythonChecks.run(root / "scripts" / "gen_tg_profile.py",
        Seq("--out", (dest / name).toString, "--profile", profile, "--dec", dec.toString,
          "--src", golden.toString), dest / s"$name.log")
    }
    run("linear", "linear")
    run("linear_dec16", "linear", 16)
    run("minjerk", "minjerk")
    for (dec <- Seq(1, 2, 4, 8, 16)) {
      val log = run(s"cubic_dec$dec", "cubic", dec)
      val deviation = "worst deviation ([0-9.]+)".r.findFirstMatchIn(log)
        .getOrElse(throw new AssertionError(s"Missing fit report:\n$log")).group(1).toDouble
      assert(deviation <= 6000, s"Cubic dec=$dec fit error=$deviation LSB")
    }
    // Pin the geometry used by the simulation bindings to the shipped fixture.
    val cfg = read(golden / "aod_tone1_cfg.vh")
    for ((key, value) <- Seq("ACC" -> 32, "UW" -> 30, "MOVE_STEPS" -> 6250,
        "NSEG" -> 64, "SEG_LEN" -> 98, "N_EXP" -> 20000, "RATIO" -> 8,
        "A_WORD_TEST" -> 751619277, "D_SITE_TEST" -> 23)) {
      val got = ("localparam int " + key + "\\s*=\\s*([0-9]+)").r
        .findFirstMatchIn(cfg).get.group(1).toInt
      assert(got == value, s"Reference fixture $key changed: $got != $value; update bindings and expectations together")
    }
    dest / "linear"
  }
}
