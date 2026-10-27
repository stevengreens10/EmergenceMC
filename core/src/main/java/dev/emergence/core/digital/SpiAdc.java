package dev.emergence.core.digital;

import java.util.List;

/**
 * Serial analog-to-digital converter (manufactured, MCP3001-class): a successive-approximation converter that turns
 * the voltage on its input into a number, read out bit by bit over a serial (SPI) bus.
 * <ul>
 *   <li>Taking chip select (cs) low samples the input (sample-and-hold) and presents the result's most significant
 *       bit on dout; each falling edge of the serial clock (sclk) then presents the next bit, so a master reading on
 *       rising edges gets the code MSB first, {@code bits} of them; after the last, dout reads low.</li>
 *   <li>The code is the input as a fraction of the reference — here its supply (ratiometric: a sensor fed from the
 *       same supply reads the same whatever the supply), {@code round(v/V_DD·(2^bits − 1))}, clamped — with ±½ LSB
 *       of noise (a fixed pseudo-random sequence: the same run gives the same codes).</li>
 *   <li>A clock faster than its 1 MHz rating leaves the comparator unsettled: that bit reads as the one before.</li>
 *   <li>Quiescent supply 300 µA.</li>
 * </ul>
 * Pins: inputs cs, sclk, vin (analog); output dout.
 */
public final class SpiAdc implements DigitalComponent {
    public static final double MAX_SCLK = 1e6;
    private final int bits;
    private boolean selected, dout;
    private int code, next;
    private double lastFall = Double.NEGATIVE_INFINITY;
    private long seed = 0x9E3779B97F4A7C15L, samples;
    private int lastCode = -1;

    public SpiAdc(int bits) {
        this.bits = bits;
    }

    @Override
    public List<String> inputs() {
        return List.of("cs", "sclk", "vin");
    }

    @Override
    public List<String> outputs() {
        return List.of("dout");
    }

    @Override
    public int[] analogInputs() {
        return new int[]{2};
    }

    @Override
    public void input(int k, boolean level, double t, Ctx ctx) {
        if (k == 0) {
            if (!level && !selected) {
                selected = true;
                // sample and hold, convert
                double v = ctx.analog(2, t), vref = Math.max(ctx.supply(), 1e-6);
                int full = (1 << bits) - 1;
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                double noise = ((seed >>> 11) * 0x1.0p-53) - 0.5;
                code = (int) Math.max(0, Math.min(full, Math.round(v / vref * full + noise)));
                lastCode = code;
                samples++;
                next = bits - 1;
                set(ctx, ((code >> next) & 1) != 0);
            } else if (level && selected) {
                selected = false;
                set(ctx, false);
            }
        } else if (k == 1 && selected && !level) {
            double period = 2 * (t - lastFall);
            lastFall = t;
            if (next <= 0) {
                set(ctx, false);
                next = -1;
                return;
            }
            next--;
            // faster than rated: the comparator has not settled and the bit repeats the one before
            boolean b = period < 1 / MAX_SCLK ? dout : ((code >> next) & 1) != 0;
            set(ctx, b);
        }
    }

    private void set(Ctx ctx, boolean level) {
        dout = level;
        ctx.drive(0, level, 100e-9); // output valid 100 ns after the edge
    }

    @Override
    public void powerUp(double t, Ctx ctx) {
        selected = false;
        set(ctx, false);
    }

    @Override
    public boolean output(int k) {
        return dout;
    }

    @Override
    public double energyPerEdge(double v) {
        return 5e-12 * v * v;
    }

    @Override
    public double leakage(double v) {
        return 300e-6 * Math.max(0, v) / 5;
    }

    /** The last code converted (−1 before the first), and conversions so far. */
    public int lastCode() {
        return lastCode;
    }

    public long samples() {
        return samples;
    }

    public int bits() {
        return bits;
    }
}
