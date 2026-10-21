package dev.emergence.core.assembly;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The part editor's schema: everything the exhibits are built from can be set in it, and its defaults are sound. */
class PartParametersTest {
    @Test
    void everyExhibitPartPassesTheEditorsChecks() {
        List<String> bad = new ArrayList<>();
        for (Exhibit e : Exhibits.all()) {
            for (Exhibit.PartDef d : e.parts) {
                String why = PartParameters.check(d.kind(), d.material(), d.params());
                if (why != null) bad.add(e.id + ": " + d.kind() + " of " + d.material() + ": " + why);
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " parts a player could not make:\n" + String.join("\n", bad));
    }

    @Test
    void defaultsAreInRangeAndDefaultMaterialsAllowed() {
        for (PartKind k : PartKind.values()) {
            var s = PartParameters.of(k);
            assertNotNull(s, "schema for " + k);
            var m = MaterialDatabase.standard().getOrNull(s.defaultMaterial());
            assertNotNull(m, k + ": default material " + s.defaultMaterial());
            assertTrue(s.materials().test(m), k + " may be made of its default material");
            assertFalse(s.allowedMaterials().isEmpty(), k + " has materials");
            for (var p : s.params()) {
                assertTrue(p.min() <= p.max(), k + "." + p.key() + " range");
                if (p.hasFixedDefault()) assertNull(PartParameters.checkValue(p, p.defaultValue()), k + "." + p.key() + " default " + p.defaultValue());
                else assertNotNull(p.auto(), k + "." + p.key() + " says what its automatic default is");
                if (p.type() == PartParameters.Type.CHOICE) assertFalse(p.choices().isEmpty(), k + "." + p.key() + " choices");
            }
        }
    }

    @Test
    void checksCatchWhatAPlayerCouldGetWrong() {
        assertNull(PartParameters.check(PartKind.RESISTOR, "alumina", java.util.Map.of("resistance", 470.0)));
        assertNotNull(PartParameters.check(PartKind.RESISTOR, "copper_sulfate_1m", java.util.Map.of()), "no resistor of a solution");
        assertNotNull(PartParameters.check(PartKind.MAGNET, "copper", java.util.Map.of()), "no magnet of copper");
        assertNotNull(PartParameters.check(PartKind.WIRE, "copper", java.util.Map.of("diameter", -1.0)), "no negative diameter");
        assertNotNull(PartParameters.check(PartKind.WIRE, "copper", java.util.Map.of("colour", 1.0)), "no unknown parameter");
        assertNotNull(PartParameters.check(PartKind.TRANSISTOR, "silicon", java.util.Map.of("type", 7.0)), "no such transistor type");
        assertNotNull(PartParameters.check(PartKind.BATTERY, "pvc", java.util.Map.of("cells", 2.5)), "whole cells");
        assertNull(PartParameters.check(PartKind.TANK, "steel_1045", java.util.Map.of("x_CH4", 0.1, "x_O2", 0.2, "x_N2", 0.7)), "a gas charge");
        assertNull(PartParameters.check(PartKind.CHIP, "silicon", java.util.Map.of("function", 3.0, "w0", 133.0)), "a ROM word");
    }

    @Test
    void everyCataloguePresetPassesTheEditorsChecks() {
        for (var p : PartCatalog.all()) assertNull(PartParameters.check(p.kind(), p.material(), p.params()), p.name());
        for (PartKind k : PartKind.values()) assertFalse(PartCatalog.of(k).isEmpty(), k + " has at least one stock variant");
    }
}
