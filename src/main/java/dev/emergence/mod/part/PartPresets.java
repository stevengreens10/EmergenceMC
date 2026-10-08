package dev.emergence.mod.part;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made part variants offered in the creative tab. These are stock sizes of primitive parts
 * (as a workshop would keep in stock), not machines.
 */
public final class PartPresets {
    private PartPresets() {}

    public static ItemStack stack(PartKind kind, PartSpec spec) {
        ItemStack s = new ItemStack(ModRegistries.PART_ITEMS.get(kind).get());
        s.set(ModRegistries.PART_SPEC.get(), spec);
        return s;
    }

    public static List<ItemStack> all() {
        List<ItemStack> l = new ArrayList<>();
        // structure
        for (String m : new String[]{"steel_1045", "aluminium_6061", "oak", "granite", "copper", "ptfe"}) {
            l.add(stack(PartKind.BLOCK, PartSpec.of(m, 1, 1)));
        }
        l.add(stack(PartKind.BLOCK, PartSpec.of("steel_1045", 1, 3)));
        l.add(stack(PartKind.BLOCK, PartSpec.of("aluminium_6061", 1, 5, "fill", 0.15)));
        l.add(stack(PartKind.BLOCK, PartSpec.of("aluminium_6061", 1, 3, "fill", 0.25)));
        l.add(stack(PartKind.BLOCK, PartSpec.of("copper", 1, 1, "fill", 0.5)));
        // rotating machinery elements
        l.add(stack(PartKind.SHAFT, PartSpec.of("steel_1045", 4, 1, "diameter", 0.02)));
        l.add(stack(PartKind.SHAFT, PartSpec.of("steel_1045", 8, 1, "diameter", 0.02)));
        l.add(stack(PartKind.SHAFT, PartSpec.of("steel_1045", 16, 1, "diameter", 0.025)));
        l.add(stack(PartKind.BEARING, PartSpec.of("steel_1045", 1, 1, "type", 0)));
        l.add(stack(PartKind.BEARING, PartSpec.of("bronze", 1, 1, "type", 1)));
        l.add(stack(PartKind.GEAR, PartSpec.of("steel_1045", 1, 1, "teeth", 8)));
        l.add(stack(PartKind.GEAR, PartSpec.of("steel_1045", 1, 3, "teeth", 24)));
        l.add(stack(PartKind.GEAR, PartSpec.of("steel_1045", 1, 5, "teeth", 40)));
        l.add(stack(PartKind.CRANK, PartSpec.of("steel_1045", 1, 1, "radius", 0.15)));
        // magnetics
        l.add(stack(PartKind.MAGNET, PartSpec.of("ndfeb_n42", 1, 1)));
        l.add(stack(PartKind.MAGNET, PartSpec.of("ferrite_c8", 1, 1)));
        l.add(stack(PartKind.MAGNET, PartSpec.of("alnico_5", 1, 1)));
        l.add(stack(PartKind.COIL, PartSpec.of("copper", 1, 1, "turns", 100, "wire_diameter", 1.0e-3)));
        l.add(stack(PartKind.COIL, PartSpec.of("copper", 1, 1, "turns", 400, "wire_diameter", 0.5e-3)));
        l.add(stack(PartKind.COIL, PartSpec.of("copper", 2, 1, "turns", 300, "wire_diameter", 0.8e-3)));
        l.add(stack(PartKind.COIL, PartSpec.of("copper", 2, 3, "turns", 600, "wire_diameter", 1.0e-3)));
        // conductors
        l.add(stack(PartKind.WIRE, PartSpec.of("copper", 1, 1, "diameter", 1.5e-3)));
        l.add(stack(PartKind.INSULATED_WIRE, PartSpec.of("copper", 1, 1, "diameter", 1.5e-3)));
        l.add(stack(PartKind.INSULATED_WIRE, PartSpec.of("copper", 1, 1, "diameter", 0.5e-3)));
        l.add(stack(PartKind.WIRE, PartSpec.of("nichrome", 1, 1, "diameter", 0.3e-3)));
        l.add(stack(PartKind.WIRE, PartSpec.of("tungsten", 1, 1, "diameter", 0.05e-3)));
        l.add(stack(PartKind.WIRE, PartSpec.of("constantan", 1, 1, "diameter", 0.5e-3)));
        l.add(stack(PartKind.BRUSH, PartSpec.of("graphite", 1, 1)));
        // manufactured components
        for (double r : new double[]{1, 10, 100, 1000}) l.add(stack(PartKind.RESISTOR, PartSpec.of("alumina", 1, 1, "resistance", r)));
        l.add(stack(PartKind.CAPACITOR, PartSpec.of("alumina", 1, 1, "capacitance", 1e-4)));
        l.add(stack(PartKind.CAPACITOR, PartSpec.of("alumina", 1, 1, "capacitance", 1e-2)));
        l.add(stack(PartKind.DIODE, PartSpec.of("silicon", 1, 1)));
        l.add(stack(PartKind.SWITCH, PartSpec.of("brass", 1, 1)));
        return l;
    }
}
