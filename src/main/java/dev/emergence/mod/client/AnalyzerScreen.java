package dev.emergence.mod.client;

import dev.emergence.core.units.Units;
import dev.emergence.mod.net.AnalyzerPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The logic analyser's screen: its probes' traces against time, one lane each — a digital net as its level (a band
 * where it switched faster than a pixel), an analog node as its voltage (auto-ranged). The wheel zooms, dragging pans;
 * the time axis is marked in seconds before the newest record (or, for a held single capture, after its trigger). Run
 * rolls; Single arms the trigger (channel and edge chosen beside it) and holds what follows; Hold freezes.
 */
public final class AnalyzerScreen extends Screen {
    private static final int LABEL_W = 92, LANE_H = 24, TOP = 30;
    private static final double[] SPANS = {10e-6, 20e-6, 50e-6, 100e-6, 200e-6, 500e-6, 1e-3, 2e-3, 5e-3, 10e-3, 20e-3, 50e-3, 0.1, 0.2, 0.5, 1, 2, 5};
    private static final int[] COLOURS = {0xFFFFD25A, 0xFF5AD2FF, 0xFF7CFF7C, 0xFFFF7CB4, 0xFFC08CFF, 0xFFFF9B5C, 0xFF5CFFD2, 0xFFE0E0E0};

    private int spanIdx = 6;
    private double back;
    private AnalyzerPayloads.Data data;
    private int ticks;
    private double dragX = Double.NaN, dragBack;
    private Button trigBtn, edgeBtn;

    public AnalyzerScreen() {
        super(Component.literal("Logic analyser"));
    }

    void data(AnalyzerPayloads.Data d) {
        data = d;
        if (trigBtn != null) {
            trigBtn.setMessage(Component.literal("Trig CH" + (d.trigChannel() + 1)));
            edgeBtn.setMessage(Component.literal(d.trigRising() ? "Edge ↑" : "Edge ↓"));
        }
    }

    /** How many probed channels the screen shows switching (between levels, or over a volt for a voltage); −1 before data. */
    int switching() {
        if (data == null) return -1;
        int n = 0;
        for (int k = 0; k < data.names().length; k++) if (!data.names()[k].isEmpty() && swing(k) > (data.digital()[k] ? 0.5 : 1.0)) n++;
        return n;
    }

