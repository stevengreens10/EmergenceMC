package dev.emergence.core.digital;

import java.util.List;

/**
 * Monochrome OLED display module (manufactured: a 128×64 panel and its controller, SSD1306-class), written over a
 * serial (SPI) bus. The panel's pixels are not simulated one by one; its controller is, as the datasheet describes it:
 * <ul>
 *   <li>Serial input: while chip select (cs) is low, a bit is taken from din on each rising edge of sclk, most
 *       significant first; every eighth bit completes a byte — a command if d/c is low then, display data if high.
 *       A clock faster than its 10 MHz rating loses bits (that edge is ignored).</li>
 *   <li>Display memory: 8 pages of 128 bytes, each byte a column of 8 pixels (bit 0 on top). Data is written at the
 *       current page and column, which then advance as the addressing mode says: page mode (the default) moves
 *       along the page, horizontal mode on to the next page at the end of the column range, vertical down the
 *       pages first.</li>
 *   <li>Commands: AE/AF display off/on (off at power-up); 20 n addressing mode (0 horizontal, 1 vertical, 2 page);
 *       21 a b column range, 22 a b page range (horizontal and vertical modes); B0–B7 page (page mode); 00–0F, 10–1F
 *       the column's low and high nibbles (page mode); 81 n contrast; A6/A7 normal/inverted; A4/A5 memory/all on;
 *       the setup commands that take one argument (8D charge pump, A8, D3, D5, D9, DA, DB) accepted and ignored.
 *       rst low resets the controller.</li>
 *   <li>Supply: 300 µA for the controller, and when on the panel's current — about 2.5 µA per lit pixel at full
 *       contrast (a full white screen ≈ 20 mA), in proportion to the contrast.</li>
 * </ul>
 * Pins: inputs cs, dc, sclk, din, rst. The image is {@link #pixel}; {@link #version} counts its changes.
 */
public final class DisplayPanel implements DigitalComponent {
    public static final int WIDTH = 128, HEIGHT = 64, PAGES = HEIGHT / 8;
    public static final double MAX_SCLK = 10e6;
    private final byte[] ram = new byte[PAGES * WIDTH];
    private boolean on, inverted, allOn, selected;
    private int contrast = 0x7f, mode = 2, page, col, colStart, colEnd = WIDTH - 1, pageStart, pageEnd = PAGES - 1;
    private int shift, nbits, argsLeft, pendingCmd = -1;
    private final int[] args = new int[2];
    private int argCount;
    private boolean dc, rstLevel = true;
    private double lastRise = Double.NEGATIVE_INFINITY;
    private int lit;
    private long version, bytes;

    @Override
    public List<String> inputs() {
        return List.of("cs", "dc", "sclk", "din", "rst");
    }

    @Override
    public List<String> outputs() {
        return List.of();
    }

    @Override
    public void input(int k, boolean level, double t, Ctx ctx) {
        switch (k) {
            case 0 -> {
                selected = !level;
                if (selected) {
                    nbits = 0;
                    shift = 0;
                }
            }
            case 1 -> dc = level;
            case 2 -> {
                if (!level || !selected || !rstLevel) return;
                double period = t - lastRise;
                lastRise = t;
                if (period < 1 / MAX_SCLK) return;
                shift = (shift << 1) | (din ? 1 : 0);
                if (++nbits == 8) {
                    nbits = 0;
                    take(shift & 0xff);
                    shift = 0;
                }
            }
            case 3 -> din = level;
            case 4 -> {
                if (rstLevel && !level) reset();
                rstLevel = level;
            }
            default -> { }
        }
    }

    private boolean din;

    private void take(int b) {
        bytes++;
        awaiting = false;
        if (dc) {
            data(b);
            return;
        }
        if (argsLeft > 0) {
            args[argCount++] = b;
            if (--argsLeft == 0) command(pendingCmd, args);
            return;
        }
        int n = switch (b) {
            case 0x20, 0x81, 0x8D, 0xA8, 0xD3, 0xD5, 0xD9, 0xDA, 0xDB -> 1;
            case 0x21, 0x22 -> 2;
            default -> 0;
        };
        if (n > 0) {
            pendingCmd = b;
            argsLeft = n;
            argCount = 0;
        } else {
            command(b, args);
        }
    }

