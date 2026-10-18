// Simulation-only observation adapter. Original RTL is instantiated unchanged.
// No stimulus, assertions, or state belong in this adapter.
`default_nettype none
module aod_top_probe #(
    parameter integer NT=4, NCH=2,
    parameter UNIT_FILE="aod_unit.mem", SEED_FILE="aod_seed.mem"
)(
    input  wire                 clk,
    input  wire                 rst,
    input  wire        [15:0]   s_axi_awaddr,
    input  wire        [2:0]    s_axi_awprot,
    input  wire                 s_axi_awvalid,
    output wire                 s_axi_awready,
    input  wire        [31:0]   s_axi_wdata,
    input  wire        [3:0]    s_axi_wstrb,
    input  wire                 s_axi_wvalid,
    output wire                 s_axi_wready,
    output wire        [1:0]    s_axi_bresp,
    output wire                 s_axi_bvalid,
    input  wire                 s_axi_bready,
    input  wire        [15:0]   s_axi_araddr,
    input  wire        [2:0]    s_axi_arprot,
    input  wire                 s_axi_arvalid,
    output wire                 s_axi_arready,
    output wire        [31:0]   s_axi_rdata,
    output wire        [1:0]    s_axi_rresp,
    output wire                 s_axi_rvalid,
    input  wire                 s_axi_rready,
    input  wire                 trig_in,
    output wire        [31:0]   dac_x,
    output wire        [31:0]   dac_y,
    input  wire                 probe_ch,
    input  wire        [5:0]    probe_tone,
    input  wire        [7:0]    probe_addr,
    output wire        [31:0]   probe_table, probe_urom, probe_srom, probe_base,
    output wire        [1:0]    probe_zoom,
    output wire                 probe_enable,
    output wire        [7:0]    probe_index,
    output wire        [15:0]   probe_gain, probe_ce_gain
);
    aod_tg_top #(
        .NT(NT), .NCH(NCH), .SPC(2), .ACC(32),
        .UW(30), .CALW(16), .DAC_BITS(16), .DAC_PHYS(14),
        .AXI_AW(16), .RATIO_MAX(65535), .DEC_MAX(16), .GCAL_TABN(256),
        .RDIV_EXACT(17), .MOVE_STEPS(6250), .NSEG(64), .SEG_LEN(98),
        .UNIT_FILE(UNIT_FILE), .SEED_FILE(SEED_FILE)
    ) dut (
        .clk(clk),
        .rst(rst),
        .s_axi_awaddr(s_axi_awaddr),
        .s_axi_awprot(s_axi_awprot),
        .s_axi_awvalid(s_axi_awvalid),
        .s_axi_awready(s_axi_awready),
        .s_axi_wdata(s_axi_wdata),
        .s_axi_wstrb(s_axi_wstrb),
        .s_axi_wvalid(s_axi_wvalid),
        .s_axi_wready(s_axi_wready),
        .s_axi_bresp(s_axi_bresp),
        .s_axi_bvalid(s_axi_bvalid),
        .s_axi_bready(s_axi_bready),
        .s_axi_araddr(s_axi_araddr),
        .s_axi_arprot(s_axi_arprot),
        .s_axi_arvalid(s_axi_arvalid),
        .s_axi_arready(s_axi_arready),
        .s_axi_rdata(s_axi_rdata),
        .s_axi_rresp(s_axi_rresp),
        .s_axi_rvalid(s_axi_rvalid),
        .s_axi_rready(s_axi_rready),
        .trig_in(trig_in),
        .dac_x(dac_x),
        .dac_y(dac_y)
    );
    wire [31:0] tables [0:NCH*NT-1], uroms [0:NCH*NT-1], sroms [0:NCH*NT-1];
    wire [7:0] indices [0:NCH*NT-1];
    wire [15:0] gains [0:NCH*NT-1], ce_gains [0:NCH*NT-1];
    wire [31:0] bases [0:NCH-1];
    wire [1:0] zooms [0:NCH-1];
    wire enables [0:NCH-1];
    for (genvar c=0; c<NCH; c=c+1) begin: p_ch
        assign bases[c] = dut.g_ch[c].u_bank.gb_q;
        assign zooms[c] = dut.g_ch[c].u_bank.gz_q;
        assign enables[c] = dut.g_ch[c].u_bank.ge_q;
        for (genvar t=0; t<NT; t=t+1) begin: p_tone
            assign tables[c*NT+t] = dut.g_ch[c].u_bank.g_tone[t].u_t.u_gcal.tab[probe_addr];
            assign uroms[c*NT+t] = dut.g_ch[c].u_bank.g_tone[t].u_t.urom[probe_addr[5:0]][31:0];
            assign sroms[c*NT+t] = dut.g_ch[c].u_bank.g_tone[t].u_t.srom[probe_addr[5:0]][31:0];
            assign indices[c*NT+t] = dut.g_ch[c].u_bank.g_tone[t].u_t.u_gcal.idx_q;
            assign gains[c*NT+t] = dut.g_ch[c].u_bank.g_tone[t].u_t.u_gcal.g;
            assign ce_gains[c*NT+t] = dut.g_ch[c].u_bank.g_tone[t].u_t.u_gcal.ce_g;
        end
    end
    wire [6:0] selected = (probe_ch ? NT : 0) + probe_tone;
    assign probe_table = tables[selected];
    assign probe_urom = uroms[selected];
    assign probe_srom = sroms[selected];
    assign probe_index = indices[selected];
    assign probe_gain = gains[selected];
    assign probe_ce_gain = ce_gains[selected];
    assign probe_base = bases[probe_ch];
    assign probe_zoom = zooms[probe_ch];
    assign probe_enable = enables[probe_ch];
endmodule
`default_nettype wire
