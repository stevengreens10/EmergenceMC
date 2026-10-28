package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartCatalog;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.part.PartItem;
import dev.emergence.mod.part.PartSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * A look at the part editor without a person at the keyboard: with {@code -Demergence.editorShots=BATTERY,TANK,…}
 * (or {@code all}) the showcase client opens the editor on each kind of part — as it comes, then as its last stock
 * variant with the advanced settings open — saves {@code screenshots/editor-*.png} and quits.
 */
final class EditorShots {
    private EditorShots() {}

    private static final List<Object[]> SHOTS = new ArrayList<>();
    private static int index, wait = -1, joined;

    static boolean init() {
        String which = System.getProperty("emergence.editorShots", "");
        if (which.isBlank()) return false;
        // the chip designer on a chip cut from a wafer: a process that works (the two-step furnace's) and one that does not
        if (which.equals("all") || List.of(which.split(",")).contains("DESIGNER_FAB")) {
            SHOTS.add(new Object[]{null, java.util.Map.of(dev.emergence.core.digital.ChipProcess.OXIDE, 24.9e-9, dev.emergence.core.digital.ChipProcess.JUNCTION,
                    0.36e-6, dev.emergence.core.digital.ChipProcess.GATE, 3e-6, dev.emergence.core.digital.ChipProcess.CHANNEL, 0.0), false, "designer-fab"});
            SHOTS.add(new Object[]{null, java.util.Map.of(dev.emergence.core.digital.ChipProcess.OXIDE, 26.7e-9, dev.emergence.core.digital.ChipProcess.JUNCTION,
                    1.4e-6, dev.emergence.core.digital.ChipProcess.GATE, 3e-6, dev.emergence.core.digital.ChipProcess.CHANNEL, 0.0), false, "designer-fab-dead"});
        }
        for (PartKind k : PartKind.values()) {
            if (!which.equals("all") && !List.of(which.split(",")).contains(k.name())) continue;
            SHOTS.add(new Object[]{k, PartItem.defaultSpec(k), false, "editor-" + k.name().toLowerCase()});
            var presets = PartCatalog.of(k);
            var last = presets.get(presets.size() - 1);
            SHOTS.add(new Object[]{k, new PartSpec(last.material(), last.length(), 1, last.params()), true, "editor-" + k.name().toLowerCase() + "-preset"});
        }
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        return true;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || joined++ < 40) return;
        if (index >= SHOTS.size()) {
            mc.stop();
            return;
        }
        Object[] s = SHOTS.get(index);
        if (wait < 0 && s[0] == null) {
            @SuppressWarnings("unchecked") var params = (java.util.Map<String, Double>) s[1];
            mc.setScreen(ChipDesignerScreen.forItem(null, dev.emergence.core.digital.ChipLibrary.source("gauge"), t -> { }).madeFrom(params));
            wait = 10;
        } else if (wait < 0) {
            PartEditorScreen screen = new PartEditorScreen((PartKind) s[0], false, (PartSpec) s[1]);
            if ((boolean) s[2]) screen.showAdvanced();
            if (s[0] == PartKind.BATTERY && (boolean) s[2]) screen.startTyping("capacity");
            mc.setScreen(screen);
            if (s[0] == PartKind.BLOCK && (boolean) s[2]) {
                mc.setScreen(new MaterialPickerScreen(screen, dev.emergence.core.assembly.PartParameters.of(PartKind.BLOCK).allowedMaterials(),
                        "steel_1045", m -> m.name(), m -> { }));
            }
            wait = 10;
        } else if (wait-- == 0) {
            Screenshot.grab(mc.gameDirectory, s[3] + ".png", mc.getMainRenderTarget(), msg -> EmergenceMod.LOG.info("Editor shot: {}", msg.getString()));
            index++;
            wait = -1;
        }
    }
}
