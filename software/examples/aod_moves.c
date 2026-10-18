/* AodSoc firmware example and CPU-in-the-loop integration fixture.
 * Seven separated pulsed movements; another trap must support atoms in the
 * zero-output intervals. Start times count DSP clocks, targeted at 400 MHz.
 */
#include "riscq_aod.h"
volatile uint32_t starts[7];
static void stage(unsigned variant) {
    aod_write(RQ_AOD_RATIO, 32 + (variant & 1));
    aod_write(RQ_AOD_DECIMATION, (variant & 1) ? 4 : 1);
    for (unsigned axis = 0; axis < RQ_AOD_AXES; ++axis) {
        uint32_t base = rq_aod_channels[axis];
        aod_write(base, 3);               /* two enabled tones per axis */
        aod_write(base + 4, 0);           /* upper mask, if present */
        aod_write(base + 8, 12000 + variant * 2000 + axis * 500);
        aod_write(base + 20, 1);          /* gain calibration enabled, zoom 0 */
        aod_write(base + 24, 0);
        for (unsigned tone = 0; tone < RQ_AOD_TONES; ++tone)
            aod_tone(axis, tone, 0x01000000 + variant * 0x70000 + tone * 0x100000 + axis * 0x300000,
                     (variant & 1) ? -0x100000 : 0x200000,
                     variant * 0x10000000 + axis * 0x2000000 + tone * 0x3000000,
                     0x6000 + variant, 0x7000 + variant);
    }
}
int main(void) {
    stage(0);
    uint32_t t = now() + 4096;
    for (unsigned i = 0; i < 7; ++i) {
        if (i) stage(i % 3);    /* next parameters may change during the active job */
        starts[i] = t;
        aod_fire_at(t);
        /* Pace the finite queue; do not enqueue the whole experiment up front.
         * wait_until shares the existing RISC-Q time comparator, no new ISA. */
        wait_until(t + 16);
        t += aod_start_spacing(33) + 32768;
    }
    wait_until(starts[6] + aod_start_spacing(32));
    RQ_MMIO(RQ_HOSTWIN_BASE) = starts[6]; /* optional result path to PS DDR */
    return 0;
}
