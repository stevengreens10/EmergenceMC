package dev.emergence.mod.client;

import dev.emergence.core.digital.ChipDesign;
import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.units.Units;
import dev.emergence.mod.net.ChipProbePayload;
import dev.emergence.mod.net.ChipTextPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The chip designer: a chip's design in the chip language, edited with its errors, its synthesis estimates (size,
 * speed, power) and the pins it will have, as one types. Opened on a chip in hand (from the part editor) or on a
 * placed chip (right-click it with an empty hand), where it also shows the chip running: powered or not, its pins'
 * levels, its registers. Writing it to a placed chip rebuilds the chip with the new design, its row of blocks
 * lengthened or shortened if its pins changed. The mod's example designs are a click away.
 * <p>
 * The panel beside the text has tabs: Check (does it compile, how big and fast it is), Pins (the package, block by
 * block, as the pins will be named on the chip in the world; live levels on a placed chip), Running (a placed chip's
 * registers) and Guide (the language at a glance). It takes about two fifths of the screen, whatever its size.
 */
public final class ChipDesignerScreen extends Screen {
    private static final String[] TABS = {"Check", "Pins", "Running", "Guide"};
    private static final int TAB_H = 14;

    private final Screen parent;
    private final Consumer<String> onDone;
    private final BlockPos pos;
    private final long partId;
    private final String original;
    private CodeEditor editor;
    private String text;
    private ChipDesign compiled;
    private ChipDesign.ChipError error;
    private ChipProbePayload live;
    private int ticks, tab, panelW, panelX;
    /** The process the chip was made in (one cut from a wafer carries its; null: the ready-made chips'). */
    private dev.emergence.core.digital.ChipProcess process;

    /** The chip's settings: its process, if it was cut from a wafer. */
    ChipDesignerScreen madeFrom(java.util.Map<String, Double> params) {
        process = params != null && params.containsKey(dev.emergence.core.digital.ChipProcess.OXIDE) ? dev.emergence.core.digital.ChipProcess.of(params) : null;
        return this;
    }

    private ChipDesignerScreen(Screen parent, Consumer<String> onDone, BlockPos pos, long partId, String text) {
        super(Component.literal("Chip designer"));
        this.parent = parent;
        this.onDone = onDone;
        this.pos = pos;
        this.partId = partId;
        this.original = text == null ? "" : text;
        this.text = original.isBlank() ? TEMPLATE : original;
        compile();
    }

    /** On a chip in hand: Done gives the design back to {@code onDone} (the part editor), Cancel returns to it. */
    public static ChipDesignerScreen forItem(Screen parent, String text, Consumer<String> onDone) {
        return new ChipDesignerScreen(parent, onDone, null, 0, text);
    }

    /** On a placed chip: Write sends the design to the server for every segment of it. */
    public static ChipDesignerScreen forPlaced(BlockPos pos, long partId, String text) {
        return new ChipDesignerScreen(null, null, pos, partId, text);
    }

    /** A new chip's starting point: a small design to change. */
    static final String TEMPLATE = """
            // A new chip. Name it, list its pins, and say what it does on each clock edge.
            // Examples: the menu at the bottom. The language: the Guide tab.

            chip my_chip
            in clk
            out [3:0] q

            reg [3:0] n = 0

            on rise clk {
                n <= n + 1
            }

            assign q = n
            """;

    private void compile() {
        try {
            compiled = ChipDesign.compile(text);
            error = null;
        } catch (ChipDesign.ChipError e) {
            compiled = null;
            error = e;
        }
        if (editor != null) editor.errorLine(error == null ? -1 : error.line - 1);
    }

    boolean placed() {
        return pos != null;
    }

    long partId() {
        return partId;
    }

    void live(ChipProbePayload p) {
        live = p;
    }

    /** Shows a tab of the panel (0 Check, 1 Pins, 2 Running, 3 Guide), as clicking it does. */
    void tab(int t) {
        tab = t;
    }

