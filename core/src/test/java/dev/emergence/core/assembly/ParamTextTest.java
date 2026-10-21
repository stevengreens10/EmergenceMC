package dev.emergence.core.assembly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Values as the part editor shows them and reads them back. */
class ParamTextTest {
    private static PartParameters.Param p(PartKind k, String key) {
        return PartParameters.of(k).param(key);
    }

    @Test
    void showsValuesInReadableUnits() {
        assertEquals("1.5 mm", ParamText.format(p(PartKind.WIRE, "diameter"), 1.5e-3));
        assertEquals("0.5 mm", ParamText.format(p(PartKind.COIL, "wire_diameter"), 0.5e-3));
        assertEquals("150 mm", ParamText.format(p(PartKind.CRANK, "radius"), 0.15));
        assertEquals("2 m", ParamText.format(p(PartKind.MIRROR, "radius"), 2.0));
        assertEquals("4.7 kΩ", ParamText.format(p(PartKind.RESISTOR, "resistance"), 4700));
        assertEquals("100 pF", ParamText.format(p(PartKind.CAPACITOR, "capacitance"), 1e-10));
        assertEquals("80 %", ParamText.format(p(PartKind.ELECTROLYTE, "fill"), 0.8));
        assertEquals("630 nm", ParamText.format(p(PartKind.LED, "wavelength"), 630));
        assertEquals("7.5 mm²", ParamText.format(p(PartKind.PHOTODIODE, "area"), 7.5e-6));
        assertEquals("13 V/µs", ParamText.format(p(PartKind.OPAMP, "slew"), 13e6));
        assertEquals("45°", ParamText.format(p(PartKind.ROTOR, "blade_angle"), 45));
        assertEquals("6", ParamText.format(p(PartKind.BATTERY, "cells"), 6));
        assertEquals("101.3 kPa", ParamText.format(p(PartKind.TANK, "pressure"), 101325));
    }

    @Test
    void readsWhatPeopleType() {
        var r = p(PartKind.RESISTOR, "resistance");
        assertEquals(4700, ParamText.parse(r, "4.7k", 1000), 1e-9);
        assertEquals(4700, ParamText.parse(r, "4.7 kΩ", 1000), 1e-9);
        assertEquals(0.22, ParamText.parse(r, "220 mΩ", 1000), 1e-12);
        assertEquals(10_000, ParamText.parse(r, "10", 4700), 1e-9, "a bare number in the prefix shown (kΩ)");
        var d = p(PartKind.WIRE, "diameter");
        assertEquals(2e-3, ParamText.parse(d, "2 mm", 1.5e-3), 1e-12);
        assertEquals(2e-3, ParamText.parse(d, "2", 1.5e-3), 1e-12, "a bare number in millimetres, as shown");
        assertEquals(2.0, ParamText.parse(d, "2 m", 1.5e-3), 1e-12, "metres when said so");
        assertEquals(0.5, ParamText.parse(p(PartKind.ELECTROLYTE, "fill"), "50", 0.8), 1e-12, "a percentage");
        assertEquals(1.5e-6, ParamText.parse(p(PartKind.COIL, "wire_diameter"), "1.5u", 1e-3), 1e-15);
        assertEquals(0.8e-3, ParamText.parse(p(PartKind.COIL, "wire_diameter"), "0.8", 0.5e-3), 1e-15, "a bare number in millimetres");
        assertNull(ParamText.parse(r, "lots", 1));
        assertNull(ParamText.parse(r, "5 kg", 1), "the wrong unit");
    }
}
