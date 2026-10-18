package riscq.aod

import utest._

/** Standalone engine regression against local numerical fixtures and invariants. */
object AodTests extends TestSuite {
  import TestSupport._
  lazy val tone = config.compile(new ToneDut)
  lazy val linear = ProfileChecks.generate()
  // Resolve fixtures before entering Spinal's elaboration thread (Scala 2 lazy
  // initializers on this object share a monitor).
  lazy val top4 = { val roms = linear; config.compile(new TopDut(4, roms)) }
  lazy val top2 = { val roms = linear; config.compile(new TopDut(2, roms)) }
  lazy val spectrumLinear = { val roms = linear.resolveSibling("linear_dec16"); config.compile(new EngineDut(AodConfig(roms.resolve("aod_unit.mem"), roms.resolve("aod_seed.mem"), tones = 8, channels = 1))) }
  lazy val spectrumMinjerk = { val roms = linear.resolveSibling("minjerk"); config.compile(new EngineDut(AodConfig(roms.resolve("aod_unit.mem"), roms.resolve("aod_seed.mem"), tones = 8, channels = 1))) }
  lazy val engine4 = { val roms = linear; config.compile(new EngineDut(AodConfig(roms.resolve("aod_unit.mem"), roms.resolve("aod_seed.mem"), tones = 4))) }
  val tests = Tests {
    test("cordic") { config.compile(new CordicDut).doSim(seed = 42)(NumericalChecks.cordic) }
    test("profiles") { linear }
    test("gcal256") { config.compile(new GcalDut(256, 1)).doSim(seed = 42)(d => NumericalChecks.gcal(d, 256, 1)) }
    test("gcal128") { config.compile(new GcalDut(128, 1)).doSim(seed = 42)(d => NumericalChecks.gcal(d, 128, 1)) }
    test("gcal128TwoCurves") { config.compile(new GcalDut(128, 2)).doSim(seed = 42)(d => NumericalChecks.gcal(d, 128, 2)) }
    test("pulsed") { tone.doSim("pulsed", seed = 42)(ToneChecks.pulsed) }
    test("indices") { tone.doSim("indices", seed = 42)(ToneChecks.indices) }
    test("cw") { tone.doSim("cw", seed = 42)(ToneChecks.cw) }
    test("engineControl") { engine4.doSim("engineControl", seed = 42)(EngineChecks.control) }
    test("registers") { top4.doSim("registers", seed = 42)(TopChecks.registers) }
    test("gcalSetup") { top2.doSim("gcalSetup", seed = 42)(TopChecks.gcal) }
    test("coefficientLoading") { top2.doSim("coefficientLoading", seed = 42)(SetupChecks.loading) }
    test("profilesThroughSetup") { top2.doSim("profilesThroughSetup", seed = 42)(SetupChecks.playback) }
    test("registerGeometry") { SetupChecks.registerGeometry() }
    test("gcalGenerated128") { CalibrationChecks.generated(128) }
    test("gcalGenerated256") { CalibrationChecks.generated(256) }
    test("spectrumCw") { SpectralChecks.cw(spectrumLinear) }
    test("spectrumNegative") { SpectralChecks.negative(spectrumLinear) }
    test("spectrumComb") { SpectralChecks.comb(spectrumLinear) }
    test("spectrumChirp") { SpectralChecks.chirp(spectrumLinear, spectrumMinjerk) }
  }
}
