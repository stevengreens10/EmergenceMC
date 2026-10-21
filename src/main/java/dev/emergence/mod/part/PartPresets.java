package dev.emergence.mod.part;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Part items: one of each kind for the creative tab (its stock variants are presets in the part editor). */
public final class PartPresets {
    private PartPresets() {}

    public static ItemStack stack(PartKind kind, PartSpec spec) {
        ItemStack s = new ItemStack(ModRegistries.PART_ITEMS.get(kind).get());
        s.set(ModRegistries.PART_SPEC.get(), spec);
        return s;
    }

    /**
     * The creative tab: one of each kind of part, as it comes; the part editor (right-click the air with it) makes it
     * into any variant — its stock presets, or settings of the player's own.
     */
    public static List<ItemStack> all() {
        List<ItemStack> l = new ArrayList<>();
        for (PartKind k : PartKind.values()) l.add(stack(k, PartItem.defaultSpec(k)));
        return l;
    }
}