    /** What each probe shows, for the tour's report. */
    String summary() {
        if (data == null) return "no data yet";
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < data.names().length; k++) {
            if (data.names()[k].isEmpty()) continue;
            b.append(String.format("CH%d %s %s swing %.2f; ", k + 1, data.names()[k], data.digital()[k] ? "levels" : "volts", swing(k)));
        }
        return b.length() == 0 ? "no probes" : b.toString();
    }

    private double swing(int k) {
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (float v : data.min()[k]) if (!Float.isNaN(v)) lo = Math.min(lo, v);
        for (float v : data.max()[k]) if (!Float.isNaN(v)) hi = Math.max(hi, v);
        return hi >= lo ? hi - lo : 0;
    }

    private int plotX0() {
        return 8 + LABEL_W;
    }

    private int plotX1() {
        return width - 8;
    }

    private double span() {
        return SPANS[spanIdx];
    }

    @Override
    protected void init() {
        int x = 8, y = 6;
        addRenderableWidget(Button.builder(Component.literal("Run"), b -> send(AnalyzerPayloads.RUN, 0)).bounds(x, y, 34, 18)
                .tooltip(Tooltip.create(Component.literal("Record continuously (the newest at the right)."))).build());
        x += 36;
        addRenderableWidget(Button.builder(Component.literal("Single"), b -> {
            back = 0;
            send(AnalyzerPayloads.SINGLE, 0);
        }).bounds(x, y, 44, 18).tooltip(Tooltip.create(Component.literal("Arm the trigger: capture what follows its edge, then hold."))).build());
        x += 46;
        addRenderableWidget(Button.builder(Component.literal("Hold"), b -> send(AnalyzerPayloads.HOLD, 0)).bounds(x, y, 36, 18)
                .tooltip(Tooltip.create(Component.literal("Freeze the record to look at it."))).build());
        x += 38;
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> send(AnalyzerPayloads.CLEAR, 0)).bounds(x, y, 38, 18).build());
        x += 44;
        trigBtn = addRenderableWidget(Button.builder(Component.literal("Trig CH1"), b -> send(AnalyzerPayloads.TRIGGER_CHANNEL,
                data == null ? 0 : data.trigChannel() + 1)).bounds(x, y, 56, 18)
                .tooltip(Tooltip.create(Component.literal("The channel whose edge triggers a single capture."))).build());
        x += 58;
        edgeBtn = addRenderableWidget(Button.builder(Component.literal("Edge ↑"), b -> send(AnalyzerPayloads.TRIGGER_EDGE, 0)).bounds(x, y, 46, 18).build());
        x += 50;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> zoom(1)).bounds(x, y, 18, 18)
                .tooltip(Tooltip.create(Component.literal("Longer time span (the mouse wheel zooms too)."))).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoom(-1)).bounds(x + 20, y, 18, 18)
                .tooltip(Tooltip.create(Component.literal("Shorter time span."))).build());
        addRenderableWidget(Button.builder(Component.literal("Now"), b -> back = 0).bounds(x + 42, y, 32, 18)
                .tooltip(Tooltip.create(Component.literal("Back to the newest record."))).build());
    }

    private void send(int op, int arg) {
        PacketDistributor.sendToServer(new AnalyzerPayloads.Control(op, arg));
    }

    private void zoom(int d) {
        spanIdx = Math.max(0, Math.min(SPANS.length - 1, spanIdx + d));
        // the window after a single capture's trigger follows the span shown
        send(AnalyzerPayloads.WINDOW_US, (int) Math.min(Integer.MAX_VALUE, Math.round(span() * 0.9e6)));
    }

    @Override
    public void tick() {
        if (ticks++ % 3 == 0) PacketDistributor.sendToServer(new AnalyzerPayloads.View(span(), back, Math.max(8, plotX1() - plotX0())));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        zoom(sy > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        // a probe's label, right-clicked: unclip it
        if (button == 1 && mx < plotX0() && my >= TOP) {
            int k = (int) ((my - TOP) / LANE_H);
            if (k >= 0 && k < 8) send(AnalyzerPayloads.REMOVE_PROBE, k);
            return true;
        }
        if (mx >= plotX0() && mx < plotX1() && my >= TOP) {
            dragX = mx;
            dragBack = back;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!Double.isNaN(dragX)) {
            back = Math.max(0, dragBack + (mx - dragX) / (plotX1() - plotX0()) * span());
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragX = Double.NaN;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        int x0 = plotX0(), x1 = plotX1(), w = x1 - x0, bottom = TOP + 8 * LANE_H;
        g.fill(x0, TOP, x1, bottom, 0xF0080A0C);
        // grid: ten divisions
        for (int i = 0; i <= 10; i++) {
            int gx = x0 + i * w / 10;
            g.fill(gx, TOP, gx + 1, bottom, i == 10 ? 0x60FFFFFF : 0x28FFFFFF);
        }
        String state;
        if (data == null) state = "connecting…";
        else if (data.frozen()) state = data.mode() == 1 ? "TRIGGERED — holding" : "HOLD";
        else if (data.armed()) state = "ARMED — waiting for CH" + (data.trigChannel() + 1) + (data.trigRising() ? " ↑" : " ↓");
        else state = "RUN";
        g.drawString(font, state, width - 8 - font.width(state), 12, data != null && data.armed() ? 0xFFFFC66B : 0xFF9AA6B2, false);
        for (int k = 0; k < 8; k++) {
            int ly = TOP + k * LANE_H;
            g.fill(8, ly, x0 - 2, ly + LANE_H - 1, 0xC0101418);
            String name = data == null || data.names()[k].isEmpty() ? "—" : data.names()[k];
            g.drawString(font, "CH" + (k + 1), 11, ly + 3, COLOURS[k], false);
            g.drawString(font, font.plainSubstrByWidth(name, LABEL_W - 12), 11, ly + 13, 0xFF7A8794, false);
            if (data == null || data.min()[k].length == 0) continue;
            float[] mn = data.min()[k], mx2 = data.max()[k];
            int n = mn.length;
            if (data.digital()[k]) {
                int hi = ly + 4, lo = ly + LANE_H - 5;
                for (int i = 0; i < n; i++) {
                    if (Float.isNaN(mn[i])) continue;
                    int px = x0 + i * w / n, px1 = x0 + (i + 1) * w / n;
                    if (mn[i] != mx2[i]) g.fill(px, hi, Math.max(px + 1, px1), lo + 1, (COLOURS[k] & 0x00FFFFFF) | 0x90000000);
                    else {
                        int yy = mn[i] > 0.5 ? hi : lo;
                        g.fill(px, yy, Math.max(px + 1, px1), yy + 1, COLOURS[k]);
                        // an edge between bins
                        if (i > 0 && !Float.isNaN(mx2[i - 1]) && mn[i - 1] == mx2[i - 1] && mn[i - 1] != mn[i]) g.fill(px, hi, px + 1, lo + 1, COLOURS[k]);
                    }
                }
            } else {
                // analog: auto-ranged over what is shown
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (Float.isNaN(mn[i])) continue;
                    lo = Math.min(lo, mn[i]);
                    hi = Math.max(hi, mx2[i]);
                }
                if (lo > hi) continue;
                if (hi - lo < 0.1f) {
                    hi += 0.05f;
                    lo -= 0.05f;
                }
                int top = ly + 3, bot = ly + LANE_H - 4;
                for (int i = 0; i < n; i++) {
                    if (Float.isNaN(mn[i])) continue;
                    int px = x0 + i * w / n;
                    int ya = bot - Math.round((mx2[i] - lo) / (hi - lo) * (bot - top)), yb = bot - Math.round((mn[i] - lo) / (hi - lo) * (bot - top));
                    g.fill(px, ya, px + 1, Math.max(ya + 1, yb + 1), COLOURS[k]);
                }
                g.drawString(font, String.format("%.2f V", hi), x1 - 44, ly + 2, 0x80FFFFFF, false);
                g.drawString(font, String.format("%.2f V", lo), x1 - 44, ly + LANE_H - 10, 0x80FFFFFF, false);
            }
        }
        // the time axis, and the cursor's readout
        String spanTxt = Units.formatSI(span() / 10, "s") + "/div";
        g.drawString(font, spanTxt, x0, bottom + 4, 0xFF9AA6B2, false);
        String endTxt = back == 0 ? "newest at right" : Units.formatSI(back, "s") + " before the newest";
        g.drawString(font, endTxt, x1 - font.width(endTxt), bottom + 4, 0xFF9AA6B2, false);
        if (data != null && !data.grounded()) g.drawString(font, "no ground lead: voltages against the circuit's reference (sneak-clip one)", x0, bottom + 16, 0xFF7A8794, false);
        if (mx >= x0 && mx < x1 && my >= TOP && my < bottom && data != null) {
            g.fill(mx, TOP, mx + 1, bottom, 0x80FFFFFF);
            double tAt = data.end() - span() + (mx - x0) / (double) w * span();
            String rel = !Double.isNaN(data.trigTime()) && data.frozen() ? "t = " + Units.formatSI(tAt - data.trigTime(), "s") + " from the trigger"
                    : "t = −" + Units.formatSI(Math.max(0, data.newest() - tAt), "s");
            g.drawString(font, rel, Math.min(mx + 4, x1 - font.width(rel)), bottom + 16, 0xFFFFFFFF, false);
        }
        g.drawString(font, "Logic analyser", 8, TOP - 9, 0xFFFFFFFF, true);
    }
}
