#ifndef RISCQ_AOD_H
#define RISCQ_AOD_H
#include <stdint.h>
#include "riscq_map.h"
#define RQ_MMIO(a) (*(volatile uint32_t *)(uintptr_t)(a))
#define RQ_PARAM __attribute__((section(".data")))
static inline uint32_t now(void) { return RQ_MMIO(RQ_CTRL_TIME); }
static inline void wait_until(uint32_t t) {
    RQ_MMIO(RQ_CTRL_TIME_CMP) = t;
    (void)RQ_MMIO(RQ_CTRL_WAIT_TIME_CMP);
}
/* All parameters must be initialized before the first fire, including disabled
 * tones. Posted writes are ordered; a fire atomically snapshots the next job.
 * These helpers use raw fixed-point codes from aod/INTERFACE.md. */
static inline void aod_write(uint32_t offset, uint32_t value) {
    RQ_MMIO(RQ_AOD + offset) = value;
}
static inline void aod_tone(unsigned axis, unsigned tone, uint32_t frequency,
                            int32_t displacement, uint32_t phase0, uint16_t cal_a, uint16_t cal_b) {
    aod_write(rq_aod_tones[axis][tone][0], frequency);
    aod_write(rq_aod_tones[axis][tone][1], (uint32_t)displacement);
    aod_write(rq_aod_tones[axis][tone][2], phase0);
    aod_write(rq_aod_tones[axis][tone][3], cal_a | ((uint32_t)cal_b << 16));
}
static inline void aod_fire_at(uint32_t time) {
    aod_write(RQ_AOD_START, time);
    aod_write(RQ_AOD_FIRE, 1);
}
/* Sufficient spacing between start times; includes prep and drain, independent
 * of decimation. The programmer must still pace the finite FIFO with wait_until.
 * Keep every future timestamp within the signed 32-bit half-window. */
static inline uint32_t aod_start_spacing(uint32_t ratio) {
    return RQ_AOD_MOVE_STEPS * ratio + RQ_AOD_LEAD_TIME +
           RQ_AOD_AXES * (RQ_AOD_TONES + 8) + 64;
}
#endif
