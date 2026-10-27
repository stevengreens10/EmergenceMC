package dev.emergence.core.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock variants of primitive parts, by name — sizes and ratings a workshop would keep (a 12 V car battery, a 10 kΩ
 * resistor, a 24-tooth gear), not machines. The part editor offers them as starting points for each kind.
 */
public final class PartCatalog {
    private PartCatalog() {}

    /**
     * A named stock part: its kind, material, length in cells (a shaft or rack) and parameters, and the text it carries
     * (a chip's design or a ROM's contents; empty for most).
     */
    public record Preset(PartKind kind, String name, String material, int length, Map<String, Double> params, String text) {
        public Preset(PartKind kind, String name, String material, int length, Map<String, Double> params) {
            this(kind, name, material, length, params, "");
        }
    }

    private static final List<Preset> ALL = new ArrayList<>();

    private static void add(PartKind kind, String name, String material, Object... kv) {
        addLong(kind, name, material, 1, kv);
    }

    private static void addLong(PartKind kind, String name, String material, int length, Object... kv) {
        Map<String, Double> p = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) p.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        ALL.add(new Preset(kind, name, material, length, Map.copyOf(p)));
    }

    /** The presets for one kind, in catalogue order. */
    public static List<Preset> of(PartKind kind) {
        List<Preset> out = new ArrayList<>();
        for (Preset p : ALL) if (p.kind() == kind) out.add(p);
        return out;
    }

    public static List<Preset> all() {
        return List.copyOf(ALL);
    }

    static {
        // structure (at block scale each block is one 6.25 cm cell of the model)
        add(PartKind.BLOCK, "Steel block", "steel_1045");
        add(PartKind.BLOCK, "Aluminium block", "aluminium_6061");
        add(PartKind.BLOCK, "Oak block", "oak");
        add(PartKind.BLOCK, "Granite block", "granite");
        add(PartKind.BLOCK, "Copper block", "copper");
        add(PartKind.BLOCK, "Steel plate, 1.25 mm", "steel_1045", "fill", 0.02);
        add(PartKind.BLOCK, "Aluminium plate, 2.5 mm", "aluminium_6061", "fill", 0.04);
        add(PartKind.BLOCK, "Oak board, 12.5 mm", "oak", "fill", 0.2);
        add(PartKind.BLOCK, "Glass pane, 4.7 mm", "glass", "fill", 0.075);
        add(PartKind.BLOCK, "Lead sheet, 10 mm", "lead", "fill", 0.16);
        add(PartKind.BLOCK, "Electrical-steel core", "electrical_steel");
        add(PartKind.BLOCK, "Zinc electrode", "zinc");
        // rotating machinery
        addLong(PartKind.SHAFT, "Steel shaft, 20 mm", "steel_1045", 4, "diameter", 0.02);
        addLong(PartKind.SHAFT, "Steel shaft, 10 mm", "steel_1045", 4, "diameter", 0.01);
        addLong(PartKind.SHAFT, "Nylon spindle, 6 mm", "nylon_66", 4, "diameter", 0.006);
        add(PartKind.BEARING, "Ball bearing", "steel_1045", "type", 0);
        add(PartKind.BEARING, "Bronze bushing", "bronze", "type", 1);
        add(PartKind.BEARING, "Damped knob bearing", "bronze", "type", 2, "viscosity", 1.5);
        // spur gears of one tooth module (m = 2r/z = 7.8 mm) so any two of them mesh at centre distance r1 + r2
        add(PartKind.GEAR, "Pinion, 8 teeth", "steel_1045", "teeth", 8, "pitch_radius", 0.5 / 16);
        add(PartKind.GEAR, "Gear, 24 teeth", "steel_1045", "teeth", 24, "pitch_radius", 1.5 / 16);
        add(PartKind.GEAR, "Gear, 40 teeth", "steel_1045", "teeth", 40, "pitch_radius", 2.5 / 16);
        add(PartKind.GEAR, "Brass gear, 16 teeth", "brass", "teeth", 16, "pitch_radius", 1.0 / 16);
        add(PartKind.CRANK, "Hand crank", "steel_1045", "radius", 0.15);
        add(PartKind.CRANK, "Small knob", "steel_1045", "radius", 0.03);
        add(PartKind.ECCENTRIC, "Crank throw, 15 mm", "steel_1045", "throw", 0.015);
        add(PartKind.ECCENTRIC, "Crank throw, 10 mm", "steel_1045", "throw", 0.01);
        // magnetics
        add(PartKind.MAGNET, "Neodymium magnet (N42)", "ndfeb_n42");
        add(PartKind.MAGNET, "Ferrite magnet", "ferrite_c8");
        add(PartKind.MAGNET, "Alnico magnet", "alnico_5");
        add(PartKind.MAGNET, "Samarium–cobalt magnet", "smco_2_17");
        add(PartKind.COIL, "Coil, 200 turns", "copper", "turns", 200, "wire_diameter", 0.5e-3);
        add(PartKind.COIL, "Coil, 400 turns of 0.5 mm", "copper", "turns", 400, "wire_diameter", 0.5e-3);
        add(PartKind.COIL, "Transformer primary on a core", "copper", "turns", 200, "wire_diameter", 0.8e-3, "core", 1, "bore_fraction", 0.6, "lead_axis", 1);
        add(PartKind.COIL, "Transformer secondary on a core", "copper", "turns", 1000, "wire_diameter", 0.3e-3, "core", 1, "bore_fraction", 0.6, "lead_axis", 1);
        // conductors
        add(PartKind.WIRE, "Copper wire, 1.5 mm", "copper", "diameter", 1.5e-3);
        add(PartKind.WIRE, "Nichrome heating wire, 0.3 mm", "nichrome", "diameter", 0.3e-3);
        add(PartKind.WIRE, "Tungsten filament, 50 µm", "tungsten", "diameter", 0.05e-3);
        add(PartKind.WIRE, "Constantan wire, 0.5 mm", "constantan", "diameter", 0.5e-3);
        add(PartKind.WIRE, "Flexible tinsel lead", "copper", "diameter", 0.3e-3, "flexible", 1);
        add(PartKind.INSULATED_WIRE, "Hook-up wire, 1.5 mm", "copper", "diameter", 1.5e-3);
        add(PartKind.INSULATED_WIRE, "Thin hook-up wire, 0.5 mm", "copper", "diameter", 0.5e-3);
        add(PartKind.INSULATED_WIRE, "Glass-fibre insulated (hot)", "copper", "diameter", 1.5e-3, "max_temperature", 800);
        add(PartKind.BRUSH, "Graphite brush", "graphite");
        // manufactured components
        for (double r : new double[]{1, 10, 100, 470, 1000, 10_000, 100_000}) {
            add(PartKind.RESISTOR, "Resistor, " + dev.emergence.core.units.Units.formatSI(r, "Ω").replace(".000", ""), "alumina", "resistance", r);
        }
        add(PartKind.RESISTOR, "NTC thermistor, 10 kΩ", "alumina", "resistance", 10_000, "ntc_beta", 3950);
        add(PartKind.RESISTOR, "Heating element, 6 Ω", "alumina", "resistance", 6, "exposed", 1);
        add(PartKind.CAPACITOR, "Capacitor, 10 µF", "alumina", "capacitance", 10e-6);
        add(PartKind.CAPACITOR, "Capacitor, 100 µF", "alumina", "capacitance", 1e-4);
        add(PartKind.CAPACITOR, "Capacitor, 10 mF", "alumina", "capacitance", 1e-2);
        add(PartKind.CAPACITOR, "Supercapacitor, 10 F", "alumina", "capacitance", 10);
        add(PartKind.DIODE, "Silicon diode", "silicon");
        add(PartKind.SWITCH, "Toggle switch", "brass");
        add(PartKind.BATTERY, "12 V lead–acid, 7 A·h", "pvc", "cells", 6, "capacity", 7);
        add(PartKind.BATTERY, "24 V lead–acid, 7 A·h", "pvc", "cells", 12, "capacity", 7);
        add(PartKind.BATTERY, "6 V lead–acid, 1.2 A·h", "pvc", "cells", 3, "capacity", 1.2);
        add(PartKind.BATTERY, "5 V pack: 4 NiMH cells, 2 A·h", "pvc", "chemistry", 1, "cells", 4, "capacity", 2);
        // electrochemistry: solutions in glass jars
        for (String m : new String[]{"copper_sulfate_1m", "zinc_sulfate_1m", "sulfuric_acid_1m", "brine", "silver_nitrate_01m"}) {
            add(PartKind.ELECTROLYTE, m.replace('_', ' '), m);
        }
        // fluids: pipes, vessels, valves and bladed rotors
        add(PartKind.PIPE, "Copper pipe, 25 mm, air", "copper", "bore", 0.025);
        add(PartKind.PIPE, "Copper pipe, 25 mm, water", "copper", "bore", 0.025, "fluid", 1);
        add(PartKind.PIPE, "PVC duct, 50 mm", "pvc", "bore", 0.05, "wall", 0.003);
        add(PartKind.PIPE, "Hydraulic line, 12 mm, oil", "steel_1045", "bore", 0.012, "wall", 0.003, "fluid", 4);
        add(PartKind.PIPE, "Steam pipe, 8 mm", "copper", "bore", 0.008, "fluid", 7);
        add(PartKind.TANK, "Air vessel", "steel_1045");
        add(PartKind.TANK, "Air receiver, 10 bar", "steel_1045", "pressure", 1e6, "wall", 0.004, "gauge_range", 16);
        add(PartKind.TANK, "Water tank", "steel_1045", "fluid", 1);
        add(PartKind.TANK, "Boiler, 60 % full", "steel_1045", "fluid", 7, "fill", 0.6, "wall", 0.003, "gauge_range", 6);
        add(PartKind.TANK, "Helium cylinder, 5 bar", "aluminium_6061", "fluid", 2, "pressure", 5e5, "gauge_range", 10);
        add(PartKind.TANK, "Flask of N₂O₄ (brown gas)", "glass", "x_N2O4", 1, "pressure", 5000, "wall", 0.002);
        add(PartKind.TANK, "Can of air", "steel_1045", "x_N2", 0.78, "x_O2", 0.21, "x_Ar", 0.01, "pressure", 101325);
        add(PartKind.VALVE, "Brass valve, 25 mm", "brass", "bore", 0.025);
        add(PartKind.VALVE, "Steam valve, 8 mm", "brass", "bore", 0.008, "fluid", 7);
        add(PartKind.VALVE, "Safety valve, 4.5 bar (steam)", "brass", "bore", 0.006, "fluid", 7, "relief", 4.5e5);
        add(PartKind.VALVE, "Cam-worked slide valve (steam)", "brass", "bore", 0.008, "fluid", 7, "lap", 0.7);
        add(PartKind.BRAKE, "Wooden brake block", "oak", "force", 20);
        add(PartKind.BRAKE, "Bronze brake shoe", "bronze", "force", 100);
        add(PartKind.ROTOR, "Fan, right-hand", "aluminium_6061", "blade_angle", 45, "pitch", 1);
        add(PartKind.ROTOR, "Fan, left-hand", "aluminium_6061", "blade_angle", 45, "pitch", -1);
        add(PartKind.ROTOR, "Turbine wheel", "steel_1045", "blade_angle", 65, "pitch", 1);
        add(PartKind.CYLINDER, "Air-cooled cylinder, 40 mm", "aluminium_6061", "bore", 0.04, "fins", 6);
        add(PartKind.CYLINDER, "Steam cylinder, 25 mm", "steel_1045", "bore", 0.025, "fluid", 7);
        // semiconductors
        add(PartKind.TRANSISTOR, "NPN transistor", "silicon", "type", 0);
        add(PartKind.TRANSISTOR, "PNP transistor", "silicon", "type", 1);
        add(PartKind.TRANSISTOR, "n-channel MOSFET", "silicon", "type", 2);
        add(PartKind.TRANSISTOR, "p-channel MOSFET", "silicon", "type", 3);
        add(PartKind.TRANSISTOR, "NPN power transistor", "silicon", "type", 0, "beta", 60, "saturation_current", 1e-12, "surface_area", 0.03);
        add(PartKind.TRANSISTOR, "Phototransistor (window up)", "silicon", "type", 0, "photo", 7.5e-6, "face", 1);
        add(PartKind.LED, "Red LED", "silicon", "wavelength", 630);
        add(PartKind.LED, "Amber LED", "silicon", "wavelength", 590);
        add(PartKind.LED, "Green LED", "silicon", "wavelength", 525);
        add(PartKind.LED, "Blue LED", "silicon", "wavelength", 470);
        add(PartKind.LED, "Infrared LED", "silicon", "wavelength", 850, "efficiency", 0.3, "beam", 20);
        String[] gates = {"Buffer", "NOT gate", "NAND gate", "NOR gate", "AND gate", "OR gate", "XOR gate", "Schmitt inverter", "D flip-flop"};
        for (int fn = 0; fn < gates.length; fn++) add(PartKind.LOGIC, gates[fn], "silicon", "function", fn);
        String[] chips = {"4-bit counter", "4-bit register", "4-bit adder", "16 × 8 ROM", "16 × 4 RAM", "Custom chip (your design)",
                "Crystal clock", "10-bit SPI ADC", "Display controller", "256 × 8 ROM"};
        for (int fn = 0; fn < chips.length; fn++) add(PartKind.CHIP, chips[fn], "silicon", "function", fn);
        // shipped designs and ROM images, ready to place (and to open in the chip designer or the ROM editor)
        for (String n : dev.emergence.core.digital.ChipLibrary.NAMES) {
            ALL.add(new Preset(PartKind.CHIP, dev.emergence.core.digital.ChipLibrary.title(n) + " (design)", "silicon", 1,
                    Map.of("function", (double) dev.emergence.core.digital.ChipTypes.DESIGN), dev.emergence.core.digital.ChipLibrary.source(n)));
        }
        for (String n : dev.emergence.core.digital.ChipLibrary.ROMS) {
            ALL.add(new Preset(PartKind.CHIP, dev.emergence.core.digital.ChipLibrary.romTitle(n) + " (256 × 8 ROM)", "silicon", 1,
                    Map.of("function", (double) dev.emergence.core.digital.ChipTypes.ROM256), dev.emergence.core.digital.ChipLibrary.rom(n)));
        }
        add(PartKind.CHIP, "Crystal clock, 100 kHz", "silicon", "function", 6, "frequency", 100_000);
        add(PartKind.DISPLAY, "128 × 64 OLED display", "glass");
        add(PartKind.RIBBON, "Ribbon cable, 16 ways", "copper");
        add(PartKind.PRESSURE_SENSOR, "Pressure transducer, 0–10 bar", "stainless_304", "pmin", 0, "pmax", 10e5);
        add(PartKind.PRESSURE_SENSOR, "Pressure transducer, 0–2 bar", "stainless_304", "pmin", 0, "pmax", 2e5);
        add(PartKind.PHOTODIODE, "Photodiode (BPW34)", "silicon", "area", 7.5e-6);
        add(PartKind.PHOTODIODE, "Solar cell", "silicon", "area", 0.0035);
        add(PartKind.OPAMP, "Op-amp (TL071)", "silicon");
        add(PartKind.POTENTIOMETER, "Potentiometer, 10 kΩ", "nylon_66", "resistance", 10_000);
        add(PartKind.POTENTIOMETER, "Potentiometer, 1 kΩ", "nylon_66", "resistance", 1_000);
        // slides
        add(PartKind.LINEAR_BEARING, "Ball bushing", "steel_1045");
        add(PartKind.LINEAR_BEARING, "Ball bushing with ±1.5 mm stops", "steel_1045", "travel", 0.0015);
        add(PartKind.SPRING, "Spring, 2 mm wire", "steel_1045", "wire", 0.002);
        add(PartKind.SPRING, "Spring, 3 mm wire", "steel_1045", "wire", 0.003);
        add(PartKind.SPRING, "Governor spring, preloaded", "steel_1045", "wire", 0.006, "preload", 0.0087);
        addLong(PartKind.RACK, "Rack, teeth up", "steel_1045", 4, "face", 1);
        add(PartKind.NUT, "Lead-screw nut, 4 mm lead", "bronze", "lead", 0.004);
        add(PartKind.NUT, "Steep nut, 40 mm lead", "bronze", "lead", 0.04);
        add(PartKind.LINK, "Link bar", "steel_1045");
        // optics
        add(PartKind.MIRROR, "Flat mirror (aluminium)", "aluminium");
        add(PartKind.MIRROR, "Flat mirror (silver)", "silver");
        add(PartKind.MIRROR, "45° mirror", "aluminium", "tilt", 45, "tilt_toward", 2);
        add(PartKind.MIRROR, "Concave mirror, f 0.25 m", "aluminium", "radius", 0.5);
        add(PartKind.MIRROR, "Concave mirror, f 1 m", "aluminium", "radius", 2.0);
        add(PartKind.LENS, "Biconvex lens (BK7)", "glass_bk7");
        add(PartKind.LENS, "Biconvex lens, R 0.25 m", "glass_bk7", "radius", 0.25);
        add(PartKind.LENS, "Plano-convex lens", "glass_bk7", "radius", 0.06, "radius2", 0);
        add(PartKind.LENS, "Biconcave lens", "glass_bk7", "radius", -0.12, "radius2", 0.12);
        add(PartKind.PRISM, "Equilateral prism (BK7)", "glass_bk7", "apex", 60);
        add(PartKind.PRISM, "Dispersing prism (SF11)", "glass_sf11", "apex", 45);
        for (int pitch : new int[]{-10, 10}) add(PartKind.BLADE, "Blade, " + pitch + "° pitch", "aluminium_6061", "pitch", pitch);
        // radioactivity
        add(PartKind.SOURCE, "Cs-137 check source, 370 kBq", "stainless_304", "nuclide", 0, "activity", 3.7e5);
        add(PartKind.SOURCE, "Co-60 check source, 37 kBq", "stainless_304", "nuclide", 2, "activity", 3.7e4);
        add(PartKind.SOURCE, "Am-241 check source, 37 kBq", "stainless_304", "nuclide", 3, "activity", 3.7e4);
        add(PartKind.GM_TUBE, "Geiger–Müller tube", "stainless_304");
    }
}
