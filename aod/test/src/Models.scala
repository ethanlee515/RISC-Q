package riscq.aod

/** Integer sample models; no HDL, simulator hierarchy, or production arithmetic helpers. */
object Models {
  private def signed(value: Long, width: Int): Int = {
    val half = 1L << (width - 1)
    (((value + half) & ((1L << width) - 1)) - half).toInt
  }
  case class Rotation(x: Int, y: Int, phase: Int, tag: Boolean)

  /** Rotation with the specified finite precision and the gain retained.
    * Compute angles from atan independently of the hardware's constant table.
    * The standalone rotator publishes the result after iterations 0 through 20.
    */
  def rotate(xin: Int, yin: Int, phase: Int, tag: Boolean): Rotation = {
    val fold = (((phase >> 22) ^ (phase >> 21)) & 1) != 0
    var x = if (fold) -xin else xin
    var y = if (fold) -yin else yin
    var z = phase ^ (if (fold) 1 << 22 else 0)
    for (iteration <- 0 until 21) {
      val angle32 = math.round(math.atan(math.pow(2.0, -iteration)) * 4294967296.0 / (2 * math.Pi))
      val angle = (angle32 >> 9).toInt
      val subtract = (z & (1 << 22)) != 0
      val dx = y >> iteration; val dy = x >> iteration
      x = signed(x.toLong + (if (subtract) dx else -dx), 21)
      y = signed(y.toLong + (if (subtract) -dy else dy), 21)
      z = (z + (if (subtract) angle else -angle)) & ((1 << 23) - 1)
    }
    Rotation(signed(x, 20), signed(y, 20), z, tag)
  }

  case class GainInput(half: Boolean, enabled: Boolean, write: Boolean, base: Long,
                       zoom: Int, address: Int, data: Long, increment: Long, amplitude: Int)

  /** Streaming lookup model with an independently maintained memory image.
    * A lookup subtracts on one edge, samples zoom/axis on the next, then reads
    * the table before that edge's write. Gain reaches the output six edges
    * after the read, with amplitude/bypass sampled two edges before output.
    * This captures configuration timing without reproducing the DSP registers.
    */
  class Gain(entries: Int, halves: Int) {
    private val table = Array.fill[Long](entries * halves)(32768L)
    private var offset = 0L
    private var index = 0
    private var fraction = 0
    private var axis = false
    private val gains = scala.collection.mutable.ArrayBuffer.empty[Int]
    private val inputs = scala.collection.mutable.ArrayBuffer.empty[GainInput]
    def step(input: GainInput): Option[Int] = {
      val entry = table((if (halves == 2 && axis) entries else 0) + index)
      val value = (entry & 65535).toInt
      val slope = signed(entry >> 16, 16)
      gains += math.max(0, math.min(65535, value + ((slope * fraction) >> 8)))
      inputs += input
      val shift = 31 - input.zoom - Integer.numberOfTrailingZeros(entries)
      if ((offset & 0x80000000L) != 0) { index = 0; fraction = 0 }
      else if ((offset >> (31 - input.zoom)) != 0) { index = entries - 1; fraction = 0 }
      else {
        index = ((offset >> shift) & (entries - 1)).toInt
        fraction = ((offset >> (shift - 8)) & 255).toInt
      }
      axis = input.half
      offset = (input.increment - input.base) & 0xffffffffL
      if (input.write) table(input.address) = input.data
      val cycle = inputs.size - 1
      if (cycle < 8) None else {
        val scale = inputs(cycle - 2)
        val gain = if (scale.enabled) gains(cycle - 6) else 32768
        Some(math.min(65535, ((scale.amplitude.toLong * gain) >> 15).toInt))
      }
    }
  }
}
