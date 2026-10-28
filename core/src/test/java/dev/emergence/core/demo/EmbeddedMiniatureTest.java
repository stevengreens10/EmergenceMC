package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A miniature block inside a block-scale machine: a full-size battery's wires tap a full-size ribbon cable, and the
 * ribbon, against the miniature block's north face, feeds a miniature LED circuit through the wires of its colours.
 */
class EmbeddedMiniatureTest {
    @Test
    void aRibbonPowersAnEmbeddedMiniatureCircuit() {
        Exhibit mini = new Exhibit("lamp", "Lamp", List.of(), "");
        mini.miniature = true;
        // the supply's two colours in at the north face (z 0), down to an LED and its resistor
        for (int z = 0; z <= 2; z++) mini.add(INSULATED_WIRE, "copper", 4, 0, z, Dir.UP, "color", 1);
        for (int z = 0; z <= 2; z++) mini.add(INSULATED_WIRE, "copper", 8, 0, z, Dir.UP, "color", 0);
        mini.name("led", mini.add(LED, "silicon", 4, 0, 3, Dir.SOUTH, "wavelength", 565));
        mini.add(RESISTOR, "alumina", 8, 0, 3, Dir.SOUTH, "resistance", 220);
        mini.add(INSULATED_WIRE, "copper", 4, 0, 4, Dir.UP, "color", 2);
        for (int x = 4; x <= 8; x++) mini.add(INSULATED_WIRE, "copper", x, 0, 5, Dir.UP, "color", 2);
        mini.add(INSULATED_WIRE, "copper", 8, 0, 4, Dir.UP, "color", 2);
        Exhibit e = new Exhibit("host", "Host", List.of(), "");
        // the battery (+ east) and its wires onto the ribbon at (3, 0, 2), the miniature block at (3, 0, 3)
        e.add(BATTERY, "pvc", 1, 0, 0, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 1);
        e.add(INSULATED_WIRE, "copper", 2, 0, 0, Dir.UP, "color", 1);
        e.add(INSULATED_WIRE, "copper", 3, 0, 0, Dir.UP, "color", 1);
        e.add(INSULATED_WIRE, "copper", 3, 0, 1, Dir.UP, "color", 1);
        e.add(INSULATED_WIRE, "copper", 0, 0, 0, Dir.UP, "color", 0);
        e.add(INSULATED_WIRE, "copper", 0, 0, 1, Dir.UP, "color", 0);
        e.add(INSULATED_WIRE, "copper", 0, 0, 2, Dir.UP, "color", 0);
        e.add(INSULATED_WIRE, "copper", 1, 0, 2, Dir.UP, "color", 0);
        e.add(INSULATED_WIRE, "copper", 2, 0, 2, Dir.UP, "color", 0);
        e.add(RIBBON, "copper", 3, 0, 2, Dir.EAST);
        int k = e.embed(3, 0, 3, mini);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        for (int i = 0; i < 10; i++) c.step(0.05);
        double i = c.probe(b.embeddedId(e, k, "led")).current();
        System.out.printf("embedded miniature: LED %.2f mA%n", 1e3 * i);
        assertTrue(Math.abs(i) > 5e-3, "the full-size battery lights the miniature LED through the ribbon: " + i + " A");
    }

    /**
     * The other way round: a miniature battery's two colours onto miniature ribbons on two faces of its block, and the
     * full-size insulated wires against those faces (whose ends cover the faces) to a full-size LED and its resistor.
     */
    @Test
    void miniatureRibbonsFeedFullSizeWires() {
        Exhibit mini = new Exhibit("cell", "Cell", List.of(), "");
        mini.miniature = true;
        // three cells, + to the north face's ribbon on colour 1, − to the south face's on colour 0
        // (its leads across, along x: + east at 7, − west at 5)
        mini.add(BATTERY, "pvc", 6, 0, 6, Dir.NORTH, "chemistry", 1, "cells", 3, "capacity", 1, "lead_axis", 0);
        for (int z = 1; z <= 6; z++) mini.add(INSULATED_WIRE, "copper", 7, 0, z, Dir.UP, "color", 1);
        for (int z = 6; z <= 14; z++) mini.add(INSULATED_WIRE, "copper", 5, 0, z, Dir.UP, "color", 0);
        mini.add(RIBBON, "copper", 7, 0, 0, Dir.EAST);
        mini.add(RIBBON, "copper", 5, 0, 15, Dir.EAST);
        Exhibit e = new Exhibit("host", "Host", List.of(), "");
        e.add(INSULATED_WIRE, "copper", 3, 0, 4, Dir.UP, "color", 0);  // against the block's south face
        e.add(INSULATED_WIRE, "copper", 4, 0, 4, Dir.UP, "color", 0);
        e.add(INSULATED_WIRE, "copper", 5, 0, 4, Dir.UP, "color", 0);
        e.name("led", e.add(LED, "silicon", 5, 0, 3, Dir.EAST, "wavelength", 630));
        e.add(INSULATED_WIRE, "copper", 5, 0, 2, Dir.UP, "color", 1);
        e.add(RESISTOR, "alumina", 4, 0, 2, Dir.NORTH, "resistance", 220);
        e.add(INSULATED_WIRE, "copper", 3, 0, 2, Dir.UP, "color", 1);  // against the block's north face
        int k = e.embed(3, 0, 3, mini);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        for (int i = 0; i < 10; i++) c.step(0.05);
        double i = c.probe(b.id(e, "led")).current();
        System.out.printf("miniature ribbons out: full-size LED %.2f mA (%.3f V across it); %d bridges%n", 1e3 * i, c.probe(b.id(e, "led")).voltage(),
                b.assembly().bridges().size());
        assertTrue(Math.abs(i) > 3e-3, "the miniature battery lights the full-size LED through its ribbons: " + i + " A");
    }
}
