package dev.emergence.core.assembly;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A single-cell component brings its leads out on its sides (along its lead axis) as well as its ends: what touches
 * those faces joins its leads. The compiler warns where that is likely an accident — a wire of another colour beside
 * a lead that a wire reaches at its end, or two components side by side (both found the hard way: the station's
 * regulator, the dashboard's amplifier).
 */
class LeadJoinWarningTest {
    static List<String> warnings(Exhibit e) {
        var b = Exhibits.build(e, 293.15);
        return AssemblyCompiler.compile(b.assembly(), b.state()).warnings();
    }

    @Test
    void aWireOfAnotherColourBesideALeadIsFlagged() {
        Exhibit e = new Exhibit("t", "t", List.of(), "");
        e.add(RESISTOR, "alumina", 5, 0, 5, Dir.EAST, "resistance", 100);
        e.add(INSULATED_WIRE, "copper", 6, 0, 5, Dir.UP, "color", 0);   // its east end (+)
        e.add(INSULATED_WIRE, "copper", 5, 0, 6, Dir.UP, "color", 3);   // its south side: the + lead too
        var w = warnings(e);
        assertTrue(w.stream().anyMatch(s -> s.contains("on its side by colour 3")), "warned: " + w);
    }

    @Test
    void componentsSideBySideAreFlaggedAndARowApartAreNot() {
        Exhibit e = new Exhibit("t", "t", List.of(), "");
        e.add(RESISTOR, "alumina", 5, 0, 5, Dir.EAST, "resistance", 100);
        e.add(CAPACITOR, "alumina", 5, 0, 6, Dir.EAST, "capacitance", 1e-6);
        assertTrue(warnings(e).stream().anyMatch(s -> s.contains("touch side by side")), "warned: " + warnings(e));
        Exhibit apart = new Exhibit("t", "t", List.of(), "");
        apart.add(RESISTOR, "alumina", 5, 0, 5, Dir.EAST, "resistance", 100);
        apart.add(CAPACITOR, "alumina", 5, 0, 7, Dir.EAST, "capacitance", 1e-6);
        assertTrue(warnings(apart).stream().noneMatch(s -> s.contains("side by side")), "a row apart: " + warnings(apart));
    }
}
