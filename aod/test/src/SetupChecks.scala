package riscq.aod

import spinal.core.sim._

object SetupChecks {
  import TestSupport._
  import TopChecks._

  /** Drive the producer as fast as possible, interleaving tables, slices and
    * axes. Check actual destination memories, not a register-file mirror.
    */
  def loading(dut: TopDut): Unit = {
    val d = new TopDriver(dut.io); val io = dut.io; val clock = d.clock
    val random = new scala.util.Random(381)
    case class Write(table: Int, address: Int, beat: Int, channel: Int, data: BigInt)
    val profiles = for (table <- 0 until 2; row <- 0 until dut.p.segments; beat <- 0 until 3)
      yield Write(table, row, beat, random.nextInt(2), BigInt(32, random))
    val gains = for (ch <- 0 until 2; entry <- 0 until dut.p.gcalEntries)
      yield Write(AodTable.gain, entry, 0, ch, BigInt(32, random))
    val writes = random.shuffle(profiles ++ gains)

    // A valid producer held through reset cannot transfer. Its first transfer
    // after reset must still reach the memories, with its original payload.
    io.rst #= true; io.setup.valid #= true; io.setup.table #= AodTable.gain
    io.setup.address #= 17; io.setup.data #= 12345
    clock.tick(4)
    assert(!io.setup.ready.toBoolean && io.setup_busy.toBoolean)
    d.probe(addr = 17)
    assert(io.probe_table.toBigInt == 32768)
    io.rst #= false; sleep(1)
    assert(io.setup.ready.toBoolean)
    clock.tick(); io.setup.valid #= false; d.settled(); d.probe(addr = 17)
    assert(io.probe_table.toBigInt == 12345)

    var stalled = 0
    var previousAcceptance = -100L
    for ((w, index) <- writes.zipWithIndex) {
      // Occasional producer gaps exercise idle/draining transitions too.
      if (index % 13 == 0) clock.tick(random.nextInt(12))
      stalled += d.load(w.table, w.address, w.data, w.channel,
        if (w.beat == 2 && index % 2 == 0) 3 else w.beat)
      assert(clock.cycles - previousAcceptance >= AodCoefficientLoader.interval)
      previousAcceptance = clock.cycles
      assert(io.setup_busy.toBoolean && !io.config_ready.toBoolean)
    }
    assert(stalled > writes.size, "producer must have exercised real backpressure")
    d.settled()
    assert(!io.setup_busy.toBoolean)
    for (w <- profiles; ch <- 0 until 2; t <- 0 until dut.p.tones) {
      d.probe(ch, t, w.address, w.beat)
      val width = if (w.table == AodTable.envelope && w.beat == 2) 8 else 32
      val expected = w.data & ((BigInt(1) << width) - 1)
      val got = if (w.table == AodTable.trajectory) io.probe_urom.toBigInt else io.probe_srom.toBigInt
      assert(got == expected, s"profile table=${w.table} row=${w.address} beat=${w.beat} ch=$ch tone=$t")
    }
    for (w <- gains; t <- 0 until dut.p.tones) {
      d.probe(w.channel, t, w.address)
      assert(io.probe_table.toBigInt == w.data, s"gain axis=${w.channel} entry=${w.address} tone=$t")
    }
    // Reset after a completed upload preserves coefficient memories.
    io.rst #= true; clock.tick(10); io.rst #= false; d.settled()
    for (w <- gains.take(16)) {
      d.probe(w.channel, 0, w.address)
      assert(io.probe_table.toBigInt == w.data, "reset must preserve loaded coefficients")
    }
    val original = gains.find(w => w.channel == 0 && w.address == 17).get.data
    d.write(8,4); clock.tick(16)
    io.setup.valid #= true; io.setup.table #= AodTable.gain
    io.setup.channel #= false; io.setup.address #= 17; io.setup.data #= 54321
    clock.tick(4); d.probe(addr = 17)
    assert(!io.setup.ready.toBoolean && io.probe_table.toBigInt == original,
      "soft reset must backpressure an offered write")
    d.write(8,0); sleep(1)
    assert(io.setup.ready.toBoolean)
    clock.tick(); io.setup.valid #= false; d.settled(); d.probe(addr = 17)
    assert(io.probe_table.toBigInt == 54321)
  }

