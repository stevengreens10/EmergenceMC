package dev.emergence.mod.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The automated showcase's caption, so someone watching can follow it: which exhibit this is and what it shows, what
 * the player is doing to it now, what should happen, and how the checks came out. Drawn only during the showcase run.
 */
public final class ShowcaseHud {
    private ShowcaseHud() {}

    private static volatile String exhibit = "", about = "", doing = "";
    /** The current step's checks: {what should happen, what is measured now}. */
    private static volatile List<String[]> pending = List.of();
    private record Result(String text, String measured, boolean pass, long at) {}
    private static final Deque<Result> RESULTS = new ArrayDeque<>();

    /** A new exhibit: "n / total  Title", and what it demonstrates. */
    static void exhibit(int n, int total, String title, String description) {
        exhibit = n + " / " + total + "   " + title;
        about = firstSentences(description, 1);
        if (about.length() > 200) about = about.substring(0, 197) + "…";
        doing = "Looking at the exhibit";
        pending = List.of();
        synchronized (RESULTS) {
            RESULTS.clear();
        }
    }

    static void doing(String what) {
        if (what != null && !what.isBlank()) doing = what;
    }

    /** The checks of the step under way, each with what it measures now. */
    static void pending(List<String[]> rows) {
        pending = List.copyOf(rows);
    }

    static void result(String what, String measured, boolean pass) {
        synchronized (RESULTS) {
            RESULTS.addLast(new Result(what, measured, pass, System.currentTimeMillis()));
            while (RESULTS.size() > 4) RESULTS.removeFirst();
        }
    }

    private static String firstSentences(String s, int n) {
        int at = 0;
        for (int k = 0; k < n; k++) {
            int dot = s.indexOf(". ", at);
            if (dot < 0) return s;
            at = dot + 2;
        }
        return s.substring(0, at).trim();
    }

    public static void render(GuiGraphics g, DeltaTracker dt) {
        if (!(Boolean.getBoolean("emergence.autoShowcase") || WatchBuild.enabled()) || exhibit.isEmpty()) return;
        if (!System.getProperty("emergence.gifs", "").isEmpty()) return; // filming for the README: the machine, not the caption
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        // small whatever the GUI scale (as at GUI scale 2): a caption, not a screenful
        float s = (float) Math.min(1.0, 2.0 / mc.getWindow().getGuiScale());
        g.pose().pushPose();
        g.pose().scale(s, s, 1);
        int w = (int) Math.min(300, g.guiWidth() / s * 0.4), x = 4, y = 4, pad = 4;
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        add(lines, colours, f, exhibit, w, 0xFFE070);
        add(lines, colours, f, about, w, 0xD0D0D0);
        add(lines, colours, f, "Now: " + doing, w, 0x80D0FF);
        for (String[] e : pending) {
            add(lines, colours, f, "Checking: " + e[0], w, 0xF0D060);
            add(lines, colours, f, "   " + e[1], w, 0xC0C0C0);
        }
        synchronized (RESULTS) {
            long now = System.currentTimeMillis();
            for (Result r : RESULTS) {
                if (now - r.at > 25_000) continue;
                add(lines, colours, f, (r.pass ? "PASS  " : "FAIL  ") + r.text, w, r.pass ? 0x70E070 : 0xFF6060);
                add(lines, colours, f, "   " + r.measured, w, r.pass ? 0xA0D0A0 : 0xF0A0A0);
            }
        }
        int h = lines.size() * (f.lineHeight + 1) + 2 * pad;
        g.fill(x, y, x + w + 2 * pad, y + h, 0xB0101418);
        int ly = y + pad;
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(f, lines.get(i), x + pad, ly, colours.get(i), false);
            ly += f.lineHeight + 1;
        }
        g.pose().popPose();
    }

    private static void add(List<FormattedCharSequence> lines, List<Integer> colours, Font f, String text, int w, int colour) {
        for (FormattedCharSequence s : f.split(Component.literal(text), w)) {
            lines.add(s);
            colours.add(colour);
        }
    }
}
