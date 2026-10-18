package riscq.aod

import spinal.core._
import spinal.lib._
import riscq.dsp.pulse.TimedQueue

private[aod] case class AodQueuedSlot(depth: Int) extends Bundle {
  val slot = UInt((log2Up(depth) max 1) bits)
  val startTime = UInt(32 bits)
}

object AodScheduleError {
  val overflow = 0
  val late = 1
  val overlap = 2
  val invalid = 3
  val order = 4
  val setupConflict = 5
  val width = 6
}

case class AodScheduleStatus(depth: Int) extends Bundle {
  val queued = UInt(log2Up(depth + 1) bits)
  val active, idle, can_enqueue = Bool()
  val errors = Bits(AodScheduleError.width bits)
  val accepted, started, completed = UInt(32 bits)
}

object AodTop {
  val resetClocks = 8
  def settleClocks(p: AodConfig): Int = p.tones + 8
  // One clock captures the queued configuration before the reset/settle sequence.
  def leadTime(p: AodConfig): Int = resetClocks + settleClocks(p) + 1 + AodEngine.launchLatency(p)
  def minimumEnqueueLead(p: AodConfig): Int = leadTime(p) + 3
  /** Sufficient (deliberately conservative) spacing for any legal decimation.
    * Includes waveform length, converter/phase-scan drain and next-job prep.
    */
  def conservativeStartSpacing(p: AodConfig, ratio: Int): Long = {
    require(ratio >= p.rateMin && ratio <= p.ratioMax)
    p.moveSteps.toLong * ratio + leadTime(p) + p.channels * (p.tones + 8) + 64
  }
}

/** Standalone posted frontend for separated, timestamped pulsed movements.
  * A fire captures all staged parameters across both axes. Tables are shared
  * setup state and are locked until every queued/active movement has drained.
  * time is an externally supplied, continuously incrementing 32-bit counter.
  */
class AodTop(val p: AodConfig, queueDepth: Int = 4) extends Component {
  require(queueDepth >= 1)
  val io = new Bundle {
    val clk, rst = in Bool()
    val time = in UInt(32 bits)
    val cmd = slave(Flow(AodWrite()))
    val setup = slave(Stream(AodCoefficientWrite()))
    val setup_busy = out Bool()
    val schedule = out(AodScheduleStatus(queueDepth))
    val status = out(AodStatus(p))
    val dac_valid = out Bits(p.channels bits)
    val dac_x, dac_y = out Bits(32 bits)
  }

  val staging = new AodRegisters(p)
  staging.io.clk := io.clk; staging.io.rst := io.rst
  staging.io.cmd.payload := io.cmd.payload
  staging.io.cmd.valid := io.cmd.valid && io.cmd.address =/= AodRegisterMap.control
  val loader = new AodCoefficientLoader
  loader.io.clk := io.clk; loader.io.rst := io.rst
  val engine = new AodEngine(p)
  engine.io.clk := io.clk; engine.io.rst := io.rst; engine.io.trig_in := False
  engine.io.coefficients << loader.io.coefficients
  io.status := engine.io.status
  io.setup_busy := loader.io.busy

