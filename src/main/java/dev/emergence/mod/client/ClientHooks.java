package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.digital.ChipTypes;
import dev.emergence.mod.net.ChipProbePayload;
import dev.emergence.mod.part.ChipRows;
import dev.emergence.mod.part.PartSpec;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Client-only actions called from common code on the client side (kept apart so a server never loads screens). */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openPartEditor(PartKind kind, boolean offhand, PartSpec spec) {
        Minecraft.getInstance().setScreen(new PartEditorScreen(kind, offhand, spec));
    }

    /**
     * A placed chip right-clicked with an empty hand: a design opens in the chip designer, a 256-byte ROM's contents in
     * the ROM editor; any other chip says what is on the pins of the block clicked.
     */
    public static void openPlacedChip(BlockPos pos, PlacedPart part) {
        Minecraft mc = Minecraft.getInstance();
        int fn = ChipRows.function(part.spec);
        if (fn == ChipTypes.DESIGN) {
            mc.setScreen(ChipDesignerScreen.forPlaced(pos, part.id, part.spec.text()));
        } else if (fn == ChipTypes.ROM256) {
            mc.setScreen(RomEditorScreen.forPlaced(pos, part.id, part.spec.text()));
        } else if (mc.player != null && mc.level != null) {
            var row = ChipRows.row(mc.level, pos, part);
            var pins = ChipRows.pins(part.spec);
            int k = ChipRows.index(row, part);
            if (pins.error() != null) {
                mc.player.displayClientMessage(Component.literal(pins.label() + ": " + pins.error()), true);
                return;
            }
            String[] at = pins.at(k);
            mc.player.displayClientMessage(Component.literal(pins.label() + ", block " + (k + 1) + " of " + pins.segments()
                    + (row.size() != pins.segments() ? " (needs " + pins.segments() + " in a row, has " + row.size() + ")" : "")
                    + ": in " + (at[0] == null ? "—" : at[0]) + " · out " + (at[1] == null ? "—" : at[1])), true);
        }
    }

    /** Says where a two-terminal component's leads come out (see LeadFaces). */
    public static void showLeads(BlockPos pos, int resolution, PlacedPart part) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(LeadFaces.describe(pos, resolution, part)), true);
    }

    public static void openAnalyzer() {
        Minecraft.getInstance().setScreen(new AnalyzerScreen());
    }

    public static void gogglesData(dev.emergence.mod.net.GogglesPayloads.Data d) {
        GogglesHud.apply(d);
    }

    public static void analyzerData(dev.emergence.mod.net.AnalyzerPayloads.Data d) {
        if (Minecraft.getInstance().screen instanceof AnalyzerScreen s) s.data(d);
    }

    /** A running chip's state came back: to the designer or ROM editor that asked. */
    public static void chipProbe(ChipProbePayload p) {
        var s = Minecraft.getInstance().screen;
        if (s instanceof ChipDesignerScreen d && d.placed() && d.partId() == p.partId()) d.live(p);
        if (s instanceof RomEditorScreen r && r.placed() && r.partId() == p.partId()) r.live(p);
    }
}
