package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.part.PartSpec;
import net.minecraft.client.Minecraft;

/** Client-only actions called from common code on the client side (kept apart so a server never loads screens). */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openPartEditor(PartKind kind, boolean offhand, PartSpec spec) {
        Minecraft.getInstance().setScreen(new PartEditorScreen(kind, offhand, spec));
    }
}