  // Unlike the selective-reset arithmetic, the shared queue uses a resettable
  // clock domain so reset reliably flushes its FIFO and registered head.
  val logic = new ClockingArea(ClockDomain(io.clk, io.rst,
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))) {
    val startTime = Reg(UInt(32 bits)) init 0
    val lastStart = Reg(UInt(32 bits)) init 0
    val queued = Reg(UInt(log2Up(queueDepth + 1) bits)) init 0
    val state = Reg(UInt(2 bits)) init 0 // idle, preparing, playing/draining
    val active = state =/= 0
    val parameters = Reg(AodControl(p))
    val prepareClocks = AodTop.resetClocks + AodTop.settleClocks(p)
    val countdown = Reg(UInt(log2Up(prepareClocks + 1) bits)) init 0
    val seenOutput = Reg(Bool()) init False
    val errors = Reg(Bits(AodScheduleError.width bits)) init 0
    val accepted, started, completed = Reg(UInt(32 bits)) init 0

    // Preserve the requested time in the payload too, for an exact dispatch
    // check. TimedQueue itself drains past-due entries; we must never play them.
    val writeSlot = Reg(UInt((log2Up(queueDepth) max 1) bits)) init 0
    val queue = TimedQueue(HardType(AodQueuedSlot(queueDepth)),
      timeWidth = 32, depth = queueDepth, leadTime = AodTop.leadTime(p))
    queue.io.time := io.time
    queue.io.push.startTime := startTime
    queue.io.push.data.startTime := startTime
    queue.io.push.data.slot := writeSlot

    val write = io.cmd.valid && io.cmd.address(1 downto 0) === 0 && !io.rst
    val fire = write && io.cmd.address === AodRegisterMap.fire
    val clear = write && io.cmd.address === AodRegisterMap.clearSticky
    when(write && io.cmd.address === AodRegisterMap.startTime) { startTime := io.cmd.data.asUInt }
    when(clear) { errors := 0 }

    val distance = startTime - io.time
    val earlyEnough = !distance.msb && distance >= AodTop.minimumEnqueueLead(p)
    val orderDistance = startTime - lastStart
    val ordered = !(active || queued =/= 0) || (!orderDistance.msb && orderDistance =/= 0)
    val c = staging.io.control
    val validParameters = c.ratio >= p.rateMin && c.ratio <= p.ratioMax && c.dec >= 1 && c.dec <= p.decMax
    val setupFree = !loader.io.busy && !io.setup.valid
    // Limit the *total* pending population, including TimedQueue's head stage,
    // to exactly queueDepth, rather than exposing an extra undocumented slot.
    val room = queued < queueDepth && queue.io.push.ready
    val canEnqueue = room && setupFree && !io.rst
    queue.io.push.valid := fire && canEnqueue && earlyEnough && ordered && validParameters
    when(fire) {
      when(!room) { errors(AodScheduleError.overflow) := True }
      when(!setupFree) { errors(AodScheduleError.setupConflict) := True }
      when(!earlyEnough) { errors(AodScheduleError.late) := True }
      when(!ordered) { errors(AodScheduleError.order) := True }
      when(!validParameters) { errors(AodScheduleError.invalid) := True }
    }
    when(queue.io.push.fire) {
      accepted := accepted + 1; lastStart := startTime
      writeSlot := Mux(writeSlot === queueDepth - 1, U(0, writeSlot.getWidth bits), writeSlot + 1)
    }
    queued := queued + U(queue.io.push.fire) - U(queue.io.pop.valid)

    val dispatch = queue.io.pop.valid && !active &&
      io.time === queue.io.pop.startTime - AodTop.leadTime(p)
    // Snapshot memories share the ring's write address/enable. Banking by leaf
    // field keeps every memory word <= 32 bits even at 64 tones on both axes;
    // a single packed descriptor exceeds Spinal's normal signal-width limit.
    // A slot is freed only on pop, when its fields are captured into parameters.
    for ((staged, latched) <- staging.io.control.flatten.zip(parameters.flatten)) {
      val memory = Mem(Bits(staged.getBitsWidth bits), queueDepth)
      memory.write(writeSlot.resized, staged.asBits, queue.io.push.fire)
      val selected = memory.readAsync(queue.io.pop.slot.resized)
      when(dispatch) { latched.assignFromBits(selected) }
    }

    // Setup has priority over a simultaneous fire when quiescent. A producer
    // must withdraw setup.valid after the final transfer before submitting jobs.
    val setupAllowed = !active && queued === 0 && !io.rst
    loader.io.write.payload := io.setup.payload
    loader.io.write.valid := io.setup.valid && setupAllowed
    io.setup.ready := loader.io.write.ready && setupAllowed

    val effective = AodControl(p)
    val commandFields = Set("cw", "go", "soft_rst", "trig_en", "trig_pol", "snap", "sticky_clr")
    for ((name, field) <- effective.elements if !commandFields(name)) {
      field.assignFromBits(parameters.elements.toMap.apply(name).asBits)
    }
    effective.cw := False; effective.trig_en := False; effective.trig_pol := False
    effective.snap := False; effective.sticky_clr := clear
    effective.soft_rst := state === 1 && countdown > AodTop.settleClocks(p)
    effective.go := state === 1 && countdown === 0 && !io.rst
    engine.io.control := effective

    when(queue.io.pop.valid) {
      when(io.time =/= queue.io.pop.startTime - AodTop.leadTime(p)) {
        errors(AodScheduleError.late) := True
      } elsewhen(active) {
        errors(AodScheduleError.overlap) := True
      } otherwise {
        countdown := prepareClocks
        seenOutput := False
        state := 1
      }
    }
    when(state === 1) {
      when(countdown =/= 0) { countdown := countdown - 1 } otherwise {
        state := 2; started := started + 1
      }
    }
    when(state === 2) {
      when(engine.io.dac_valid.orR) { seenOutput := True }
      // Empty FIFO and bank busy are insufficient: include converter drain and
      // the automatic phase scan before allowing reset for another movement.
      when(seenOutput && !engine.io.dac_valid.orR && !engine.io.status.snap_busy && !engine.io.status.pa_we) {
        state := 0; completed := completed + 1
      }
    }

    io.schedule.queued := queued
    io.schedule.active := active
    io.schedule.idle := !active && queued === 0 && !loader.io.busy && !io.rst
    io.schedule.can_enqueue := canEnqueue
    io.schedule.errors := errors
    io.schedule.accepted := accepted; io.schedule.started := started; io.schedule.completed := completed
    io.dac_valid := Mux(state === 2 && !io.rst, engine.io.dac_valid, B(0, p.channels bits))
    // The scheduled pulsed API explicitly emits zero throughout off intervals.
    io.dac_x := Mux(io.dac_valid(0), engine.io.dac_x, B(0, 32 bits))
    io.dac_y := (if (p.channels > 1) Mux(io.dac_valid(1), engine.io.dac_y, B(0, 32 bits)) else B(0, 32 bits))
  }
}