    private void command(int c, int[] a) {
        if (c == 0xAE) on = false;
        else if (c == 0xAF) on = true;
        else if (c == 0x20) mode = a[0] & 3;
        else if (c == 0x21) {
            colStart = a[0] & 0x7f;
            colEnd = a[1] & 0x7f;
            col = colStart;
        } else if (c == 0x22) {
            pageStart = a[0] & 7;
            pageEnd = a[1] & 7;
            page = pageStart;
        } else if (c >= 0xB0 && c <= 0xB7) page = c & 7;
        else if (c <= 0x0F) col = (col & 0x70) | (c & 0x0F);
        else if (c >= 0x10 && c <= 0x1F) col = ((c & 0x07) << 4) | (col & 0x0F);
        else if (c == 0x81) contrast = a[0];
        else if (c == 0xA6) inverted = false;
        else if (c == 0xA7) inverted = true;
        else if (c == 0xA4) allOn = false;
        else if (c == 0xA5) allOn = true;
        else return;
        version++;
    }

    private void data(int b) {
        int i = page * WIDTH + col;
        int old = ram[i] & 0xff;
        if (old != b) {
            lit += Integer.bitCount(b) - Integer.bitCount(old);
            ram[i] = (byte) b;
            version++;
        }
        switch (mode) {
            case 0 -> { // horizontal: along the column range, then the next page
                if (++col > colEnd) {
                    col = colStart;
                    if (++page > pageEnd) page = pageStart;
                }
            }
            case 1 -> { // vertical: down the page range, then the next column
                if (++page > pageEnd) {
                    page = pageStart;
                    if (++col > colEnd) col = colStart;
                }
            }
            default -> col = (col + 1) & 0x7f; // page mode: along the page, wrapping
        }
    }

    private void reset() {
        on = inverted = allOn = false;
        contrast = 0x7f;
        mode = 2;
        page = col = colStart = pageStart = 0;
        colEnd = WIDTH - 1;
        pageEnd = PAGES - 1;
        argsLeft = 0;
        nbits = 0;
        version++;
    }

    @Override
    public void powerUp(double t, Ctx ctx) {
        reset();
        java.util.Arrays.fill(ram, (byte) 0);
        lit = 0;
        // its inputs start low, as the domain has them (it tells a component only of those already high): cs low is
        // selected. (Starting deselected, a controller whose chip held cs low — a gauge saved with its world, which keeps
        // its only device selected — never heard a byte again after a reload.)
        selected = true;
        nbits = 0;
        shift = 0;
        awaiting = true;
        applyRestore();
    }

    /** Powered up and not yet sent a byte since (what it shows is only what it was restored with). */
    private boolean awaiting;

    /**
     * Whether it is on but has heard nothing since it powered up: what it shows was saved with its machine, not drawn
     * now. The game shows "NO SIGNAL" for it then (see {@link #noSignalImage}), not a reading that is not being read.
     */
    public boolean awaitingData() {
        return on && awaiting;
    }

    /** A screen's image saying it has no signal: the words in a frame (8 pages of 128 column bytes). */
    public static byte[] noSignalImage() {
        byte[] img = new byte[WIDTH * PAGES];
        String[] glyphs = {
                "N:10001,11001,10101,10011,10001,10001,10001", "O:01110,10001,10001,10001,10001,10001,01110",
                "S:01111,10000,10000,01110,00001,00001,11110", "I:01110,00100,00100,00100,00100,00100,01110",
                "G:01110,10001,10000,10111,10001,10001,01111", "A:01110,10001,10001,11111,10001,10001,10001",
                "L:10000,10000,10000,10000,10000,10000,11111"};
        java.util.Map<Character, String[]> font = new java.util.HashMap<>();
        for (String g : glyphs) font.put(g.charAt(0), g.substring(2).split(","));
        String text = "NO SIGNAL";
        int scale = 2, x0 = (WIDTH - text.length() * 6 * scale) / 2, y0 = (HEIGHT - 7 * scale) / 2;
        for (int i = 0; i < text.length(); i++) {
            String[] rows = font.get(text.charAt(i));
            if (rows == null) continue;
            for (int r = 0; r < 7; r++)
                for (int c = 0; c < 5; c++)
                    if (rows[r].charAt(c) == '1')
                        for (int dy = 0; dy < scale; dy++)
                            for (int dx = 0; dx < scale; dx++) set(img, x0 + (i * 6 + c) * scale + dx, y0 + r * scale + dy);
        }
        // a dashed frame round the screen
        for (int x = 0; x < WIDTH; x += 2) {
            set(img, x, 0);
            set(img, x, HEIGHT - 1);
        }
        for (int y = 0; y < HEIGHT; y += 2) {
            set(img, 0, y);
            set(img, WIDTH - 1, y);
        }
        return img;
    }