  /** Preserve an exact launch-relative converter trace after replacing and
    * restoring profiles through setup. Muting the loaded envelope must silence
    * the samples while leaving their valid timing intact.
    */
  def playback(dut: TopDut): Unit = {
    val d = new TopDriver(dut.io); val io = dut.io; val clock = d.clock
    val initialUnit = AodHardware.table(dut.p.unitFile, 96, dut.p.segments)
    val initialSeed = AodHardware.table(dut.p.seedFile, 72, dut.p.segments)
    for (ch <- 0 until 2) {
      for (t <- 0 until dut.p.tones) {
        d.write(tone(ch,t,0), BigInt("04000000",16) + ch * 1234567 + t * 76543)
        d.write(tone(ch,t,1), word(BigInt(if (ch == 0) 1 else -1) * 8388608))
        d.write(tone(ch,t,2), BigInt("12345678",16) + t * 23456)
        d.write(tone(ch,t,3), BigInt("80008000",16))
      }
      d.write(en(ch), 1); d.write(amp(ch), 4096)
    }
    d.write(0xc,17); d.write(0x10,16); d.settled()
    d.probe()
    case class Sample(x: BigInt, y: BigInt, valid: BigInt)
    def capture(): Vector[Sample] = {
      // The reference leaves decimation work/segment state live after pulsed
      // completion. Reset the engine between independent decimated shots so
      // coefficient equivalence compares the same initial state.
      d.write(8,4); clock.tick(16); d.write(8,0)
      d.settled(); clock.tick(128)
      d.write(8,1)
      val samples = Vector.tabulate(7200) { _ =>
        val sample = Sample(io.dac_x.toBigInt, io.dac_y.toBigInt, io.dac_valid.toBigInt)
        assert(sample.valid == 0 || sample.valid == 3, "both axes must become valid on the same clock")
        clock.tick(); sample
      }
      assert(io.status.busy.toBigInt == 0 && io.dac_valid.toBigInt == 0)
      assert(io.status.cfg_err.toBigInt == 0 && !io.status.go_dropped.toBoolean)
      samples
    }
    def upload(table: Int, words: Seq[BigInt]): Unit = {
      for ((row, address) <- words.zipWithIndex; beat <- 0 until 3)
        d.load(table, address, word(row >> (32 * beat)), beat = beat)
      d.settled()
      for ((row, address) <- words.zipWithIndex; ch <- 0 until 2; t <- 0 until dut.p.tones; beat <- 0 until 3) {
        d.probe(ch,t,address,beat)
        val bits = if (table == AodTable.envelope && beat == 2) 8 else 32
        val got = if (table == AodTable.trajectory) io.probe_urom.toBigInt else io.probe_srom.toBigInt
        assert(got == ((row >> (32 * beat)) & ((BigInt(1) << bits) - 1)), s"loaded profile table=$table row=$address beat=$beat")
      }
      d.probe()
    }
    val baseline = capture()
    val repeated = capture()
    val repeatedDifferences = repeated.zip(baseline).zipWithIndex.filter { case ((a,b),_) => a != b }
    assert(repeatedDifferences.isEmpty, s"unmodified repeat: ${repeatedDifferences.size} differences; first=${repeatedDifferences.take(3)}")
    assert(baseline.exists(s => s.x != 0 && s.y != 0))
    val first = baseline.indexWhere(_.valid != 0)
    val last = baseline.lastIndexWhere(_.valid != 0)
    assert(first == AodTop.idleLaunchLatency(dut.p) && baseline.slice(first,last + 1).forall(_.valid == 3))
    println(s"Native AOD ${dut.p.tones} tones: first valid $first clocks after the posted-go acceptance edge")
    upload(AodTable.envelope, Seq.fill(dut.p.segments)(BigInt(0)))
    val muted = capture()
    assert(muted.forall(s => s.x == 0 && s.y == 0), "uploaded zero envelope must silence both axes")
    assert(muted.map(_.valid) == baseline.map(_.valid))
    upload(AodTable.envelope, initialSeed)
    upload(AodTable.trajectory, Seq.fill(dut.p.segments)(BigInt(0)))
    val static = capture()
    assert(static != baseline, "uploaded trajectory must affect waveform generation")
    upload(AodTable.trajectory, initialUnit)
    val restored = capture()
    val differences = restored.zip(baseline).zipWithIndex.filter { case ((a,b),_) => a != b }
    assert(differences.isEmpty, s"reloaded coefficients: ${differences.size} sample differences; first=${differences.take(5)}")

    // CW and a later reset/pulsed launch use the same newly loaded memories.
    d.write(8,3); clock.tick(15000)
    assert(io.status.busy.toBigInt == 3 && io.dac_valid.toBigInt == 3)
    d.write(8,0); d.idle(); clock.tick(128)
    assert(capture() == baseline, "a reset/pulsed launch must reload phase0 after CW")
  }

  /** Test the lightweight decoder at mask boundaries without instantiating
    * dozens of oscillators just to check register addressing.
    */
  def registerGeometry(): Unit = {
    for ((tones, channels) <- Seq((2,1), (33,2), (64,2))) {
      val p = AodConfig.default.copy(tones = tones, channels = channels)
      config.compile(new AodRegisters(p)).doSim(seed = 42) { dut =>
        val io = dut.io; val clock = new Clock(io.clk)
        io.rst #= true; io.cmd.valid #= false; io.cmd.address #= 0; io.cmd.data #= 0
        clock.tick(10); io.rst #= false
        def write(addr: Int, data: BigInt): Unit = {
          io.cmd.valid #= true; io.cmd.address #= addr; io.cmd.data #= word(data); clock.tick()
        }
        for (ch <- 0 until channels; t <- 0 until tones) {
          write(tone(ch,t,0), ch * 1000 + t)
          write(tone(ch,t,1), mask - ch * 1000 - t)
        }
        for (ch <- 0 until channels) {
          write(en(ch), mask); write(en(ch)+4, mask)
        }
        // A nonexistent axis must not alias axis zero.
        if (channels == 1) write(tone(1,0,0), 123456)
        io.cmd.valid #= false
        for (ch <- 0 until channels; t <- 0 until tones) {
          assert(io.control.a_word(ch * tones + t).toBigInt == ch * 1000 + t)
          assert(io.control.d_word(ch * tones + t).toBigInt == mask - ch * 1000 - t)
        }
        assert(io.control.tone_en.toBigInt == (BigInt(1) << (tones * channels)) - 1)
        clock.tick(tones + 8)
        assert(io.settled.toBoolean)
      }
    }
  }
}
