package dev.emergence.mod.client;

import dev.emergence.core.units.Units;
import dev.emergence.mod.net.LoggerSyncPayload;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The data logger's screen: while the logger is held, the newest trace of its shown channel is drawn in the
 * top-right corner as a peak-detect envelope against time, auto-ranged with the range printed at the edges.
 */
public final class LoggerHud {
    private static LoggerSyncPayload last;
    /** Set by the automated showcase tour, which hides the rest of the GUI: draw the screen anyway. */
    public static volatile boolean showcase;
    private static long received;

    private LoggerHud() {
    }

    public static synchronized void apply(LoggerSyncPayload p) {
        last = p;
        received = System.currentTimeMillis();
    }

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (mc.options.hideGui && !showcase)) return;
        boolean held = mc.player.getMainHandItem().is(ModRegistries.DATA_LOGGER.get())
                || mc.player.getOffhandItem().is(ModRegistries.DATA_LOGGER.get());
        LoggerSyncPayload p;
        synchronized (LoggerHud.class) {
            p = last;
        }
        if (!held || p == null || System.currentTimeMillis() - received > 3000) return;
        Font font = mc.font;
        int w = 220, h = 110, x0 = g.guiWidth() - w - 6, y0 = 6;
        int px0 = x0 + 6, px1 = x0 + w - 6, py0 = y0 + 20, py1 = y0 + h - 14;
        g.fill(x0, y0, x0 + w, y0 + h, 0xD0101418);
        g.fill(px0, py0, px1, py1, 0xFF0A1A10);
        // range over the shown samples
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < p.lo().length; i++) {
            if (!Float.isNaN(p.lo()[i])) lo = Math.min(lo, p.lo()[i]);
            if (!Float.isNaN(p.hi()[i])) hi = Math.max(hi, p.hi()[i]);
        }
        String head = p.channel() + ": " + (Float.isNaN(p.latest()) ? "—" : Units.formatSI(p.latest(), p.unit()));
        g.drawString(font, trim(font, p.title(), w - 10), x0 + 5, y0 + 2, 0xFFE0E0E0, false);
        g.drawString(font, head, x0 + 5, y0 + 11, 0xFF80F0A0, false);
        // graticule: 10 × 4 divisions
        for (int k = 1; k < 10; k++) {
            int x = px0 + (px1 - px0) * k / 10;
            g.fill(x, py0, x + 1, py1, 0xFF1C3424);
        }
        for (int k = 1; k < 4; k++) {
            int y = py0 + (py1 - py0) * k / 4;
            g.fill(px0, y, px1, y + 1, 0xFF1C3424);
        }
        if (lo <= hi) {
            float mid = 0.5f * (lo + hi), half = Math.max(0.5f * (hi - lo) * 1.1f, Math.max(Math.abs(mid) * 1e-3f, 1e-9f));
            float bot = mid - half, top = mid + half;
            int n = p.lo().length, prevY = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                if (Float.isNaN(p.lo()[i])) continue;
                int x = px0 + (px1 - px0 - 1) * i / Math.max(1, n - 1);
                int ya = toY(p.hi()[i], bot, top, py0, py1), yb = toY(p.lo()[i], bot, top, py0, py1);
                if (prevY != Integer.MIN_VALUE) {
                    ya = Math.min(ya, prevY);
                    yb = Math.max(yb, prevY);
                }
                g.fill(x, ya, x + 1, yb + 1, 0xFF60FF90);
                prevY = toY(0.5f * (p.lo()[i] + p.hi()[i]), bot, top, py0, py1);
            }
            g.drawString(font, Units.formatSI(top, p.unit()), px0 + 2, py0 + 2, 0xFF70A080, false);
            g.drawString(font, Units.formatSI(bot, p.unit()), px0 + 2, py1 - 10, 0xFF70A080, false);
        }
        g.drawString(font, Units.formatSI(p.span() / 10, "s") + "/div   " + Units.formatSI(p.rate(), "Hz") + " sampling",
                x0 + 5, y0 + h - 11, 0xFFA0A0A0, false);
    }

    private static int toY(float v, float bot, float top, int py0, int py1) {
        float f = (v - bot) / (top - bot);
        return Math.round(py1 - 1 - f * (py1 - py0 - 2));
    }

    private static String trim(Font font, String s, int width) {
        return font.width(s) <= width ? s : font.plainSubstrByWidth(s, width - font.width("…")) + "…";
    }
}
