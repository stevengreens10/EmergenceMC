package dev.emergence.core.instruments;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The analyser's decoders on recorded levels: UART at a found baud rate, framing errors, SPI with and without select. */
class BusDecoderTest {
    /** Records a UART line sending {@code bytes} at {@code baud} from t0 (8N1, idle high); a low stop bit if {@code breakStop}. */
    static void uart(LogicCapture c, int ch, double baud, double t0, int[] bytes, boolean breakStop) {
        double bit = 1 / baud, t = t0;
        int level = 1;
        c.record(ch, 0, 1);
        for (int by : bytes) {
            int[] bits = new int[10];
            for (int b = 0; b < 8; b++) bits[1 + b] = (by >> b) & 1;
            bits[9] = breakStop ? 0 : 1;
            for (int b = 0; b < 10; b++) {
                if (bits[b] != level) {
                    c.record(ch, t + b * bit, bits[b]);
                    level = bits[b];
                }
            }
            t += 12 * bit; // two idle bits between bytes
            if (level == 0) {
                c.record(ch, t - 2 * bit, 1);
                level = 1;
            }
        }
        c.record(ch, t, 1); // the record runs on, idle, past the last stop bit
    }

    @Test
    void uartAtItsBaudRate() {
        LogicCapture c = new LogicCapture(4096);
        c.configure(0, true);
        uart(c, 0, 9600, 1e-3, new int[]{'H', 'i', 0x55, 0x00, 0xFF}, false);
        // 0x55 alternates every bit: the shortest pulse is one bit time
        assertEquals(9600, BusDecoder.guessBaud(c, 0));
        List<BusDecoder.Frame> f = BusDecoder.uart(c, 0, 9600, 0, 1);
        assertEquals(5, f.size(), "frames: " + f);
        assertEquals('H', f.get(0).value());
        assertEquals('i', f.get(1).value());
        assertEquals(0x55, f.get(2).value());
        assertEquals(0x00, f.get(3).value());
        assertEquals(0xFF, f.get(4).value());
        assertTrue(f.stream().noneMatch(BusDecoder.Frame::error));
        assertEquals(1e-3, f.get(0).start(), 1e-12);
        // at the wrong rate the bytes come out wrong
        assertNotEquals('H', BusDecoder.uart(c, 0, 4800, 0, 1).get(0).value());
    }

    @Test
    void aLowStopBitIsAFramingError() {
        LogicCapture c = new LogicCapture(4096);
        c.configure(0, true);
        uart(c, 0, 115200, 1e-4, new int[]{0x41}, true);
        List<BusDecoder.Frame> f = BusDecoder.uart(c, 0, 115200, 0, 1);
        assertEquals(0x41, f.get(0).value());
        assertTrue(f.get(0).error());
        assertEquals("41!", f.get(0).text());
    }

    /** SPI mode 0, MSB first: two bytes in one transfer, a stray clock outside it, a partial byte cut off by the select. */
    @Test
    void spiWithItsSelect() {
        LogicCapture c = new LogicCapture(4096);
        for (int k = 0; k < 3; k++) c.configure(k, true);
        double p = 1e-6, t = 10e-6;
        c.record(0, 0, 0);
        c.record(1, 0, 0);
        c.record(2, 0, 1);
        // a clock edge while not selected: ignored
        c.record(0, 2e-6, 1);
        c.record(0, 3e-6, 0);
        c.record(2, t - p, 0);
        int[] bytes = {0xA5, 0x3C};
        for (int by : bytes) {
            for (int b = 7; b >= 0; b--) {
                c.record(1, t, (by >> b) & 1);      // data set up while the clock is low
                c.record(0, t + p / 2, 1);          // sampled on the rise
                c.record(0, t + p, 0);
                t += p;
            }
        }
        // three bits, then the select is released: no byte
        for (int b = 0; b < 3; b++) {
            c.record(0, t + p / 2, 1);
            c.record(0, t + p, 0);
            t += p;
        }
        c.record(2, t + p, 1);
        List<BusDecoder.Frame> f = BusDecoder.spi(c, 0, 1, 2, true, 0, 1);
        assertEquals(2, f.size(), "two whole bytes: " + f);
        assertEquals(0xA5, f.get(0).value());
        assertEquals(0x3C, f.get(1).value());
        // without the select the stray edge shifts every bit after it: the select matters
        assertNotEquals(0xA5, BusDecoder.spi(c, 0, 1, -1, true, 0, 1).get(0).value());
    }

    @Test
    void csvHasARowForEveryChange() {
        LogicCapture c = new LogicCapture(64);
        c.configure(0, true);
        c.configure(2, false);
        c.record(0, 0, 0);
        c.record(2, 0, 1.25);
        c.record(0, 1e-3, 1);
        c.record(2, 2e-3, 3.5);
        String csv = BusDecoder.csv(c, new String[]{"clk", "", "vout"});
        List<String> rows = csv.lines().toList();
        assertEquals("time_s,\"clk (level)\",\"vout (V)\"", rows.get(0));
        assertEquals(4, rows.size());
        assertEquals("0,1.2500", rows.get(1).substring(rows.get(1).indexOf(',') + 1));
        assertEquals("1,1.2500", rows.get(2).substring(rows.get(2).indexOf(',') + 1));
        assertEquals("1,3.5000", rows.get(3).substring(rows.get(3).indexOf(',') + 1));
        assertEquals(1e-3, Double.parseDouble(rows.get(2).split(",")[0]), 1e-15);
    }
}