    /** Whether the server has said the chip is running (for the tour). */
    boolean livePowered() {
        return live != null && live.simulated() && live.powered();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        panelW = Math.max(170, Math.min(260, width * 2 / 5));
        panelX = width - panelW - 4;
        int ew = panelX - 10;
        editor = new CodeEditor(font, 6, 20, ew, height - 20 - 30, text);
        editor.onChange(t -> {
            text = t;
            compile();
        });
        editor.errorLine(error == null ? -1 : error.line - 1);
        addRenderableWidget(editor);
        setInitialFocus(editor);
        int by = height - 25;
        // under the text: examples and revert
        List<String> examples = new ArrayList<>(List.of(ChipLibrary.NAMES));
        examples.add(0, "");
        int half = Math.max(60, (ew - 4) / 2);
        addRenderableWidget(CycleButton.<String>builder(n -> Component.literal(n.isEmpty() ? "Examples ▸" : ChipLibrary.title(n)))
                .withValues(examples).withInitialValue("").displayOnlyValue()
                .withTooltip(n -> Tooltip.create(Component.literal("The mod's example designs: pick one to load it (Ctrl+Z to undo).")))
                .create(6, by, half, 20, Component.literal("Examples"), (b, n) -> {
                    if (!n.isEmpty()) editor.replaceText(ChipLibrary.source(n));
                }));
        addRenderableWidget(Button.builder(Component.literal("Revert"), b -> editor.replaceText(original.isBlank() ? TEMPLATE : original))
                .bounds(10 + half, by, ew - half - 4, 20)
                .tooltip(Tooltip.create(Component.literal("Back to the design as it was when opened (Ctrl+Z to undo)."))).build());
        // under the panel: write and cancel
        int w1 = (panelW - 4) * 3 / 5;
        addRenderableWidget(Button.builder(Component.literal(placed() ? "Write to chip" : "Done"), b -> done()).bounds(panelX, by, w1, 20)
                .tooltip(Tooltip.create(Component.literal(placed() ? "Rebuild the chip with this design (Ctrl+S)." : "Back to the part editor with this design (Ctrl+S)."))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(panelX + w1 + 4, by, panelW - w1 - 4, 20).build());
        if (!placed() && tab == 2) tab = 0;
    }

    private void done() {
        if (placed()) {
            PacketDistributor.sendToServer(new ChipTextPayload(pos, partId, text));
            minecraft.setScreen(null);
        } else {
            onDone.accept(text);
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_S && hasControlDown()) {
            done();
            return true;
        }
        // Escape closes; every other key is the editor's while it has focus (E must not close the screen)
        if (key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && editor.isFocused() && editor.keyPressed(key, scan, mods)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // the tabs
        if (my >= 20 && my < 20 + TAB_H && mx >= panelX && mx < panelX + panelW) {
            int t = (int) ((mx - panelX) * TABS.length / panelW);
            if (t != 2 || placed()) tab = t;
            return true;
        }
        // the error, clicked: the cursor to it
        if (error != null && my >= 20 + TAB_H + 2 && my < 20 + TAB_H + 24 && mx >= panelX) {
            editor.goTo(error.line - 1, Math.max(0, error.column - 1));
            setFocused(editor);
            editor.setFocused(true);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void tick() {
        if (placed() && ticks++ % 5 == 0) PacketDistributor.sendToServer(new ChipProbePayload.Request(pos, partId));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        String head = compiled != null ? "Chip designer — " + compiled.name() : "Chip designer";
        g.drawString(font, head, 8, 6, 0xFFFFFFFF, true);
        String where = placed() ? "placed chip at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() : "chip in hand";
        if (font.width(head) + font.width(where) + 24 < width) g.drawString(font, where, width - 8 - font.width(where), 6, 0xFF8A949E, false);
        int x = panelX, top = 20, bottom = height - 30;
        g.fill(x, top, x + panelW, bottom, 0xD0101418);
        g.renderOutline(x, top, panelW, bottom - top, 0xFF2A323C);
        // tabs
        int tw = panelW / TABS.length;
        for (int t = 0; t < TABS.length; t++) {
            boolean on = t == tab, off = t == 2 && !placed();
            int tx = x + t * tw;
            g.fill(tx, top, tx + tw, top + TAB_H, on ? 0xFF2A3A4C : 0xFF161B21);
            String label = TABS[t] + (t == 0 && error != null ? " ✖" : "");
            g.drawString(font, label, tx + (tw - font.width(label)) / 2, top + 3, off ? 0xFF4A535C : on ? 0xFFFFFFFF : 0xFF9AA6B2, false);
        }
        int cx = x + 5, y = top + TAB_H + 4;
        // what the design is: compiles, or where and why not (on every tab)
        if (error != null) {
            String msg = "✖ " + error.getMessage();
            List<FormattedCharSequence> ls = font.split(Component.literal(msg), panelW - 10);
            for (int i = 0; i < Math.min(2, ls.size()); i++) g.drawString(font, ls.get(i), cx, y + 10 * i, 0xFFFF6B6B, false);
            y += 10 * Math.min(2, ls.size()) + 3;
        } else {
            g.drawString(font, "✔ Compiles", cx, y, 0xFF6BD66B, false);
            y += 13;
        }
        g.enableScissor(x + 1, y - 1, x + panelW - 1, bottom - 1);
        switch (tab) {
            case 0 -> drawCheck(g, cx, y);
            case 1 -> drawPins(g, cx, y, bottom);
            case 2 -> drawLive(g, cx, y, bottom);
            default -> drawGuide(g, cx, y);
        }
        g.disableScissor();
    }

    private void drawCheck(GuiGraphics g, int x, int y) {
        if (compiled == null) {
            para(g, "Click the error to go to it. The Guide tab has the language; the examples show it in use.", x, y, 0xFF9AA6B2);
            return;
        }
        var e = compiled.estimate();
        // made in a process of the player's: its speed and leakage scaled (see ChipProcess)
        double slow = process == null ? 1 : process.delayScale(), leak = process == null ? 1 : process.leakageScale();
        if (process != null) {
            y = section(g, "Made in (cut from a wafer)", x, y);
            y = row(g, "Gate oxide", String.format("%.1f nm", process.oxide() * 1e9), x, y);
            y = row(g, "Junctions", String.format("%.2f µm deep", process.junction() * 1e6), x, y);
            y = row(g, "Gate length", String.format("%.1f µm (%.2f left)", process.gateLength() * 1e6, Math.max(0, process.effectiveLength()) * 1e6), x, y);
            y = row(g, "Threshold", String.format("%.2f V, %s-channel", process.threshold(), process.pChannel() ? "p" : "n"), x, y);
            if (!process.works()) {
                y = para(g, "This chip does not work: " + process.fault() + ".", x, y, 0xFFFF6B6B);
                y += 4;
            } else {
                y = row(g, "Delay", String.format("%.2f× ready-made", slow), x, y);
                y = row(g, "Leakage", String.format("%.2g× ready-made", leak), x, y);
            }
            y += 4;
        }
        y = section(g, "Size and speed (estimate)", x, y);
        y = row(g, "Gates", String.valueOf(e.gates()), x, y);
        y = row(g, "Flip-flops", String.valueOf(e.flipFlops()), x, y);
        if (e.memoryBits() > 0) y = row(g, "Memory", e.memoryBits() + " bits", x, y);
        y = row(g, "Logic depth", e.registerDepth() + " gates", x, y);
        y = row(g, "Fastest clock", Units.formatSI(e.maxClock() / slow, "Hz"), x, y);
        y = row(g, "In → out delay", Units.formatSI(e.propagationDelay() * slow, "s"), x, y);
        y = row(g, "Leakage, 5 V", Units.formatSI(e.leakage(5) * leak, "A"), x, y);
        y = row(g, "Per clock edge", Units.formatSI(e.energyPerEdge(5), "J"), x, y);
        int ni = compiled.inputPins().size(), no = compiled.outputPins().size();
        y += 4;
        y = section(g, "Package", x, y);
        para(g, (Math.max(ni, no) + 1) + " blocks in a row: " + ni + " inputs, " + no + " outputs, GND and V+ on the last block. "
                + "See the Pins tab for which is where.", x, y, 0xFFB0C4D8);
    }

    private void drawPins(GuiGraphics g, int x, int y, int bottom) {
        if (compiled == null) {
            para(g, "The pins come from the design: fix it first.", x, y, 0xFF9AA6B2);
            return;
        }
        int ni = compiled.inputPins().size(), no = compiled.outputPins().size(), n = Math.max(ni, no) + 1;
        boolean[] lin = live != null && live.simulated() ? live.inputs() : null, lout = live != null && live.simulated() ? live.outputs() : null;
        g.drawString(font, "block", x, y, 0xFF5C6773, false);
        g.drawString(font, "input side", x + 30, y, 0xFF9FD3FF, false);
        g.drawString(font, "output side", x + 30 + (panelW - 40) / 2, y, 0xFFFFC36B, false);
        y += 11;
        for (int k = 0; k < n && y < bottom - 4; k++) {
            String in = k == n - 1 ? "GND" : k < ni ? compiled.inputPins().get(k) : "—", out = k == n - 1 ? "V+" : k < no ? compiled.outputPins().get(k) : "—";
            g.drawString(font, String.valueOf(k + 1), x + 6, y, 0xFF5C6773, false);
            int cin = lin != null && k < ni && k < lin.length ? (lin[k] ? 0xFF7CFF7C : 0xFF6A7480) : 0xFFD8E4F0;
            int cout = lout != null && k < no && k < lout.length ? (lout[k] ? 0xFF7CFF7C : 0xFF6A7480) : 0xFFD8E4F0;
            g.drawString(font, (lin != null && k < ni ? (lin[k] ? "▮ " : "▯ ") : "") + in, x + 30, y, cin, false);
            g.drawString(font, (lout != null && k < no ? (lout[k] ? "▮ " : "▯ ") : "") + out, x + 30 + (panelW - 40) / 2, y, cout, false);
            y += 10;
        }
        if (lin != null && y < bottom - 14) para(g, "Live: green ▮ high, grey ▯ low.", x, y + 3, 0xFF7A8794);
    }

    private void drawLive(GuiGraphics g, int x, int y, int bottom) {
        if (live == null || !live.simulated()) {
            para(g, live == null ? "Asking the chip…" : "Not simulated: is its design building, and is it in a machine?", x, y, 0xFF8A949E);
            return;
        }
        g.drawString(font, live.powered() ? String.format("● powered, %.2f V", live.supply()) : String.format("○ unpowered (%.2f V)", live.supply()), x, y,
                live.powered() ? 0xFF7CFF7C : 0xFFFF9B5C, false);
        y += 10;
        String edges = live.edges() + " clock edges";
        g.drawString(font, edges, x, y, 0xFFB0C4D8, false);
        y += 10;
        if (live.violations() > 0) {
            g.drawString(font, live.violations() + " edges too fast: missed", x, y, 0xFFFF6B6B, false);
            y += 10;
        }
        y += 2;
        int colW = (panelW - 10) / 2, cx = x, cy = y;
        for (int i = 0; i < live.names().size() && cy < bottom - 4; i++) {
            long v = live.values()[i];
            int w = live.widths()[i];
            String s = live.names().get(i) + " = " + (w <= 1 ? String.valueOf(v) : w <= 8 ? v + " (0x" + Long.toHexString(v).toUpperCase() + ")"
                    : "0x" + Long.toHexString(v).toUpperCase());
            g.drawString(font, font.plainSubstrByWidth(s, colW - 4), cx, cy, 0xFFE0E6EC, false);
            if (cx == x) cx = x + colW;
            else {
                cx = x;
                cy += 10;
            }
        }
    }

    private int section(GuiGraphics g, String title, int x, int y) {
        g.drawString(font, title, x, y, 0xFFFFD27A, false);
        return y + 11;
    }

    private int row(GuiGraphics g, String k, String v, int x, int y) {
        g.drawString(font, k, x, y, 0xFF9AA6B2, false);
        g.drawString(font, v, x + panelW - 12 - font.width(v), y, 0xFFE0E6EC, false);
        return y + 10;
    }

    private int para(GuiGraphics g, String s, int x, int y, int colour) {
        for (FormattedCharSequence l : font.split(Component.literal(s), panelW - 10)) {
            g.drawString(font, l, x, y, colour, false);
            y += 10;
        }
        return y;
    }

    private static final String[] GUIDE = {
            "§6Declarations",
            "chip name",
            "in clk, a      in [7:0] d  (pins d0..d7)",
            "out q = 1  (starts at 1)   out [3:0] y",
            "reg [9:0] n = 0     wire w = a & b",
            "mem [7:0] rom[16] = {0x12, 0x34}",
            "param half = 500  (part setting p_half)",
            "assign y = n[3:0]",
            "§6Clocked logic",
            "on rise clk { … }   on fall clk { … }",
            "n <= n + 1   (all take effect together)",
            "if (a) { … } else { … }",
            "case (st) { 0: { … }  1, 2: …  default: … }",
            "§6Expressions (unsigned, to 64 bits)",
            "+ - * / %   & | ^ ~   ! && ||   << >>",
            "== != < <= > >=     c ? a : b",
            "x[3:0]  x[i]  {a, b}  8'hff  4'b1010",
            "§6In the world",
            "Inputs along one side of the row, outputs",
            "along the other, in order; the last block",
            "is GND and V+. An open input reads low.",
            "§6Timing",
            "A clock faster than the fastest shown",
            "misses edges (counted as too fast).",
    };

    private void drawGuide(GuiGraphics g, int x, int y) {
        for (String l : GUIDE) {
            boolean h = l.startsWith("§6");
            g.drawString(font, font.plainSubstrByWidth(h ? l.substring(2) : l, panelW - 8), x, y, h ? 0xFFFFD27A : 0xFFD8DEE4, false);
            y += h ? 12 : 10;
        }
    }
}