    private static void set(byte[] img, int x, int y) {
        if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) img[(y >> 3) * WIDTH + x] |= (byte) (1 << (y & 7));
    }

    /** Without power its memory is lost and the panel goes dark (it was showing its last image for ever). */
    @Override
    public void powerDown(double t) {
        reset();
        java.util.Arrays.fill(ram, (byte) 0);
        lit = 0;
    }

    @Override
    public boolean output(int k) {
        return false;
    }

    @Override
    public double energyPerEdge(double v) {
        return 2e-12 * v * v;
    }

    /** The controller's quiescent current and, when on, the lit pixels' [A]. */
    @Override
    public double leakage(double v) {
        double panel = on ? 2.5e-6 * shown() * contrast / 255.0 : 0;
        return (300e-6 + panel) * Math.max(0, v) / 5;
    }

    /** Pixels lit as shown (after inversion and all-on). */
    public int shown() {
        if (allOn) return WIDTH * HEIGHT;
        return inverted ? WIDTH * HEIGHT - lit : lit;
    }

    /** Whether the pixel at column x, row y (0 at the top) is lit on the screen (dark while the display is off). */
    public boolean pixel(int x, int y) {
        if (!on) return false;
        if (allOn) return true;
        boolean b = ((ram[(y >> 3) * WIDTH + x] >> (y & 7)) & 1) != 0;
        return b != inverted;
    }

    /** The display memory (8 pages of 128 column bytes; read-only use). */
    public byte[] memory() {
        return ram;
    }

    public boolean on() {
        return on;
    }

    public boolean inverted() {
        return inverted;
    }

    public int contrast() {
        return contrast;
    }

    /** Changes to what is shown so far (to send a client only a changed image). */
    public long version() {
        return version;
    }

    /** Bytes received over the bus. */
    public long bytes() {
        return bytes;
    }

    @Override
    public long[] save() {
        long[] s = new long[ram.length / 8 + 2];
        for (int i = 0; i < ram.length; i++) s[i / 8] |= (ram[i] & 0xffL) << (8 * (i % 8));
        s[ram.length / 8] = (on ? 1 : 0) | (inverted ? 2 : 0) | (allOn ? 4 : 0) | ((long) contrast << 8) | ((long) mode << 16);
        s[ram.length / 8 + 1] = page | ((long) col << 8) | ((long) colStart << 16) | ((long) colEnd << 24) | ((long) pageStart << 32) | ((long) pageEnd << 40);
        return s;
    }

    @Override
    public void restore(long[] s) {
        if (s == null || s.length != ram.length / 8 + 2) return;
        restoring = s.clone();
    }

    private long[] restoring;

    /** Applies a restored state (after power-up, which clears the memory). */
    void applyRestore() {
        long[] s = restoring;
        if (s == null) return;
        restoring = null;
        lit = 0;
        for (int i = 0; i < ram.length; i++) {
            ram[i] = (byte) (s[i / 8] >>> (8 * (i % 8)));
            lit += Integer.bitCount(ram[i] & 0xff);
        }
        long f = s[ram.length / 8], g = s[ram.length / 8 + 1];
        on = (f & 1) != 0;
        inverted = (f & 2) != 0;
        allOn = (f & 4) != 0;
        contrast = (int) ((f >> 8) & 0xff);
        mode = (int) ((f >> 16) & 3);
        page = (int) (g & 7);
        col = (int) ((g >> 8) & 0x7f);
        colStart = (int) ((g >> 16) & 0x7f);
        colEnd = (int) ((g >> 24) & 0x7f);
        pageStart = (int) ((g >> 32) & 7);
        pageEnd = (int) ((g >> 40) & 7);
        version++;
    }
}
