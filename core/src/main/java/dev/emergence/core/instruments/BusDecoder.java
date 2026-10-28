package dev.emergence.core.instruments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * A logic analyser's protocol decoders: the bytes on a serial bus, read back from the levels it recorded, as a real
 * analyser's are — from the edges, not from what the chips meant to send.
 * <ul>
 *   <li><b>SPI</b>: a clock, a data line and (optionally) an active-low select. Each sampling edge of the clock (rising:
 *       modes 0 and 3) takes the data line's level, most significant bit first; eight make a byte. The select's rising
 *       edge (end of a transfer) drops a partial byte; without a select, a pause of ten clock periods does.</li>
 *   <li><b>UART</b>: one line, idle high. A falling edge starts a frame; its eight data bits (least significant first)
 *       are read at the middles of their bit times, then the stop bit, which must be high (else a framing error). The
 *       bit time comes from the baud rate, or is found from the shortest pulse on the line and set to the nearest
 *       standard rate.</li>
 * </ul>
 * And the capture as CSV, one row for each moment any channel changed, every channel's value then.
 */
public final class BusDecoder {
    private BusDecoder() {
    }

    /** A decoded byte on {@code channel}, from {@code start} to {@code end} [s]; {@code error}: a framing error. */
    public record Frame(int channel, double start, double end, int value, boolean error) {
        public String text() {
            return String.format("%02X", value) + (error ? "!" : "");
        }
    }

    public static final double[] STANDARD_BAUDS = {300, 600, 1200, 2400, 4800, 9600, 14400, 19200, 38400, 57600, 115200, 230400, 460800, 921600};

    /** The first event index on channel ch at or after time t0. */
    private static int firstAtOrAfter(LogicCapture c, int ch, double t0) {
        int lo = 0, hi = c.size(ch) - 1, first = c.size(ch);
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (c.time(ch, mid) >= t0) {
                first = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return first;
    }

    /**
     * SPI bytes clocked within [t0, t1]: clock channel clk, data channel data, select cs (−1: none), sampled on the
     * clock's rising edges (or falling, {@code rising} false). Frames are reported on the data channel.
     */
    public static List<Frame> spi(LogicCapture c, int clk, int data, int cs, boolean rising, double t0, double t1) {
        List<Frame> out = new ArrayList<>();
        int n = c.size(clk);
        int bits = 0, value = 0;
        double start = 0, lastEdge = Double.NEGATIVE_INFINITY, period = Double.POSITIVE_INFINITY;
        int csIdx = cs >= 0 ? firstAtOrAfter(c, cs, t0) : 0;
        for (int i = Math.max(1, firstAtOrAfter(c, clk, t0)); i < n; i++) {
            double t = c.time(clk, i);
            if (t > t1) break;
            boolean now = c.value(clk, i) > 0.5, was = c.value(clk, i - 1) > 0.5;
            if (now == was || now != rising) continue;
            // a transfer ended (select released) since the last edge, or a long pause: start afresh
            if (cs >= 0) {
                boolean released = false;
                while (csIdx < c.size(cs) && c.time(cs, csIdx) <= t) {
                    if (c.value(cs, csIdx) > 0.5) released = true;
                    csIdx++;
                }
                if (released) bits = 0;
                if (c.valueAt(cs, t) > 0.5) continue; // not selected: not for this bus's device
            } else if (t - lastEdge > 10 * period) {
                bits = 0;
            } else if (Double.isFinite(lastEdge)) {
                period = t - lastEdge;
            }
            lastEdge = t;
            if (bits == 0) {
                start = t;
                value = 0;
            }
            value = (value << 1) | (c.valueAt(data, t) > 0.5 ? 1 : 0);
            if (++bits == 8) {
                out.add(new Frame(data, start, t, value, false));
                bits = 0;
            }
        }
        return out;
    }

    /** The shortest time between two changes on a channel [s] (NaN if fewer than two). */
    public static double shortestPulse(LogicCapture c, int ch) {
        double m = Double.NaN;
        for (int i = 1; i < c.size(ch); i++) {
            double d = c.time(ch, i) - c.time(ch, i - 1);
            if (d > 0 && !(d >= m)) m = d;
        }
        return m;
    }

    /** The standard baud rate nearest the shortest pulse on a line (NaN if it has not switched). */
    public static double guessBaud(LogicCapture c, int ch) {
        double p = shortestPulse(c, ch);
        if (!(p > 0)) return Double.NaN;
        double best = STANDARD_BAUDS[0];
        for (double b : STANDARD_BAUDS) if (Math.abs(Math.log(b * p)) < Math.abs(Math.log(best * p))) best = b;
        return best;
    }

    /** UART bytes (8 data bits, no parity, one stop bit) starting within [t0, t1] on channel ch at {@code baud}. */
    public static List<Frame> uart(LogicCapture c, int ch, double baud, double t0, double t1) {
        List<Frame> out = new ArrayList<>();
        if (!(baud > 0)) return out;
        double bit = 1 / baud;
        int n = c.size(ch);
        double busyUntil = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(1, firstAtOrAfter(c, ch, t0)); i < n; i++) {
            double t = c.time(ch, i);
            if (t > t1) break;
            if (t < busyUntil) continue;
            boolean now = c.value(ch, i) > 0.5, was = c.value(ch, i - 1) > 0.5;
            if (!(was && !now)) continue; // a start bit begins with a fall from idle
            // the start bit must still be low at its middle (else a glitch)
            if (c.valueAt(ch, t + 0.5 * bit) > 0.5) continue;
            int value = 0;
            for (int b = 0; b < 8; b++) if (c.valueAt(ch, t + (1.5 + b) * bit) > 0.5) value |= 1 << b;
            double stopAt = t + 9.5 * bit;
            if (stopAt > c.newest()) break; // not all recorded yet
            boolean framing = !(c.valueAt(ch, stopAt) > 0.5);
            out.add(new Frame(ch, t, t + 10 * bit, value, framing));
            busyUntil = t + 9.5 * bit;
        }
        return out;
    }

    /**
     * The capture as CSV: a header (time_s and each used channel's name, with its unit), then a row at every moment
     * any channel recorded, each channel's value at that moment (its level 0/1, or its voltage), empty before its
     * first record.
     */
    public static String csv(LogicCapture c, String[] names) {
        TreeSet<Double> times = new TreeSet<>();
        List<Integer> chans = new ArrayList<>();
        for (int k = 0; k < LogicCapture.CHANNELS; k++) {
            if (!c.used(k)) continue;
            chans.add(k);
            for (int i = 0; i < c.size(k); i++) times.add(c.time(k, i));
        }
        StringBuilder b = new StringBuilder("time_s");
        for (int k : chans) {
            String name = names != null && k < names.length && names[k] != null && !names[k].isEmpty() ? names[k] : "CH" + (k + 1);
            b.append(',').append('"').append(name.replace("\"", "'")).append(c.digital(k) ? " (level)" : " (V)").append('"');
        }
        b.append('\n');
        for (double t : times) {
            b.append(String.format(Locale.ROOT, "%.9g", t));
            for (int k : chans) {
                double v = c.valueAt(k, t);
                b.append(',');
                if (!Double.isNaN(v)) b.append(c.digital(k) ? String.valueOf((int) Math.round(v)) : String.format(Locale.ROOT, "%.5g", v));
            }
            b.append('\n');
        }
        return b.toString();
    }
}
