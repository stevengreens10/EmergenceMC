package dev.emergence.mod.client;

import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.net.GogglesPayloads;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The engineer's goggles: worn (or held), the part under the crosshair has its readings drawn beside it — what the
 * multimeter would report, live, a few times a second, without clicking. The client finds the part it looks at and
 * asks the server, which takes the readings from the simulation.
 */
public final class GogglesHud {
    private GogglesHud() {
    }

    /** Set by the automated showcase tour, which hides the rest of the GUI: draw the panel anyway. */
    public static volatile boolean showcase;
    private static BlockPos lookPos;
    private static PlacedPart look;
    private static GogglesPayloads.Data data;
    private static long dataAt;
    private static int ticks;

    /** Whether the player wears the goggles or holds them. */
    public static boolean wearing() {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        var g = ModRegistries.GOGGLES.get();
        return p.getItemBySlot(EquipmentSlot.HEAD).is(g) || p.getMainHandItem().is(g) || p.getOffhandItem().is(g);
    }

    /** The part looked at now, and the readings shown for it (for the tour): null if none. */
    public static synchronized GogglesPayloads.Data shown() {
        return look != null && data != null && data.partId() == look.id && data.pos().equals(lookPos) ? data : null;
    }

    /** Each client tick: what is looked at, and a fresh reading of it every five ticks. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        PlacedPart part = null;
        BlockPos pos = null;
        if (mc.player != null && mc.level != null && wearing() && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                && mc.level.getBlockEntity(bhr.getBlockPos()) instanceof AssemblyBlockEntity be) {
            part = be.partHit(bhr.getLocation(), bhr.getDirection());
            pos = bhr.getBlockPos().immutable();
        }
        boolean changed;
        synchronized (GogglesHud.class) {
            changed = part == null ? look != null : look == null || look.id != part.id || !pos.equals(lookPos);
            look = part;
            lookPos = pos;
        }
        if (part != null && (changed || ++ticks % 5 == 0)) PacketDistributor.sendToServer(new GogglesPayloads.Query(pos, part.id));
    }

    public static synchronized void apply(GogglesPayloads.Data d) {
        data = d;
        dataAt = System.currentTimeMillis();
    }

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (mc.options.hideGui && !showcase && !Boolean.getBoolean("emergence.autoShowcase")) || mc.screen != null) return;
        PlacedPart part;
        GogglesPayloads.Data d;
        synchronized (GogglesHud.class) {
            part = look;
            d = shown();
        }
        if (part == null) return;
        Font font = mc.font;
        String title = Component.translatable("item.emergence." + part.kind.name().toLowerCase()).getString();
        String material = part.spec.resolveMaterial().name().split(" \\(")[0];
        boolean stale = d == null || System.currentTimeMillis() - dataAt > 2000;
        int rows = d == null ? 1 : Math.max(1, d.labels().length);
        int labelW = 0, valueW = 0;
        if (d != null) {
            for (String s : d.labels()) labelW = Math.max(labelW, font.width(s));
            for (String s : d.values()) valueW = Math.max(valueW, font.width(s));
        }
        int w = Math.max(Math.max(font.width(title), font.width(material)) + 8, labelW + valueW + 18);
        w = Math.min(w, g.guiWidth() / 2 - 24);
        int h = 26 + rows * 10;
        int x0 = g.guiWidth() / 2 + 14, y0 = Math.max(4, g.guiHeight() / 2 - h / 2);
        // a brass-edged card, as the goggles' lenses tint it
        g.fill(x0, y0, x0 + w, y0 + h, 0xE0101418);
        g.fill(x0, y0, x0 + w, y0 + 1, 0xFFB8924A);
        g.fill(x0, y0 + h - 1, x0 + w, y0 + h, 0xFFB8924A);
        g.fill(x0, y0, x0 + 1, y0 + h, 0xFFB8924A);
        g.fill(x0 + w - 1, y0, x0 + w, y0 + h, 0xFFB8924A);
        g.drawString(font, font.plainSubstrByWidth(title, w - 8), x0 + 4, y0 + 4, 0xFFFFD27A, false);
        g.drawString(font, font.plainSubstrByWidth(material, w - 8), x0 + 4, y0 + 14, 0xFF8A96A2, false);
        int y = y0 + 25;
        if (d == null || d.labels().length == 0) {
            g.drawString(font, d == null ? "reading…" : "not simulated yet", x0 + 4, y, 0xFF8A96A2, false);
            return;
        }
        for (int i = 0; i < d.labels().length; i++) {
            g.drawString(font, d.labels()[i], x0 + 4, y, 0xFF9AA6B2, false);
            g.drawString(font, font.plainSubstrByWidth(d.values()[i], w - labelW - 14), x0 + labelW + 10, y, stale ? 0xFF8A96A2 : 0xFFFFFFFF, false);
            y += 10;
        }
    }
}
