package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The full-size crank turns the miniature generator through the shaft coupling across the block's top face. */
class AcrossScalesExhibitTest {
    @Test
    void theFullSizeCrankTurnsTheMiniatureGenerator() {
        Exhibit e = ScaleExhibits.crankAcrossScales();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        assertEquals(1, c.shaftCouplings(), "the two shafts are coupled");
        double peak = 0;
        for (int i = 0; i < 60; i++) {
            c.setCrankTorque(b.blockId(e, "crank"), 1.0);
            c.step(0.05);
            peak = Math.max(peak, Math.abs(c.probe(b.id(e, "load")).current()));
        }
        double big = c.probe(b.blockId(e, "bigshaft")).angularVelocity(), small = c.probe(b.id(e, "shaft")).angularVelocity();
        System.out.printf("across scales: big shaft %.2f rad/s, miniature %.2f rad/s, peak load current %.2f mA%n", big, small, 1e3 * peak);
        assertTrue(Math.abs(big) > 2, "the full-size crank turns its shaft: " + big);
        assertEquals(Math.abs(big), Math.abs(small), 0.03 * Math.abs(big), "the miniature shaft turns with it");
        assertTrue(peak > 1e-3, "the miniature generator drives its load: " + peak + " A");
    }

    /** Air from the full-size tank fills the miniature one through the pipes meeting at the face; heat crosses the top. */
    @Test
    void airAndHeatCrossTheFaces() {
        Exhibit e = ScaleExhibits.pipesAndHeatAcrossScales();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        assertTrue(c.vents().isEmpty(), "the pipes meeting at the face do not vent: " + c.vents().size());
        long small = b.id(e, "minitank"), big = b.blockId(e, "bigtank"), cell = b.id(e, "cell"), hot = b.blockId(e, "hot");
        double cell0 = c.probe(cell).temperature();
        for (int i = 0; i < 1200; i++) c.step(0.05);
        double ps = c.fluid(small)[0], pb = c.fluid(big)[0], tc = c.probe(cell).temperature(), th = c.probe(hot).temperature();
        System.out.printf("across scales: tanks %.0f / %.0f Pa; copper cell %.1f K (from %.1f), full-size block %.1f K (from 450)%n", ps, pb, tc, cell0, th);
        assertTrue(ps > 1.6e5 && Math.abs(ps - pb) < 0.05e5, "the small tank filled to the big one's pressure: " + ps + " / " + pb);
        assertTrue(tc > cell0 + 40, "the miniature cell warmed through the top face: " + cell0 + " -> " + tc);
        assertTrue(th < 440, "the full-size block gave its heat: " + th);
    }
}
