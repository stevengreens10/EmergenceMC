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
}
