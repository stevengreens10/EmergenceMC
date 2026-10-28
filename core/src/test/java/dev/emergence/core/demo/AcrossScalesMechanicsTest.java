package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Gears meshing, and structure held, across a block's face between full-size and miniature parts. */
class AcrossScalesMechanicsTest {
    /**
     * A full-size 32-tooth gear on a cranked vertical shaft beside a miniature block, and in that block a 16-tooth gear
     * of the same tooth size on its own vertical shaft, against the face: they mesh, the miniature turning twice as
     * fast the other way.
     */
    @Test
    void gearsMeshAcrossTheFace() {
        String st = "steel_1045";
        Exhibit mini = new Exhibit("minigear", "Mini gear", List.of(), "");
        mini.miniature = true;
        int shaft = mini.fill(SHAFT, st, 0, 0, 8, 0, 12, 8, Dir.UP, "diameter", 0.006);
        mini.name("shaft", shaft);
        mini.add(BEARING, st, 0, 0, 8, Dir.UP);
        mini.add(GEAR, st, 0, 8, 8, Dir.UP, "teeth", 16, "pitch_radius", 1.0 / 16, "thickness", 0.01);
        mini.add(BLOCK, st, 1, 12, 8, Dir.UP, "fill", 0.3); // a little inertia on its shaft
        // (a block up: on the ground a gear is anchored; the miniature block stands on a full-size block, which holds it)
        Exhibit e = new Exhibit("host", "Host", List.of(), "");
        int bigShaft = e.fill(SHAFT, st, 3, 1, 3, 3, 3, 3, Dir.UP, "diameter", 0.012);
        e.name("bigshaft", bigShaft);
        e.add(BEARING, st, 3, 2, 3, Dir.UP);
        // the bearing's post, round the gear (a block beside the gear would weld it)
        e.fill(BLOCK, st, 2, 0, 4, 2, 2, 4, Dir.UP);
        e.add(BLOCK, st, 2, 2, 3, Dir.UP);
        e.add(GEAR, st, 3, 1, 3, Dir.UP, "teeth", 32, "pitch_radius", 2.0 / 16, "thickness", 0.01);
        e.name("crank", e.add(CRANK, st, 3, 4, 3, Dir.UP, "radius", 0.1));
        e.add(BLOCK, st, 4, 0, 3, Dir.UP); // under the miniature block
        int k = e.embed(4, 1, 3, mini);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        assertEquals(1, c.gearLinks(), "the gears mesh across the face");
        for (int i = 0; i < 40; i++) {
            c.setCrankTorque(b.id(e, "crank"), 0.3);
            c.step(0.05);
        }
        double big = c.probe(b.id(e, "bigshaft")).angularVelocity(), small = c.probe(b.embeddedId(e, k, "shaft")).angularVelocity();
        System.out.printf("gears across scales: full-size %.2f rad/s, miniature %.2f rad/s (ratio %.3f)%n", big, small, small / big);
        assertTrue(Math.abs(big) > 1, "the crank turns its gear: " + big);
        assertEquals(-2.0, small / big, 0.05, "the 16-tooth miniature turns twice as fast, the other way");
    }

    /**
     * A miniature block resting on a full-size block that turns with a shaft: the slab on the miniature block's floor
     * is held to the block below — one rigid piece with it, it turns with the rotor. (Without the link a lone slab
     * would be a machine of its own, standing still.)
     */
    @Test
    void aMiniatureBlockRidesOnTheFullSizeBlockUnderIt() {
        String st = "steel_1045";
        Exhibit mini = new Exhibit("slab", "Slab", List.of(), "");
        mini.miniature = true;
        mini.name("slab", mini.box(BLOCK, st, 4, 0, 4, 11, 0, 11, Dir.UP));
        Exhibit e = new Exhibit("host", "Host", List.of(), "");
        e.name("shaft", e.fill(SHAFT, st, 2, 0, 2, 2, 2, 2, Dir.UP, "diameter", 0.012));
        e.add(BEARING, st, 2, 0, 2, Dir.UP);
        e.name("arm", e.fill(BLOCK, st, 2, 1, 2, 3, 1, 2, Dir.EAST, "fill", 0.3) + 1); // through the shaft: the rotor's arm
        e.name("crank", e.add(CRANK, st, 2, 3, 2, Dir.UP, "radius", 0.1));
        int k = e.embed(3, 2, 2, mini);                    // on the arm
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        CompiledAssembly.Body rotor = c.bodyOf(b.id(e, "arm")), slab = c.bodyOf(b.embeddedId(e, k, "slab"));
        System.out.printf("structure across scales: rotor body %s, the slab's %s%n", rotor == null ? "-" : rotor.index(), slab == null ? "-" : slab.index());
        assertTrue(rotor != null && !rotor.isStatic(), "the arm turns with its shaft");
        assertSame(rotor, slab, "the slab rides on the arm under it: one rigid piece");
    }

    /** The exhibit: the crank turns the miniature gear's shaft twice as fast; the miniature bearing is held by the stand. */
    @Test
    void theExhibitGearsDownAcrossScales() {
        Exhibit e = ScaleExhibits.gearsAcrossScales();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        assertEquals(1, c.gearLinks());
        for (int i = 0; i < 40; i++) {
            c.setCrankTorque(b.id(e, "crank"), 0.3);
            c.step(0.05);
        }
        double big = c.probe(b.id(e, "bigshaft")).angularVelocity(), small = c.probe(b.embeddedId(e, 0, "shaft")).angularVelocity();
        var bearing = c.bodyOf(b.embeddedId(e, 0, "bearing"));
        System.out.printf("gears across scales (exhibit): %.2f → %.2f rad/s; the miniature bearing static %s%n", big, small, bearing == null || bearing.isStatic());
        assertEquals(-2.0, small / big, 0.05);
        assertTrue(bearing == null || bearing.isStatic(), "the miniature bearing is held by the stand");
    }
}
