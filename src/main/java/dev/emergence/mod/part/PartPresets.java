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
        // structure (at block scale each block is one 6.25 cm cell of the model)
        for (String m : new String[]{"steel_1045", "aluminium_6061", "stainless_304", "oak", "granite", "copper", "ptfe", "cfrp"}) {
            l.add(stack(PartKind.BLOCK, PartSpec.of(m, 1, 1)));
        }
        l.add(stack(PartKind.BLOCK, PartSpec.of("aluminium_6061", 1, 1, "fill", 0.25)));
        l.add(stack(PartKind.BLOCK, PartSpec.of("copper", 1, 1, "fill", 0.5)));
        // rotating machinery elements
        l.add(stack(PartKind.SHAFT, PartSpec.of("steel_1045", 1, 1, "diameter", 0.02)));
        l.add(stack(PartKind.SHAFT, PartSpec.of("steel_1045", 4, 1, "diameter", 0.02)));
        l.add(stack(PartKind.SHAFT, PartSpec.of("steel_1045", 16, 1, "diameter", 0.025)));
        l.add(stack(PartKind.BEARING, PartSpec.of("steel_1045", 1, 1, "type", 0)));
        l.add(stack(PartKind.BEARING, PartSpec.of("bronze", 1, 1, "type", 1)));
        // spur gears of one tooth module (m = 2r/z = 7.8 mm) so any two of them mesh at centre distance r1 + r2
        l.add(stack(PartKind.GEAR, PartSpec.of("steel_1045", 1, 1, "teeth", 8, "pitch_radius", 0.5 / 16)));
        l.add(stack(PartKind.GEAR, PartSpec.of("steel_1045", 1, 1, "teeth", 24, "pitch_radius", 1.5 / 16)));
        l.add(stack(PartKind.GEAR, PartSpec.of("steel_1045", 1, 1, "teeth", 40, "pitch_radius", 2.5 / 16)));
        l.add(stack(PartKind.GEAR, PartSpec.of("brass", 1, 1, "teeth", 16, "pitch_radius", 1.0 / 16)));
        l.add(stack(PartKind.CRANK, PartSpec.of("steel_1045", 1, 1, "radius", 0.15)));
        // magnetics
        l.add(stack(PartKind.MAGNET, PartSpec.of("ndfeb_n42", 1, 1)));
        l.add(stack(PartKind.MAGNET, PartSpec.of("ferrite_c8", 1, 1)));
        l.add(stack(PartKind.MAGNET, PartSpec.of("alnico_5", 1, 1)));
        l.add(stack(PartKind.MAGNET, PartSpec.of("smco_2_17", 1, 1)));
        l.add(stack(PartKind.COIL, PartSpec.of("copper", 1, 1, "turns", 100, "wire_diameter", 1.0e-3)));
        l.add(stack(PartKind.COIL, PartSpec.of("copper", 1, 1, "turns", 400, "wire_diameter", 0.5e-3)));
        l.add(stack(PartKind.COIL, PartSpec.of("copper", 2, 1, "turns", 300, "wire_diameter", 0.8e-3)));
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
        // electrochemistry: solutions in glass jars and electrode metals
        for (String m : new String[]{"copper_sulfate_1m", "zinc_sulfate_1m", "sulfuric_acid_1m", "brine", "silver_nitrate_01m"}) {
            l.add(stack(PartKind.ELECTROLYTE, PartSpec.of(m, 1, 1)));
        }
        for (String m : new String[]{"zinc", "lead", "nickel", "silver", "iron", "graphite"}) {
            l.add(stack(PartKind.BLOCK, PartSpec.of(m, 1, 1)));
        }
        // fluids: pipes, vessels, valves and bladed rotors (fluid codes: 0 air, 1 water, 2 helium, 4 oil)
        l.add(stack(PartKind.PIPE, PartSpec.of("copper", 1, 1, "bore", 0.025)));
        l.add(stack(PartKind.PIPE, PartSpec.of("steel_1045", 1, 1, "bore", 0.04)));
        l.add(stack(PartKind.PIPE, PartSpec.of("pvc", 1, 1, "bore", 0.05, "wall", 0.003)));
        l.add(stack(PartKind.PIPE, PartSpec.of("copper", 1, 1, "bore", 0.025, "fluid", 1)));
        l.add(stack(PartKind.PIPE, PartSpec.of("steel_1045", 1, 1, "bore", 0.012, "wall", 0.003, "fluid", 4)));
        l.add(stack(PartKind.TANK, PartSpec.of("steel_1045", 1, 1)));
        l.add(stack(PartKind.TANK, PartSpec.of("steel_1045", 1, 1, "pressure", 1e6, "wall", 0.004, "gauge_range", 16)));
        l.add(stack(PartKind.TANK, PartSpec.of("steel_1045", 1, 1, "fluid", 1)));
        l.add(stack(PartKind.TANK, PartSpec.of("aluminium_6061", 1, 1, "fluid", 2, "pressure", 5e5, "gauge_range", 10)));
        l.add(stack(PartKind.VALVE, PartSpec.of("brass", 1, 1, "bore", 0.025)));
        l.add(stack(PartKind.VALVE, PartSpec.of("steel_1045", 1, 1, "bore", 0.04)));
        // water and steam (fluid 7): a boiler a third full, steam pipe, safety valve, cam-worked valve, throttle
        l.add(stack(PartKind.TANK, PartSpec.of("steel_1045", 1, 1, "fluid", 7, "fill", 0.6, "wall", 0.003, "gauge_range", 6)));
        l.add(stack(PartKind.PIPE, PartSpec.of("copper", 1, 1, "bore", 0.008, "fluid", 7)));
        l.add(stack(PartKind.VALVE, PartSpec.of("brass", 1, 1, "bore", 0.006, "fluid", 7, "relief", 4.5e5)));
        l.add(stack(PartKind.VALVE, PartSpec.of("brass", 1, 1, "bore", 0.008, "fluid", 7, "lap", 0.7)));
        l.add(stack(PartKind.VALVE, PartSpec.of("brass", 1, 1, "bore", 0.0012, "fluid", 7)));
        l.add(stack(PartKind.VALVE, PartSpec.of("brass", 1, 1, "bore", 0.01, "relief", 2e4)));
        l.add(stack(PartKind.BRAKE, PartSpec.of("oak", 1, 1, "force", 20)));
        l.add(stack(PartKind.BRAKE, PartSpec.of("alumina", 1, 1, "force", 100)));
        l.add(stack(PartKind.ROTOR, PartSpec.of("aluminium_6061", 1, 1, "blade_angle", 45, "pitch", 1)));
        l.add(stack(PartKind.ROTOR, PartSpec.of("aluminium_6061", 1, 1, "blade_angle", 45, "pitch", -1)));
        l.add(stack(PartKind.ROTOR, PartSpec.of("steel_1045", 1, 1, "blade_angle", 65, "pitch", 1)));
        // reciprocating machinery
        l.add(stack(PartKind.CYLINDER, PartSpec.of("steel_1045", 1, 1, "bore", 0.04, "fins", 6)));
        l.add(stack(PartKind.CYLINDER, PartSpec.of("aluminium_6061", 1, 1, "bore", 0.04, "fins", 6)));
        l.add(stack(PartKind.CYLINDER, PartSpec.of("steel_1045", 1, 1, "bore", 0.025)));
        for (double t : new double[]{0.01, 0.015, 0.02}) l.add(stack(PartKind.ECCENTRIC, PartSpec.of("steel_1045", 1, 1, "throw", t)));
        // semiconductors
        for (int type = 0; type < 4; type++) l.add(stack(PartKind.TRANSISTOR, PartSpec.of("silicon", 1, 1, "type", type)));
        for (double nm : new double[]{630, 590, 525, 470}) l.add(stack(PartKind.LED, PartSpec.of("silicon", 1, 1, "wavelength", nm)));
        l.add(stack(PartKind.CAPACITOR, PartSpec.of("alumina", 1, 1, "capacitance", 10)));
        l.add(stack(PartKind.CAPACITOR, PartSpec.of("alumina", 1, 1, "capacitance", 10e-6)));
        for (double r : new double[]{470, 10_000, 100_000}) l.add(stack(PartKind.RESISTOR, PartSpec.of("alumina", 1, 1, "resistance", r)));
        l.add(stack(PartKind.RESISTOR, PartSpec.of("alumina", 1, 1, "resistance", 10_000, "ntc_beta", 3950)));
        l.add(stack(PartKind.RESISTOR, PartSpec.of("alumina", 1, 1, "resistance", 6)));
        for (int fn = 0; fn <= 8; fn++) l.add(stack(PartKind.LOGIC, PartSpec.of("silicon", 1, 1, "function", fn)));
        return l;
    }
}
