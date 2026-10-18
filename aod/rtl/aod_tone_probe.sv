// Simulation-only observation adapter. Original RTL is instantiated unchanged.
// No stimulus, assertions, or state belong in this adapter.
`default_nettype none
module aod_tone_probe #(
    parameter UNIT_FILE="aod_unit.mem", SEED_FILE="aod_seed.mem"
)(
    input  wire                 clk,
    input  wire                 rst,
    input  wire signed [31:0]   d_word,
    input  wire        [31:0]   a_word,
    input  wire        [9:0]    ratio,
    input  wire        [4:0]    dec,
    input  wire                 rom_we,
    input  wire        [2:0]    rom_sel,
    input  wire        [6:0]    rom_addr,
    input  wire        [1:0]    rom_beat,
    input  wire        [31:0]   rom_wdata,
    input  wire                 gcal_we,
    input  wire        [7:0]    gcal_addr,
    input  wire        [31:0]   gcal_base,
    input  wire        [1:0]    gcal_zoom,
    input  wire                 gcal_en,
    input  wire        [31:0]   phase0,
    input  wire        [15:0]   cal_a,
    input  wire        [15:0]   cal_b,
    input  wire                 en,
    input  wire        [15:0]   amp_scale,
    input  wire                 go,
    input  wire                 cw,
    output wire                 busy,
    output wire                 done,
    output wire        [31:0]   phi_acc,
    output wire                 go_drop,
    output wire signed [15:0]   dac,
    output wire signed [15:0]   dac_q,
    output wire                 dac_valid,
    output wire        [31:0]   probe_inc, probe_phi,
    output wire                 probe_osc, probe_run, probe_end
);
    aod_tg_tone1 #(
        .ACC(32), .UW(30), .DW(8), .NDIFF(3),
        .AMP_FRAC(16), .CALW(16), .CAL_FRAC(15), .DAC_BITS(16),
        .RATIO_MAX(512), .DEC_MAX(16), .MOVE_STEPS(6250), .NSEG(64),
        .SEG_LEN(98), .TW(23), .NST(18), .SHIFT(3),
        .XW(20), .IPS(1), .UNIT_FILE(UNIT_FILE), .SEED_FILE(SEED_FILE)
    ) dut (
        .clk(clk),
        .rst(rst),
        .d_word(d_word),
        .a_word(a_word),
        .ratio(ratio),
        .dec(dec),
        .rom_we(rom_we),
        .rom_sel(rom_sel),
        .rom_addr(rom_addr),
        .rom_beat(rom_beat),
        .rom_wdata(rom_wdata),
        .gcal_we(gcal_we),
        .gcal_addr(gcal_addr),
        .gcal_base(gcal_base),
        .gcal_zoom(gcal_zoom),
        .gcal_en(gcal_en),
        .phase0(phase0),
        .cal_a(cal_a),
        .cal_b(cal_b),
        .en(en),
        .amp_scale(amp_scale),
        .go(go),
        .cw(cw),
        .busy(busy),
        .done(done),
        .phi_acc(phi_acc),
        .go_drop(go_drop),
        .dac(dac),
        .dac_q(dac_q),
        .dac_valid(dac_valid)
    );
    assign probe_inc = dut.inc_live;
    assign probe_phi = dut.phi;
    assign probe_osc = dut.osc;
    assign probe_run = dut.run;
    assign probe_end = dut.mv_end;
endmodule
`default_nettype wire
